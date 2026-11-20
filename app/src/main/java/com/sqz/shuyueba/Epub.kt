package com.sqz.shuyueba

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.text.Html
import android.util.LruCache
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.IOException
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipFile

/** 一本书的文件格式。EPUB / MOBI 走文字排版，PDF 按页渲染。 */
enum class BookFormat(val label: String) {
    EPUB("EPUB"),
    MOBI("MOBI"),
    PDF("PDF");

    companion object {
        /** 按文件扩展名判断格式；无法识别返回 null。 */
        fun fromFileName(name: String?): BookFormat? =
            when (name?.substringAfterLast('.', "")?.lowercase()) {
                "epub" -> EPUB
                "mobi", "prc" -> MOBI
                "pdf" -> PDF
                else -> null
            }
    }
}

/** 目录项：entry 是 EPUB 的 zip 路径 / MOBI 的章节序号 / PDF 的页码；level 用于目录缩进。 */
data class BookChapter(val entry: String, val title: String, val level: Int = 0)

data class BookMeta(
    val format: BookFormat,
    val coverEntry: String?,
    val chapters: List<BookChapter>
)

/** 一章解析出来的内容块，按文档顺序排列。 */
sealed interface ChapterBlock {
    data class Text(val text: String) : ChapterBlock
    data class Image(val entry: String) : ChapterBlock
}

data class ChapterContent(val blocks: List<ChapterBlock>)

/** 解析 EPUB 的 container.xml → OPF → spine / 目录 / 封面。 */
object EpubParser {

    fun parse(file: File): BookMeta {
        ZipFile(file).use { zip ->
            val opfPath = findOpfPath(zip)
            val opf = readOpf(zip, opfPath)
            val opfDir = opfPath.substringBeforeLast('/', "")
            val titles = readNavTitles(zip, opf, opfDir)

            val chapters = ArrayList<BookChapter>()
            for (idref in opf.spine) {
                val item = opf.items[idref] ?: continue
                if (!isHtml(item.mediaType, item.href)) continue
                val path = resolve(opfDir, item.href)
                if (!entryExists(zip, path)) continue
                val nav = titles[path]
                val title = nav?.title?.takeIf { it.isNotBlank() }
                    ?: "第 ${chapters.size + 1} 节"
                chapters.add(BookChapter(path, title, nav?.level ?: 0))
            }

            val cover = findCover(zip, opf, opfDir)
            return BookMeta(
                format = BookFormat.EPUB,
                coverEntry = cover,
                chapters = chapters
            )
        }
    }

    /** 读取单章 HTML 并抽取文字段落与插图块。必须在 IO 线程调用。 */
    fun readChapter(zip: ZipFile, entry: String): ChapterContent {
        val raw = readEntryBytes(zip, entry) ?: return ChapterContent(emptyList())
        val html = decodeText(raw)
        val docDir = entry.substringBeforeLast('/', "")
        return EpubHtml.extract(html) { src ->
            if (src.startsWith("data:", true)) return@extract null
            val path = resolve(docDir, src)
            val zipEntry = zip.getEntry(path) ?: zip.getEntry(normalize(path)) ?: return@extract null
            if (isUsableIllustration(zip, zipEntry, path)) path else null
        }
    }

    /** 过滤 1x1 占位图、装饰线等小图，避免每张小图都占一整页。 */
    private fun isUsableIllustration(
        zip: ZipFile,
        entry: java.util.zip.ZipEntry,
        path: String
    ): Boolean {
        if (!isImage(path) || entry.size < 1024) return false
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        return try {
            zip.getInputStream(entry).use { BitmapFactory.decodeStream(it, null, bounds) }
            bounds.outWidth >= 200 && bounds.outHeight >= 200
        } catch (_: Exception) {
            false
        }
    }

    fun loadImage(zip: ZipFile, entry: String, targetWidth: Int): Bitmap? {
        val zipEntry = zip.getEntry(entry) ?: return null
        return decodeSampled(zip, zipEntry, targetWidth)
    }

    private data class Item(
        val id: String,
        val href: String,
        val mediaType: String,
        val properties: String
    )

