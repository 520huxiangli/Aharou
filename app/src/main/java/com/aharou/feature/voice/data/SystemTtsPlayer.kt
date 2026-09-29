package com.aharou.feature.voice.data

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.aharou.core.util.FileLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 系统 TTS 播放器：用手机自带的文字转语音引擎念，不需要配置任何 provider。
 *
 * 云端 TTS 没配（或合成失败）时兜底，代价是音质与断句不如云端。
 * 引擎初始化要几秒，所以懒加载并缓存；同一时刻只念一段，新的念白直接顶掉旧的。
 *
 * 所有引擎调用都必须在主线程，所以这里每次都切 [Dispatchers.Main]。
 */
@Singleton
internal class SystemTtsPlayer @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {

    private var engine: TextToSpeech? = null
    private var ready: CompletableDeferred<Boolean>? = null

    /** 当前这段念白的完成句柄；[stop] 要主动完成它，等待方才能醒过来。 */
    private var speaking: CompletableDeferred<Unit>? = null

    /** 系统里有没有可用的 TTS 引擎（首次调用会触发引擎初始化）。 */
    suspend fun isAvailable(): Boolean = ensureEngine()

    /**
     * 念一段文字并等它念完。返回 false 表示系统没有可用引擎。
     */
    suspend fun speakAndWait(text: String): Boolean {
        if (text.isBlank() || !ensureEngine()) return false
        val tts = engine ?: return false
        val done = CompletableDeferred<Unit>()
        speaking = done
        val utteranceId = UUID.randomUUID().toString()
        val accepted = withContext(Dispatchers.Main) {
            runCatching {
                tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId) == TextToSpeech.SUCCESS
            }.getOrElse {
                FileLogger.w(TAG, "系统 TTS 播报失败：$text", it)
                false
            }
        }
        if (!accepted) {
            speaking = null
            return false
        }
        done.await()
        if (speaking === done) speaking = null
        return true
    }

    /** 打断当前念白。 */
    fun stop() {
        speaking?.complete(Unit)
        speaking = null
        runCatching { engine?.stop() }
    }

    private suspend fun ensureEngine(): Boolean {
        ready?.let { return it.await() }
        val deferred = CompletableDeferred<Boolean>()
        ready = deferred
        withContext(Dispatchers.Main) {
            var created: TextToSpeech? = null
            created = TextToSpeech(context) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    created?.language = Locale.getDefault()
                    created?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) = Unit

                        override fun onDone(utteranceId: String?) {
                            speaking?.complete(Unit)
                        }

                        @Deprecated("被 onError(String, Int) 取代，但抽象方法必须实现")
                        override fun onError(utteranceId: String?) {
                            speaking?.complete(Unit)
                        }

                        override fun onError(utteranceId: String?, errorCode: Int) {
                            FileLogger.w(TAG, "系统 TTS 报错 errorCode=$errorCode")
                            speaking?.complete(Unit)
                        }
                    })
                    engine = created
                    deferred.complete(true)
                } else {
                    FileLogger.w(TAG, "系统 TTS 引擎不可用 status=$status")
                    deferred.complete(false)
                }
            }
        }
        return deferred.await()
    }

    private companion object {
        const val TAG = "SystemTtsPlayer"
    }
}
