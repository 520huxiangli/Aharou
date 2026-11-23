package com.aharou.feature.plugin.runtime

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.net.Uri
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes as Media3AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.aharou.core.util.FileLogger
import com.aharou.feature.plugin.domain.PluginHostException
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient

/**
 * 插件音乐播放器：应用内自建播放（media3 ExoPlayer），不是遥控别的 App。
 *
 * 与 Operit 对齐的点：Operit 的 `Tools.music` 就是自带 ExoPlayer 的播放器（
 * `StandardMusicPlaybackTools` + `MusicPlaybackManager`），所以这里也用 ExoPlayer 管队列、
 * 循环与音源；HLS 由 `media3-exoplayer-hls` 按 `.m3u8` 自动识别。
 *
 * 数据源走 OkHttp：从全局 [OkHttpClient] 派生（继承代理、拦截器与连接池），
 * 只把读超时改成有限值——全局客户端给流式对话设的是 0（无限），拿它拉音频会在
 * 网络半死时永远挂着。超时后 ExoPlayer 会报 IO 错误，插件拿到明确的失败码。
 *
 * 音频焦点交给 ExoPlayer 的 `handleAudioFocus = true`：TTS 播报申请瞬时焦点时，
 * ExoPlayer 自动暂停（被压低场景自动 duck），TTS 释放后自动恢复，不需要和
 * `VoiceAudioSession` 互相持有引用，也不会跟它抢同一个 `onInterrupted` 回调。
 *
 * 播放器是常驻单例：ExoPlayer 只创建一个，重复调用复用同一实例（换音源用 setMediaItems），
 * 避免每次调用重建 30MB 级的解码器与缓冲。
 */