    private data class Opf(
        val items: Map<String, Item>,
        val spine: List<String>,
        val tocId: String?,
        val coverId: String?,
        val guideCoverHref: String?
    )

    private fun findOpfPath(zip: ZipFile): String {
        val container = readEntryText(zip, "META-INF/container.xml")
        if (container != null) {
            val parser = newParser(container.reader())
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG && localName(parser.name) == "rootfile") {
                    val path = parser.getAttributeValue(null, "full-path")
                    if (!path.isNullOrBlank()) return normalize(path)
                }
                event = parser.next()
            }
        }
        return zip.entries().toList().firstOrNull { it.name.endsWith(".opf", true) }?.name
            ?: throw IOException("EPUB 缺少 OPF 文件")
    }

    private fun readOpf(zip: ZipFile, opfPath: String): Opf {
        val text = readEntryText(zip, opfPath) ?: throw IOException("无法读取 OPF")
        val parser = newParser(text.reader())
        val items = LinkedHashMap<String, Item>()
        val spine = ArrayList<String>()
        var tocId: String? = null
        var coverId: String? = null
        var guideCover: String? = null

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (localName(parser.name)) {
                    "item" -> {
                        val id = parser.getAttributeValue(null, "id") ?: ""
                        val href = parser.getAttributeValue(null, "href") ?: ""
                        val mediaType = parser.getAttributeValue(null, "media-type") ?: ""
                        val properties = parser.getAttributeValue(null, "properties") ?: ""
                        if (id.isNotEmpty() && href.isNotEmpty()) {
                            items[id] = Item(id, href, mediaType, properties)
                        }
                    }
                    "spine" -> tocId = parser.getAttributeValue(null, "toc")
                    "itemref" -> parser.getAttributeValue(null, "idref")?.let { spine.add(it) }
                    "meta" -> if (parser.getAttributeValue(null, "name") == "cover") {
                        coverId = parser.getAttributeValue(null, "content")
                    }
                    "reference" -> if (parser.getAttributeValue(null, "type") == "cover") {
                        guideCover = parser.getAttributeValue(null, "href")
                    }
                }
            }
            event = parser.next()
        }
        return Opf(items, spine, tocId, coverId, guideCover)
    }

    /** 目录项标题 + 层级（0 为一级）。 */
    private data class NavInfo(val title: String, val level: Int)

    /** 依次读取 EPUB3 navigation document 与 EPUB2 NCX，得到「文件路径 → 标题 / 层级」。 */
    private fun readNavTitles(zip: ZipFile, opf: Opf, opfDir: String): Map<String, NavInfo> {
        val result = HashMap<String, NavInfo>()
        val navItem = opf.items.values.firstOrNull {
            it.properties.split(Regex("\\s+")).contains("nav")
        }
        if (navItem != null) {
            parseNavDoc(zip, resolve(opfDir, navItem.href), result)
        }
        val ncx = opf.items[opf.tocId.orEmpty()]
            ?: opf.items.values.firstOrNull { it.mediaType.contains("dtbncx", true) }
        if (ncx != null) {
            parseNcx(zip, resolve(opfDir, ncx.href), result)
        }
        return result
    }

    /** EPUB3 导航文档：用嵌套 <ol> 的深度得到目录层级。 */
    private fun parseNavDoc(zip: ZipFile, navPath: String, result: MutableMap<String, NavInfo>) {
        val text = readEntryText(zip, navPath) ?: return
        val baseDir = navPath.substringBeforeLast('/', "")
        try {
            val parser = newParser(text.reader())
            var event = parser.eventType
            var href: String? = null
            var olDepth = 0
            val label = StringBuilder()
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    when (localName(parser.name)) {
                        "ol", "ul" -> olDepth++
                        "a" -> {
                            href = parser.getAttributeValue(null, "href")
                            label.setLength(0)
                        }
                    }
                } else if (event == XmlPullParser.TEXT) {
                    if (href != null) label.append(parser.text)
                } else if (event == XmlPullParser.END_TAG) {
                    when (localName(parser.name)) {
                        "a" -> if (href != null) {
                            putTitle(result, baseDir, href, label.toString(), olDepth - 1)
                            href = null
                        }
                        "ol", "ul" -> if (olDepth > 0) olDepth--
                    }
                }
                event = parser.next()
            }
        } catch (_: Exception) {
            // 导航文档格式异常时退回「第 N 节」标题
        }
    }

    private class NavPointBuilder {
        val label = StringBuilder()
        var src: String? = null
    }

    /** EPUB2 NCX：navPoint 的嵌套深度就是目录层级。 */
    private fun parseNcx(zip: ZipFile, ncxPath: String, result: MutableMap<String, NavInfo>) {
        val text = readEntryText(zip, ncxPath) ?: return
        val baseDir = ncxPath.substringBeforeLast('/', "")
        try {
            val parser = newParser(text.reader())
            var event = parser.eventType
            var inLabel = false
            val stack = ArrayDeque<NavPointBuilder>()
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    when (localName(parser.name)) {
                        "navPoint" -> stack.addLast(NavPointBuilder())
                        "navLabel" -> inLabel = true
                        "content" -> {
                            val src = parser.getAttributeValue(null, "src")
                            val top = stack.lastOrNull()
                            if (src != null && top != null && top.src == null) top.src = src
                        }
                    }
                } else if (event == XmlPullParser.TEXT) {
                    if (inLabel) stack.lastOrNull()?.label?.append(parser.text)
                } else if (event == XmlPullParser.END_TAG) {
                    when (localName(parser.name)) {
                        "navLabel" -> inLabel = false
                        "navPoint" -> {
                            val point = stack.removeLastOrNull()
                            if (point?.src != null) {
                                putTitle(result, baseDir, point.src!!, point.label.toString(), stack.size)
                            }
                        }
                    }
                }
                event = parser.next()
            }
        } catch (_: Exception) {
            // NCX 异常时同样退回默认标题
        }
    }

    private fun putTitle(
        result: MutableMap<String, NavInfo>,
        baseDir: String,
        href: String,
        rawLabel: String,
        level: Int
    ) {
        if (href.isBlank()) return
        val title = rawLabel.replace(Regex("\\s+"), " ").trim()
        if (title.isBlank()) return
        val path = resolve(baseDir, href)
        if (!result.containsKey(path)) {
            result[path] = NavInfo(title, level.coerceIn(0, 3))
        }
    }

    private val coverImageTag = Regex(
        "(?is)<(?:img|image)\\b[^>]*?(?:src|xlink:href)\\s*=\\s*[\"']([^\"']+)[\"'][^>]*>"
    )

    /**
     * 封面识别兜底链，按可信度依次尝试：
     *  1. OPF `<meta name="cover">`（可能指向 manifest id，也可能直接是文件名）
     *  2. manifest 里 `properties="cover-image"`
     *  3. guide 的 `type="cover"`（本身是图片，或是内嵌 <img> 的 cover.xhtml 页面）
     *  4. manifest 中 id / href 带 cover 的图片
     *  5. spine 首页里的第一张图（有书把封面直接放在正文第一页）
     *  6. manifest 里第一张足够大的图片
     * 每个候选都用 BitmapFactory 按文件内容解码，因此扩展名与实际编码不符也能识别。
     */
    private fun findCover(zip: ZipFile, opf: Opf, opfDir: String): String? {
        val explicit = ArrayList<String>()

        opf.coverId?.let { ref ->
            opf.items[ref]?.let { explicit.add(resolve(opfDir, it.href)) }
            explicit.add(resolve(opfDir, ref))
        }
        opf.items.values
            .filter { it.properties.split(Regex("\\s+")).contains("cover-image") }
            .forEach { explicit.add(resolve(opfDir, it.href)) }
        opf.guideCoverHref?.let { href ->
            val page = resolve(opfDir, href)
            coverInsidePage(zip, page)?.let { explicit.add(it) }
            explicit.add(page)
        }
        explicit.firstOrNull { isUsableCover(zip, it) }?.let { return it }

        opf.items.values
            .filter { isImage(it.href) && (it.id.contains("cover", true) || it.href.contains("cover", true)) }
            .map { resolve(opfDir, it.href) }
            .firstOrNull { isUsableCover(zip, it) }
            ?.let { return it }

        val firstSpineImage = opf.spine.firstOrNull()
            ?.let { opf.items[it] }
            ?.let { resolve(opfDir, it.href) }
            ?.let { coverInsidePage(zip, it) }
        if (firstSpineImage != null && isUsableCover(zip, firstSpineImage, minSide = 400)) return firstSpineImage

        return opf.items.values
            .filter { isImage(it.href) }
            .map { resolve(opfDir, it.href) }
            .firstOrNull { isUsableCover(zip, it, minSide = 400) }
    }

    /** 从 cover.xhtml 这类封面页里取第一张可用图片，路径相对该页面解析。 */
    private fun coverInsidePage(zip: ZipFile, pagePath: String): String? {
        if (!isHtml("", pagePath)) return null
        val text = readEntryText(zip, pagePath) ?: return null
        val pageDir = pagePath.substringBeforeLast('/', "")
        for (match in coverImageTag.findAll(text)) {
            val src = match.groupValues[1]
            if (src.startsWith("data:", true)) continue
            val path = resolve(pageDir, src)
            if (isUsableCover(zip, path)) return path
        }
        return null
    }

    /** 用文件内容解码判断是否可用作封面的图片（不依赖扩展名）。 */
    private fun isUsableCover(zip: ZipFile, path: String, minSide: Int = 1): Boolean {
        val entry = zip.getEntry(path) ?: zip.getEntry(normalize(path)) ?: return false
        if (entry.size <= 0L) return false
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        return try {
            zip.getInputStream(entry).use { BitmapFactory.decodeStream(it, null, bounds) }
            bounds.outWidth >= minSide && bounds.outHeight >= minSide
        } catch (_: Exception) {
            false
        }
    }

    private fun newParser(reader: java.io.Reader): XmlPullParser {
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(reader)
        return parser
    }

    private fun localName(name: String?): String = name?.substringAfter(':') ?: ""

    private fun isHtml(mediaType: String, href: String): Boolean {
        val type = mediaType.lowercase()
        return type.contains("html") || type.contains("xml") && !type.contains("ncx") ||
            href.lowercase().let { it.endsWith(".xhtml") || it.endsWith(".html") || it.endsWith(".htm") }
    }

    fun readEntryText(zip: ZipFile, name: String): String? {
        val bytes = readEntryBytes(zip, name) ?: return null
        return decodeText(bytes)
    }

    private fun readEntryBytes(zip: ZipFile, name: String): ByteArray? {
        val entry = zip.getEntry(name) ?: zip.getEntry(normalize(name)) ?: return null
        return try {
            zip.getInputStream(entry).use { it.readBytes() }
        } catch (_: Exception) {
            null
        }
    }

    fun decodeText(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        val head = String(bytes, 0, minOf(bytes.size, 1024), Charsets.ISO_8859_1)
        val charsetName = Regex("(?i)encoding\\s*=\\s*[\"']([A-Za-z0-9_\\-]+)")
            .find(head)?.groupValues?.get(1)
            ?: Regex("(?i)charset\\s*=\\s*[\"']?([A-Za-z0-9_\\-]+)")
                .find(head)?.groupValues?.get(1)
        val text = try {
            if (charsetName != null && !charsetName.equals("utf-8", true)) {
                String(bytes, charset(charsetName))
            } else {
                String(bytes, Charsets.UTF_8)
            }
        } catch (_: Exception) {
            String(bytes, Charsets.UTF_8)
        }
        return text.removePrefix("\uFEFF")
    }

    fun entryExists(zip: ZipFile, path: String): Boolean =
        zip.getEntry(path) != null || zip.getEntry(normalize(path)) != null

    fun isImage(path: String): Boolean {
        val lower = path.lowercase()
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") ||
            lower.endsWith(".webp") || lower.endsWith(".gif") || lower.endsWith(".bmp")
    }

    fun resolve(baseDir: String, href: String): String {
        var value = href.substringBefore('#').substringBefore('?')
        value = try {
            URLDecoder.decode(value.replace("+", "%2B"), "UTF-8")
        } catch (_: Exception) {
            value
        }
        if (value.startsWith("/")) return normalize(value.removePrefix("/"))
        return normalize(if (baseDir.isEmpty()) value else "$baseDir/$value")
    }

    fun normalize(path: String): String {
        val parts = path.replace('\\', '/').split('/')
        val out = ArrayList<String>(parts.size)
        for (part in parts) {
            when (part) {
                "", "." -> Unit
                ".." -> if (out.isNotEmpty()) out.removeAt(out.lastIndex)
                else -> out.add(part)
            }
        }
        return out.joinToString("/")
    }

    fun decodeSampled(zip: ZipFile, entry: java.util.zip.ZipEntry, targetWidth: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        zip.getInputStream(entry).use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, targetWidth)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return zip.getInputStream(entry).use { BitmapFactory.decodeStream(it, null, options) }
    }

    fun sampleSizeFor(width: Int, targetWidth: Int): Int {
        var sample = 1
        while (width / (sample * 2) >= targetWidth) sample *= 2
        return sample
    }
}

