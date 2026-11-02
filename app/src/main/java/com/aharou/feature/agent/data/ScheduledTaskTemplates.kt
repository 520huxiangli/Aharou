package com.aharou.feature.agent.data

import androidx.annotation.StringRes
import com.aharou.R
import com.aharou.feature.agent.data.local.entity.ScheduledTaskEntity

/*
 * 自动化任务内置模板。两条产品原则写死在这里，改模板时必须守住：
 *
 * 1. 数据来源只限工作目录/仓库与公开网页，绝不周期性地读取用户私人账号数据（邮箱、IM、日程）：
 *    每次运行都消耗 token，还会把隐私数据喂进定时任务。
 * 2. 产出必须是可交付物（报告、草稿、PR），不能只是提醒。
 *
 * 因此每条 prompt 里都带约束条款：只处理有明确证据的问题、采用影响面最小的改法、
 * 只报告有证据支撑的结论、无法安全处理时交代失败原因与建议的排查步骤。
 */

/** 模板分类：设置页按它分组展示。 */
enum class ScheduledTaskTemplateCategory {
    DEVELOPMENT,
    RADAR,
    DOCUMENT,
}

/**
 * 一条内置模板。名称与描述是用户可见文案（走 strings.xml），[prompt] 是下发给模型的指令正文。
 *
 * 触发配置按本仓模型给出默认值：不用 cron，只用 [scheduleType] + 当天第几分钟的
 * [dailyMinuteOfDay]（510 = 08:30）或周期性 [intervalMinutes]，落到设置页编辑器里仍可改。
 */
data class ScheduledTaskTemplate(
    val id: String,
    @StringRes val nameRes: Int,
    @StringRes val descriptionRes: Int,
    val category: ScheduledTaskTemplateCategory,
    val prompt: String,
    val scheduleType: String = ScheduledTaskEntity.TYPE_DAILY,
    /** 当天第几分钟（0 = 00:00）；只有 [ScheduledTaskEntity.TYPE_DAILY] 用。 */
    val dailyMinuteOfDay: Int = 9 * 60,
    /** 周期分钟数；只有 [ScheduledTaskEntity.TYPE_INTERVAL] 用（低于下限会被调度层收敛）。 */
    val intervalMinutes: Int = 24 * 60,
    /** 非空表示 prompt 里留了 [ScheduledTaskTemplates.TOPIC_PLACEHOLDER]，建任务前要填主题。 */
    @StringRes val topicHintRes: Int? = null,
    val topicDefault: String = "",
)

object ScheduledTaskTemplates {

    /**
     * 用户自填主题的占位符。
     *
     * 它不是运行期占位符：运行期只有事件触发那套 `{{event.*}}`（见 EventPayload.renderPrompt，
     * 由 Worker 在派发时替换）。这里的 `{{topic}}` 只活到「从模板创建」那一刻——选择器把用户
     * 填的主题替换进去，落库的指令里不会留这个标记。故意做成同一套双花括号写法，好让人一眼
     * 看出是待填的位置。
     */
    const val TOPIC_PLACEHOLDER = "{{topic}}"

