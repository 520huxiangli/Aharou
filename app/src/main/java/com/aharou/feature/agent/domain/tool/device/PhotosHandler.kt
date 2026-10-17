package com.aharou.feature.agent.domain.tool.device

import android.content.Context
import android.provider.MediaStore
import com.aharou.core.util.FileLogger
import com.aharou.feature.agent.domain.tool.ToolResult
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Date
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 相册查询：从 MediaStore 取最近的图片元数据（不返回图片本体，避免把大图塞进上下文）。
 *
 * 依赖「所有文件访问」或相册读权限；两者都没有时 MediaStore 会返回空集，这里会翻译成
 * 明确的权限指引而不是让模型以为是「相册是空的」。
 */
@Singleton
class PhotosHandler @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {

    private companion object {
        const val TAG = "PhotosHandler"
        const val DEFAULT_LIMIT = 20
        const val MAX_LIMIT = 200
    }

    suspend fun recent(limitRaw: Int?, newerThanMs: Long?): ToolResult = withContext(Dispatchers.IO) {
        val limit = (limitRaw ?: DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT,
            MediaStore.Images.Media.DATA,
        )
        val selection = newerThanMs?.let { "${MediaStore.Images.Media.DATE_ADDED} >= ?" }
        val args = newerThanMs?.let { arrayOf((it / 1000L).toString()) }

        runCatching {
            val items = mutableListOf<Map<String, Any>>()
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                args,
                "${MediaStore.Images.Media.DATE_ADDED} DESC",
            )?.use { cursor ->
                val idIdx = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val nameIdx = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                val dateIdx = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
                val sizeIdx = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
                val wIdx = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH)
                val hIdx = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT)
                val dataIdx = cursor.getColumnIndex(MediaStore.Images.Media.DATA)
                while (cursor.moveToNext() && items.size < limit) {
                    items += mapOf(
                        "name" to (cursor.getString(nameIdx) ?: ""),
                        "date" to Date(cursor.getLong(dateIdx) * 1000L).toString(),
                        "size_kb" to cursor.getLong(sizeIdx) / 1024,
                        "width" to cursor.getInt(wIdx),
                        "height" to cursor.getInt(hIdx),
                        "path" to (if (dataIdx >= 0) cursor.getString(dataIdx).orEmpty() else ""),
                        "uri" to android.content.ContentUris.withAppendedId(
                            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                            cursor.getLong(idIdx),
                        ).toString(),
                    )
                }
            }
            items
        }.fold(
            onSuccess = { items ->
                if (items.isEmpty()) {
                    ToolResult.Success(buildJsonObject {
                        put("count", 0)
                        put("note", "没有查到图片。若相册里确实有照片，多半是缺权限：" +
                            "到系统设置给 Aharou 开「照片和视频」或「所有文件访问」。")
                    })
                } else {
                    ToolResult.Success(buildJsonObject {
                        put("count", items.size)
                        put("photos", buildJsonArray {
                            items.forEach { item ->
                                add(buildJsonObject {
                                    put("name", item["name"].toString())
                                    put("date", item["date"].toString())
                                    put("size_kb", item["size_kb"] as Long)
                                    put("width", item["width"] as Int)
                                    put("height", item["height"] as Int)
                                    put("path", item["path"].toString())
                                    put("uri", item["uri"].toString())
                                })
                            }
                        })
                    })
                }
            },
            onFailure = { e ->
                FileLogger.e(TAG, "查询相册失败", e)
                ToolResult.Error("查询相册失败：${e.message}", "PHOTOS_QUERY_FAILED")
            },
        )
    }
}