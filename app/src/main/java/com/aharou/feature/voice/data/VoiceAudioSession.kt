package com.aharou.feature.voice.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import androidx.core.content.ContextCompat
import com.aharou.core.util.FileLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 音频会话：统一管理播报期间的音频焦点与「输出设备变化」事件。
 *
 * 此前完全没有这一层，带来两个实际毛病：播报时别的 App（音乐/导航）跟它对吼谁也不让；
 * 拔耳机后外放继续大声念，该停不停。这里申请瞬时焦点，并在失去焦点（被打断）
 * 或输出被切走（[AudioManager.ACTION_AUDIO_BECOMING_NOISY]）时，通过 [onInterrupted]
 * 通知播放方停掉当前音频。
 */
@Singleton
internal class VoiceAudioSession @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {

    private val audioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    /** 需要停播时的回调（失去焦点 / 拔耳机）。播放方在开始前挂上。 */
    @Volatile
    var onInterrupted: (() -> Unit)? = null

    private var focusRequest: AudioFocusRequest? = null
    private var noisyRegistered = false

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> onInterrupted?.invoke()
            else -> Unit
        }
    }

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                onInterrupted?.invoke()
            }
        }
    }

    /** 申请播放用的瞬时焦点；被拒也应照播（只是让音量时让不了别人）。 */
    fun requestFocus() {
        runCatching {
            val request = focusRequest ?: buildFocusRequest().also { focusRequest = it }
            if (audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                registerNoisyReceiver()
            }
        }.onFailure { FileLogger.w(TAG, "申请音频焦点失败", it) }
    }

    /** 释放焦点并停止监听输出变化。 */
    fun abandonFocus() {
        runCatching {
            focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        }.onFailure { FileLogger.w(TAG, "释放音频焦点失败", it) }
        focusRequest = null
        unregisterNoisyReceiver()
    }

    /**
     * 进入通话音频模式（启用系统回声消除与通话路由）。需要 MODIFY_AUDIO_SETTINGS 权限。
     *
     * 通话是「外放对话」而不是贴耳打电话：MODE_IN_COMMUNICATION 默认走听筒，
     * 不把扬声器置开的话，用户对着屏幕根本听不见 AI 说话。
     */
    fun enterCommunicationMode() {
        runCatching {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            audioManager.isSpeakerphoneOn = true
        }.onFailure { FileLogger.w(TAG, "进入通话音频模式失败", it) }
    }

    /** 退出通话音频模式，恢复普通路由。 */
    fun exitCommunicationMode() {
        runCatching {
            audioManager.isSpeakerphoneOn = false
            audioManager.mode = AudioManager.MODE_NORMAL
        }.onFailure { FileLogger.w(TAG, "退出通话音频模式失败", it) }
    }

    private fun buildFocusRequest(): AudioFocusRequest = AudioFocusRequest.Builder(
        AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
    )
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        // 被别的 App 压低音量时直接暂停，比压着嗓子继续念更清楚
        .setWillPauseWhenDucked(true)
        .setOnAudioFocusChangeListener(focusListener)
        .build()

    private fun registerNoisyReceiver() {
        if (noisyRegistered) return
        runCatching {
            ContextCompat.registerReceiver(
                context,
                noisyReceiver,
                IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            noisyRegistered = true
        }.onFailure { FileLogger.w(TAG, "注册输出设备变化监听失败", it) }
    }

    private fun unregisterNoisyReceiver() {
        if (!noisyRegistered) return
        runCatching { context.unregisterReceiver(noisyReceiver) }
            .onFailure { FileLogger.w(TAG, "注销输出设备变化监听失败", it) }
        noisyRegistered = false
    }

    private companion object {
        const val TAG = "VoiceAudioSession"
    }
}
