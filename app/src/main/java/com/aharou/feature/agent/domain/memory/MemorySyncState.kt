package com.aharou.feature.agent.domain.memory

import java.io.File
import java.io.IOException
import java.util.Base64

internal class MemorySyncState(private val root: File, private val files: MemorySyncFiles) {
    private val baselineFile = files.checked(root, "baseline.state")

    fun load(): Map<String, String> {
        val raw = files.read(baselineFile)?.bytes?.toString(Charsets.UTF_8) ?: return emptyMap()
        val lines = raw.split('\n')
        if (lines.size < 3 || lines[0] != VERSION) throw IOException("invalid_baseline")
        val body = lines.drop(2).joinToString("\n")
        if (MemorySyncFiles.hash(body.toByteArray()) != lines[1]) throw IOException("invalid_baseline")
        val result = linkedMapOf<String, String>()
        body.lineSequence().filter { it.isNotEmpty() }.forEach { line ->
            val parts = line.split('\t')
            if (parts.size != 2 || !HASH.matches(parts[1])) throw IOException("invalid_baseline")
            val path = try {
                String(Base64.getUrlDecoder().decode(parts[0]), Charsets.UTF_8)
            } catch (e: IllegalArgumentException) {
                throw IOException("invalid_baseline", e)
            }
            files.checked(root, path)
            if (path.isEmpty() || result.put(path, parts[1]) != null) throw IOException("invalid_baseline")
        }
        return result
    }

    fun save(value: Map<String, String>) {
        val body = value.toSortedMap().entries.joinToString("\n") { (path, hash) ->
            files.checked(root, path)
            if (!HASH.matches(hash)) throw IOException("invalid_baseline")
            Base64.getUrlEncoder().withoutPadding().encodeToString(path.toByteArray()) + "\t" + hash
        }
        val digest = MemorySyncFiles.hash(body.toByteArray())
        files.atomicWrite(baselineFile, "$VERSION\n$digest\n$body".toByteArray())
        if (load() != value) throw IOException("baseline_write_failed")
    }

    companion object {
        private const val VERSION = "memory-sync-baseline-v1"
        private val HASH = Regex("[0-9a-f]{64}")

        fun key(url: String, branch: String): String = MemorySyncFiles.hash(
            (url.trim() + "\u0000" + branch.trim()).toByteArray()
        )
    }
}
