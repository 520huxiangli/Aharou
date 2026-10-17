package com.aharou.feature.agent.domain.tool.device

import android.content.Context
import android.media.AudioManager
import android.view.KeyEvent
import com.aharou.feature.agent.domain.tool.ToolResult
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 控制正在播放的媒体（播放/暂停/切歌/音量）。
 *
 * 走媒体键事件交给系统的当前媒体会话去处理，因此对任何播放器都有效（音乐 App、播客、我们自己的朗读），
 * 不需要知道是谁在播，也不需要任何权限。
 */
@Singleton
class MediaHandler @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {

    private fun audio(): AudioManager? =
        context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    suspend fun control(action: String): ToolResult = withContext(Dispatchers.Main) {
        val am = audio() ?: return@withContext ToolResult.Error("音频服务不可用", "NO_AUDIO")

        val keyCode = when (action) {
            "play" -> KeyEvent.KEYCODE_MEDIA_PLAY
            "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
            "toggle" -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "previous" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            "stop" -> KeyEvent.KEYCODE_MEDIA_STOP
            "volume_up", "volume_down", "mute" -> null
            else -> return@withContext ToolResult.Error(
                "不支持的 media 动作：$action（play / pause / toggle / next / previous / stop / volume_up / volume_down / mute）",
                "INVALID_ACTION",
            )
        }

        if (keyCode != null) {
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
            return@withContext ToolResult.Success(buildJsonObject {
                put("message", "已发送媒体控制：$action")
                put("action", action)
            })
        }

        when (action) {
            "volume_up" -> am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
            "volume_down" -> am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
            "mute" -> am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI)
        }
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val now = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        ToolResult.Success(buildJsonObject {
            put("message", "媒体音量：$now / $max")
            put("volume", now)
            put("volume_max", max)
        })
    }
}