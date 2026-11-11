package com.sqz.shuyueba

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import kotlin.math.roundToInt

private val ReaderBackground = Color(0xFF101012)
private val ReaderTextColor = Color(0xFFD2D2D7)
private val FooterColor = Color(0xFF63636B)
private val AccentColor = Color(0xFFEDEDF0)
private val PanelBackground = Color(0xFF141417)

private data class PageRef(val chapter: Int, val page: Int)

@Composable
fun ReaderScreen(
    book: LibraryBook,
    repository: EpubRepository,
    store: LibraryStore,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val measurer = rememberTextMeasurer(cacheSize = 6)
    val paginateDispatcher = remember {
        Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "shuyueba-layout") }
            .asCoroutineDispatcher()
    }
    DisposableEffect(Unit) {
        onDispose { paginateDispatcher.close() }
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(ReaderBackground)) {
        val density = LocalDensity.current
        val metrics = remember(maxWidth, maxHeight) {
            buildReaderMetrics(density, maxWidth, maxHeight)
        }
        val paginator = remember(metrics, measurer) { ChapterPaginator(measurer, metrics) }
        val session = remember(book.id, metrics) {
            ReaderSession(
                uri = Uri.parse(book.uri),
                repository = repository,
                paginator = paginator,
                paginateDispatcher = paginateDispatcher,
                imageTargetWidth = metrics.pageWidthPx,
                scope = scope
            )
        }
        LaunchedEffect(session) { session.open() }
        DisposableEffect(session) {
            onDispose { session.close() }
        }

        val meta = session.meta
        val error = session.errorMessage
        when {
            error != null -> ReaderMessage(error, onBack)
            meta == null -> ReaderMessage("正在排版…", onBack)
            else -> ReaderPager(
                session = session,
                meta = meta,
                metrics = metrics,
                startChapter = book.chapterIndex,
                startPage = book.pageIndex,
                onProgress = { chapter, page ->
                    scope.launch(Dispatchers.IO) { store.updateProgress(book.id, chapter, page) }
                },
                onExit = { chapter, page ->
                    scope.launch {
                        withContext(Dispatchers.IO) { store.updateProgress(book.id, chapter, page) }
                        onBack()
                    }
                }
            )
        }
    }
}

@Composable
private fun ReaderMessage(text: String, onBack: () -> Unit) {
    BackHandler { onBack() }
    Box(Modifier.fillMaxSize().background(ReaderBackground), contentAlignment = Alignment.Center) {
        Text(text = text, color = FooterColor, fontSize = 14.sp)
    }
}