/** 把一章 XHTML 转成「段落文字 + 插图」序列。 */
object EpubHtml {

    private const val MARK_START = '\uE000'
    private const val MARK_END = '\uE001'
    private const val LINE_BREAK = '\uE010'

    private val imageTag = Regex(
        "(?is)<(?:img|image)\\b[^>]*?(?:src|xlink:href)\\s*=\\s*[\"']([^\"']+)[\"'][^>]*>"
    )
    private val breakTag = Regex("(?is)<br\\s*/?>")
    private val svgTag = Regex("(?is)</?svg[^>]*>")
    private val dropBlocks = Regex("(?is)<(script|style|head)[^>]*>.*?</\\1>")
    private val rubyRt = Regex("(?is)<(rt|rp)[^>]*>.*?</\\1>")
    private val comments = Regex("(?is)<!--.*?-->")
    private val tags = Regex("(?s)<[^>]+>")
    private val entities = Regex("&(#x?[0-9a-fA-F]+|[a-zA-Z]+);")

    fun extract(html: String, resolveImage: (String) -> String?): ChapterContent {
        var source = comments.replace(html, "")
        source = dropBlocks.replace(source, "")
        source = rubyRt.replace(source, "")
        // 段内换行先打标记，稍后与段落分隔区分开
        source = breakTag.replace(source, LINE_BREAK.toString())
        // SVG 只保留其中已经抽出的 image 标记，其余标签丢弃
        source = svgTag.replace(source, "")

        val images = ArrayList<String>()
        source = imageTag.replace(source) { match ->
            val path = resolveImage(match.groupValues[1])
            if (path != null) {
                images.add(path)
                "$MARK_START${images.lastIndex}$MARK_END"
            } else {
                ""
            }
        }

        val plain = try {
            Html.fromHtml(source, Html.FROM_HTML_MODE_COMPACT).toString()
        } catch (_: Exception) {
            entities.replace(tags.replace(source, "\n"), "")
        }

        val blocks = ArrayList<ChapterBlock>()
        val buffer = StringBuilder()
        var i = 0
        while (i < plain.length) {
            if (plain[i] == MARK_START) {
                flushText(buffer, blocks)
                val end = plain.indexOf(MARK_END, i + 1)
                val index = if (end > i) plain.substring(i + 1, end).toIntOrNull() else null
                if (index != null) {
                    blocks.add(ChapterBlock.Image(images[index]))
                    i = end + 1
                    continue
                }
            }
            buffer.append(plain[i])
            i++
        }
        flushText(buffer, blocks)
        return ChapterContent(blocks)
    }

