package com.sqz.shuyueba

import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

private val ShelfBackground = Color(0xFF0D0D0F)
private val CoverPlaceholder = Color(0xFF1A1A1E)
private val DeleteButtonColor = Color(0xFFC62828)
private val DeleteButtonActiveColor = Color(0xFFE53935)

/** 长按该时长后进入可删除状态。 */
private const val DeleteLongPressMillis = 1000L

@Composable
fun ShelfScreen(
    books: List<LibraryBook>,
    store: LibraryStore,
    repository: EpubRepository,
    targetWidth: Int,
    onOpenBook: (LibraryBook) -> Unit,
    onLibraryChanged: (List<LibraryBook>) -> Unit
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    var adding by remember { mutableStateOf(false) }

    var deleteMode by remember { mutableStateOf(false) }
    var draggingBookId by remember { mutableStateOf<String?>(null) }
    var dragFinger by remember { mutableStateOf(Offset.Zero) }
    var dragGrab by remember { mutableStateOf(Offset.Zero) }
    var dragSize by remember { mutableStateOf(IntSize.Zero) }
    var rootOrigin by remember { mutableStateOf(Offset.Zero) }
    var deleteBounds by remember { mutableStateOf(Rect.Zero) }

    fun exitDeleteMode() {
        deleteMode = false
        draggingBookId = null
    }

    fun showToast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    val addBook: (Uri) -> Unit = { uri ->
        scope.launch {
            adding = true
            try {
                SafUtils.persistReadPermission(context, uri)
                // 仍然解析一次，确认选中的文件确实是可读的 EPUB
                withContext(Dispatchers.IO) { repository.parse(uri) }
                val title = SafUtils.bookTitle(context, uri) ?: "未命名"
                val added = store.add(
                    LibraryBook(
                        id = uri.toString(),
                        title = title,
                        uri = uri.toString(),
                        chapterIndex = 0,
                        pageIndex = 0,
                        lastReadAt = 0L,
                        addedAt = System.currentTimeMillis()
                    )
                )
                if (added) showToast("已添加《$title》") else showToast("这本书已在书柜中")
                onLibraryChanged(store.load())
            } catch (_: Exception) {
                showToast("不是有效的 EPUB 文件")
            } finally {
                adding = false
            }
        }
    }

    val fileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let(addBook) }

    Box(
        Modifier
            .fillMaxSize()
            .background(ShelfBackground)
            .onGloballyPositioned { rootOrigin = it.boundsInWindow().topLeft }
            .pointerInput(deleteMode) {
                if (deleteMode) {
                    // 删除模式下点击空白处退出，避免状态卡死
                    detectTapGestures { exitDeleteMode() }
                }
            }
    ) {
        Column(Modifier.fillMaxSize()) {
            Text(
                text = "书阅吧",
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 20.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(start = 18.dp, top = 22.dp, bottom = 14.dp)
            )
            if (books.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "还没有书，点击右下角 + 添加 EPUB",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 14.sp
                    )
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 2.dp, bottom = 96.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    userScrollEnabled = !deleteMode,
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(books, key = { it.id }) { book ->
                        BookCell(
                            book = book,
                            repository = repository,
                            targetWidth = (targetWidth / 3).coerceAtLeast(180),
                            deleteMode = deleteMode,
                            isDragging = draggingBookId == book.id,
                            onClick = { onOpenBook(book) },
                            onLongPress = { deleteMode = true },
                            onDragStart = { finger, grab, cellSize ->
                                deleteMode = true
                                draggingBookId = book.id
                                dragFinger = finger
                                dragGrab = grab
                                dragSize = cellSize
                            },
                            onDragMove = { finger -> dragFinger = finger },
                            onDragEnd = {
                                val target = books.firstOrNull { it.id == draggingBookId }
                                draggingBookId = null
                                deleteMode = false
                                if (target != null && deleteBounds.contains(dragFinger)) {
                                    store.remove(target.id)
                                    onLibraryChanged(store.load())
                                }
                            },
                            onTapInDeleteMode = { exitDeleteMode() }
                        )
                    }
                }
            }
        }

        // 跟随手指的拖拽预览；原卡片同时降低不透明度
        val dragBook = books.firstOrNull { it.id == draggingBookId }
        if (dragBook != null && dragSize.width > 0 && dragSize.height > 0) {
            val previewWidth = with(density) { dragSize.width.toDp() }
            val previewHeight = with(density) { dragSize.height.toDp() }
            val previewX = (dragFinger.x - dragGrab.x - rootOrigin.x).roundToInt()
            val previewY = (dragFinger.y - dragGrab.y - rootOrigin.y).roundToInt()
            Column(
                Modifier
                    .offset { IntOffset(previewX, previewY) }
                    .size(previewWidth, previewHeight)
                    .alpha(0.92f)
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .shadow(14.dp, RoundedCornerShape(6.dp))
                        .clip(RoundedCornerShape(6.dp))
                        .background(CoverPlaceholder)
                ) {
                    BookCover(dragBook, repository, (targetWidth / 3).coerceAtLeast(180))
                }
                Text(
                    text = dragBook.title,
                    color = MaterialTheme.colorScheme.onBackground,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 7.dp)
                )
            }
        }

        if (deleteMode) {
            val overTarget = draggingBookId != null && deleteBounds.contains(dragFinger)
            val buttonScale by animateFloatAsState(
                targetValue = if (overTarget) 1.16f else 1f,
                label = "deleteButtonScale"
            )
            FloatingActionButton(
                onClick = { },
                shape = CircleShape,
                containerColor = if (overTarget) DeleteButtonActiveColor else DeleteButtonColor,
                contentColor = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 22.dp, bottom = 94.dp)
                    .scale(buttonScale)
                    .onGloballyPositioned { deleteBounds = it.boundsInWindow() }
            ) {
                Text(text = "删除", fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
        }

        Box(Modifier.align(Alignment.BottomEnd).padding(22.dp)) {
            FloatingActionButton(
                onClick = {
                    fileLauncher.launch(
                        arrayOf(
                            "application/epub+zip",
                            "application/octet-stream",
                            "application/zip"
                        )
                    )
                },
                shape = CircleShape,
                containerColor = Color(0xFFEDEDF0),
                contentColor = Color(0xFF0D0D0F)
            ) {
                Text(text = "+", fontSize = 30.sp, fontWeight = FontWeight.Light)
            }
        }

        if (adding) {
            Box(
                Modifier.fillMaxSize().background(Color(0x99000000)),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(32.dp),
                    color = MaterialTheme.colorScheme.onBackground,
                    strokeWidth = 2.dp
                )
            }
        }
    }
}

