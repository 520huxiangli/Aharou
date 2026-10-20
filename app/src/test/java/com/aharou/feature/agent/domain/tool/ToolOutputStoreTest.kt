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
        assertEquals(StoredToolOutput("ok", false, 2, 1), small)
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

    @Test
    fun partialMessageLinesWinOverSmallData() {
        val message = numberedLines(count = 2_500, width = 8)
        val original = ToolResult.Partial(JsonObject(mapOf("done" to JsonPrimitive(false))), message)
        val processed = store.process("test", "partial-lines", original) as ToolResult.Partial
        assertBounded(processed)
        assertEquals(JsonPrimitive(false), processed.data.jsonObject["done"])
        assertEquals(JsonPrimitive(2_500), processed.data.jsonObject["output_total_lines"])
        assertEquals(2_500, markerTotalLines(processed.message))
    }

    @Test
    fun errorMessageLinesDriveOutputTotalLines() {
        val message = numberedLines(count = 2_000, width = 8)
        val processed = store.process("test", "error-lines", ToolResult.Error(message, "REMOTE_FAILED")) as ToolResult.Error
        assertBounded(processed)
        assertEquals("REMOTE_FAILED", processed.code)
        val metadata = Json.parseToJsonElement(processed.message.substringAfterLast('\n')).jsonObject
        assertEquals(JsonPrimitive(2_000), metadata["output_total_lines"])
        assertEquals(2_000, markerTotalLines(processed.message))
        // 错误结果的正文就是 message，行号必须指回 message 而不是单行的 JSON 传输串。
        assertOmittedRangeMatchesSlices(message, processed.message.substringBeforeLast('\n'))
    }

    @Test
    fun untruncatedResultsCarryNoLineMetadataAndStillCountLines() {
        val short = ToolResult.Success(JsonPrimitive("a\nb\nc"))
        assertSame(short, store.process("test", "short-lines", short))
        assertTrue(archives.isEmpty())
        // 不截断就不动结果（沿用既有行为），但 boundText 仍报真实行数，字段不会拿 0 充数。
        assertEquals(StoredToolOutput("a\nb\nc", false, 5, 3), store.boundText("test", "short-raw", "a\nb\nc"))
        assertTrue(archives.isEmpty())
    }

    @Test
    fun truncatedPreviewNamesTheOmittedLineRangeAndTotalLines() {
        val text = numberedLines(count = 4_000, width = 8)
        val processed = store.process("test", "line-range", ToolResult.Success(JsonPrimitive(text))) as ToolResult.Success
        assertBounded(processed)
        assertArchive(ToolResult.Success(JsonPrimitive(text)), processed.data)
        assertEquals(JsonPrimitive(4_000), processed.data.jsonObject["output_total_lines"])
        val preview = processed.data.jsonObject.getValue("output").jsonPrimitive.content
        assertOmittedRangeMatchesSlices(text, preview)
        assertTrue(preview.startsWith("line 0001: "))
        assertTrue(preview.endsWith("line 4000: aaaaaaaa\n"))
        assertTrue(preview.contains("of 4000,"))
    }

    @Test
    fun crlfLoneCrAndTrailingNewlineFollowReadFileLineNumbers() {
        val crlf = numberedLines(count = 4_000, width = 8, ending = "\r\n")
        val crlfStored = store.boundText("test", "crlf", crlf)
        assertTrue(crlfStored.truncated)
        assertEquals(4_000, crlfStored.totalLines)
        assertEquals(crlf, assertArchivedRaw(crlfStored))
        assertOmittedRangeMatchesSlices(crlf, crlfStored.preview)
        assertTrue(crlfStored.preview.endsWith("line 4000: aaaaaaaa\r\n"))

        // 末尾无换行不多算一行，末尾有换行也不多算一行（与 BufferedReader.readLine 一致）。
        val noTrailing = numberedLines(count = 4_000, width = 8).trimEnd('\n')
        assertEquals(4_000, store.boundText("test", "no-trailing", noTrailing).totalLines)
        assertEquals(StoredToolOutput("a\nb\nc", false, 5, 3), store.boundText("test", "lf", "a\nb\nc"))
        assertEquals(StoredToolOutput("a\nb\nc\n", false, 6, 3), store.boundText("test", "lf-tail", "a\nb\nc\n"))
        assertEquals(StoredToolOutput("a\r\nb\r\nc", false, 7, 3), store.boundText("test", "crlf-small", "a\r\nb\r\nc"))
        assertEquals(StoredToolOutput("a\rb", false, 3, 2), store.boundText("test", "lone-cr", "a\rb"))
        assertEquals(StoredToolOutput("", false, 0, 0), store.boundText("test", "empty", ""))
    }

    @Test
    fun singleOmittedLineIsReportedAsOneLine() {
        val text = "x".repeat(60_000)
        val stored = store.boundText("test", "one-line", text)
        assertTrue(stored.truncated)
        assertEquals(1, stored.totalLines)
        assertTrue(stored.preview.contains("...[output truncated; omitted line 1 of 1, "))
        assertEquals(text, assertArchivedRaw(stored))
        assertOmittedRangeMatchesSlices(text, stored.preview)
    }

    @Test
    fun wideEmojiLinesKeepSurrogatesAndLineNumbers() {
        val text = (1..4_000).joinToString("\n") { "row $it 汉字😀😀" }
        val stored = store.boundText("test", "emoji-lines", text)
        assertEquals(4_000, stored.totalLines)
        assertValidSurrogates(stored.preview)
        assertEquals(text, assertArchivedRaw(stored))
        assertOmittedRangeMatchesSlices(text, stored.preview)
    }

    @Test
    fun multiFieldResultReportsLongestFieldLinesAndPerFieldRanges() {
        val manyLinesField = numberedLines(count = 1_500, width = 8)
        val wideField = numberedLines(count = 600, width = 50)
        val original = ToolResult.Success(JsonObject(mapOf(
            "stdout" to JsonPrimitive(manyLinesField),
            "nested" to JsonObject(mapOf("stderr" to JsonPrimitive(wideField)))
        )))
        val processed = store.process("test", "multi-field-lines", original) as ToolResult.Success
        assertBounded(processed)
        assertArchive(original, processed.data)
        val data = processed.data.jsonObject
        assertEquals(JsonPrimitive(1_500), data["output_total_lines"])
        val stdout = data.getValue("stdout").jsonPrimitive.content
        val stderr = data.getValue("nested").jsonObject.getValue("stderr").jsonPrimitive.content
        assertEquals(1_500, markerTotalLines(stdout))
        assertEquals(600, markerTotalLines(stderr))
        assertOmittedRangeMatchesSlices(manyLinesField, stdout)
        assertOmittedRangeMatchesSlices(wideField, stderr)
    }

    private fun assertBounded(result: ToolResult) {
        assertTrue("Text transport exceeds 40000 characters", textTransport(result).length <= 40_000)
    }

    private fun assertArchive(original: ToolResult, data: JsonElement) {
        val path = data.jsonObject.getValue("output_path").jsonPrimitive.content
        assertTrue(path.startsWith("/home/remote/.aharou/tool-output/"))
        assertEquals(original.toTransportString(), archives.getValue(path))
        assertEquals(JsonPrimitive(original.toTransportString().length), data.jsonObject["output_total_chars"])
        // 既有字段一个都不能少，新行数字段与它们并存。
        assertTrue(data.jsonObject.containsKey("output_truncated"))
        assertTrue(data.jsonObject.containsKey("output_total_lines"))
    }

    private fun assertArchivedRaw(stored: StoredToolOutput): String {
        val path = requireNotNull(stored.outputPath)
        assertTrue(path.startsWith("/home/remote/.aharou/tool-output/"))
        return archives.getValue(path)
    }

    /**
     * 从预览里取出 head/tail 两段（它们必须是正文的前缀/后缀），再用这两段反算被省掉的行区间，
     * 验证标记里的行号确实指向「没给你的那几行」，而不是一个看着像行号的数字。
     */
    private fun assertOmittedRangeMatchesSlices(text: String, preview: String) {
        val markerStart = preview.indexOf("...[output truncated; omitted ")
        assertTrue("预览缺少截断标记", markerStart > 2)
        val markerEnd = preview.indexOf("]...\n\n", markerStart)
        assertTrue("截断标记没有收尾", markerEnd > markerStart)
        val head = preview.substring(0, markerStart - 2)
        val tail = preview.substring(markerEnd + 6)
        assertTrue("head 必须是正文前缀", text.startsWith(head))
        assertTrue("tail 必须是正文后缀", text.endsWith(tail))
        val omitted = text.substring(head.length, text.length - tail.length)
        val marker = preview.substring(markerStart, markerEnd)
        val chars = Regex(", (\\d+) characters").find(marker)?.groupValues?.get(1)?.toInt()
        assertEquals(omitted.length, chars)
        val expectedStart = 1 + head.count { it == '\n' }
        val expectedEnd = expectedStart + omitted.count { it == '\n' } - if (omitted.endsWith('\n')) 1 else 0
        assertEquals(expectedStart, markerStartLine(marker))
        assertEquals(expectedEnd, markerEndLine(marker))
        assertTrue(expectedEnd >= expectedStart)
    }

    private fun markerStartLine(marker: String): Int =
        Regex("omitted (?:lines (\\d+)-(\\d+)|line (\\d+)) of").find(marker)
            ?.groupValues?.let { if (it[1].isNotEmpty()) it[1].toInt() else it[3].toInt() }
            ?: error("标记里没有行号: $marker")

    private fun markerEndLine(marker: String): Int =
        Regex("omitted (?:lines (\\d+)-(\\d+)|line (\\d+)) of").find(marker)
            ?.groupValues?.let { if (it[2].isNotEmpty()) it[2].toInt() else it[3].toInt() }
            ?: error("标记里没有行号: $marker")

    private fun markerTotalLines(text: String): Int =
        Regex("omitted (?:lines \\d+-\\d+|line \\d+) of (\\d+),").find(text)
            ?.groupValues?.get(1)?.toInt()
            ?: error("标记里没有总行数")

    private fun numberedLines(count: Int, width: Int, ending: String = "\n"): String = buildString {
        repeat(count) { index ->
            append("line ")
            append((index + 1).toString().padStart(4, '0'))
            append(": ")
            append("a".repeat(width))
            append(ending)
        }
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