    private fun flushText(buffer: StringBuilder, blocks: MutableList<ChapterBlock>) {
        if (buffer.isEmpty()) return
        val text = normalize(buffer.toString())
        buffer.setLength(0)
        if (text.isNotBlank()) blocks.add(ChapterBlock.Text(text))
    }

    private fun normalize(raw: String): String {
        val paragraphs = ArrayList<String>()
        // Html.fromHtml 用 \n 分隔块级元素；块内的 <br> 已被替换为 LINE_BREAK
        for (block in raw.replace('\u00A0', ' ').split('\n')) {
            val lines = block.split(LINE_BREAK)
                .map { cleanInline(it) }
                .filter { it.isNotBlank() }
            if (lines.isNotEmpty()) paragraphs.add(lines.joinToString("\n"))
        }
        return paragraphs.joinToString("\n\n")
    }

    private fun cleanInline(line: String): String {
        var text = line.replace(Regex("[\\t ]+"), " ").trim()
        // 中文排版里源码换行常被折成空格，汉字之间的空格要去掉
        text = text.replace(
            Regex("(?<=[\\u3000-\\u303f\\u4e00-\\u9fff\\uff00-\\uffef]) (?=[\\u3000-\\u303f\\u4e00-\\u9fff\\uff00-\\uffef])"),
            ""
        )
        text = text.replace(Regex(" +(?=[，。；：、？！》）】」』…—,.!?;:)\\]])"), "")
        text = text.replace(Regex("(?<=[（【「『]) "), "")
        return text
    }
}

