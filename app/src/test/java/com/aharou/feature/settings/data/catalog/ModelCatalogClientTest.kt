package com.aharou.feature.settings.data.catalog

import com.aharou.feature.settings.data.remote.ModelMetadataService
import com.aharou.feature.settings.domain.model.ProviderType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelCatalogClientTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val rawCatalog = """
        {
          "openai": {
            "id": "openai",
            "models": {
              "gpt-5": {"id": "gpt-5", "name": "GPT-5", "limit": {"context": 400000, "output": 128000}, "tool_call": true, "reasoning": true},
              "broken-model": "not-an-object"
            }
          },
          "anthropic": {
            "id": "anthropic",
            "models": {
              "claude-sonnet-5": {"id": "claude-sonnet-5", "name": "Claude Sonnet 5", "limit": {"context": 200000}}
            }
          },
          "openrouter": {
            "id": "openrouter",
            "models": {
              "z-ai/glm-5.3": {"id": "z-ai/glm-5.3"}
            }
          },
          "broken-provider": "not-an-object"
        }
    """.trimIndent()

    @Test
    fun filterCatalog_keepsOnlyOfficialProviders() {
        val filtered = ModelCatalogClient.filterCatalog(rawCatalog)
        assertNotNull(filtered)
        val root = json.parseToJsonElement(filtered!!).jsonObject
        assertEquals(setOf("openai", "anthropic"), root.keys)
        assertNull(root["openrouter"])
        assertNull(root["broken-provider"])
    }

    @Test
    fun filterCatalog_skipsMalformedModelEntries() {
        val filtered = ModelCatalogClient.filterCatalog(rawCatalog)!!
        val root = json.parseToJsonElement(filtered).jsonObject
        val openaiModels = root.getValue("openai").jsonObject.getValue("models").jsonObject
        assertEquals(setOf("gpt-5"), openaiModels.keys)
    }

    @Test
    fun filterCatalog_dropsWrongTypedFieldsInsteadOfFailing() {
        val raw = """
            {
              "google": {
                "models": {
                  "gemini-3-pro": {
                    "id": "gemini-3-pro",
                    "name": {"unexpected": "object"},
                    "limit": {"context": {"nested": 1}, "output": 65536},
                    "tool_call": "yes",
                    "modalities": {"input": [{"bad": 1}, "image"], "output": "not-an-array"},
                    "reasoning_options": [{"type": {"bad": 1}}, {"type": "effort", "values": [{"x": 1}, "low"]}]
                  }
                }
              }
            }
        """.trimIndent()
        val filtered = ModelCatalogClient.filterCatalog(raw)!!
        val catalog = ModelMetadataService.parseCatalog(json.parseToJsonElement(filtered))
        val metadata = catalog.byProvider.getValue("google").getValue("gemini-3-pro")

        // name 类型不符 -> 剔掉，回退到 id
        assertEquals("gemini-3-pro", metadata.displayName)
        // limit.context 是对象 -> 该字段被剔掉，context 回退 0；output 保留
        assertEquals(0, metadata.contextTokens)
        assertEquals(65536, metadata.outputTokens)
        // tool_call 是字符串而不是布尔 -> 不认
        assertFalse(metadata.supportsTools)
        // modalities.input 里的非字符串项被剔除，剩 image
        assertTrue(metadata.supportsVision)
        // reasoning_options 里结构非法的项被剔除，只留 effort + low
        assertEquals(listOf("low"), metadata.reasoningEffortOptions)
    }

    @Test
    fun filterCatalog_toleratesMalformedProviderAndModelShapes() {
        val raw = """
            {
              "xai": {"models": {"grok-5": {"id": "grok-5"}}},
              "deepseek": {"models": "not-an-object"},
              "moonshotai": {"no-models-key": true}
            }
        """.trimIndent()
        val filtered = ModelCatalogClient.filterCatalog(raw)!!
        val root = json.parseToJsonElement(filtered).jsonObject
        assertEquals(setOf("xai"), root.keys)
    }

    @Test
    fun filterCatalog_returnsNullWhenNothingUsable() {
        assertNull(ModelCatalogClient.filterCatalog("""{"openrouter": {"models": {}}}"""))
        assertNull(ModelCatalogClient.filterCatalog("""{"openai": "not-an-object"}"""))
        assertNull(ModelCatalogClient.filterCatalog("""[]"""))
    }

    @Test
    fun filterCatalog_returnsNullOnInvalidJson() {
        assertNull(ModelCatalogClient.filterCatalog("not json at all"))
        assertNull(ModelCatalogClient.filterCatalog(""))
    }

    @Test
    fun filteredCatalog_isParsableByMetadataService() {
        val filtered = ModelCatalogClient.filterCatalog(rawCatalog)!!
        val catalog = ModelMetadataService.parseCatalog(json.parseToJsonElement(filtered))
        val gpt5 = ModelMetadataService.findMetadata(catalog, ProviderType.OPENAI, "gpt-5")
        assertNotNull(gpt5)
        assertEquals(400000, gpt5!!.contextTokens)
        assertEquals(128000, gpt5.outputTokens)
    }

    @Test
    fun officialProviderIds_matchBundledSnapshot() {
        // 白名单必须与内置快照的 provider 集合一致；工作目录不匹配时跳过。
        val asset = java.io.File("src/main/assets/api.official.json")
        org.junit.Assume.assumeTrue(asset.exists())
        val keys = json.parseToJsonElement(asset.readText()).jsonObject.keys
        assertEquals(keys, ModelCatalogClient.OFFICIAL_PROVIDER_IDS)
    }
}
