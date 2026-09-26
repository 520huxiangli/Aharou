package com.aharou.feature.agent.domain.tool.file

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerateImageToolTest {

    @Test
    fun estimatedDecodedBytes_rejectsOversizedPayloadBeforeDecode() {
        val maxBase64Length = (GenerateImageTool.MAX_IMAGE_BYTES * 4 / 3).toInt()

        assertTrue(
            GenerateImageTool.estimatedDecodedBytes(maxBase64Length + 8) >
                GenerateImageTool.MAX_IMAGE_BYTES
        )
    }

    @Test
    fun validateImageParams_acceptsSupportedGptImageValues() {
        val error = validate(
            model = "gpt-image-1",
            isGptImage = true,
            quality = "high",
            background = "transparent",
            moderation = "auto",
            outputFormat = "png"
        )

        assertNull(error)
    }

    @Test
    fun validateImageParams_rejectsInvalidEnumValue() {
        val error = validate(
            model = "gpt-image-1",
            isGptImage = true,
            background = "blurred"
        )

        assertNotNull(error)
        assertTrue(error!!.contains("background 取值 blurred 不支持"))
    }

    @Test
    fun validateImageParams_rejectsDalle3MultipleImages() {
        val error = validate(
            model = "dall-e-3",
            isDalle3 = true,
            n = 2
        )

        assertEquals("dall-e-3 一次只能生成 1 张（n=1），需要多张请改用 GPT Image 系列模型。", error)
    }

    private fun validate(
        model: String,
        isGptImage: Boolean = false,
        isDalle2: Boolean = false,
        isDalle3: Boolean = false,
        n: Int = 1,
        quality: String? = null,
        background: String? = null,
        moderation: String? = null,
        style: String? = null,
        outputFormat: String? = null
    ): String? = GenerateImageTool.validateImageParams(
        model = model,
        isGptImage = isGptImage,
        isDalle2 = isDalle2,
        isDalle3 = isDalle3,
        n = n,
        quality = quality,
        background = background,
        moderation = moderation,
        style = style,
        outputFormat = outputFormat
    )
}
