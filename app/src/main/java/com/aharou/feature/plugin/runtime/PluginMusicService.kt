package com.aharou.feature.plugin.runtime

import com.aharou.feature.plugin.domain.PluginHostException
import com.aharou.feature.plugin.domain.PluginManager
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * `music.*` 宿主方法（对应 Operit 的 `Tools.System.music`，8 个方法）。
 *
 * 主路径是 [PluginMusicPlayer]：应用内 ExoPlayer 真正播放插件给的音源（url / 本地路径 / uri）。
 * 兜底路径是 [PluginMusicRemoteControl]：本地播放器里没有任何音源时，转为遥控用户正在用的
 * 播放器，避免「没有 sound 可播就整组方法全废」。
 *
 * 两条路都不静默降级：本地装载失败按 media3 的错误码抛 `PLAYBACK_*`，兜底没有会话时抛
 * `NO_SOURCE` / `NO_ACTIVE_SESSION` / `NO_SESSION_PERMISSION`。
 */
@Singleton
class PluginMusicService @Inject constructor(
    private val player: PluginMusicPlayer,
    private val remoteControl: PluginMusicRemoteControl,
    private val pluginManager: PluginManager
) {

    suspend fun play(pluginId: String, params: JsonObject): JsonElement = withContext(Dispatchers.Main) {
        ensureActive(pluginId)
        if (params.musicString("source") != null) {
            val track = musicTrackFrom(params)
            return@withContext player.play(
                PluginMusicPlayer.PlayOptions(
                    source = track.source,
                    sourceType = track.sourceType,
                    title = track.title,
                    artist = track.artist,
                    loop = params.musicFlag("loop") ?: false,
                    volume = params.musicDouble("volume"),
                    startPositionMs = params.musicLong("start_position_ms")
                        ?: params.musicLong("startPositionMs") ?: 0L
                )
            )
        }
        // 没给音源：已经装过的就继续播，否则交给遥控兜底
        if (player.isLoaded()) return@withContext player.resume()
        withRemote { it.play() }
    }

    suspend fun playQueue(pluginId: String, params: JsonObject): JsonElement = withContext(Dispatchers.Main) {
        ensureActive(pluginId)
        val items = params.musicItems()
            ?: throw PluginHostException("PARAM_INVALID", "playQueue 需要 items 数组")
        if (items.isEmpty()) throw PluginHostException("PARAM_INVALID", "playQueue 需要非空 items 数组")
        val tracks = items.mapIndexed { index, element ->
            val entry = element as? JsonObject
                ?: throw PluginHostException("PARAM_INVALID", "items[$index] 必须是对象")
            musicTrackFrom(entry)
        }
        player.playQueue(
            PluginMusicPlayer.QueueOptions(
                items = tracks,
                loop = params.musicFlag("loop") ?: false,
                volume = params.musicDouble("volume"),
                startIndex = params.musicInt("start_index") ?: params.musicInt("startIndex") ?: 0,
                startPositionMs = params.musicLong("start_position_ms")
                    ?: params.musicLong("startPositionMs") ?: 0L
            )
        )
    }

    suspend fun pause(pluginId: String, params: JsonObject): JsonElement = withContext(Dispatchers.Main) {
        ensureActive(pluginId)
        if (player.isLoaded()) player.pause() else withRemote { it.pause() }
    }

    suspend fun resume(pluginId: String, params: JsonObject): JsonElement = withContext(Dispatchers.Main) {
        ensureActive(pluginId)
        if (player.isLoaded()) player.resume() else withRemote { it.play() }
    }

    suspend fun stop(pluginId: String, params: JsonObject): JsonElement = withContext(Dispatchers.Main) {
        ensureActive(pluginId)
        if (player.isLoaded()) player.stop() else withRemote { it.stop() }
    }

    suspend fun seek(pluginId: String, params: JsonObject): JsonElement = withContext(Dispatchers.Main) {
        ensureActive(pluginId)
        val position = params.musicLong("position_ms") ?: params.musicLong("positionMs")
            ?: throw PluginHostException("PARAM_INVALID", "seek 需要 position_ms（毫秒）")
        if (player.isLoaded()) player.seek(position) else withRemote { it.seek(position) }
    }

    suspend fun setVolume(pluginId: String, params: JsonObject): JsonElement = withContext(Dispatchers.Main) {
        ensureActive(pluginId)
        val volume = params.musicDouble("volume")
            ?: throw PluginHostException("PARAM_INVALID", "setVolume 需要 volume（0~1）")
        if (player.isLoaded()) {
            player.setVolume(volume)
        } else {
            // 遥控别人的播放器改不了它的内部音量，只能动系统媒体音量
            remoteControl.setSystemVolume(volume)
        }
    }

    suspend fun status(pluginId: String, params: JsonObject): JsonElement = withContext(Dispatchers.Main) {
        ensureActive(pluginId)
        if (player.isLoaded()) player.status() else withRemote { it.status() }
    }

    private suspend fun withRemote(block: suspend (PluginMusicRemoteControl) -> JsonObject): JsonElement =
        when (remoteControl.availability()) {
            PluginMusicRemoteControl.Availability.READY -> block(remoteControl)
            PluginMusicRemoteControl.Availability.NO_PERMISSION -> throw PluginHostException(
                "NO_SESSION_PERMISSION",
                "播放器里没有音源；要遥控系统正在播放的媒体，需要先开启「通知使用权」：" +
                    "设置 → 权限与后台 → 通知使用权 → Aharou"
            )
            PluginMusicRemoteControl.Availability.NO_SESSION -> throw PluginHostException(
                "NO_SOURCE",
                "播放器里没有音源，当前也没有正在播放的媒体会话；" +
                    "先调用 music.play({ source, sourceType }) 给播放器装一个音源"
            )
        }

    private fun ensureActive(pluginId: String) {
        pluginManager.ensureLoaded()
        pluginManager.plugins.value.firstOrNull { it.id == pluginId && it.isActive }
            ?: throw PluginHostException("PLUGIN_INACTIVE", "plugin is not active: $pluginId")
    }
}
