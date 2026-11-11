package com.sqz.shuyueba

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract

object SafUtils {

    fun persistReadPermission(context: Context, uri: Uri) {
        try {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: SecurityException) {
            // 某些文档提供方不支持持久授权，本次会话内仍可读取
        }
    }

    fun displayName(context: Context, uri: Uri): String? {
        return try {
            context.contentResolver.query(
                uri,
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        } catch (_: Exception) {
            null
        }
    }

    /** 书柜显示用的书名：文件显示名去掉扩展名。 */
    fun bookTitle(context: Context, uri: Uri): String? =
        displayName(context, uri)
            ?.substringBeforeLast('.')
            ?.trim()
            ?.takeIf { it.isNotBlank() }
}
