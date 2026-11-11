package com.sqz.shuyueba

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** 一本书就是一个 EPUB 文件；chapterIndex + pageIndex 记录精确阅读位置。 */
data class LibraryBook(
    val id: String,
    val title: String,
    val uri: String,
    val chapterIndex: Int,
    val pageIndex: Int,
    val lastReadAt: Long,
    val addedAt: Long
)

class LibraryStore(context: Context) {

    private val prefs = context.getSharedPreferences("library", Context.MODE_PRIVATE)

    fun load(): List<LibraryBook> {
        val raw = prefs.getString(KEY_BOOKS, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            val books = ArrayList<LibraryBook>(array.length())
            for (i in 0 until array.length()) {
                val o = array.getJSONObject(i)
                val uri = o.optString("uri")
                if (uri.isEmpty()) continue
                books.add(
                    LibraryBook(
                        id = o.optString("id", uri),
                        title = o.optString("title", "未命名"),
                        uri = uri,
                        chapterIndex = o.optInt("chapterIndex", 0),
                        pageIndex = o.optInt("pageIndex", 0),
                        lastReadAt = o.optLong("lastReadAt", 0L),
                        addedAt = o.optLong("addedAt", 0L)
                    )
                )
            }
            books.sortedWith(bookOrder)
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun save(books: List<LibraryBook>) {
        val array = JSONArray()
        books.forEach { b ->
            array.put(
                JSONObject().apply {
                    put("id", b.id)
                    put("title", b.title)
                    put("uri", b.uri)
                    put("chapterIndex", b.chapterIndex)
                    put("pageIndex", b.pageIndex)
                    put("lastReadAt", b.lastReadAt)
                    put("addedAt", b.addedAt)
                }
            )
        }
        prefs.edit().putString(KEY_BOOKS, array.toString()).apply()
    }

    @Synchronized
    fun add(book: LibraryBook): Boolean {
        val books = load()
        if (books.any { it.id == book.id }) return false
        save(books + book)
        return true
    }

    /** 只从书柜移除条目，不删除手机里的 EPUB 原文件。 */
    @Synchronized
    fun remove(id: String): Boolean {
        val books = load()
        if (books.none { it.id == id }) return false
        save(books.filterNot { it.id == id })
        return true
    }

    @Synchronized
    fun updateProgress(id: String, chapterIndex: Int, pageIndex: Int) {
        val books = load()
        val index = books.indexOfFirst { it.id == id }
        if (index < 0) return
        val updated = books[index].copy(
            chapterIndex = chapterIndex,
            pageIndex = pageIndex,
            lastReadAt = System.currentTimeMillis()
        )
        save(books.toMutableList().also { it[index] = updated })
    }

    /** 打开书时把最近阅读时间提到最前，不改变已记录的阅读位置。 */
    @Synchronized
    fun touch(id: String) {
        val books = load()
        val index = books.indexOfFirst { it.id == id }
        if (index < 0) return
        val updated = books[index].copy(lastReadAt = System.currentTimeMillis())
        save(books.toMutableList().also { it[index] = updated })
    }

    fun lastReadBook(): LibraryBook? =
        load().maxByOrNull { it.lastReadAt }?.takeIf { it.lastReadAt > 0L }

    /** 旧版本把 EPUB 内部的 dc:title 当书名，这里统一改成文件名（去掉扩展名）。 */
    @Synchronized
    fun migrateTitles(titleOf: (uri: String) -> String?) {
        val books = load()
        var changed = false
        val updated = books.map { book ->
            val name = titleOf(book.uri) ?: return@map book
            if (name == book.title) book else {
                changed = true
                book.copy(title = name)
            }
        }
        if (changed) save(updated)
    }

    companion object {
        private const val KEY_BOOKS = "books_v1"

        private val bookOrder =
            compareByDescending<LibraryBook> { it.lastReadAt }.thenByDescending { it.addedAt }
    }
}
