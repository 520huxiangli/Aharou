package com.aharou.feature.agent.domain.skill.market

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 检索型源（skills.sh 这类）：只有关键词接口、没有「列全部」的入口，
 * 所以源的地址拼装与响应解析都要锁住，改坏了会直接表现为「搜不到东西」。
 */
class SkillMarketSourceTest {

    private val json = Json { ignoreUnknownKeys = true }

    private val skillsSh = SkillMarketSourceDef(
        kind = "search",
        searchUrl = "https://skills.sh/api/search?q={q}"
    )

    @Test
    fun searchSource_isRecognized() {
        assertTrue(skillsSh.isSearch)
        assertTrue(!skillsSh.isDirectory)
    }

    @Test
    fun searchUrlFor_fillsTrimmedKeyword() {
        assertEquals("https://skills.sh/api/search?q=pdf", skillsSh.searchUrlFor("  pdf  "))
    }

    @Test
    fun searchUrlFor_escapesKeyword() {
        assertEquals("https://skills.sh/api/search?q=a+b%26c", skillsSh.searchUrlFor("a b&c"))
    }

    @Test
    fun searchUrlFor_rejectsBlankKeywordAndMissingEndpoint() {
        assertNull(skillsSh.searchUrlFor("   "))
        assertNull(SkillMarketSourceDef(kind = "search").searchUrlFor("pdf"))
    }

    @Test
    fun parsesSkillsShResponse() {
        val body = """
            {"query":"pdf","searchType":"fuzzy","searchVersion":"algolia","skills":[
              {"id":"anthropics/skills/pdf","source":"anthropics/skills","skillId":"pdf","name":"pdf","installs":202784},
              {"id":"openai/skills/pdf","source":"openai/skills","skillId":"pdf","name":"pdf","installs":12848}
            ],"count":2}
        """.trimIndent()
        val parsed = json.decodeFromString<SkillSearchResponse>(body)
        assertEquals(2, parsed.skills.size)
        assertEquals("anthropics/skills", parsed.skills[0].source)
        assertEquals("pdf", parsed.skills[0].skillId)
        assertEquals(202784L, parsed.skills[0].installs)
    }

    @Test
    fun parsesResponseWithMissingFields() {
        // 接口字段缺失时不能整段炸掉，缺的那条由上层按空值过滤
        val parsed = json.decodeFromString<SkillSearchResponse>("""{"skills":[{"source":"a/b"}]}""")
        assertEquals(1, parsed.skills.size)
        assertEquals("", parsed.skills[0].skillId)
    }
}
