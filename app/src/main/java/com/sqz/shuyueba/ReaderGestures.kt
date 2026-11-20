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
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/**
 * 阅读页手势的统一配置。方向与阈值都集中在这里，后续调整只改本文件。
 *
 * 手势约定：
 *  - 向左滑 / 向上滑 → 下一页
 *  - 向右滑 / 向下滑 → 上一页
 *  - 点击屏幕左侧 [ChapterTapZoneFraction] 区域 → 拉出章节列表（不依赖滑动方向的兜底入口）
 *  - 返回书柜由系统返回事件处理，不在阅读手势中拦截边缘滑动
 */
object ReaderGestureConfig {
    /** 点击左侧这一比例宽度的区域可唤出章节列表；不依赖滑动方向，避开系统返回手势区。 */
    const val ChapterTapZoneFraction = 0.25f

    /** 翻页提交阈值：拖动超过页宽该比例即翻页。 */
    const val TurnCommitFraction = 0.22f

    /** 翻页提交速度阈值（px/s）。 */
    const val TurnVelocity = 900f

    /** 主方向判定：水平位移超过垂直位移该倍数才算横向手势。 */
    const val HorizontalDominance = 1.2f

    /** 主方向判定：垂直位移超过水平位移该倍数才算竖向手势。 */
    const val VerticalDominance = 1.2f
}

enum class ReaderGestureMode { NONE, PAGE }

class ReaderGestureCallbacks(
    val onPageDragStart: () -> Unit,
    val onPageDrag: (deltaX: Float) -> Unit,
    val onPageDragEnd: (totalDx: Float, velocityX: Float) -> Unit,
    val onOpenChapters: () -> Unit
)

suspend fun PointerInputScope.detectReaderGestures(callbacks: ReaderGestureCallbacks) {
    val tapZonePx = size.width * ReaderGestureConfig.ChapterTapZoneFraction
    val slop = viewConfiguration.touchSlop
    val tapTimeout = viewConfiguration.longPressTimeoutMillis

    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val startX = down.position.x
        val startY = down.position.y

        val tracker = VelocityTracker()
        tracker.addPosition(down.uptimeMillis, down.position)

        var mode = ReaderGestureMode.NONE
        var dx = 0f
        var dy = 0f
        var maxPointers = 1
        // 翻页手势的进度轴：横向用 dx，竖向用 dy；左滑/上滑为负值，右滑/下滑为正值
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
                    mode = ReaderGestureMode.PAGE
                    verticalPage = false
                    lastDrag = dx
                    change.consume()
                    callbacks.onPageDragStart()
                } else if (vertical) {
                    // 上滑下一页、下滑上一页，竖向位移复用横向翻页的进度与提交逻辑
                    mode = ReaderGestureMode.PAGE
                    verticalPage = true
                    lastDrag = dy
                    change.consume()
                    callbacks.onPageDragStart()
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
                    onOpenChapters = { latest.value.onOpenChapters() }
                )
            )
        }
    }
}
