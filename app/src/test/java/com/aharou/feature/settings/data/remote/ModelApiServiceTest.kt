package com.aharou.feature.settings.data.remote

import android.content.Context
import com.aharou.feature.settings.domain.model.ProviderType
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelApiServiceTest {
    private fun service(body: String): ModelApiService {
        val context = mockk<Context>(relaxed = true)
        every { context.getString(any()) } returns "diagnostic"
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        return ModelApiService(client, context)
    }

    @Test fun successfulHttpWithApiErrorIsNotUsableModel() = runTest {
        val result = service("""{"error":{"message":"upstream unavailable"}}""").testModel(
            "https://example.invalid", "", ProviderType.OPENAI, false, false, "fixture")
        assertFalse(result.success)
    }

    @Test fun successfulHttpWithEmptyButValidMessageIsUsable() = runTest {
        val result = service("""{"choices":[{"message":{"role":"assistant","content":null},"finish_reason":"length"}]}""").testModel(
            "https://example.invalid", "", ProviderType.OPENAI, false, false, "fixture")
        assertTrue(result.success)
    }
}
