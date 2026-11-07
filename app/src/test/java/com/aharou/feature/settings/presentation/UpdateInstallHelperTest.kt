package com.aharou.feature.settings.presentation

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateInstallHelperTest {
    @Test fun manualInstallCanOnlyBeClaimedOnce() {
        val state = MutableStateFlow<UpdateDownloadUiState>(UpdateDownloadUiState.Idle)
        val helper = UpdateInstallHelper(state)
        assertNull(helper.prepare("apk", "tag", false))
        val ready = state.value as UpdateDownloadUiState.Ready
        assertEquals("apk", helper.claim(ready))
        assertNull(helper.claim(ready))
        helper.failed(ready)
        assertEquals(ready, state.value)
    }

    @Test fun silentSuccessNeverBecomesSystemInstallReady() = runTest {
        val state = MutableStateFlow<UpdateDownloadUiState>(UpdateDownloadUiState.Idle)
        val helper = UpdateInstallHelper(state)
        val ready = helper.prepare("apk", "tag", true)!!
        helper.install(ready) { true }
        assertTrue(state.value is UpdateDownloadUiState.Installing)
        assertNull(helper.claim(ready))
    }

    @Test fun silentFailureAllowsManualRetryOnly() = runTest {
        val state = MutableStateFlow<UpdateDownloadUiState>(UpdateDownloadUiState.Idle)
        val helper = UpdateInstallHelper(state)
        val ready = helper.prepare("apk", "tag", true)!!
        helper.install(ready) { false }
        assertEquals(ready, state.value)
        assertEquals("apk", helper.claim(ready))
    }

    @Test fun cancellationDoesNotTriggerSystemInstaller() = runTest {
        val state = MutableStateFlow<UpdateDownloadUiState>(UpdateDownloadUiState.Idle)
        val helper = UpdateInstallHelper(state)
        val ready = helper.prepare("apk", "tag", true)!!
        val error = CancellationException("cancel")
        try { helper.install(ready) { throw error } } catch (actual: CancellationException) { assertTrue(actual === error) }
        assertEquals(UpdateDownloadUiState.Idle, state.value)
        assertNull(helper.claim(ready))
    }

    @Test fun installerReturnOnlyReleasesSystemInstallRequest() {
        val state = MutableStateFlow<UpdateDownloadUiState>(UpdateDownloadUiState.Idle)
        val helper = UpdateInstallHelper(state)
        helper.prepare("apk", "tag", false)
        val ready = state.value as UpdateDownloadUiState.Ready
        helper.claim(ready)
        helper.returned()
        assertEquals(ready, state.value)
        helper.prepare("apk", "tag", true)
        helper.returned()
        assertTrue(state.value is UpdateDownloadUiState.Installing)
    }

    @Test fun oldSilentResultCannotOverwriteNewDownload() = runTest {
        val state = MutableStateFlow<UpdateDownloadUiState>(UpdateDownloadUiState.Idle)
        val helper = UpdateInstallHelper(state)
        val ready = helper.prepare("old", "tag", true)!!
        helper.install(ready) { state.value = UpdateDownloadUiState.Resolving; false }
        assertEquals(UpdateDownloadUiState.Resolving, state.value)
    }
}