@Composable
private fun BookCell(
    book: LibraryBook,
    repository: EpubRepository,
    targetWidth: Int,
    deleteMode: Boolean,
    isDragging: Boolean,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    onDragStart: (finger: Offset, grab: Offset, size: IntSize) -> Unit,
    onDragMove: (finger: Offset) -> Unit,
    onDragEnd: () -> Unit,
    onTapInDeleteMode: () -> Unit
) {
    var cellOrigin by remember { mutableStateOf(Offset.Zero) }
    var cellSize by remember { mutableStateOf(IntSize.Zero) }

    val deleteModeState = rememberUpdatedState(deleteMode)
    val onClickState = rememberUpdatedState(onClick)
    val longPressState = rememberUpdatedState(onLongPress)
    val dragStartState = rememberUpdatedState(onDragStart)
    val dragMoveState = rememberUpdatedState(onDragMove)
    val dragEndState = rememberUpdatedState(onDragEnd)
    val tapInDeleteModeState = rememberUpdatedState(onTapInDeleteMode)

    Column(
        Modifier
            .onGloballyPositioned {
                cellOrigin = it.boundsInWindow().topLeft
                cellSize = it.size
            }
            .alpha(if (isDragging) 0.35f else 1f)
            .pointerInput(book.id) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val startPosition = down.position
                    val origin = cellOrigin
                    val size = cellSize

                    if (deleteModeState.value) {
                        // 删除模式：按住超过滑动阈值即视为拖拽；松手未拖动则退出删除状态
                        down.consume()
                        var dragging = false
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) {
                                if (dragging) dragMoveState.value(origin + change.position)
                                break
                            }
                            if (!dragging &&
                                (change.position - startPosition).getDistance() > viewConfiguration.touchSlop
                            ) {
                                dragging = true
                                dragStartState.value(origin + down.position, startPosition, size)
                            }
                            if (dragging) {
                                change.consume()
                                dragMoveState.value(origin + change.position)
                            }
                        }
                        if (dragging) dragEndState.value() else tapInDeleteModeState.value()
                        return@awaitEachGesture
                    }

                    // 正常模式：1 秒内抬手或滑动都交还给网格；超时才进入长按
                    var abortedByMove = false
                    var lifted = false
                    val timedOut = withTimeoutOrNull(DeleteLongPressMillis) {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id }
                            if (change == null || !change.pressed) {
                                lifted = true
                                break
                            }
                            if ((change.position - startPosition).getDistance() > viewConfiguration.touchSlop) {
                                abortedByMove = true
                                break
                            }
                        }
                        Unit
                    } == null

                    if (timedOut) {
                        // 长按成立：进入删除模式，并直接接管这次按住的拖动
                        longPressState.value()
                        dragStartState.value(origin + down.position, startPosition, size)
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) {
                                dragMoveState.value(origin + change.position)
                                break
                            }
                            change.consume()
                            dragMoveState.value(origin + change.position)
                        }
                        dragEndState.value()
                    } else if (lifted && !abortedByMove) {
                        onClickState.value()
                    }
                }
            }
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(0.75f)
                .clip(RoundedCornerShape(6.dp))
                .background(CoverPlaceholder)
        ) {
            BookCover(book, repository, targetWidth)
        }
        Text(
            text = book.title,
            color = MaterialTheme.colorScheme.onBackground,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 7.dp)
        )
    }
}

@Composable
private fun BookCover(book: LibraryBook, repository: EpubRepository, targetWidth: Int) {
    val coverKey = book.uri
    var bitmap by remember(coverKey) { mutableStateOf(CoverBitmapCache.get(coverKey)) }
    var failed by remember(coverKey) { mutableStateOf(false) }

    LaunchedEffect(coverKey) {
        val cached = CoverBitmapCache.get(coverKey)
        if (cached != null) {
            bitmap = cached
            return@LaunchedEffect
        }
        val loaded = withContext(Dispatchers.IO) {
            runCatching { repository.loadCover(Uri.parse(coverKey), targetWidth) }.getOrNull()
        }
        if (loaded != null) {
            CoverBitmapCache.put(coverKey, loaded)
            bitmap = loaded
        } else {
            failed = true
        }
    }

    val image: Bitmap? = bitmap
    if (image != null) {
        Image(
            bitmap = image.asImageBitmap(),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )
    } else if (failed) {
        // 书里没有任何可用封面（例如纯文字 EPUB）时显示占位封面，避免整块空白
        Box(Modifier.fillMaxSize().padding(10.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "EPUB",
                    color = Color(0xFF5E5E66),
                    fontSize = 10.sp,
                    letterSpacing = 1.sp
                )
                Text(
                    text = book.title,
                    color = Color(0xFFB9B9BE),
                    fontSize = 12.sp,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
        }
    }
}
