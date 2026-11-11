package com.sqz.shuyueba

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 单个 EPUB 的阅读会话：解析目录、按需抽取章节文字并在后台分页。
 * 布局结果放在 mutableStateMap 里，主线程只读取，不做解析与测量。
 */
class ReaderSession(
    private val uri: Uri,
    private val repository: EpubRepository,
    private val paginator: ChapterPaginator,
    private val paginateDispatcher: CoroutineDispatcher,
    private val imageTargetWidth: Int,
    private val scope: CoroutineScope
) {

    var meta by mutableStateOf<EpubBookMeta?>(null)
        private set

    var loading by mutableStateOf(true)
        private set

    var errorMessage by mutableStateOf<String?>(null)
        private set

    val layouts = mutableStateMapOf<Int, ChapterLayout>()

    private val jobs = HashMap<Int, Job>()
    private var closed = false

    suspend fun open() {
        loading = true
        try {
            val parsed = withContext(Dispatchers.IO) { repository.parse(uri) }
            if (closed) return
            if (parsed.chapters.isEmpty()) {
                errorMessage = "这本书没有可阅读的章节"
            } else {
                meta = parsed
            }
        } catch (_: Exception) {
            if (!closed) errorMessage = "无法打开这本书"
        } finally {
            if (!closed) loading = false
        }
    }

    /** 请求排版某一章；重复请求会复用进行中的任务。 */
    fun ensure(chapter: Int) {
        val book = meta ?: return
        if (chapter !in book.chapters.indices) return
        if (closed || layouts.containsKey(chapter) || jobs.containsKey(chapter)) return
        val entry = book.chapters[chapter].entry
        jobs[chapter] = scope.launch {
            val layout = try {
                val content = withContext(Dispatchers.IO) { repository.loadChapter(uri, entry) }
                withContext(paginateDispatcher) { paginator.paginate(content) }
            } catch (_: Exception) {
                ChapterLayout("", emptyList())
            }
            if (!closed) layouts[chapter] = layout
            jobs.remove(chapter)
        }
    }

    fun cachedImage(entry: String): Bitmap? = repository.cachedImage(uri, entry)

    suspend fun loadImage(entry: String): Bitmap? =
        withContext(Dispatchers.IO) { repository.loadImage(uri, entry, imageTargetWidth) }

    fun close() {
        closed = true
        jobs.values.forEach { it.cancel() }
        jobs.clear()
    }
}
