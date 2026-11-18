package com.sqz.shuyueba

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.IOException
import kotlin.math.max
import kotlin.math.roundToInt

/** PDF：用系统 PdfRenderer 按页渲染成图片，一页就是一个「章节」。 */
object PdfParser {

    fun open(file: File): BookMeta {
        val count = pageCount(file)
        if (count <= 0) throw IOException("PDF 没有可显示的页面")
        return BookMeta(
            format = BookFormat.PDF,
            coverEntry = "pdf:0",
            chapters = (0 until count).map { page ->
                BookChapter("pdf:$page", "第 ${page + 1} 页")
            }
        )
    }

    fun pageCount(file: File): Int = withRenderer(file) { it.pageCount }

    fun render(file: File, entry: String, targetWidth: Int): Bitmap? {
        val index = entry.removePrefix("pdf:").toIntOrNull() ?: return null
        if (index < 0) return null
        return withRenderer(file) { renderer ->
            if (index >= renderer.pageCount) return@withRenderer null
            renderer.openPage(index).use { page ->
                if (page.width <= 0 || page.height <= 0) return@use null
                val width = max(1, targetWidth.coerceAtMost(1600))
                val scale = width.toFloat() / page.width
                val height = max(1, (page.height * scale).roundToInt())
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(Color.WHITE)
                val matrix = Matrix().apply { setScale(scale, scale) }
                page.render(bitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bitmap
            }
        }
    }

    fun cover(file: File, targetWidth: Int): Bitmap? = render(file, "pdf:0", targetWidth)

    private fun <T> withRenderer(file: File, block: (PdfRenderer) -> T): T {
        val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        val renderer = try {
            PdfRenderer(descriptor)
        } catch (e: Exception) {
            descriptor.close()
            throw e
        }
        try {
            return block(renderer)
        } finally {
            try {
                renderer.close()
            } catch (_: Exception) {
            }
            descriptor.close()
        }
    }
}

/** PDF 只保存页数相关的轻量状态，渲染走 PdfParser。 */
class PdfRepository {
    fun open(file: File): BookMeta = PdfParser.open(file)

    fun loadImage(file: File, entry: String, targetWidth: Int): Bitmap? =
        try {
            PdfParser.render(file, entry, targetWidth)
        } catch (_: Exception) {
            null
        }

    fun loadCover(file: File, targetWidth: Int): Bitmap? =
        try {
            PdfParser.cover(file, targetWidth)
        } catch (_: Exception) {
            null
        }
}
