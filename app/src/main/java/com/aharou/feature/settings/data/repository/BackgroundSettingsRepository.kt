package com.aharou.feature.settings.data.repository

import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.aharou.core.datastore.preferencesCorruptionHandler
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

private val Context.backgroundDataStore by preferencesDataStore(
    name = "background_prefs",
    corruptionHandler = preferencesCorruptionHandler
)

/**
 * 全局自定义背景图：图片文件拷贝到应用私有目录持久化（不依赖 content URI 授权），
 * 透明度与图片路径存 DataStore。路径为 null 表示未设置背景。
 */
@Singleton
class BackgroundSettingsRepository @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    companion object {
        const val DEFAULT_ALPHA = 0.15f
        /** 顶层水印模式下透明度上限：超过会盖住文字影响可读性。 */
        const val MAX_ALPHA = 0.2f
        /** 最小透明度：0 表示完全透明（背景不可见）。 */
        const val MIN_ALPHA = 0f
        private val IMAGE_PATH_KEY = stringPreferencesKey("background_image_path")
        private val ALPHA_KEY = floatPreferencesKey("background_alpha")

        /** 把 UI 百分比（0~100）线性映射为实际透明度（0~MAX_ALPHA）。 */
        fun sliderToAlpha(percent: Float): Float =
            MAX_ALPHA * (percent / 100f).coerceIn(0f, 1f)

        /** 把实际透明度反映射为 UI 百分比（0~100）。 */
        fun alphaToSlider(alpha: Float): Float =
            (alpha / MAX_ALPHA).coerceIn(0f, 1f) * 100f
    }

    /** 当前背景图文件绝对路径；null 表示未设置。 */
    val imagePathFlow: Flow<String?> = context.backgroundDataStore.data.map { it[IMAGE_PATH_KEY] }

    /** 背景图不透明度（0.05~0.2，顶层水印保证文字可读）。 */
    val alphaFlow: Flow<Float> = context.backgroundDataStore.data.map {
        (it[ALPHA_KEY] ?: DEFAULT_ALPHA).coerceIn(MIN_ALPHA, MAX_ALPHA)
    }

    /**
     * 选择新背景图：把 [uri] 拷贝到私有目录 backgrounds/ 后替换旧图。
     * 先写新文件成功再更新 DataStore，失败时旧背景保持不变。
     */
    suspend fun setBackgroundImage(uri: Uri) {
        val extension = extensionFor(uri)
        val target = File(context.filesDir, "backgrounds/bg_${System.currentTimeMillis()}.$extension")
        target.parentFile?.mkdirs()
        val copied = withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
                target.length() > 0
            }.getOrDefault(false)
        }
        if (!copied) return
        val oldPath = context.backgroundDataStore.data.first()[IMAGE_PATH_KEY]
        context.backgroundDataStore.edit { it[IMAGE_PATH_KEY] = target.absolutePath }
        oldPath?.let { runCatching { File(it).delete() } }
    }

    /** 移除背景：删图片文件并清路径。 */
    suspend fun clearBackground() {
        val oldPath = context.backgroundDataStore.data.first()[IMAGE_PATH_KEY]
        context.backgroundDataStore.edit { it.remove(IMAGE_PATH_KEY) }
        oldPath?.let { runCatching { File(it).delete() } }
    }

    suspend fun setBackgroundAlpha(alpha: Float) {
        context.backgroundDataStore.edit {
            it[ALPHA_KEY] = alpha.coerceIn(MIN_ALPHA, MAX_ALPHA)
        }
    }

    private fun extensionFor(uri: Uri): String = when (context.contentResolver.getType(uri)) {
        "image/png" -> "png"
        "image/webp" -> "webp"
        else -> "jpg"
    }
}
