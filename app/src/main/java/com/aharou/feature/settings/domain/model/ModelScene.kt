package com.aharou.feature.settings.domain.model

/**
 * 模型场景：一种「用途」。每个场景可以绑定自己的 provider + model。
 *
 * 绑定存储不新建：直接复用各角色已有的 DataStore 仓库（见 feature/settings/data/repository
 * 下的 *ModelSettingsRepository），config 通道路径见 [configPath]。本枚举只做
 * 「场景 → 既有存储」的映射，因此不会与「设置 → 默认模型」里的各行形成两套真相。
 *
 * 未绑定、或绑定已失效（供应商被删/停用/没 Key）时不抛错，交给调用方按主对话链路兜底
 * （会话绑定 → 新会话默认模型 → 全局激活供应商），保证某个场景配置坏掉不会拖垮主流程。
 */
enum class ModelScene(
    val id: String,
    /** config 通道路径；null 表示本场景没有独立绑定，复用 [reusesFrom]。 */
    val configPath: String?,
    /** 没有独立绑定时复用的场景（记忆整理复用压缩模型，这是既有行为）。 */
    val reusesFrom: ModelScene? = null,
    /** 绑定失效时是否允许回退到主对话链路；生图不允许（聊天模型没有图像输出能力，回退只会更难排查）。 */
    val fallsBackToMainChat: Boolean = true,
) {
    /** 主对话：绑定的是「新会话默认模型」；会话内切换的模型优先于此，由调用方决定。 */
    MAIN_CHAT("main_chat", "model.roles.default"),

    /** 会话标题自动生成。 */
    TITLE("title", "model.roles.title"),

    /** 上下文压缩（会话历史摘要）。 */
    COMPACTION("compaction", "model.roles.compaction"),

    /** 记忆整理 / 摘要抽取：沿用压缩模型的绑定，不单独提供开关。 */
    MEMORY("memory", null, reusesFrom = COMPACTION),

    /** 视觉理解（识图、截图理解）。 */
    VISION("vision", "model.roles.vision"),

    /** 图片生成：必须显式绑定支持图像输出的模型。 */
    IMAGE_GEN("image_gen", "model.roles.image_gen", fallsBackToMainChat = false),
}

/** 一个场景的显式绑定；两串都非空才算「用户绑过」。 */
data class SceneBinding(val providerId: String, val model: String) {
    val isSet: Boolean get() = providerId.isNotBlank() && model.isNotBlank()
}

/** 绑定不可用的原因，用于告诉用户「你绑的那个已经用不了，实际走的是回退」。 */
enum class SceneBindingIssue {
    /** 从未绑定。 */
    NOT_CONFIGURED,

    /** 绑定的供应商已被删除。 */
    PROVIDER_MISSING,

    /** 绑定的供应商被停用。 */
    PROVIDER_DISABLED,

    /** 绑定的供应商没有可用 Key。 */
    NO_USABLE_API_KEY,

    /** 读取配置本身失败（异常/存储损坏）。 */
    RESOLVE_FAILED,
}

/** 场景解析结果。解析过程不抛异常：取不到就回退，回退不了才报 [Unavailable]。 */
sealed interface SceneResolution {
    val scene: ModelScene

    /** 场景有可用绑定；[inheritedFrom] 非空表示复用了别的场景的绑定（记忆整理复用压缩）。 */
    data class Bound(
        override val scene: ModelScene,
        val providerId: String,
        val providerName: String,
        val model: String,
        val inheritedFrom: ModelScene? = null,
    ) : SceneResolution

    /** 没有自己的可用绑定，由调用方按主对话链路兜底（会话绑定 → 默认模型 → 全局激活供应商）。 */
    data class Inherited(
        override val scene: ModelScene,
        val issue: SceneBindingIssue? = null,
    ) : SceneResolution

    /** 既没有可用绑定也没有回退目标，调用方必须报错（目前只有生图）。 */
    data class Unavailable(
        override val scene: ModelScene,
        val issue: SceneBindingIssue,
    ) : SceneResolution
}
