package com.aharou.feature.voice.input

import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceInputPermissionTest {
    @Test fun permissionResultDoesNotContinueReleasedGesture() {
        var starts = 0
        var requests = 0
        var notified = false
        VoiceInputPermission.requestStart(false, { starts++ }, { requests++ })
        VoiceInputPermission.grantedResult(true) { notified = it }
        assertEquals(1, requests)
        assertEquals(0, starts)
        assertEquals(true, notified)
        VoiceInputPermission.requestStart(true, { starts++ }, { requests++ })
        assertEquals(1, starts)
        assertEquals(1, requests)
    }

    @Test fun rejectionOnlyReportsPermissionResult() {
        var result: Boolean? = null
        VoiceInputPermission.grantedResult(false) { result = it }
        assertEquals(false, result)
    }
}
