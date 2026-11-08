package com.aharou.feature.im.data.napcat

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import java.net.URI
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal data class NapCatDownloadCandidate(val url: String, val sha256: String?)

internal data class NapCatAsset(
    val name: String,
    val source: String,
    val url: String,
    val sha256: String,
    val sizeBytes: Long
)

internal class NapCatAssetManifest private constructor(private val assets: List<NapCatAsset>) {
    fun candidates(source: String, officialSha256: String? = null): List<NapCatDownloadCandidate> {
        val officialDigest = officialSha256?.takeIf { it.isNotBlank() }
        val asset = assets.singleOrNull { it.source == source }
            ?: return listOf(NapCatDownloadCandidate(source, officialDigest))
        if (officialDigest != null && !officialDigest.equals(asset.sha256, ignoreCase = true)) {
            throw IOException("NapCat asset checksum metadata conflict: ${asset.name}")
        }
        val digest = officialDigest ?: asset.sha256
        return listOf(NapCatDownloadCandidate(asset.url, digest), NapCatDownloadCandidate(source, digest))
            .distinctBy { it.url }
    }

    companion object {
        fun parse(text: String): NapCatAssetManifest {
            val root = Json.parseToJsonElement(text).jsonObject
            require(root.getValue("schemaVersion").jsonPrimitive.content == "1") { "Unsupported NapCat asset schema" }
            listOf("napcatVersion", "qqVersion", "rootfsVersion").forEach {
                require(root.getValue(it).jsonPrimitive.content.isNotBlank()) { "Missing NapCat asset version" }
            }
            val assets = root.getValue("assets").jsonArray.map { element ->
                val asset = element.jsonObject
                fun string(key: String) = asset.getValue(key).jsonPrimitive.content
                NapCatAsset(string("name"), string("source"), string("url"), string("sha256"), string("sizeBytes").toLong()).also {
                    require(it.name.isNotBlank() && it.sizeBytes > 0) { "Invalid NapCat asset metadata" }
                    require(it.sha256.matches(Regex("[a-fA-F0-9]{64}"))) { "Invalid NapCat asset checksum" }
                    listOf(it.source, it.url).forEach { url ->
                        val uri = URI(url)
                        require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.fragment == null) {
                            "Invalid NapCat asset URL"
                        }
                    }
                }
            }
            require(assets.map { it.source }.distinct().size == assets.size) { "Duplicate NapCat asset source" }
            return NapCatAssetManifest(assets)
        }
    }
}

@Singleton
class NapCatAssetCatalog @Inject constructor(@param:ApplicationContext private val context: Context) {
    private val manifest by lazy {
        context.assets.open("napcat-downloads.json").bufferedReader().use { NapCatAssetManifest.parse(it.readText()) }
    }

    internal fun candidates(source: String, officialSha256: String? = null): List<NapCatDownloadCandidate> =
        manifest.candidates(source, officialSha256)
}
