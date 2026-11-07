package com.aharou.feature.voice.input

internal object VoiceInputPermission {
    fun requestStart(granted: Boolean, start: () -> Unit, request: () -> Unit) {
        if (granted) start() else request()
    }

    fun grantedResult(granted: Boolean, notify: (Boolean) -> Unit) {
        notify(granted)
    }
}