/** 书籍本地副本、解析缓存、封面 / 插图解码；按格式分派 EPUB / MOBI / PDF。 */
class BookRepository(context: Context) {

    private val appContext = context.applicationContext
    private val cacheDir = File(appContext.cacheDir, "books").apply { mkdirs() }
    private val coverDir = File(appContext.cacheDir, "covers").apply { mkdirs() }
    private val parsedCache = object : LruCache<String, BookMeta>(16) {}
    private val copyLocks = ConcurrentHashMap<String, Any>()
    private val coverLocks = ConcurrentHashMap<String, Any>()
    private val mobi = MobiRepository()
    private val pdf = PdfRepository()

    fun open(uri: Uri, format: BookFormat): BookMeta {
        val key = "${format.name}|$uri"
        parsedCache.get(key)?.let { return it }
        val meta = when (format) {
            BookFormat.EPUB -> EpubParser.parse(localFile(uri, "epub"))
            BookFormat.MOBI -> mobi.open(localFile(uri, "mobi"))
            BookFormat.PDF -> pdf.open(localFile(uri, "pdf"))
        }
        parsedCache.put(key, meta)
        return meta
    }

    fun localFile(uri: Uri, extension: String = "epub"): File {
        val name = hash(uri.toString()) + "." + extension
        val target = File(cacheDir, name)
        if (target.exists() && target.length() > 0) return target
        val lock = copyLocks.getOrPut(name) { Any() }
        synchronized(lock) {
            if (target.exists() && target.length() > 0) return target
            val tmp = File(cacheDir, "$name.tmp")
            appContext.contentResolver.openInputStream(uri)?.use { input ->
                tmp.outputStream().use { output -> input.copyTo(output) }
            } ?: throw IOException("无法读取所选文件")
            if (target.exists()) target.delete()
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
        }
        return target
    }