@Singleton
class PluginMusicPlayer @Inject constructor(
    @param:ApplicationContext private val context: Context,
    baseClient: OkHttpClient
) {

    internal data class Track(
        val source: String,
        val sourceType: TrackSourceType,
        val title: String? = null,
        val artist: String? = null
    )

    internal enum class TrackSourceType { PATH, URL, URI }

    private val mediaClient: OkHttpClient = baseClient.newBuilder()
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val mediaSourceFactory = DefaultMediaSourceFactory(
        OkHttpDataSource.Factory(mediaClient).setUserAgent(userAgent())
    )

    private var player: ExoPlayer? = null
    private var tracks: List<Track> = emptyList()
    private var errorCode: String? = null
    private var errorMessage: String? = null
    private var noisyRegistered = false

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(receiverContext: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                player?.pause()
            }
        }
    }

    private val listener = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            val mapped = mapError(error)
            errorCode = mapped.first
            errorMessage = mapped.second
            FileLogger.w(TAG, "插件音乐播放失败 ${error.errorCodeName}: ${error.message}")
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState != Player.STATE_IDLE) {
                errorCode = null
                errorMessage = null
            }
        }
    }

    /** 自建播放器里有没有装过音源（决定走本地播放还是回落到遥控系统会话）。 */
    fun isLoaded(): Boolean = tracks.isNotEmpty()

    internal suspend fun play(options: PlayOptions): JsonObject = withContext(Dispatchers.Main) {
        val track = Track(options.source, options.sourceType, options.title, options.artist)
        validateSource(track)
        tracks = listOf(track)
        errorCode = null
        errorMessage = null
        val p = ensurePlayer()
        p.repeatMode = if (options.loop) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        options.volume?.let { applyVolume(p, it) }
        p.setMediaItem(mediaItem(track), options.startPositionMs.coerceAtLeast(0))
        p.prepare()
        p.playWhenReady = true
        awaitPlaybackStart(p)
        snapshot("开始播放")
    }

    internal suspend fun playQueue(options: QueueOptions): JsonObject = withContext(Dispatchers.Main) {
        if (options.items.isEmpty()) {
            throw PluginHostException("PARAM_INVALID", "playQueue 需要非空 items 数组")
        }
        if (options.startIndex !in options.items.indices) {
            throw PluginHostException(
                "PARAM_INVALID",
                "start_index 超出范围：0~${options.items.lastIndex}（items 共 ${options.items.size} 条）"
            )
        }
        options.items.forEach { validateSource(it) }
        tracks = options.items
        errorCode = null
        errorMessage = null
        val p = ensurePlayer()
        p.repeatMode = if (options.loop) Player.REPEAT_MODE_ALL else Player.REPEAT_MODE_OFF
        options.volume?.let { applyVolume(p, it) }
        p.setMediaItems(
            options.items.map { mediaItem(it) },
            options.startIndex,
            options.startPositionMs.coerceAtLeast(0)
        )
        p.prepare()
        p.playWhenReady = true
        awaitPlaybackStart(p)
        snapshot("已开始播放列表")
    }

    suspend fun pause(): JsonObject = withContext(Dispatchers.Main) {
        val p = requirePlayer()
        p.pause()
        snapshot("已暂停")
    }

    suspend fun resume(): JsonObject = withContext(Dispatchers.Main) {
        val p = requirePlayer()
        if (p.playbackState == Player.STATE_ENDED) p.seekTo(0)
        // stop() 会把播放器打回 IDLE，恢复前必须重新 prepare，否则 play() 不生效
        if (p.playbackState == Player.STATE_IDLE) p.prepare()
        p.play()
        snapshot("已继续播放")
    }

    suspend fun stop(): JsonObject = withContext(Dispatchers.Main) {
        val p = requirePlayer()
        p.pause()
        p.stop()
        snapshot("已停止")
    }

    suspend fun seek(positionMs: Long): JsonObject = withContext(Dispatchers.Main) {
        if (positionMs < 0) throw PluginHostException("PARAM_INVALID", "position_ms 不能为负")
        val p = requirePlayer()
        val duration = durationOf(p)
        val target = if (duration > 0) positionMs.coerceAtMost(duration) else positionMs
        p.seekTo(target)
        buildJsonObject {
            snapshot("已跳转到 ${target}ms").forEach { (key, value) -> put(key, value) }
            put("positionMs", target)
        }
    }

    suspend fun setVolume(fraction: Double): JsonObject = withContext(Dispatchers.Main) {
        if (fraction < 0.0 || fraction > 1.0) {
            throw PluginHostException("PARAM_INVALID", "volume 必须在 0~1 之间")
        }
        val p = requirePlayer()
        applyVolume(p, fraction)
        snapshot("已设置音量")
    }

    suspend fun status(): JsonObject = withContext(Dispatchers.Main) {
        requirePlayer()
        snapshot("")
    }

    // ---------- 内部 ----------

    private fun ensurePlayer(): ExoPlayer {
        player?.let { return it }
        val p = ExoPlayer.Builder(context)
            .setMediaSourceFactory(mediaSourceFactory)
            .setLooper(Looper.getMainLooper())
            .build()
        p.setAudioAttributes(
            Media3AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build(),
            /* handleAudioFocus = */ true
        )
        p.addListener(listener)
        player = p
        registerNoisyReceiver()
        return p
    }

    private fun requirePlayer(): ExoPlayer {
        if (tracks.isEmpty()) throw PluginHostException("NO_SOURCE", "播放器里还没有音源")
        return player ?: throw PluginHostException("PLAYER_UNAVAILABLE", "播放器尚未创建")
    }

    /**
     * 等第一帧就绪再返回：插件调用 `play` 后立刻 `status` 时不该看到含糊的 buffering，
     * 而网络/格式错误也要在这一步以异常返回，而不是只留在状态里。
     * 超时不算失败——长缓冲（HLS 起播慢）继续在后台走，调用方从 status 看 buffering。
     */
    private suspend fun awaitPlaybackStart(p: ExoPlayer) {
        val deadline = SystemClock.elapsedRealtime() + START_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            errorCode?.let { code -> throw PluginHostException(code, errorMessage.orEmpty()) }
            val state = p.playbackState
            if (state == Player.STATE_READY || state == Player.STATE_ENDED) return
            delay(100)
        }
    }

    private fun validateSource(track: Track) {
        when (track.sourceType) {
            TrackSourceType.PATH -> if (!File(track.source).isFile) {
                throw PluginHostException("SOURCE_NOT_FOUND", "音源文件不存在：${track.source}")
            }
            TrackSourceType.URL -> if (!track.source.startsWith("http://") &&
                !track.source.startsWith("https://")
            ) {
                throw PluginHostException("PARAM_INVALID", "url 类型只支持 http/https 地址")
            }
            TrackSourceType.URI -> if (track.source.isBlank()) {
                throw PluginHostException("PARAM_INVALID", "uri 类型需要非空地址")
            }
        }
    }

    private fun mediaItem(track: Track): MediaItem {
        val builder = MediaItem.Builder().setUri(Uri.parse(track.source))
        val metadata = MediaMetadata.Builder()
            .setTitle(track.title)
            .setArtist(track.artist)
            .build()
        return builder.setMediaMetadata(metadata).build()
    }

    private fun applyVolume(p: ExoPlayer, fraction: Double) {
        p.volume = fraction.toFloat().coerceIn(0f, 1f)
    }

    private fun durationOf(p: ExoPlayer): Long {
        val duration = runCatching { p.duration }.getOrDefault(C.TIME_UNSET)
        return if (duration == C.TIME_UNSET || duration < 0) 0L else duration
    }

    private fun snapshot(message: String): JsonObject {
        val p = player
        val index = p?.currentMediaItemIndex ?: 0
        val track = tracks.getOrNull(index)
        val duration = p?.let { durationOf(it) } ?: 0L
        val position = p?.let { runCatching { it.currentPosition }.getOrDefault(0L) } ?: 0L
        return buildJsonObject {
            put("state", stateName(p))
            put("source", track?.source.orEmpty())
            put("sourceType", track?.sourceType?.wireName().orEmpty())
            put("title", track?.title.orEmpty())
            put("artist", track?.artist.orEmpty())
            put("durationMs", duration)
            put("positionMs", position.coerceAtLeast(0))
            put("volume", (p?.volume ?: 1f).toDouble())
            put("loop", p?.repeatMode?.let { it != Player.REPEAT_MODE_OFF } ?: false)
            put("repeatMode", repeatModeName(p?.repeatMode))
            put("queueIndex", index)
            put("queueSize", p?.mediaItemCount ?: tracks.size)
            put("bufferedPositionMs", p?.let { runCatching { it.bufferedPosition }.getOrDefault(0L) } ?: 0L)
            put("message", message)
            errorCode?.let { put("errorCode", it) }
            errorMessage?.let { put("errorMessage", it) }
        }
    }

    private fun stateName(p: ExoPlayer?): String {
        if (p == null) return "idle"
        if (errorCode != null) return "error"
        return when (p.playbackState) {
            Player.STATE_IDLE -> if (tracks.isEmpty()) "idle" else "stopped"
            Player.STATE_BUFFERING -> "buffering"
            Player.STATE_READY -> if (p.isPlaying) "playing" else "paused"
            Player.STATE_ENDED -> "completed"
            else -> "unknown"
        }
    }

    private fun repeatModeName(mode: Int?): String = when (mode) {
        Player.REPEAT_MODE_ONE -> "one"
        Player.REPEAT_MODE_ALL -> "all"
        else -> "off"
    }

    /** 错误码按 media3 的 `errorCodeName` 归类，不硬依赖具体常量名，跨版本更稳。 */
    private fun mapError(error: PlaybackException): Pair<String, String> {
        val name = error.errorCodeName
        val detail = error.message?.take(300).orEmpty()
        return when {
            name.startsWith("ERROR_CODE_IO_FILE_NOT_FOUND") -> "SOURCE_NOT_FOUND" to "音源不存在：$detail"
            name.startsWith("ERROR_CODE_IO_BAD_HTTP_STATUS") -> "SOURCE_UNREACHABLE" to "音源返回错误状态：$detail"
            name.startsWith("ERROR_CODE_IO_") -> "PLAYBACK_IO" to "音源读取失败：$detail"
            name.startsWith("ERROR_CODE_PARSING_") -> "PLAYBACK_UNSUPPORTED" to "音源格式无法解析：$detail"
            name.startsWith("ERROR_CODE_DECODING_") || name.startsWith("ERROR_CODE_DECODER_") ->
                "PLAYBACK_UNSUPPORTED" to "解码器不支持该音源：$detail"
            else -> "PLAYBACK_FAILED" to "播放失败（$name）：$detail"
        }
    }

    private fun registerNoisyReceiver() {
        if (noisyRegistered) return
        runCatching {
            ContextCompat.registerReceiver(
                context,
                noisyReceiver,
                IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            noisyRegistered = true
        }.onFailure { FileLogger.w(TAG, "注册耳机拔出监听失败", it) }
    }

    private fun userAgent(): String = runCatching {
        val name = context.packageManager
            .getPackageInfo(context.packageName, 0).versionName ?: "unknown"
        "aharou/$name (Android)"
    }.getOrDefault("aharou (Android)")

    internal data class PlayOptions(
        val source: String,
        val sourceType: TrackSourceType,
        val title: String?,
        val artist: String?,
        val loop: Boolean,
        val volume: Double?,
        val startPositionMs: Long
    )

    internal data class QueueOptions(
        val items: List<Track>,
        val loop: Boolean,
        val volume: Double?,
        val startIndex: Int,
        val startPositionMs: Long
    )

    private fun TrackSourceType.wireName(): String = when (this) {
        TrackSourceType.PATH -> "path"
        TrackSourceType.URL -> "url"
        TrackSourceType.URI -> "uri"
    }

    companion object {
        private const val TAG = "PluginMusicPlayer"
        private const val START_TIMEOUT_MS = 10_000L
    }
}
