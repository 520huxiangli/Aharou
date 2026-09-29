package com.aharou.feature.agent.domain.skill.market

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 「粘贴地址」的解析：Git 托管平台的仓库地址，以及技能网站（skills.sh / LobeHub）的地址。
 *
 * 技能网站那两类最终都要落到一个 GitHub 仓库上——清单里只有 SKILL.md 与目录结构，
 * 没有仓库信息就无从下载，所以解析的产出始终是 [SkillRepoAccess.Coord]。
 */
class SkillRepoAccessTest {

    // ── Git 托管平台（回归） ──

    @Test
    fun parse_githubRepo() {
        val parsed = SkillRepoAccess.parse("https://github.com/anthropics/skills")
        assertEquals(SkillRepoAccess.GITHUB, parsed?.coord?.host)
        assertEquals("anthropics/skills", parsed?.coord?.repo)
        assertEquals("", parsed?.subPath)
    }

    @Test
    fun parse_githubTreeWithSubPath() {
        val parsed = SkillRepoAccess.parse("https://github.com/anthropics/skills/tree/main/document-skills/docx")
        assertEquals("main", parsed?.coord?.ref)
        assertEquals("document-skills/docx", parsed?.subPath)
    }

    @Test
    fun parse_gitlabRepo() {
        val parsed = SkillRepoAccess.parse("https://gitlab.com/group/repo")
        assertEquals(SkillRepoAccess.GITLAB, parsed?.coord?.host)
        assertEquals("group/repo", parsed?.coord?.repo)
    }

    @Test
    fun parse_shorthandOwnerRepo() {
        val parsed = SkillRepoAccess.parse("anthropics/skills")
        assertEquals(SkillRepoAccess.GITHUB, parsed?.coord?.host)
        assertEquals("anthropics/skills", parsed?.coord?.repo)
    }

    // ── skills.sh ──

    @Test
    fun parse_skillsShRepo_mapsToGithubRepo() {
        val parsed = SkillRepoAccess.parse("https://skills.sh/anthropics/skills")
        assertEquals(SkillRepoAccess.GITHUB, parsed?.coord?.host)
        assertEquals("anthropics/skills", parsed?.coord?.repo)
        assertEquals("", parsed?.subPath)
    }

    @Test
    fun parse_skillsShSkill_keepsSkillAsSubPath() {
        val parsed = SkillRepoAccess.parse("https://skills.sh/anthropics/skills/pdf")
        assertEquals("anthropics/skills", parsed?.coord?.repo)
        assertEquals("pdf", parsed?.subPath)
    }

    @Test
    fun parse_skillsShBareHost_isRejected() {
        assertNull(SkillRepoAccess.parse("https://skills.sh"))
    }

    @Test
    fun parse_skillsSh_isNotTreatedAsGiteaHost() {
        // 若漏了这条分支，host 会变成 "skills.sh"，列目录时去请求它自己的 API（必然 404）
        val parsed = SkillRepoAccess.parse("https://skills.sh/vercel-labs/skills")
        assertEquals(SkillRepoAccess.GITHUB, parsed?.coord?.host)
    }

    // ── LobeHub：地址里拼不出仓库，先给出要抓的页面 ──

    @Test
    fun siteLookupUrl_lobehubSkillPage() {
        assertEquals(
            "https://lobehub.com/skills/anthropics-skills-pptx",
            SkillRepoAccess.siteLookupUrl("https://lobehub.com/skills/anthropics-skills-pptx")
        )
    }

    @Test
    fun siteLookupUrl_ignoresNonLobehubAddresses() {
        assertNull(SkillRepoAccess.siteLookupUrl("https://github.com/anthropics/skills"))
        assertNull(SkillRepoAccess.siteLookupUrl("https://skills.sh/anthropics/skills/pdf"))
        assertNull(SkillRepoAccess.siteLookupUrl("anthropics/skills"))
    }

    @Test
    fun siteLookupUrl_lobehubSiteRoot_isNotASkillPage() {
        assertNull(SkillRepoAccess.siteLookupUrl("https://lobehub.com/skills"))
    }

    @Test
    fun repoFromSitePage_findsRepoInMarkdownBody() {
        val body = """
            # pptx

            Install: npx skills add https://github.com/anthropics/skills

            See [repo](https://github.com/anthropics/skills) for details.
        """.trimIndent()
        assertEquals("anthropics/skills", SkillRepoAccess.repoFromSitePage(body))
    }

    @Test
    fun repoFromSitePage_returnsNullWhenNoRepoLink() {
        assertNull(SkillRepoAccess.repoFromSitePage("# skill\n\n没有仓库链接"))
    }
}