    fun loadChapter(uri: Uri, format: BookFormat, entry: String): ChapterContent =
        when (format) {
            BookFormat.EPUB -> ZipFile(localFile(uri, "epub")).use { zip ->
                EpubParser.readChapter(zip, entry)
            }
            BookFormat.MOBI -> mobi.loadChapter(localFile(uri, "mobi"), entry)
            BookFormat.PDF -> ChapterContent(listOf(ChapterBlock.Image(entry)))
        }

    fun cachedCover(uri: Uri, format: BookFormat, targetWidth: Int): Bitmap? =
        CoverBitmapCache.get(coverKey(uri, format, targetWidth))

    fun loadCover(uri: Uri, format: BookFormat, targetWidth: Int): Bitmap? {
        val key = coverKey(uri, format, targetWidth)
        CoverBitmapCache.get(key)?.let { return it }
        val lock = coverLocks.getOrPut(key) { Any() }
        return synchronized(lock) {
            // 预载和可见项可能同时请求同一封面，拿到锁后再查一次，避免重复解析
            CoverBitmapCache.get(key)?.let { return@synchronized it }

            val diskName = hash(key)
            val diskFile = File(coverDir, "$diskName.webp")
            if (diskFile.exists()) {
                runCatching { BitmapFactory.decodeFile(diskFile.absolutePath) }.getOrNull()?.let {
                    CoverBitmapCache.put(key, it)
                    return@synchronized it
                }
                diskFile.delete()
            }

            val bitmap = try {
                when (format) {
                    BookFormat.EPUB -> {
                        val entryName = open(uri, format).coverEntry
                        if (entryName == null) null else ZipFile(localFile(uri, "epub")).use { zip ->
                            val entry = zip.getEntry(entryName) ?: return@use null
                            EpubParser.decodeSampled(zip, entry, targetWidth)
                        }
                    }
                    BookFormat.MOBI -> mobi.loadCover(localFile(uri, "mobi"), targetWidth)
                    BookFormat.PDF -> pdf.loadCover(localFile(uri, "pdf"), targetWidth)
                }
            } catch (_: Exception) {
                null
            }

            if (bitmap != null) {
                CoverBitmapCache.put(key, bitmap)
                // 磁盘缓存只用于加速下次启动，写入失败不影响本次封面显示
                runCatching {
                    val tmp = File(coverDir, "$diskName.tmp")
                    val compressed = tmp.outputStream().use { output ->
                        bitmap.compress(Bitmap.CompressFormat.WEBP, 88, output)
                    }
                    if (compressed) {
                        if (diskFile.exists()) diskFile.delete()
                        if (!tmp.renameTo(diskFile)) {
                            tmp.copyTo(diskFile, overwrite = true)
                            tmp.delete()
                        }
                    } else {
                        tmp.delete()
                    }
                }
            }
            bitmap
        }
    }

