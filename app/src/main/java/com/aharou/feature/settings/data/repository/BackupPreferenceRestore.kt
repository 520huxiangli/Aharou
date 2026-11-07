package com.aharou.feature.settings.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.File
import java.io.FileOutputStream
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

internal suspend fun DataStore<Preferences>.restoreBackupValues(values: Map<String, Any?>, recovery: File): suspend () -> Unit =
    withContext(NonCancellable) {
        var before: Map<String, Any?> = emptyMap()
        edit { prefs ->
            before = values.keys.associateWith { name -> prefs.asMap().entries.firstOrNull { it.key.name == name }?.value }
            writeBackupPreferenceRecovery(recovery, before, values)
            values.forEach { (name, value) -> prefs.setBackupValue(name, value) }
        }
        val rollback: suspend () -> Unit = {
            var conflict = false
            edit { prefs ->
                values.forEach { (name, expected) ->
                    val current = prefs.asMap().entries.firstOrNull { it.key.name == name }?.value
                    if (current == expected) prefs.setBackupValue(name, before[name]) else conflict = true
                }
            }
            if (conflict) throw IOException("Settings changed concurrently; rollback preserved newer values")
        }
        rollback
    }

internal fun writeBackupPreferenceRecovery(file: File, before: Map<String, Any?>, expected: Map<String, Any?>) {
    fun encode(values: Map<String, Any?>) = buildJsonObject {
        values.forEach { (key, value) ->
            put(key, when (value) {
                null -> JsonNull
                is Boolean -> JsonPrimitive(value)
                is Number -> JsonPrimitive(value)
                else -> JsonPrimitive(value.toString())
            })
        }
    }
    val content = buildJsonObject {
        put("before", encode(before))
        put("expected", encode(expected))
    }
    FileOutputStream(file).use { output ->
        output.write(content.toString().toByteArray(Charsets.UTF_8))
        output.fd.sync()
    }
}

@Suppress("UNCHECKED_CAST")
private fun androidx.datastore.preferences.core.MutablePreferences.setBackupValue(name: String, value: Any?) {
    val key = stringPreferencesKey(name) as Preferences.Key<Any>
    if (value == null) remove(key) else this[key] = value
}
