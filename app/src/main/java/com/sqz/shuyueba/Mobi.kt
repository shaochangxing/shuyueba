package com.sqz.shuyueba

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.charset.Charset

/**
 * MOBI（PalmDB + PalmDOC 压缩）解析。
 *
 * 只负责 MOBI6/MOBI7 这一代格式：正文按 PalmDOC 解压，目录取自书内的 NCX 索引
 * （标题 + 层级 + 正文字节位置），封面取 EXTH 201 指向的图片记录。
 * KF8（azw3 的 MOBI8）结构不同，遇到时退回「整本一节」，仍可正常阅读。
 */
object MobiParser {

    private const val COMPRESSION_NONE = 1
    private const val COMPRESSION_PALMDOC = 2

    data class Chapter(val start: Int, val end: Int, val title: String, val level: Int)

    class Book(
        val filePath: String,
        val text: ByteArray,
        val charset: Charset,
        val chapters: List<Chapter>,
        val coverRecord: Int?,
        val firstImageRecord: Int,
        val recordOffsets: IntArray
    ) {
        val meta: BookMeta = BookMeta(
            format = BookFormat.MOBI,
            coverEntry = coverRecord?.let { "mobiimg:cover" },
            chapters = chapters.mapIndexed { index, chapter ->
                BookChapter("mobi:$index", chapter.title, chapter.level)
            }
        )
    }

    fun parse(file: File): Book {
        val data = file.readBytes()
        if (data.size < 100) throw IOException("不是有效的 MOBI 文件")
        val recordCount = u16(data, 76)
        if (recordCount <= 0) throw IOException("不是有效的 MOBI 文件")
        val offsets = IntArray(recordCount + 1)
        for (i in 0 until recordCount) {
            val offset = u32(data, 78 + i * 8)
            if (offset < 0 || offset > data.size) throw IOException("MOBI 记录表损坏")
            offsets[i] = offset
        }
        offsets[recordCount] = data.size
        val record = { index: Int ->
            if (index < 0 || index >= recordCount) ByteArray(0)
            else data.copyOfRange(offsets[index], offsets[index + 1])
        }

        val header = record(0)
        if (ascii(header, 16, 4) != "MOBI") throw IOException("不是有效的 MOBI 文件")
        val headerLength = u32(header, 20)
        val codepage = u32(header, 28)
        val version = u32(header, 36)
        val firstNonBook = u32(header, 80)
        val fullName = runCatching {
            val offset = u32(header, 84)
            val length = u32(header, 88)
            if (length > 0 && offset + length <= header.size) {
                String(header, offset, length, Charsets.UTF_8).trim()
            } else null
        }.getOrNull()
        val firstImage = u32(header, 108)
        val extraFlags = if (headerLength + 16 > 0xF2) u16(header, 0xF2) else 0
        val exthFlags = if (headerLength >= 0x74) u32(header, 128) else 0
        val ncxIndex = if (headerLength + 16 >= 0xF8) u32(header, 0xF4) else -1

        var coverOffset = -1
        if (exthFlags and 0x40 != 0) {
            var p = 16 + headerLength
            if (ascii(header, p, 4) == "EXTH") {
                val count = u32(header, p + 8)
                var q = p + 12
                var i = 0
                while (i < count && q + 8 <= header.size) {
                    val type = u32(header, q)
                    val length = u32(header, q + 4)
                    if (length < 8 || q + length > header.size) break
                    if (type == 201) coverOffset = u32(header, q + 8)
                    q += length
                    i++
                }
            }
        }

        val charset = when (codepage) {
            65001 -> Charsets.UTF_8
            1252 -> Charset.forName("windows-1252")
            else -> Charsets.UTF_8
        }
        val text = readText(header, record, recordCount)
        val ncx = try {
            readNcx(record, recordCount, ncxIndex, firstNonBook, text.size)
        } catch (_: Exception) {
            emptyList()
        }
        val chapters = if (ncx.isNotEmpty()) {
            buildNcxChapters(ncx, text.size)
        } else {
            splitByPageBreak(text)
        }
        if (chapters.isEmpty()) {
            throw IOException("MOBI 正文为空")
        }
        val coverRecord = if (coverOffset >= 0 && firstImage >= 0 &&
            firstImage + coverOffset < recordCount
        ) firstImage + coverOffset else null

        return Book(
            filePath = file.absolutePath,
            text = text,
            charset = charset,
            chapters = chapters,
            coverRecord = coverRecord,
            firstImageRecord = firstImage,
            recordOffsets = offsets
        )
    }

