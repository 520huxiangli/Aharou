package com.aharou.debugmcp

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import com.aharou.AIEditorApp

/**
 * debug 变体专属的空壳 ContentProvider：唯一职责是在 App 启动时拉起 [DebugMcpServer]。
 *
 * 之所以用 ContentProvider 而不是改 `AIEditorApp` / `MainActivity`：Provider 由系统在应用启动
 * （Application.onCreate 之前）自动实例化，是「不改公共源码也能在启动时跑一段 debug 代码」的正规手段。
 * 注册在 `app/src/debug/AndroidManifest.xml`（变体专属，不碰共享的 main manifest；正式包不含本文件）。
 */
class DebugMcpProvider : ContentProvider() {

    override fun onCreate(): Boolean {
        val ctx = context ?: return false
        // 双保险：只在默认进程启动（:crash 等子进程不创建本 provider，这里再确认一次进程名）。
        if (AIEditorApp.getProcessNameCompat(ctx) != ctx.packageName) return false
        DebugMcpServer.start(ctx)
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0
}
