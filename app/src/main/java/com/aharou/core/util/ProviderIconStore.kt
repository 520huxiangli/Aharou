package com.aharou.core.util

import android.content.Context
import android.net.Uri
import java.io.File

/**
 * 供应商自定义图标的本地存放：用户从相册选的图片拷进 `filesDir/provider-icons/`，
 * 数据库里只存这个绝对路径，换图片时把旧文件删掉。
 *
 * 不放 `filesDir/aharou` 下：那儿是 AI 配置目录，会被存储统计算进「AI 配置」，
 * 也会在容器里以 `/root/.aharou` 出现，图标没必要跟着进去。
 */
object ProviderIconStore {

    private const val DIR_NAME = "provider-icons"

    private fun dir(context: Context): File = File(context.filesDir, DIR_NAME).apply { mkdirs() }

    private fun fileFor(context: Context, providerId: String): File = File(dir(context), "$providerId.image")

    /** 导入一张图片作为 [providerId] 的图标，返回可直接存进 `customIcon` 的绝对路径；失败返回 null。 */
    fun import(context: Context, providerId: String, uri: Uri): String? = runCatching {
        val target = fileFor(context, providerId)
        context.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        } ?: return null
        target.absolutePath
    }.getOrNull()

    /** 删掉 [providerId] 的图标文件；没配过或删除失败都静默跳过。 */
    fun delete(context: Context, providerId: String) {
        runCatching { fileFor(context, providerId).takeIf { it.isFile }?.delete() }
    }
}
