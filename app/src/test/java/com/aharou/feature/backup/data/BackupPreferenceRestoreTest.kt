package com.aharou.feature.backup.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.aharou.feature.settings.data.repository.restoreBackupValues
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class BackupPreferenceRestoreTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun rollbackRestoresCustomSkinAndAbsentModelKeysWithoutTouchingOtherPreferences() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val store = PreferenceDataStoreFactory.create(scope = scope) { File(temporary.root, "settings.preferences_pb") }
        try {
            val skin = stringPreferencesKey("theme_skin_id")
            val oldPreset = stringPreferencesKey("theme_preset_id")
            val model = stringPreferencesKey("vision_model")
            val unrelated = stringPreferencesKey("unrelated")
            store.edit { it[skin] = "custom-skin"; it[oldPreset] = "legacy"; it[unrelated] = "keep" }
            val recovery = File(temporary.root, "recovery.json")
            val undo = store.restoreBackupValues(mapOf(skin.name to "new", oldPreset.name to null, model.name to ""), recovery)
            assertEquals("", store.data.first()[model])
            store.edit { it[unrelated] = "external edit" }
            undo()
            val result = store.data.first()
            assertEquals("custom-skin", result[skin])
            assertEquals("legacy", result[oldPreset])
            assertNull(result[model])
            assertEquals("external edit", result[unrelated])
            assertTrue(recovery.readText().contains("custom-skin"))
            assertTrue(recovery.readText().contains("before"))
        } finally { scope.cancel() }
    }

    @Test
    fun rollbackPreservesConcurrentlyChangedSettingAndReportsConflict() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val store = PreferenceDataStoreFactory.create(scope = scope) { File(temporary.root, "conflict.preferences_pb") }
        try {
            val key = stringPreferencesKey("theme_skin_id")
            val undo = store.restoreBackupValues(mapOf(key.name to "restored"), File(temporary.root, "conflict.json"))
            store.edit { it[key] = "external edit" }
            var failed = false
            try { undo() } catch (_: IOException) { failed = true }
            assertTrue(failed)
            assertEquals("external edit", store.data.first()[key])
            assertFalse(store.data.first().asMap().isEmpty())
        } finally { scope.cancel() }
    }
}
