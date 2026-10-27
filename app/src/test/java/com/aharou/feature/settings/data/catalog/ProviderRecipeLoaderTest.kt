package com.aharou.feature.settings.data.catalog

import com.aharou.feature.settings.data.catalog.recipe.ProviderProtocol
import com.aharou.feature.settings.data.catalog.recipe.ProviderRecipeLoader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderRecipeLoaderTest {

    private val fullRecipe = """
        {
          "providerId": "anthropic",
          "displayName": "Anthropic",
          "protocol": "anthropic-messages",
          "defaultBaseUrl": "https://api.anthropic.com/",
          "requestPath": "v1/messages",
          "modelField": "model",
          "modelInPath": false,
          "authHeader": "x-api-key",
          "systemField": "system",
          "systemAsTopLevelField": true,
          "systemRoleOverrideForReasoningModels": false,
          "maxTokensField": "max_tokens",
          "temperatureMode": "model-metadata",
          "sendsTemperatureWhenThinking": false,
          "streamEndMarker": "message_stop",
          "thinkingOffField": null,
          "echoReasoningContent": false,
          "echoThinkingBlocks": true,
          "requiredFields": ["model", "messages", "max_tokens"],
          "optionalFields": ["system", "temperature", "thinking"],
          "aliases": ["claude-code"],
          "futureFieldFromNewerClient": {"ignored": true}
        }
    """.trimIndent()

    /** parseRecipes 接受数组或带 recipes 的包装对象；测试数据是单条 recipe，包成数组再用。 */
    private val fullRecipeList = "[$fullRecipe]"

    @Test
    fun fullJson_parsesAllFields() {
        val recipes = ProviderRecipeLoader.parseRecipes(fullRecipeList)
        val recipe = recipes.getValue("anthropic")
        assertEquals(ProviderProtocol.ANTHROPIC_MESSAGES, recipe.protocol)
        assertEquals("https://api.anthropic.com/", recipe.defaultBaseUrl)
        assertEquals("v1/messages", recipe.requestPath)
        assertEquals("max_tokens", recipe.maxTokensField)
        assertEquals("message_stop", recipe.streamEndMarker)
        assertTrue(recipe.systemAsTopLevelField)
        assertFalse(recipe.sendsTemperatureWhenThinking)
        assertTrue(recipe.echoThinkingBlocks)
        assertEquals(listOf("model", "messages", "max_tokens"), recipe.requiredFields)
        // 未知字段被忽略而不是让整条记录失败
        assertEquals("anthropic", recipe.providerId)
        // aliases 也进索引
        assertEquals(recipe, recipes["claude-code"])
    }

    @Test
    fun missingOptionalFields_useDefaults() {
        val recipes = ProviderRecipeLoader.parseRecipes(
            """[{"providerId":"example","protocol":"openai-chat"}]"""
        )
        val recipe = recipes.getValue("example")
        assertEquals("", recipe.displayName)
        assertEquals("", recipe.requestPath)
        assertEquals("model", recipe.modelField)
        assertNull(recipe.maxTokensField)
        assertNull(recipe.streamEndMarker)
        assertNull(recipe.thinkingOffField)
        assertEquals("model-metadata", recipe.temperatureMode)
        assertTrue(recipe.sendsTemperatureWhenThinking)
        assertTrue(recipe.requiredFields.isEmpty())
        assertTrue(recipe.optionalFields.isEmpty())
        assertTrue(recipe.aliases.isEmpty())
    }

    @Test
    fun unknownProvider_returnsNull() {
        val recipes = ProviderRecipeLoader.parseRecipes(fullRecipeList)
        assertNull(ProviderRecipeLoader.lookup(recipes, "no-such-provider"))
        assertNull(ProviderRecipeLoader.lookup(recipes, ""))
        // 大小写不敏感
        assertEquals("anthropic", ProviderRecipeLoader.lookup(recipes, "ANTHROPIC")?.providerId)
    }

    @Test
    fun illegalEntries_skippedWithoutFailingWholeFile() {
        val recipes = ProviderRecipeLoader.parseRecipes(
            """
            {
              "version": 1,
              "recipes": [
                {"providerId": "good", "protocol": "openai-chat"},
                {"providerId": "", "protocol": "openai-chat"},
                {"providerId": "bad-protocol", "protocol": "not-a-protocol"},
                {"protocol": "openai-chat"},
                "this-is-not-an-object",
                {"providerId": "also-good", "protocol": "gemini-generate-content"}
              ]
            }
            """.trimIndent()
        )
        assertEquals(setOf("good", "also-good"), recipes.keys)
    }

    @Test
    fun invalidJson_returnsEmptyWithoutThrowing() {
        assertTrue(ProviderRecipeLoader.parseRecipes("{ this is not json").isEmpty())
        assertTrue(ProviderRecipeLoader.parseRecipes("null").isEmpty())
    }

    @Test
    fun bundledRecipeFile_parsesAndCoversCoreProviders() {
        // Gradle 单测的工作目录是 app 模块目录；文件不存在时跳过，不把测试绑在执行环境上。
        val asset = java.io.File("src/main/assets/provider-recipes.json")
        org.junit.Assume.assumeTrue(asset.exists())
        val recipes = ProviderRecipeLoader.parseRecipes(asset.readText())
        assertTrue(recipes.containsKey("openai"))
        assertTrue(recipes.containsKey("anthropic"))
        assertTrue(recipes.containsKey("google"))
        // 预设 id 与 models.dev id 不一致时靠 aliases 命中同一份 recipe
        assertEquals("zhipuai", ProviderRecipeLoader.lookup(recipes, "zai")?.providerId)
        assertEquals("moonshotai", ProviderRecipeLoader.lookup(recipes, "moonshotai-cn")?.providerId)
    }
}