@Composable
private fun ReaderPager(
    session: ReaderSession,
    meta: EpubBookMeta,
    metrics: ReaderMetrics,
    startChapter: Int,
    startPage: Int,
    onProgress: (chapter: Int, page: Int) -> Unit,
    onExit: (chapter: Int, page: Int) -> Unit
) {
    val scope = rememberCoroutineScope()
    var chapter by remember(session) {
        mutableIntStateOf(startChapter.coerceIn(0, (meta.chapters.size - 1).coerceAtLeast(0)))
    }
    var page by remember(session) { mutableIntStateOf(startPage.coerceAtLeast(0)) }
    var offset by remember(session) { mutableFloatStateOf(0f) }
    var panelOpen by remember(session) { mutableStateOf(false) }
    var animJob by remember(session) { mutableStateOf<Job?>(null) }

    val widthPx = metrics.pageWidthPx.toFloat()
    val layout = session.layouts[chapter]
    val pages = layout?.pages.orEmpty()
    val pageCount = pages.size

    LaunchedEffect(chapter) { session.ensure(chapter) }

    // 空章节自动跳过；页码越界（例如 EPUB 被替换）时回到有效页
    LaunchedEffect(chapter, layout) {
        if (layout == null) return@LaunchedEffect
        if (layout.pages.isEmpty()) {
            val next = chapterWithContent(chapter + 1, 1, meta, session)
            val prev = chapterWithContent(chapter - 1, -1, meta, session)
            when {
                next != null -> {
                    chapter = next
                    page = 0
                }
                prev != null -> {
                    chapter = prev
                    page = 0
                }
            }
        } else if (page > layout.pages.lastIndex) {
            page = layout.pages.lastIndex
        }
    }

    // 预载相邻章节，降低跨章翻页等待
    LaunchedEffect(chapter, page, pageCount) {
        if (pageCount == 0) return@LaunchedEffect
        if (page >= pageCount - 3) session.ensure(chapter + 1)
        if (page <= 2) session.ensure(chapter - 1)
    }

    // 进度落盘前稍作等待，快速连续翻页时只写最后一次
    LaunchedEffect(chapter, page, pageCount) {
        if (pageCount == 0) return@LaunchedEffect
        delay(200)
        onProgress(chapter, page)
    }

    fun nextRef(): PageRef? {
        if (pageCount > 0 && page + 1 < pageCount) return PageRef(chapter, page + 1)
        var c = chapter + 1
        while (c < meta.chapters.size) {
            val l = session.layouts[c]
            if (l == null) return PageRef(c, 0)
            if (l.pages.isNotEmpty()) return PageRef(c, 0)
            c++
        }
        return null
    }

    fun prevRef(): PageRef? {
        if (page > 0) return PageRef(chapter, page - 1)
        var c = chapter - 1
        while (c >= 0) {
            val l = session.layouts[c] ?: return null
            if (l.pages.isNotEmpty()) return PageRef(c, l.pages.lastIndex)
            c--
        }
        return null
    }

    fun animateOffset(target: Float, onEnd: () -> Unit) {
        animJob?.cancel()
        animJob = scope.launch {
            val anim = Animatable(offset)
            anim.animateTo(target, tween(durationMillis = 240, easing = FastOutSlowInEasing)) {
                offset = value
            }
            onEnd()
        }
    }

    fun settleBack() {
        animJob?.cancel()
        animJob = scope.launch {
            val anim = Animatable(offset)
            anim.animateTo(0f, spring(dampingRatio = 0.9f, stiffness = 700f)) {
                offset = value
            }
        }
    }

    fun turnNext() {
        val ref = nextRef()
        if (ref == null) {
            settleBack()
            return
        }
        animateOffset(-widthPx) {
            chapter = ref.chapter
            page = ref.page
            offset = 0f
        }
    }

    fun turnPrev() {
        val ref = prevRef()
        if (ref == null) {
            settleBack()
            return
        }
        animateOffset(widthPx) {
            chapter = ref.chapter
            page = ref.page
            offset = 0f
        }
    }

    val gestureCallbacks = ReaderGestureCallbacks(
        onPageDragStart = { animJob?.cancel() },
        onPageDrag = { delta ->
            animJob?.cancel()
            val target = (offset + delta).coerceIn(-widthPx, widthPx)
            offset = when {
                target < 0f && nextRef() == null -> target * 0.3f
                target > 0f && prevRef() == null -> target * 0.3f
                else -> target
            }
        },
        onPageDragEnd = { _, velocity ->
            val fraction = offset / widthPx
            when {
                fraction <= -ReaderGestureConfig.TurnCommitFraction ||
                    velocity <= -ReaderGestureConfig.TurnVelocity -> turnNext()
                fraction >= ReaderGestureConfig.TurnCommitFraction ||
                    velocity >= ReaderGestureConfig.TurnVelocity -> turnPrev()
                else -> settleBack()
            }
        },
        onOpenChapters = {
            animJob?.cancel()
            offset = 0f
            panelOpen = true
        },
        onExit = { onExit(chapter, page) }
    )

    val gestureModifier = rememberReaderGestureModifier(
        enabled = !panelOpen && layout != null && pageCount > 0,
        callbacks = gestureCallbacks
    )

    Box(
        Modifier
            .fillMaxSize()
            .background(ReaderBackground)
            .clipToBounds()
            .then(gestureModifier)
    ) {
        // 左右相邻页跟着手指移动，松手后补完动画
        if (offset < 0f) {
            nextRef()?.let { ref ->
                ReaderPageView(
                    session = session,
                    meta = meta,
                    metrics = metrics,
                    ref = ref,
                    modifier = Modifier
                        .fillMaxSize()
                        .offset { IntOffset((widthPx + offset).roundToInt(), 0) }
                )
            }
        } else if (offset > 0f) {
            prevRef()?.let { ref ->
                ReaderPageView(
                    session = session,
                    meta = meta,
                    metrics = metrics,
                    ref = ref,
                    modifier = Modifier
                        .fillMaxSize()
                        .offset { IntOffset((-widthPx + offset).roundToInt(), 0) }
                )
            }
        }

        ReaderPageView(
            session = session,
            meta = meta,
            metrics = metrics,
            ref = PageRef(chapter, page),
            modifier = Modifier
                .fillMaxSize()
                .offset { IntOffset(offset.roundToInt(), 0) }
        )

        BackHandler {
            if (panelOpen) panelOpen = false else onExit(chapter, page)
        }

        // 把左右边缘条带从系统返回手势区里排除，边缘手势才收得到事件
        GestureExclusionZones()

        AnimatedVisibility(
            visible = panelOpen,
            enter = fadeIn(tween(140)),
            exit = fadeOut(tween(160))
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color(0x99000000))
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() }
                    ) { panelOpen = false }
            )
        }

        AnimatedVisibility(
            visible = panelOpen,
            enter = slideInHorizontally(tween(240, easing = FastOutSlowInEasing)) { -it },
            exit = slideOutHorizontally(tween(200, easing = FastOutSlowInEasing)) { -it }
        ) {
            ChapterPanel(
                chapters = meta.chapters,
                currentChapter = chapter,
                onSelect = { index ->
                    panelOpen = false
                    animJob?.cancel()
                    offset = 0f
                    chapter = index
                    page = 0
                    session.ensure(index)
                }
            )
        }
    }
}