    private fun coverKey(uri: Uri, format: BookFormat, targetWidth: Int): String =
        "${format.name}|$uri|$targetWidth"

    fun cachedImage(uri: Uri, entry: String): Bitmap? = ImageBitmapCache.get(cacheKey(uri, entry))

    fun loadImage(uri: Uri, format: BookFormat, entry: String, targetWidth: Int): Bitmap? {
        val key = cacheKey(uri, entry)
        ImageBitmapCache.get(key)?.let { return it }
        return try {
            val bitmap = when (format) {
                BookFormat.EPUB -> ZipFile(localFile(uri, "epub")).use { zip ->
                    EpubParser.loadImage(zip, entry, targetWidth)
                }
                BookFormat.MOBI -> mobi.loadImage(localFile(uri, "mobi"), entry, targetWidth)
                BookFormat.PDF -> pdf.loadImage(localFile(uri, "pdf"), entry, targetWidth)
            }
            bitmap?.also { ImageBitmapCache.put(key, it) }
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        fun cacheKey(uri: Uri, entry: String): String = "$uri#$entry"

        /** 先看扩展名，再嗅探文件头，兼容文档提供方给不出正确 MimeType 的情况。 */
        fun detectFormat(context: Context, uri: Uri): BookFormat? {
            BookFormat.fromFileName(SafUtils.displayName(context, uri))?.let { return it }
            return try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    val head = ByteArray(64)
                    val read = input.read(head)
                    if (read < 8) return null
                    when {
                        head[0] == 0x25.toByte() && head[1] == 0x50.toByte() &&
                            head[2] == 0x44.toByte() && head[3] == 0x46.toByte() -> BookFormat.PDF
                        head[0] == 0x50.toByte() && head[1] == 0x4B.toByte() -> BookFormat.EPUB
                        read >= 68 && String(head, 60, 8, Charsets.ISO_8859_1) == "BOOKMOBI" ->
                            BookFormat.MOBI
                        else -> null
                    }
                }
            } catch (_: Exception) {
                null
            }
        }

        private fun hash(value: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
            return digest.joinToString("") { "%02x".format(it) }.take(24)
        }
    }
}

object ImageBitmapCache {
    private val maxSize = (Runtime.getRuntime().maxMemory() / 1024 / 8).toInt()
    private val cache = object : LruCache<String, Bitmap>(maxSize) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    fun get(key: String): Bitmap? = cache.get(key)

    fun put(key: String, bitmap: Bitmap) {
        cache.put(key, bitmap)
    }
}

object CoverBitmapCache {
    private val maxSize = (Runtime.getRuntime().maxMemory() / 1024 / 12).toInt()
    private val cache = object : LruCache<String, Bitmap>(maxSize) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    fun get(key: String): Bitmap? = cache.get(key)

    fun put(key: String, bitmap: Bitmap) {
        cache.put(key, bitmap)
    }
}
