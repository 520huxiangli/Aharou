package com.aharou.feature.agent.domain.prompt

/**
 * 系统提示词末尾逐个可关的动态注入区块。
 *
 * [key] 会当成关闭标记的文件名写进 `prompts.custom/.disabled-blocks/`，**改了就丢用户设置**，勿动。
 * 顺序与 [PromptTailAssembler] 的追加顺序一致，设置页按这个顺序列。
 */
enum class InjectionBlock(val key: String) {
    SOUL("soul"),
    ARCHIVE("archive"),
    SKILLS("skills"),
    SUB_AGENTS("sub-agents"),
    TEAMS("teams"),
    MEMORIES("memories"),
    PROJECT_RULES("project-rules"),
    WORKSPACE("workspace"),
    RULES("rules"),
    WORKSPACE_TYPE("workspace-type"),
    MODEL_IDENTITY("model-identity"),
    INBOX("inbox"),
    TIME("time"),
    WORKSPACE_CHANGE("workspace-change")
}
