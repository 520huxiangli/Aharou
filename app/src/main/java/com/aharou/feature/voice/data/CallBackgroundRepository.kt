package com.aharou.feature.voice.data

import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.aharou.core.datastore.preferencesCorruptionHandler
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

private val Context.callBackgroundDataStore by preferencesDataStore(
    name = "call_background",
    corruptionHandler = preferencesCorruptionHandler,
)

/**
 * 通话页自己的背景，和聊天页那张分开存。
 *
 * 通话页整屏都是文字和头像，聊天背景往往不够暗，硬套会看不清；
 * 分开存也意味着换聊天背景不会连带改掉通话的观感。
 */
@Singleton
internal class CallBackgroundRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {

    data class Config(
        /** 私有目录里的文件名；null 表示不设背景，用主题底色。 */
        val imagePath: String? = null,
        val blurDp: Int = 0,
        val dim: Float = 0f,
    ) {
        val enabled: Boolean get() = !imagePath.isNullOrBlank()
    }

    val config: Flow<Config> = context.callBackgroundDataStore.data.map { prefs ->
        Config(
            imagePath = prefs[KEY_IMAGE],
            blurDp = prefs[KEY_BLUR] ?: 0,
            dim = prefs[KEY_DIM] ?: 0f,
        )
    }

    suspend fun update(config: Config) {
        context.callBackgroundDataStore.edit { prefs ->
            val path = config.imagePath
            if (path.isNullOrBlank()) prefs.remove(KEY_IMAGE) else prefs[KEY_IMAGE] = path
            prefs[KEY_BLUR] = config.blurDp.coerceIn(0, 40)
            prefs[KEY_DIM] = config.dim.coerceIn(0f, 0.9f)
        }
    }

    suspend fun clearImage() {
        context.callBackgroundDataStore.edit { prefs -> prefs.remove(KEY_IMAGE) }
        withContext(Dispatchers.IO) {
            dir().listFiles()?.forEach { it.delete() }
        }
    }

    /**
     * 把选中的图复制进私有目录，返回文件名。
     *
     * 不存 content:// URI：那个读权限是临时的，重启后背景会变空白。
     * 只留最新一张，旧图删掉，免得越换越占空间。
     */
    suspend fun saveImage(uri: Uri): String? = withContext(Dispatchers.IO) {
        runCatching {
            val name = "call_bg_${System.currentTimeMillis()}.img"
            val target = File(dir(), name)
            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            } ?: return@runCatching null
            dir().listFiles()?.forEach { file -> if (file.name != name) file.delete() }
            name
        }.getOrNull()
    }

    /** 背景图目录；放 filesDir 下，卸载就跟着清掉。 */
    fun dir(): File = File(context.filesDir, "aharou/call_bg").apply { mkdirs() }

    private companion object {
        val KEY_IMAGE = stringPreferencesKey("call_bg_image")
        val KEY_BLUR = intPreferencesKey("call_bg_blur")
        val KEY_DIM = floatPreferencesKey("call_bg_dim")
    }
}
