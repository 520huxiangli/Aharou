package com.aharou.feature.agent.domain.skill

import android.content.Context
import com.aharou.core.util.FileLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 内置技能来源：随 App 打包在 `assets/skills/<名称>/SKILL.md`。
 *
 * 只读——用户既不能编辑也不能删除，故不提供任何写入入口；落盘也免了，
 * 直接读 assets，不会出现「删掉又复活」的情况。
 */
@Singleton
class BuiltinSkillSource @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : SkillSource {

    override fun listSkills(): List<Skill> =
        runCatching {
            context.assets.list(ASSETS_ROOT).orEmpty()
                .mapNotNull { dir -> readSkill(dir) }
                .sortedBy { it.name.lowercase() }
        }.getOrElse { e ->
            FileLogger.w(TAG, "扫描内置技能失败：${e.message}")
            emptyList()
        }

    override fun loadInstructions(name: String): String? =
        listSkills().firstOrNull { it.name.equals(name, ignoreCase = true) }?.instructions

    /** 读单个内置技能目录；缺 SKILL.md 或解析失败返回 null，不影响其余技能。 */
    private fun readSkill(dir: String): Skill? = runCatching {
        val path = "$ASSETS_ROOT/$dir/$SKILL_FILE"
        val text = context.assets.open(path).bufferedReader().use { it.readText() }
        SkillParser.parseText(text, dir)
    }.getOrElse { e ->
        FileLogger.w(TAG, "读取内置技能失败：$dir（${e.message}）")
        null
    }

    private companion object {
        const val TAG = "BuiltinSkillSource"
        const val ASSETS_ROOT = "skills"
        const val SKILL_FILE = "SKILL.md"
    }
}
