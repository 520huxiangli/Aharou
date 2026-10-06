package com.aharou.feature.agent.domain.tool

import com.aharou.core.util.FileLogger
import com.aharou.feature.agent.domain.container.ContainerInstaller
import com.aharou.feature.agent.domain.model.AgentImage
import com.aharou.feature.workspace.domain.FileAccessProvider
import com.aharou.feature.workspace.domain.PathHomeResolver
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ToolOutputStoreTest {
    private lateinit var fileAccess: FileAccessProvider
    private lateinit var store: ToolOutputStore
    private val archives = mutableMapOf<String, String>()

    @Before
    fun setUp() {
        mockkObject(FileLogger)
        every { FileLogger.i(any(), any()) } returns Unit
        every { FileLogger.w(any(), any(), any()) } returns Unit
        fileAccess = mockk()
        every { fileAccess.mkdirs(any()) } returns Unit
        every { fileAccess.exists(any()) } answers { archives.containsKey(firstArg()) }
        every { fileAccess.writeFile(any(), any(), any(), any()) } answers {
            assertFalse(thirdArg<Boolean>())
            archives[firstArg()] = secondArg()
        }
        val homeResolver = mockk<PathHomeResolver>()
        every { homeResolver.aharouRoot() } returns "/home/remote/.aharou"
        store = ToolOutputStore(mockk<ContainerInstaller>(), fileAccess, homeResolver)
    }

    @After
    fun tearDown() {
        unmockkObject(FileLogger)
    }

    @Test
    fun smallResultsKeepIdentityTypesFieldsAndMessages() {
        val data = JsonObject(mapOf("content" to JsonPrimitive("中文\n\"ok\""), "count" to JsonPrimitive(2)))
        val results = listOf(
            ToolResult.Success(data),
            ToolResult.Partial(data, "try again"),
            ToolResult.Error("denied", "ACCESS_DENIED")
        )
        results.forEach { original ->
            assertSame(original, store.process("test", "small", original))
        }
        verify(exactly = 0) { fileAccess.writeFile(any(), any(), any(), any()) }
    }

    @Test
    fun exactSerializedBoundaryIncludesStatusDataAndDefaultImages() {
        val overhead = ToolResult.Success(JsonPrimitive("")).toTransportString().length
        val exact = ToolResult.Success(JsonPrimitive("a".repeat(40_000 - overhead)))
        assertEquals(40_000, exact.toTransportString().length)
        assertSame(exact, store.process("test", "exact", exact))
        val above = ToolResult.Success(JsonPrimitive("a".repeat(40_001 - overhead)))
        val processed = store.process("test", "above", above) as ToolResult.Success
        assertBounded(processed)
        assertArchive(above, processed.data)
        assertTrue(processed.data.jsonObject.getValue("output_truncated").jsonPrimitive.boolean)
    }

    @Test
    fun escapedStringsCountSerializedCharactersInsteadOfRawLength() {
        val content = "\"\\\n\r\t\u0001".repeat(4_000)
        assertTrue(content.length < 40_000)
        val original = ToolResult.Success(JsonPrimitive(content))
        assertTrue(original.toTransportString().length > 40_000)
        val processed = store.process("test", "escaped", original) as ToolResult.Success
        assertBounded(processed)
        assertArchive(original, processed.data)
        Json.parseToJsonElement(processed.toTransportString())
    }

    @Test
    fun multipleNestedFieldsShareOneBudgetAndKeepShortMetadata() {
        val original = ToolResult.Success(JsonObject(mapOf(
            "stdout" to JsonPrimitive("head-a" + "a".repeat(24_000) + "tail-a"),
            "nested" to JsonObject(mapOf(
                "stderr" to JsonPrimitive("head-b" + "b".repeat(24_000) + "tail-b"),
                "items" to JsonArray(listOf(JsonPrimitive("c".repeat(24_000))))
            )),
            "metadata" to JsonObject(mapOf("language" to JsonPrimitive("kotlin"))),
            "vision_id" to JsonPrimitive("vision-123"),
            "truncated" to JsonPrimitive(false),
            "count" to JsonPrimitive(7)
        )))
        val processed = store.process("test", "nested", original) as ToolResult.Success
        assertBounded(processed)
        assertArchive(original, processed.data)
        val data = processed.data.jsonObject
        assertEquals(original.data.jsonObject["metadata"], data["metadata"])
        assertEquals(original.data.jsonObject["vision_id"], data["vision_id"])
        assertEquals(JsonPrimitive(7), data["count"])
        assertEquals(JsonPrimitive(false), data["truncated"])
        assertTrue(data.getValue("stdout").jsonPrimitive.content.startsWith("head-a"))
        assertTrue(data.getValue("stdout").jsonPrimitive.content.endsWith("tail-a"))
        val stderr = data.getValue("nested").jsonObject.getValue("stderr").jsonPrimitive.content
        assertTrue(stderr.startsWith("head-b"))
        assertTrue(stderr.endsWith("tail-b"))
    }

    @Test
    fun hugeArrayAndLongKeysFallBackToBoundedTextPreview() {
        val payloads = listOf(
            JsonArray(List(20_000) { JsonPrimitive(it) }),
            JsonObject(mapOf("k".repeat(45_000) to JsonPrimitive("value")))
        )
        payloads.forEachIndexed { index, data ->
            val original = ToolResult.Success(data)
            val processed = store.process("test", "structure-$index", original) as ToolResult.Success
            assertBounded(processed)
            assertArchive(original, processed.data)
        }
    }

    @Test
    fun errorMessageIncludesReadableArchiveMetadataAndKeepsCode() {
        val original = ToolResult.Error("\"\\\u0000".repeat(20_000), "REMOTE_FAILED")
        val processed = store.process("test", "error", original) as ToolResult.Error
        assertEquals(original.code, processed.code)
        assertBounded(processed)
        val metadata = Json.parseToJsonElement(processed.message.substringAfterLast('\n')).jsonObject
        assertTrue(metadata.getValue("output_truncated").jsonPrimitive.boolean)
        assertArchive(original, metadata)
    }

    @Test
    fun partialMessageAndDataAreBoundedTogetherAndStoredInFull() {
        val original = ToolResult.Partial(
            JsonObject(mapOf("output" to JsonPrimitive("x".repeat(30_000)), "exit_code" to JsonPrimitive(1))),
            "\"partial\n".repeat(8_000)
        )
        val processed = store.process("test", "partial", original) as ToolResult.Partial
        assertBounded(processed)
        assertArchive(original, processed.data)
        assertEquals(JsonPrimitive(1), processed.data.jsonObject["exit_code"])
        assertTrue(processed.message.isNotEmpty())
    }

    @Test
    fun messageOnlyPartialRetainsItsSmallData() {
        val original = ToolResult.Partial(JsonObject(mapOf("done" to JsonPrimitive(false))), "p".repeat(50_000))
        val processed = store.process("test", "message-only", original) as ToolResult.Partial
        assertBounded(processed)
        assertArchive(original, processed.data)
        assertEquals(JsonPrimitive(false), processed.data.jsonObject["done"])
    }

    @Test
    fun unicodePreviewsDoNotSplitSurrogatePairs() {
        val original = ToolResult.Success(JsonPrimitive("汉字😀".repeat(20_000)))
        val processed = store.process("test", "unicode", original) as ToolResult.Success
        assertBounded(processed)
        assertArchive(original, processed.data)
        assertValidSurrogates(processed.data.jsonObject.getValue("output").jsonPrimitive.content)
        assertEquals(processed.data, Json.parseToJsonElement(processed.toTransportString()).jsonObject["data"])
    }

    @Test
    fun imagesAreUntouchedAndBase64IsExcludedFromBudgetAndPreview() {
        val image = AgentImage("image/png", "image-payload".repeat(20_000), "/tmp/image.png")
        val images = listOf(image)
        val small = ToolResult.Success(JsonObject(mapOf("vision_id" to JsonPrimitive("vision-1"))), images)
        assertSame(small, store.process("test", "small-image", small))
        assertTrue(archives.isEmpty())
        val large = small.copy(data = JsonObject(mapOf(
            "vision_id" to JsonPrimitive("vision-1"),
            "content" to JsonPrimitive("x".repeat(60_000))
        )))
        val processed = store.process("test", "large-image", large) as ToolResult.Success
        assertSame(images, processed.images)
        assertEquals(image, processed.images.single())
        assertBounded(processed)
        assertArchive(large, processed.data)
        assertFalse(processed.data.toString().contains("image-payload"))
        assertTrue(archives.values.single().contains("image-payload"))
        assertEquals(JsonPrimitive("vision-1"), processed.data.jsonObject["vision_id"])
    }

    @Test
    fun failedStorageIsExplicitBoundedAndDoesNotInventOutputPath() {
        every { fileAccess.writeFile(any(), any(), any(), any()) } throws
            IllegalStateException("disk full \"\\\n".repeat(10_000))
        val results = listOf(
            ToolResult.Success(JsonPrimitive("s".repeat(60_000))),
            ToolResult.Partial(JsonPrimitive("p".repeat(60_000)), "m".repeat(60_000)),
            ToolResult.Error("e".repeat(60_000), "ORIGINAL_CODE")
        )
        results.forEach { original ->
            val processed = store.process("test", "failed", original)
            assertBounded(processed)
            assertFalse(processed.toTransportString().contains("output_path"))
            assertTrue(processed.toTransportString().contains("output_storage_error"))
            assertTrue(processed.toTransportString().contains("could not be saved"))
            if (processed is ToolResult.Error) assertEquals("ORIGINAL_CODE", processed.code)
        }
        assertTrue(archives.isEmpty())
    }

    @Test
    fun oversizedErrorCodeReturnsBoundedMetadataAndArchivesOriginal() {
        val original = ToolResult.Error("failure", "code".repeat(20_000))
        val processed = store.process("test", "large-code", original) as ToolResult.Error
        assertBounded(processed)
        assertEquals("OUTPUT_METADATA_TOO_LARGE", processed.code)
        assertTrue(processed.message.contains("output_metadata_truncated"))
        assertEquals(original.toTransportString(), archives.values.single())
    }

    @Test
    fun oversizedImageMetadataReturnsBoundedMetadataAndArchivesOriginal() {
        val original = ToolResult.Success(JsonPrimitive("ok"), listOf(
            AgentImage("image/png", "payload", "/image".repeat(10_000))
        ))
        val processed = store.process("test", "large-image-path", original) as ToolResult.Success
        assertBounded(processed)
        assertTrue(processed.images.isEmpty())
        assertTrue(processed.data.jsonObject.getValue("output_metadata_truncated").jsonPrimitive.boolean)
        assertArchive(original, processed.data)
    }

    @Test
    fun boundTextIncludesEscapingAndMetadataInItsBudgetAndStoresRawText() {
        val small = store.boundText("test", "small-text", "ok")
        assertEquals(StoredToolOutput("ok", false, 2), small)
        val raw = "\"\\\n\u0001".repeat(10_000)
        val stored = store.boundText("test", "raw-text", raw)
        assertTrue(stored.truncated)
        assertEquals(raw.length.toLong(), stored.totalChars)
        assertEquals(raw, archives.getValue(requireNotNull(stored.outputPath)))
        assertTrue(stored.preview.startsWith("\"\\"))
        assertTrue(stored.preview.endsWith("\u0001"))
        val envelope = ToolResult.Success(JsonObject(mapOf(
            "output" to JsonPrimitive(stored.preview),
            "output_truncated" to JsonPrimitive(stored.truncated),
            "output_total_chars" to JsonPrimitive(stored.totalChars),
            "output_path" to JsonPrimitive(stored.outputPath)
        )))
        assertBounded(envelope)
    }

    @Test
    fun repeatedCallsUseDifferentArchivesWithoutOverwritingFullResults() {
        val original = ToolResult.Success(JsonPrimitive("x".repeat(50_000)))
        repeat(2) {
            val processed = store.process("same/tool", "same-call", original) as ToolResult.Success
            assertBounded(processed)
            assertArchive(original, processed.data)
        }
        assertEquals(2, archives.size)
        assertEquals(setOf(textTransport(original)), archives.values.toSet())
    }

    @Test
    fun boundTextStorageFailureHasNoPathAndStillFitsWithFailureMetadata() {
        every { fileAccess.mkdirs(any()) } throws IllegalStateException("no access".repeat(10_000))
        val stored = store.boundText("test", "raw-failed", "x".repeat(60_000))
        assertTrue(stored.truncated)
        assertEquals(null, stored.outputPath)
        assertTrue(requireNotNull(stored.storageError).contains("could not be saved"))
        assertBounded(ToolResult.Success(JsonObject(mapOf(
            "output" to JsonPrimitive(stored.preview),
            "output_truncated" to JsonPrimitive(stored.truncated),
            "output_total_chars" to JsonPrimitive(stored.totalChars),
            "output_storage_error" to JsonPrimitive(stored.storageError)
        ))))
    }

    private fun assertBounded(result: ToolResult) {
        assertTrue("Text transport exceeds 40000 characters", textTransport(result).length <= 40_000)
    }

    private fun assertArchive(original: ToolResult, data: JsonElement) {
        val path = data.jsonObject.getValue("output_path").jsonPrimitive.content
        assertTrue(path.startsWith("/home/remote/.aharou/tool-output/"))
        assertEquals(original.toTransportString(), archives.getValue(path))
        assertEquals(JsonPrimitive(original.toTransportString().length), data.jsonObject["output_total_chars"])
    }

    private fun textTransport(result: ToolResult): String = when (result) {
        is ToolResult.Success -> result.copy(images = result.images.map { it.copy(base64Data = "") }).toTransportString()
        else -> result.toTransportString()
    }

    private fun assertValidSurrogates(text: String) {
        text.forEachIndexed { index, ch ->
            if (ch.isHighSurrogate()) assertTrue(index + 1 < text.length && text[index + 1].isLowSurrogate())
            if (ch.isLowSurrogate()) assertTrue(index > 0 && text[index - 1].isHighSurrogate())
        }
    }
}