    fun loadChapter(book: Book, entry: String): ChapterContent {
        val index = entry.removePrefix("mobi:").toIntOrNull() ?: return ChapterContent(emptyList())
        val chapter = book.chapters.getOrNull(index) ?: return ChapterContent(emptyList())
        val bytes = book.text.copyOfRange(chapter.start, chapter.end)
        val html = String(bytes, book.charset)
        return EpubHtml.extract(withMobiImages(html)) { path ->
            if (path.startsWith("mobiimg:")) path else null
        }
    }

    fun loadImage(book: Book, entry: String, targetWidth: Int): Bitmap? {
        val bytes = if (entry == "mobiimg:cover") {
            val record = book.coverRecord ?: return null
            readRecord(book, record) ?: return null
        } else {
            val number = entry.removePrefix("mobiimg:").toIntOrNull() ?: return null
            if (book.firstImageRecord < 0) return null
            val record = book.firstImageRecord + number - 1
            readRecord(book, record) ?: return null
        }
        return decode(bytes, targetWidth)
    }

    private fun readRecord(book: Book, index: Int): ByteArray? {
        if (index < 0 || index + 1 >= book.recordOffsets.size) return null
        val start = book.recordOffsets[index]
        val end = book.recordOffsets[index + 1]
        if (end <= start) return null
        return try {
            RandomAccessFile(book.filePath, "r").use { file ->
                val buffer = ByteArray(end - start)
                file.seek(start.toLong())
                file.readFully(buffer)
                buffer
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun decode(bytes: ByteArray, targetWidth: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = EpubParser.sampleSizeFor(bounds.outWidth, targetWidth)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }

    /** MOBI 的插图写成 <img recindex="00001">，补一个 src 交给统一的 HTML 抽取。 */
    private val recindexTag = Regex(
        "(?is)<(?:img|image)\\b[^>]*?\\brecindex\\s*=\\s*[\"']?(\\d+)[\"']?[^>]*>"
    )

    private fun withMobiImages(html: String): String =
        recindexTag.replace(html) { match -> "<img src=\"mobiimg:${match.groupValues[1]}\">" }

    // ---- 正文 ----

    private fun readText(
        header: ByteArray,
        record: (Int) -> ByteArray,
        recordCount: Int
    ): ByteArray {
        val compression = u16(header, 0)
        val textLength = u32(header, 4)
        val textRecords = u16(header, 8)
        val extraFlags = u16(header, 0xF2)
        if (compression != COMPRESSION_NONE && compression != COMPRESSION_PALMDOC) {
            throw IOException("不支持的 MOBI 压缩格式（$compression）")
        }
        val out = ByteArray(textLength.coerceAtLeast(0))
        var written = 0
        for (i in 1..textRecords) {
            if (i >= recordCount || written >= out.size) break
            val raw = record(i)
            val body = stripTrailing(raw, extraFlags)
            val data = if (compression == COMPRESSION_PALMDOC) decompressPalmDoc(body) else body
            val available = minOf(data.size, out.size - written)
            if (available <= 0) break
            System.arraycopy(data, 0, out, written, available)
            written += available
        }
        if (written == 0 && textLength > 0) throw IOException("MOBI 正文解压失败")
        return if (written == out.size) out else out.copyOf(written)
    }

    /** 每个正文记录末尾可能带 multibyte / 尾部索引数据，按 MOBI 规则从后往前裁掉。 */
    private fun stripTrailing(record: ByteArray, flags: Int): ByteArray {
        if (flags == 0 || record.isEmpty()) return record
        var data = record
        val multibyte = flags and 1 != 0
        var trailers = 0
        var f = flags
        while (f > 1) {
            if (f and 2 != 0) trailers++
            f = f shr 1
        }
        repeat(trailers) {
            if (data.size < 4) return@repeat
            var num = 0
            for (i in 1..4) {
                val value = data[data.size - i].toInt() and 0xFF
                if (value and 0x80 != 0) num = 0
                num = (num shl 7) or (value and 0x7F)
            }
            if (num > 0 && num <= data.size) data = data.copyOfRange(0, data.size - num)
        }
        if (multibyte && data.isNotEmpty()) {
            val num = (data[data.size - 1].toInt() and 0x3) + 1
            if (num <= data.size) data = data.copyOfRange(0, data.size - num)
        }
        return data
    }

    /** PalmDOC LZ77 解压；单条记录解压后不超过 4096 字节。 */
    private fun decompressPalmDoc(data: ByteArray): ByteArray {
        val out = ByteArray(8192)
        var size = 0
        var i = 0
        while (i < data.size) {
            val c = data[i].toInt() and 0xFF
            i++
            when {
                c in 1..8 -> {
                    val end = minOf(i + c, data.size)
                    while (i < end && size < out.size) out[size++] = data[i++]
                }
                c < 128 -> if (size < out.size) out[size++] = c.toByte()
                c >= 192 -> {
                    if (size < out.size) out[size++] = ' '.code.toByte()
                    if (size < out.size) out[size++] = (c xor 128).toByte()
                }
                else -> {
                    if (i >= data.size) break
                    val code = (c shl 8) or (data[i].toInt() and 0xFF)
                    i++
                    val distance = (code shr 3) and 0x7FF
                    val length = (code and 7) + 3
                    var copied = 0
                    while (copied < length && size < out.size) {
                        out[size] = if (distance in 1..size) out[size - distance] else ' '.code.toByte()
                        size++
                        copied++
                    }
                }
            }
        }
        return out.copyOf(size)
    }

    private fun splitByPageBreak(text: ByteArray): List<Chapter> {
        val marker = "<mbp:pagebreak".toByteArray()
        val starts = ArrayList<Int>()
        starts.add(0)
        var i = 0
        outer@ while (i <= text.size - marker.size) {
            for (j in marker.indices) {
                if (text[i + j] != marker[j]) {
                    i++
                    continue@outer
                }
            }
            starts.add(i + marker.size)
            i += marker.size
        }
        val result = ArrayList<Chapter>()
        for ((index, start) in starts.withIndex()) {
            val end = if (index + 1 < starts.size) starts[index + 1] else text.size
            if (end - start < 16) continue
            result.add(Chapter(start, end, "第 ${result.size + 1} 节", 0))
        }
        return result
    }

    private fun buildNcxChapters(entries: List<NcxEntry>, textLength: Int): List<Chapter> {
        val ordered = entries.filter { it.position in 0 until textLength }
            .sortedBy { it.position }
            .distinctBy { it.position }
        if (ordered.isEmpty()) return emptyList()
        val minLevel = ordered.minOf { it.level }
        val result = ArrayList<Chapter>()
        for ((index, entry) in ordered.withIndex()) {
            val next = ordered.getOrNull(index + 1)?.position ?: textLength
            val end = minOf(
                next,
                if (entry.length > 0) entry.position + entry.length else next,
                textLength
            )
            if (end <= entry.position) continue
            result.add(
                Chapter(
                    entry.position,
                    end,
                    entry.title.ifBlank { "第 ${result.size + 1} 节" },
                    (entry.level - minLevel).coerceIn(0, 3)
                )
            )
        }
        return result
    }

    // ---- NCX 索引 ----

    private data class NcxEntry(
        val position: Int,
        val length: Int,
        val level: Int,
        val title: String
    )

    private data class IndxHeader(val length: Int, val count: Int, val idxt: Int, val ctocCount: Int)

    private data class Tag(val tag: Int, val valuesPerEntry: Int, val mask: Int, val endFlag: Int)

    private fun indxHeader(data: ByteArray): IndxHeader {
        if (ascii(data, 0, 4) != "INDX") return IndxHeader(0, 0, 0, 0)
        return IndxHeader(
            length = u32(data, 4),
            count = u32(data, 24),
            idxt = u32(data, 20),
            ctocCount = u32(data, 52)
        )
    }

    private fun readNcx(
        record: (Int) -> ByteArray,
        recordCount: Int,
        ncxIndex: Int,
        firstNonBook: Int,
        textLength: Int
    ): List<NcxEntry> {
        val index = if (ncxIndex in 0 until recordCount) ncxIndex else firstNonBook
        if (index <= 0 || index >= recordCount) return emptyList()
        val main = record(index)
        val header = indxHeader(main)
        if (header.count !in 1..4000) return emptyList()
        val (controlByteCount, tags) = readTagSection(main, header.length)
        if (tags.isEmpty()) return emptyList()

        val ctoc = HashMap<Int, String>()
        val ctocBase = index + header.count + 1
        for (j in 0 until header.ctocCount.coerceIn(0, 100)) {
            val data = record(ctocBase + j)
            if (data.isEmpty()) continue
            readCtoc(data, j * 0x10000, ctoc)
        }

        val result = ArrayList<NcxEntry>()
        for (r in index + 1..index + header.count) {
            val data = record(r)
            if (data.isEmpty()) continue
            val entryHeader = indxHeader(data)
            if (entryHeader.count !in 1..4000) continue
            val base = entryHeader.idxt
            for (j in 0 until entryHeader.count) {
                val start = u16(data, base + 4 + j * 2)
                val end = if (j + 1 < entryHeader.count) u16(data, base + 6 + j * 2) else base
                if (start < 0 || start >= data.size || end <= start) continue
                val textLengthInEntry = data[start].toInt() and 0xFF
                val tagsStart = start + 1 + textLengthInEntry
                if (tagsStart > end) continue
                val values = readTagMap(data, controlByteCount, tags, tagsStart, end)
                val position = values[1]?.firstOrNull() ?: continue
                if (position !in 0 until textLength) continue
                val length = values[2]?.firstOrNull() ?: 0
                val labelOffset = values[3]?.firstOrNull() ?: -1
                val level = values[4]?.firstOrNull() ?: 0
                val title = ctoc[labelOffset].orEmpty()
                result.add(NcxEntry(position, length, level, title))
            }
        }
        return result
    }

    private fun readTagSection(data: ByteArray, start: Int): Pair<Int, List<Tag>> {
        if (start + 12 > data.size || ascii(data, start, 4) != "TAGX") return 0 to emptyList()
        val firstEntry = start + u32(data, start + 4)
        val controlByteCount = u32(data, start + 8)
        val tags = ArrayList<Tag>()
        var p = start + 12
        while (p + 4 <= firstEntry && p + 4 <= data.size) {
            val tag = data[p].toInt() and 0xFF
            if (tag == 0) break
            tags.add(
                Tag(
                    tag = tag,
                    valuesPerEntry = data[p + 1].toInt() and 0xFF,
                    mask = data[p + 2].toInt() and 0xFF,
                    endFlag = data[p + 3].toInt() and 0xFF
                )
            )
            p += 4
        }
        return controlByteCount to tags
    }

    private fun readCtoc(data: ByteArray, offsetBase: Int, out: MutableMap<Int, String>) {
        var p = 0
        while (p < data.size) {
            if (data[p].toInt() == 0) break
            val (consumed, length) = variableWidth(data, p)
            if (consumed <= 0) break
            p += consumed
            if (length < 0 || p + length > data.size) break
            val text = String(data, p, length, Charsets.UTF_8)
            out[offsetBase + p - consumed] = text
            p += length
        }
    }

    private fun readTagMap(
        data: ByteArray,
        controlByteCount: Int,
        tags: List<Tag>,
        start: Int,
        end: Int
    ): Map<Int, IntArray> {
        if (start + controlByteCount > end) return emptyMap()
        val pending = ArrayList<Triple<Tag, Int, Int>>()
        var controlIndex = 0
        var dataStart = start + controlByteCount
        for (tag in tags) {
            if (tag.endFlag == 1) {
                controlIndex++
                continue
            }
            if (start + controlIndex >= data.size) break
            var value = (data[start + controlIndex].toInt() and 0xFF) and tag.mask
            if (value != 0) {
                if (value == tag.mask) {
                    if (Integer.bitCount(tag.mask) > 1) {
                        val (used, parsed) = variableWidth(data, dataStart)
                        dataStart += used
                        pending.add(Triple(tag, parsed, tag.valuesPerEntry))
                    } else {
                        pending.add(Triple(tag, -1, tag.valuesPerEntry))
                    }
                } else {
                    var mask = tag.mask
                    var bits = value
                    while (mask and 1 == 0) {
                        mask = mask shr 1
                        bits = bits shr 1
                    }
                    pending.add(Triple(tag, bits, tag.valuesPerEntry))
                }
            }
        }
        val out = HashMap<Int, IntArray>()
        for ((tag, count, perEntry) in pending) {
            val values = ArrayList<Int>()
            if (count >= 0) {
                repeat(count * perEntry) {
                    if (dataStart >= data.size) return@repeat
                    val (used, value) = variableWidth(data, dataStart)
                    dataStart += used
                    values.add(value)
                }
            } else {
                var consumed = 0
                while (consumed < perEntry && dataStart < data.size) {
                    val (used, value) = variableWidth(data, dataStart)
                    dataStart += used
                    consumed += used
                    values.add(value)
                }
            }
            out[tag.tag] = values.toIntArray()
        }
        return out
    }

    private fun variableWidth(data: ByteArray, start: Int): Pair<Int, Int> {
        var value = 0
        var used = 0
        while (start + used < data.size) {
            val byte = data[start + used].toInt() and 0xFF
            used++
            value = (value shl 7) or (byte and 0x7F)
            if (byte and 0x80 != 0) break
            if (used >= 4) break
        }
        return used to value
    }

    // ---- 字节读取 ----

    private fun ascii(data: ByteArray, offset: Int, length: Int): String {
        if (offset < 0 || offset + length > data.size) return ""
        return String(data, offset, length, Charsets.ISO_8859_1)
    }

    private fun u16(data: ByteArray, offset: Int): Int {
        if (offset < 0 || offset + 2 > data.size) return 0
        return ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)
    }

    private fun u32(data: ByteArray, offset: Int): Int {
        if (offset < 0 || offset + 4 > data.size) return 0
        return ((data[offset].toInt() and 0xFF) shl 24) or
            ((data[offset + 1].toInt() and 0xFF) shl 16) or
            ((data[offset + 2].toInt() and 0xFF) shl 8) or
            (data[offset + 3].toInt() and 0xFF)
    }
}

/** MOBI 本地副本 + 解析结果缓存（正文以字节数组保留，按章解码）。 */
class MobiRepository {

    private val cache = object : LruCache<String, MobiParser.Book>(12 * 1024) {
        override fun sizeOf(key: String, value: MobiParser.Book): Int =
            (value.text.size / 1024).coerceAtLeast(1)
    }

    private fun book(file: File): MobiParser.Book {
        cache.get(file.absolutePath)?.let { return it }
        val parsed = MobiParser.parse(file)
        cache.put(file.absolutePath, parsed)
        return parsed
    }

    fun open(file: File): BookMeta = book(file).meta

    fun loadChapter(file: File, entry: String): ChapterContent =
        MobiParser.loadChapter(book(file), entry)

    fun loadImage(file: File, entry: String, targetWidth: Int): Bitmap? =
        MobiParser.loadImage(book(file), entry, targetWidth)

    fun loadCover(file: File, targetWidth: Int): Bitmap? =
        MobiParser.loadImage(book(file), "mobiimg:cover", targetWidth)
}
