package com.sqz.shuyueba

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/**
 * 阅读页手势的统一配置。方向与阈值都集中在这里，后续调整只改本文件。
 *
 * 手势约定：
 *  - 屏幕中部向左滑 → 下一页；向右滑 → 上一页
 *  - 向上滑 → 下一页（走同一套翻页逻辑，竖向位移等价为水平进度）
 *  - 向下滑 → 拉出章节列表
 *  - 点击屏幕左侧 [ChapterTapZoneFraction] 区域 → 拉出章节列表（不依赖滑动方向的兜底入口）
 *  - 屏幕右边缘起手向左滑 → 返回书柜
 *  - 边缘手势优先，一旦判定成功不再触发翻页
 */
object ReaderGestureConfig {
    /**
     * 右边缘触发区宽度（返回书柜）。
     * 全面屏手势导航会在左右边缘保留 20~32dp 的返回区，这里略放宽并用
     * [GestureExclusionHeight] 的排除区把系统手势让开。
     */
    val BackEdgeWidth = 40.dp

    /** 返回书柜需要的最小水平位移（向左为负）。 */
    val EdgeTriggerDistance = 36.dp

    /** 点击左侧这一比例宽度的区域可唤出章节列表；不依赖滑动方向，避开系统返回手势区。 */
    const val ChapterTapZoneFraction = 0.25f

    /**
     * 右边缘申请手势排除区的高度。
     *
     * 系统对每条边缘的 systemGestureExclusion 上限约为 200dp（从屏幕顶部算起），
     * 超出部分会被忽略，所以按上限设置；且只排除窄条带而不是整屏，
     * 保证屏幕中部起手的系统返回手势仍能正常退出阅读页。
     */
    val GestureExclusionHeight = 200.dp

    /** 翻页提交阈值：拖动超过页宽该比例即翻页。 */
    const val TurnCommitFraction = 0.22f

    /** 翻页提交速度阈值（px/s）。 */
    const val TurnVelocity = 900f

    /** 主方向判定：水平位移超过垂直位移该倍数才算横向手势。 */
    const val HorizontalDominance = 1.2f

    /** 主方向判定：垂直位移超过水平位移该倍数才算竖向手势。 */
    const val VerticalDominance = 1.2f
}

enum class ReaderGestureMode { NONE, PAGE, CHAPTERS, EXIT }

class ReaderGestureCallbacks(
    val onPageDragStart: () -> Unit,
    val onPageDrag: (deltaX: Float) -> Unit,
    val onPageDragEnd: (totalDx: Float, velocityX: Float) -> Unit,
    val onOpenChapters: () -> Unit,
    val onExit: () -> Unit
)

suspend fun PointerInputScope.detectReaderGestures(callbacks: ReaderGestureCallbacks) {
    val backEdgePx = ReaderGestureConfig.BackEdgeWidth.toPx()
    val exitTriggerPx = ReaderGestureConfig.EdgeTriggerDistance.toPx()
    val tapZonePx = size.width * ReaderGestureConfig.ChapterTapZoneFraction
    val slop = viewConfiguration.touchSlop
    val tapTimeout = viewConfiguration.longPressTimeoutMillis

    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val startX = down.position.x
        val startY = down.position.y
        val fromBackEdge = startX >= size.width - backEdgePx
        // 手指 down 落在右边缘就立即声明本次手势的归属，不再让其它处理器竞争
        if (fromBackEdge) down.consume()

        val tracker = VelocityTracker()
        tracker.addPosition(down.uptimeMillis, down.position)

        var mode = ReaderGestureMode.NONE
        var dx = 0f
        var dy = 0f
        var maxPointers = 1
        // 翻页手势的进度轴：横向用 dx，上滑翻页用 dy（两者符号约定一致，左滑/上滑都是负值）
        var lastDrag = 0f
        var verticalPage = false
        var lastUptime = down.uptimeMillis

        while (true) {
            val event = awaitPointerEvent()
            maxPointers = max(maxPointers, event.changes.count { it.pressed })
            val change = event.changes.firstOrNull { it.id == down.id }
            if (change != null) {
                tracker.addPosition(change.uptimeMillis, change.position)
                dx = change.position.x - startX
                dy = change.position.y - startY
                lastUptime = change.uptimeMillis
            }

            if (mode == ReaderGestureMode.NONE && change != null && maxPointers == 1 &&
                hypot(dx, dy) > slop
            ) {
                // 滑过触摸阈值后先判主方向：横竖各有独立的倍数阈值，斜滑不会被两边同时认领
                val horizontal = abs(dx) > abs(dy) * ReaderGestureConfig.HorizontalDominance
                val vertical = abs(dy) > abs(dx) * ReaderGestureConfig.VerticalDominance
                if (horizontal) {
                    mode = if (fromBackEdge && dx < 0) ReaderGestureMode.EXIT else ReaderGestureMode.PAGE
                    change.consume()
                    if (mode == ReaderGestureMode.PAGE) {
                        verticalPage = false
                        lastDrag = dx
                        callbacks.onPageDragStart()
                    }
                } else if (vertical) {
                    if (dy < 0) {
                        // 上滑 = 下一页：复用横向翻页那条路，把竖向位移当作等价的水平位移驱动同一个 pager
                        mode = ReaderGestureMode.PAGE
                        verticalPage = true
                        lastDrag = dy
                        change.consume()
                        callbacks.onPageDragStart()
                    } else {
                        // 下滑唤出目录
                        mode = ReaderGestureMode.CHAPTERS
                        change.consume()
                        callbacks.onOpenChapters()
                    }
                }
            } else if (mode != ReaderGestureMode.NONE) {
                change?.consume()
                if (mode == ReaderGestureMode.PAGE && change != null) {
                    val drag = if (verticalPage) dy else dx
                    callbacks.onPageDrag(drag - lastDrag)
                    lastDrag = drag
                }
            }

            if (event.changes.none { it.pressed }) break
        }

        val velocity = tracker.calculateVelocity()
        val pageDx = if (verticalPage) dy else dx
        val pageVelocity = if (verticalPage) velocity.y else velocity.x
        when (mode) {
            ReaderGestureMode.PAGE -> callbacks.onPageDragEnd(pageDx, pageVelocity)
            ReaderGestureMode.EXIT -> if (dx <= -exitTriggerPx) callbacks.onExit()
            ReaderGestureMode.CHAPTERS -> Unit
            ReaderGestureMode.NONE -> {
                // 兜底入口：轻点屏幕左侧区域唤出目录
                val travel = hypot(dx, dy)
                val duration = lastUptime - down.uptimeMillis
                if (travel <= slop && duration <= tapTimeout && startX <= tapZonePx) {
                    callbacks.onOpenChapters()
                }
            }
        }
    }
}

@Composable
fun rememberReaderGestureModifier(
    enabled: Boolean,
    callbacks: ReaderGestureCallbacks
): Modifier {
    val latest = rememberUpdatedState(callbacks)
    return remember(enabled) {
        Modifier.pointerInput(enabled) {
            if (!enabled) return@pointerInput
            detectReaderGestures(
                ReaderGestureCallbacks(
                    onPageDragStart = { latest.value.onPageDragStart() },
                    onPageDrag = { latest.value.onPageDrag(it) },
                    onPageDragEnd = { dx, v -> latest.value.onPageDragEnd(dx, v) },
                    onOpenChapters = { latest.value.onOpenChapters() },
                    onExit = { latest.value.onExit() }
                )
            )
        }
    }
}
