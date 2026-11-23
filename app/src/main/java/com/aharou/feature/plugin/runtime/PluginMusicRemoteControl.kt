package com.aharou.feature.plugin.runtime

import android.content.ComponentName
import android.content.Context
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.SystemClock
import com.aharou.feature.agent.domain.tool.device.DeviceNotificationListener
import com.aharou.feature.plugin.domain.PluginHostException
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * `music.*` 的兜底通道：本机播放器里没有音源时，遥控用户正在用的播放器。
 *
 * 只在 [PluginMusicPlayer] 还没装过音源时启用，语义是「控制别人的播放器」而非「自己播放」，
 * 所以只能做播放/暂停/停止/跳转/查状态；音量改的是系统媒体音量（对方应用的内部音量读不到），
 * 循环模式也读不到——平台 [MediaController] 不暴露 repeat mode，这里如实不给这个字段。
 *
 * 拿活跃会话需要「通知使用权」：`MediaSessionManager.getActiveSessions` 只接受已授权的
 * 通知监听组件，这里复用应用已有的 [DeviceNotificationListener]，不为插件另建监听。
 */
@Singleton
class PluginMusicRemoteControl @Inject constructor(
    @param:ApplicationContext private val context: Context
) {

    enum class Availability { READY, NO_PERMISSION, NO_SESSION }

    fun availability(): Availability {
        val sessions = try {
            mediaSessionManager().getActiveSessions(listenerComponent())
        } catch (e: SecurityException) {
            return Availability.NO_PERMISSION
        }
        return if (sessions.isEmpty()) Availability.NO_SESSION else Availability.READY
    }

    suspend fun play(): JsonObject = withContext(Dispatchers.IO) {
        withController("已开始播放") { it.transportControls.play() }
    }

    suspend fun pause(): JsonObject = withContext(Dispatchers.IO) {
        withController("已暂停") { it.transportControls.pause() }
    }

    suspend fun stop(): JsonObject = withContext(Dispatchers.IO) {
        withController("已停止") { it.transportControls.stop() }
    }

    suspend fun seek(positionMs: Long): JsonObject = withContext(Dispatchers.IO) {
        if (positionMs < 0) throw PluginHostException("PARAM_INVALID", "position_ms 不能为负")
        withController("") { controller ->
            val duration = controller.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
            val target = if (duration > 0) positionMs.coerceAtMost(duration) else positionMs
            controller.transportControls.seekTo(target)
        }
    }

    suspend fun status(): JsonObject = withContext(Dispatchers.IO) {
        val controller = currentController()
        snapshot(controller, "")
    }

    /** 对方应用的音量读不到也设不了，只能改系统媒体音量；如实回报这一限制。 */
    suspend fun setSystemVolume(fraction: Double): JsonObject = withContext(Dispatchers.IO) {
        if (fraction < 0.0 || fraction > 1.0) {
            throw PluginHostException("PARAM_INVALID", "volume 必须在 0~1 之间")
        }
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: throw PluginHostException("NO_AUDIO", "音频服务不可用")
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (max <= 0) throw PluginHostException("NO_AUDIO", "媒体音量档位不可用")
        audio.setStreamVolume(
            AudioManager.STREAM_MUSIC,
            Math.round(fraction * max).toInt().coerceIn(0, max),
            0
        )
        buildJsonObject {
            put("state", "remote_volume")
            put("remote", true)
            put("volume", systemVolumeFraction())
            put("message", "已调整系统媒体音量（遥控的是别人的播放器，改不了它自己的音量）")
        }
    }

    private fun withController(
        message: String,
        action: (MediaController) -> Unit
    ): JsonObject {
        val controller = currentController()
        action(controller)
        return snapshot(controller, message)
    }

    private fun currentController(): MediaController {
        val sessions = try {
            mediaSessionManager().getActiveSessions(listenerComponent())
        } catch (e: SecurityException) {
            throw PluginHostException(
                "NO_SESSION_PERMISSION",
                "需要开启「通知使用权」才能读取当前媒体会话：设置 → 权限与后台 → 通知使用权 → Aharou"
            )
        }
        val token = sessions.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?.sessionToken
            ?: sessions.firstOrNull()?.sessionToken
            ?: throw PluginHostException(
                "NO_ACTIVE_SESSION",
                "当前没有正在播放的媒体会话；先在音乐/播客应用里开始播放再来控制"
            )
        return try {
            MediaController(context, token)
        } catch (e: Exception) {
            throw PluginHostException("SESSION_DETACHED", "媒体会话已断开：${e.message.orEmpty()}")
        }
    }

    private fun mediaSessionManager(): MediaSessionManager =
        context.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
            ?: throw PluginHostException("NO_MEDIA_SERVICE", "系统没有媒体会话服务")

    private fun listenerComponent(): ComponentName =
        ComponentName(context, DeviceNotificationListener::class.java)

    private fun snapshot(controller: MediaController, message: String): JsonObject {
        val state = controller.playbackState
        val metadata = controller.metadata
        val duration = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        return buildJsonObject {
            put("state", stateName(state?.state))
            put("remote", true)
            put("packageName", controller.packageName.orEmpty())
            put("title", metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)?.toString().orEmpty())
            put("artist", metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)?.toString().orEmpty())
            put("album", metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM)?.toString().orEmpty())
            put("durationMs", duration)
            put("positionMs", positionOf(state, duration))
            put("volume", systemVolumeFraction())
            put("message", message)
        }
    }

    /** 播放中的 position 要按流逝时间补齐：会话只在状态变化时刷新 position。 */
    private fun positionOf(state: PlaybackState?, duration: Long): Long {
        if (state == null) return 0L
        val base = state.position
        val position = if (state.state == PlaybackState.STATE_PLAYING) {
            base + ((SystemClock.elapsedRealtime() - state.lastPositionUpdateTime) * state.playbackSpeed)
                .toLong()
        } else {
            base
        }
        return if (duration > 0) position.coerceIn(0L, duration) else position.coerceAtLeast(0L)
    }

    private fun systemVolumeFraction(): Double {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return 0.0
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (max <= 0) return 0.0
        return String.format(
            Locale.US,
            "%.3f",
            audio.getStreamVolume(AudioManager.STREAM_MUSIC).toDouble() / max
        ).toDouble()
    }

    private fun stateName(state: Int?): String = when (state) {
        PlaybackState.STATE_NONE -> "none"
        PlaybackState.STATE_STOPPED -> "stopped"
        PlaybackState.STATE_PAUSED -> "paused"
        PlaybackState.STATE_PLAYING -> "playing"
        PlaybackState.STATE_FAST_FORWARDING -> "fast_forwarding"
        PlaybackState.STATE_REWINDING -> "rewinding"
        PlaybackState.STATE_BUFFERING -> "buffering"
        PlaybackState.STATE_ERROR -> "error"
        PlaybackState.STATE_CONNECTING -> "connecting"
        PlaybackState.STATE_SKIPPING_TO_PREVIOUS -> "skipping_to_previous"
        PlaybackState.STATE_SKIPPING_TO_NEXT -> "skipping_to_next"
        PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM -> "skipping_to_queue_item"
        else -> if (state == null) "unknown" else "state_$state"
    }
}
