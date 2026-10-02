package com.aharou.feature.voice.assistant

import android.content.Intent
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import com.aharou.core.util.FileLogger

/**
 * 占位识别服务。
 *
 * 官方要求数字助手应用必须同时声明 `VoiceInteractionService` 与 `RecognitionService`
 * （见 AOSP《语音交互简介》），缺后者会签不过默认助手的校验。本应用的识别跑在自己的
 * 引擎里、不走 `SpeechRecognizer` 这条路，所以这里只做一个合法的最小实现：
 * 收到外部识别请求即明确报错，不静默装作在听，免得别的应用调用后一直等不到结果。
 */
class AharouRecognitionService : RecognitionService() {

    override fun onStartListening(recognizerIntent: Intent, listener: Callback) {
        FileLogger.i(TAG, "收到外部识别请求，本服务不提供系统识别")
        listener.error(SpeechRecognizer.ERROR_CLIENT)
    }

    override fun onCancel(listener: Callback) {
        listener.error(SpeechRecognizer.ERROR_CLIENT)
    }

    override fun onStopListening(listener: Callback) {
        listener.error(SpeechRecognizer.ERROR_CLIENT)
    }

    private companion object {
        const val TAG = "AharouRecognition"
    }
}