@Composable
private fun ReaderPageView(
    session: ReaderSession,
    meta: EpubBookMeta,
    metrics: ReaderMetrics,
    ref: PageRef,
    modifier: Modifier
) {
    val layout = session.layouts[ref.chapter]
    Box(modifier.background(ReaderBackground).clipToBounds()) {
        if (layout == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("排版中…", color = FooterColor, fontSize = 13.sp)
            }
        } else {
            when (val content = layout.pages.getOrNull(ref.page)) {
                is ChapterPage.TextPage -> {
                    val text = remember(layout, content) {
                        val end = content.end.coerceIn(0, layout.text.length)
                        val start = content.start.coerceIn(0, end)
                        layout.text.substring(start, end)
                    }
                    Text(
                        text = text,
                        style = metrics.style.copy(color = ReaderTextColor),
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(
                                start = metrics.horizontalPadding,
                                end = metrics.horizontalPadding,
                                top = metrics.topPadding,
                                bottom = metrics.bottomPadding + metrics.footerHeight
                            )
                    )
                }
                is ChapterPage.ImagePage -> ChapterImageView(
                    session = session,
                    metrics = metrics,
                    entry = content.entry
                )
                null -> Unit
            }

            Row(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(
                        start = metrics.horizontalPadding,
                        end = metrics.horizontalPadding,
                        bottom = metrics.bottomPadding
                    )
                    .height(metrics.footerHeight),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = meta.chapters.getOrNull(ref.chapter)?.title.orEmpty(),
                    color = FooterColor,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "${ref.page + 1} / ${layout.pages.size}",
                    color = FooterColor,
                    fontSize = 11.sp
                )
            }
        }
    }
}

@Composable
private fun ChapterImageView(
    session: ReaderSession,
    metrics: ReaderMetrics,
    entry: String
) {
    var bitmap by remember(entry) { mutableStateOf(session.cachedImage(entry)) }
    LaunchedEffect(entry) {
        if (bitmap == null) bitmap = session.loadImage(entry)
    }
    val image: Bitmap? = bitmap
    Box(
        Modifier
            .fillMaxSize()
            .padding(
                start = metrics.horizontalPadding,
                end = metrics.horizontalPadding,
                top = metrics.topPadding,
                bottom = metrics.bottomPadding + metrics.footerHeight
            ),
        contentAlignment = Alignment.Center
    ) {
        if (image != null) {
            Image(
                bitmap = image.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
                filterQuality = FilterQuality.Medium
            )
        } else {
            Text("图片加载中…", color = FooterColor, fontSize = 12.sp)
        }
    }
}

@Composable
private fun ChapterPanel(
    chapters: List<EpubChapter>,
    currentChapter: Int,
    onSelect: (Int) -> Unit
) {
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = currentChapter.coerceIn(0, (chapters.size - 1).coerceAtLeast(0))
    )
    Column(
        Modifier
            .fillMaxHeight()
            .fillMaxWidth(0.82f)
            .background(PanelBackground)
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) { }
    ) {
        Text(
            text = "目录",
            color = ReaderTextColor,
            fontSize = 19.sp,
            modifier = Modifier.padding(start = 20.dp, top = 32.dp)
        )
        Text(
            text = "${currentChapter + 1} / ${chapters.size}",
            color = FooterColor,
            fontSize = 12.sp,
            modifier = Modifier.padding(start = 20.dp, top = 6.dp, bottom = 14.dp)
        )
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            itemsIndexed(chapters, key = { index, _ -> index }) { index, item ->
                val selected = index == currentChapter
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(index) }
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier
                            .width(3.dp)
                            .height(16.dp)
                            .background(if (selected) AccentColor else Color.Transparent)
                    )
                    Text(
                        text = item.title,
                        color = if (selected) AccentColor else Color(0xFFB7B7BE),
                        fontSize = 14.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 12.dp)
                    )
                }
            }
        }
    }
}

/**
 * 右边缘的手势排除条带：告诉系统这部分区域不要用来做返回手势。
 * 系统对每条边缘的排除高度上限约 200dp，因此只覆盖顶部一段，不排除整屏，
 * 屏幕中部起手的系统返回手势仍然可用。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BoxScope.GestureExclusionZones() {
    Box(
        Modifier
            .align(Alignment.TopEnd)
            .width(ReaderGestureConfig.BackEdgeWidth)
            .height(ReaderGestureConfig.GestureExclusionHeight)
            .systemGestureExclusion()
    )
}

/** direction = 1 向后找，-1 向前找；返回尚未排版或确认有内容的章节序号。 */
private fun chapterWithContent(
    from: Int,
    direction: Int,
    meta: EpubBookMeta,
    session: ReaderSession
): Int? {
    var index = from
    while (index in meta.chapters.indices) {
        val layout = session.layouts[index]
        if (layout == null) return index
        if (layout.pages.isNotEmpty()) return index
        index += direction
    }
    return null
}