    val all: List<ScheduledTaskTemplate> = listOf(
        ScheduledTaskTemplate(
            id = "dev_nightly_test_heal",
            nameRes = R.string.scheduled_tasks_template_nightly_test,
            descriptionRes = R.string.scheduled_tasks_template_nightly_test_desc,
            category = ScheduledTaskTemplateCategory.DEVELOPMENT,
            dailyMinuteOfDay = 120,
            prompt = """
                跑一轮项目测试，并只修复有明确失败证据的问题。

                步骤：
                1. 先看清这个工作区怎么跑测试（README、AGENTS.md、构建脚本里的命令），跑一轮。
                2. 只处理有明确失败证据的用例：留下报错原文、失败用例名、能复现的命令。
                3. 修的时候采用影响面最小的改法：能改一行不改一段，不改相邻代码、不顺手重构、不动无关文件。
                4. 无法安全处理时（依赖缺失、环境问题、要改架构、涉及删除数据）不要硬改，在结论里写清失败原因、疑点文件和建议的排查步骤。

                产出：一份简短报告——修了什么（文件与行号）、为什么这么改、仍未通过的有哪些、下一步建议。没有失败就只写「本轮全绿」，附测试命令与耗时。
            """.trimIndent(),
        ),
        ScheduledTaskTemplate(
            id = "dev_pr_guard",
            nameRes = R.string.scheduled_tasks_template_pr_guard,
            descriptionRes = R.string.scheduled_tasks_template_pr_guard_desc,
            category = ScheduledTaskTemplateCategory.DEVELOPMENT,
            dailyMinuteOfDay = 600,
            prompt = """
                预审当前仓库里开放的 Pull Request 新增的改动，按严重程度报出风险。

                步骤：
                1. 列出开放的 PR；一个都没有就直说，不要编造。
                2. 逐个看待合并的差异，重点看：安全面（凭据外发、命令拼接、权限）、数据与数据库迁移、构建与 CI 配置、以及超出 PR 标题范围的改动。
                3. 只报告有证据支撑的问题：说明它在什么场景下会失败，附上文件与行号；命名口味、排版偏好这类不报。
                4. 拿不准的单独列成「待确认」，不要当成缺陷下结论。

                产出：按 PR 分组的简报，每条风险标出严重程度（高/中/低）、失败场景、定位；高风险的排最前。本轮不要改代码、不要推分支。
            """.trimIndent(),
        ),
        ScheduledTaskTemplate(
            id = "radar_domain_watch",
            nameRes = R.string.scheduled_tasks_template_domain_radar,
            descriptionRes = R.string.scheduled_tasks_template_domain_radar_desc,
            category = ScheduledTaskTemplateCategory.RADAR,
            dailyMinuteOfDay = 480,
            topicHintRes = R.string.scheduled_tasks_template_topic_hint,
            topicDefault = "AI 编程助手",
            prompt = """
                围绕「$TOPIC_PLACEHOLDER」搜集过去 24 小时的动态，产出能直接读的简报。

                要求：
                1. 只从公开网页与本地工作目录取材，不读任何私人账号（邮箱、IM、日程）。
                2. 每条动态都要交叉核对来源：至少两个独立来源，只有一个来源就明确标出「单一来源」。
                3. 每条写清「为什么值得关注」——它改变了什么、影响谁、接下来可能发生什么。
                4. 没有值得报的动态就直说，不要拿旧闻重新包装凑数；时间不确定的标出来源时间。

                产出：最多 8 条，按重要性排序，每条附来源链接与时间。不要只发一句「有新动态」。
            """.trimIndent(),
        ),
        ScheduledTaskTemplate(
            id = "radar_competitor_watch",
            nameRes = R.string.scheduled_tasks_template_competitor,
            descriptionRes = R.string.scheduled_tasks_template_competitor_desc,
            category = ScheduledTaskTemplateCategory.RADAR,
            dailyMinuteOfDay = 540,
            prompt = """
                盯住指定项目或产品的最新发布与变更，汇总成变更追踪。

                要求：
                1. 先确认盯哪里：指令里没写明时，从工作区 README 或最近对话判断，并在报告开头写明判断依据。
                2. 只看公开信息：发布说明、变更日志、版本 tag、公开文档。
                3. 每条写清：改了什么、对使用者可见的影响、是否值得跟进（给理由）。
                4. 只报有证据的条目，附来源链接与时间；本期没查到发布就写「本期无发布」，不要凭传闻下结论。

                产出：一份变更清单（版本、时间、要点、影响、来源），末尾加一句「是否需要在我们的项目里做对应调整」。
            """.trimIndent(),
        ),
        ScheduledTaskTemplate(
            id = "doc_weekly_report",
            nameRes = R.string.scheduled_tasks_template_weekly_report,
            descriptionRes = R.string.scheduled_tasks_template_weekly_report_desc,
            category = ScheduledTaskTemplateCategory.DOCUMENT,
            scheduleType = ScheduledTaskEntity.TYPE_INTERVAL,
            intervalMinutes = 7 * 24 * 60,
            prompt = """
                把这一周的工作产出汇总成一份周报草稿。

                要求：
                1. 取材只限工作目录与仓库：提交记录、变更文件、PR 与 issue、文档改动。不读邮箱、IM、日程。
                2. 按「做完了什么 / 在做的是什么 / 卡住了什么」三段组织，每条写清可核对的位置（文件、提交、PR）。
                3. 没有产出的日子不要编造内容；拿不准是否完成的条目写成「待确认」。
                4. 面向人写：先说结论再说细节，不用内部术语堆砌。

                产出：一份可直接复制粘贴的 Markdown 草稿，末尾附「需要你补充的信息」清单。
            """.trimIndent(),
        ),
        ScheduledTaskTemplate(
            id = "doc_knowledge_refresh",
            nameRes = R.string.scheduled_tasks_template_docs_fresh,
            descriptionRes = R.string.scheduled_tasks_template_docs_fresh_desc,
            category = ScheduledTaskTemplateCategory.DOCUMENT,
            scheduleType = ScheduledTaskEntity.TYPE_INTERVAL,
            intervalMinutes = 3 * 24 * 60,
            prompt = """
                检查项目文档与代码是否脱节，列出需要更新的段落。

                要求：
                1. 对照文档目录与当前代码，重点看这几类最容易过期的内容：命令、路径、配置项、界面流程、参数名。
                2. 只报告有证据的脱节：给出文档位置（文件与行号）、代码位置（文件与行号），以及两者具体差在哪。
                3. 不要凭风格偏好挑刺：文案语气、排版喜好不算脱节。
                4. 无法确认的写成「疑点」并说明需要确认什么，不要直接改文档。

                产出：按文档分组的清单，每条写清「现在写的 / 实际是 / 建议改成」，并标出最该优先更新的段落。本轮不直接改文件。
            """.trimIndent(),
        ),
    )
}

/**
 * 把模板摊成一份「预填用的任务实体」，交给设置页现有编辑器当初始值。
 *
 * 编辑器只读它的字段做初始值，`id` / `nextRunAt` / 时间戳这些调度状态在真正保存时由
 * ScheduledTaskRepository 重新生成（保存时传的 existing 为 null），所以这里给占位值即可。
 */
fun ScheduledTaskTemplate.toSeedEntity(name: String, prompt: String): ScheduledTaskEntity =
    ScheduledTaskEntity(
        id = "",
        name = name,
        prompt = prompt,
        intervalMinutes = intervalMinutes,
        scheduleType = scheduleType,
        dailyMinuteOfDay = dailyMinuteOfDay,
        triggerType = ScheduledTaskEntity.TYPE_TIME,
        nextRunAt = 0L,
        createdAt = 0L,
        updatedAt = 0L,
    )
