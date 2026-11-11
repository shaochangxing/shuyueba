package com.sqz.shuyueba

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/** 章节内的一页：文字页保存全文中的字符区间，插图页单独占一页。 */
sealed interface ChapterPage {
    data class TextPage(val start: Int, val end: Int) : ChapterPage
    data class ImagePage(val entry: String) : ChapterPage
}

data class ChapterLayout(val text: String, val pages: List<ChapterPage>)

/** 阅读排版的全部尺寸参数；改字号/边距只影响这里。 */
data class ReaderMetrics(
    val style: TextStyle,
    val horizontalPadding: Dp,
    val topPadding: Dp,
    val bottomPadding: Dp,
    val footerHeight: Dp,
    val contentWidthPx: Int,
    val contentHeightPx: Int,
    val pageWidthPx: Int,
    val pageHeightPx: Int
)

fun buildReaderMetrics(density: Density, pageWidth: Dp, pageHeight: Dp): ReaderMetrics {
    val horizontalPadding = 24.dp
    val topPadding = 34.dp
    val bottomPadding = 18.dp
    val footerHeight = 24.dp
    val style = TextStyle(
        fontSize = 17.sp,
        lineHeight = 29.sp,
        letterSpacing = 0.2.sp
    )
    return with(density) {
        // 与 Compose padding 的取整方式保持一致，避免正文最后一行被裁掉
        val pageWidthPx = pageWidth.toPx().roundToInt()
        val pageHeightPx = pageHeight.toPx().roundToInt()
        val horizontalPx = horizontalPadding.toPx().roundToInt()
        val topPx = topPadding.toPx().roundToInt()
        val bottomPx = (bottomPadding + footerHeight).toPx().roundToInt()
        ReaderMetrics(
            style = style,
            horizontalPadding = horizontalPadding,
            topPadding = topPadding,
            bottomPadding = bottomPadding,
            footerHeight = footerHeight,
            contentWidthPx = pageWidthPx - horizontalPx * 2,
            contentHeightPx = pageHeightPx - topPx - bottomPx,
            pageWidthPx = pageWidthPx,
            pageHeightPx = pageHeightPx
        )
    }
}

/** 用 Compose 的 TextMeasurer 分页，保证排版测量与最终绘制使用同一套引擎。 */
class ChapterPaginator(
    private val measurer: TextMeasurer,
    private val metrics: ReaderMetrics
) {

    fun paginate(content: ChapterContent): ChapterLayout {
        val full = StringBuilder()
        val pages = ArrayList<ChapterPage>()
        for (block in content.blocks) {
            when (block) {
                is ChapterBlock.Text -> {
                    if (block.text.isBlank()) continue
                    if (full.isNotEmpty()) full.append("\n\n")
                    val base = full.length
                    full.append(block.text)
                    for (range in measurePages(block.text)) {
                        pages.add(ChapterPage.TextPage(base + range.first, base + range.second))
                    }
                }
                is ChapterBlock.Image -> pages.add(ChapterPage.ImagePage(block.entry))
            }
        }
        return ChapterLayout(full.toString(), pages)
    }

    /** 返回 [start, end) 字符区间列表，区间都落在行首/行尾，翻页后不会串行。 */
    private fun measurePages(text: String): List<Pair<Int, Int>> {
        if (text.isBlank()) return emptyList()
        val layout = measurer.measure(
            text = AnnotatedString(text),
            style = metrics.style,
            softWrap = true,
            maxLines = Int.MAX_VALUE,
            constraints = Constraints(maxWidth = metrics.contentWidthPx)
        )
        val result = ArrayList<Pair<Int, Int>>()
        var startLine = 0
        var pageTop = layout.getLineTop(0)
        for (line in 0 until layout.lineCount) {
            val bottom = layout.getLineBottom(line)
            if (bottom - pageTop > metrics.contentHeightPx && line > startLine) {
                val start = layout.getLineStart(startLine)
                val end = layout.getLineStart(line)
                if (end > start) result.add(start to end)
                startLine = line
                pageTop = layout.getLineTop(line)
            }
        }
        val lastStart = layout.getLineStart(startLine)
        if (lastStart < text.length) result.add(lastStart to text.length)
        return result
    }
}
