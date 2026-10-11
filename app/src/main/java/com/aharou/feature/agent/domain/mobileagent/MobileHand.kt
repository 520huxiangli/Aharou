package com.aharou.feature.agent.domain.mobileagent

import com.aharou.feature.agent.domain.model.AgentImage
import kotlinx.serialization.json.JsonElement

/**
 * 手机操作子代理的「一只手」：能看屏、能动手。
 *
 * 循环只依赖这个接口，不关心背后是影子屏还是无障碍——让 VdTool / A11yTool 各自实现即可，
 * 于是 [MobileOperator] 不必反向依赖工具实例，避免 DI 循环。
 */
interface MobileHand {
    /**
     * 本手是否会读无障碍节点树（同一屏空间里的可选第二输入）。影子屏没有树 → false。
     */
    val readsNodeTree: Boolean get() = false

    /**
     * 循环开始前的准备：确保这只手可用（影子屏在跑），必要时把目标 App 打开。
     * 返回错误说明文本，null 表示就绪。
     */
    suspend fun prepare(component: String?): String?

    /** 看一次屏：截图（VdTool 已带坐标网格）+ 一行结构化 caption；有树时附节点行。 */
    suspend fun observe(): HandObservation

    /** 下落一个动作。失败以 [HandActResult.ok]=false 表达，不抛异常。 */
    suspend fun act(args: Map<String, JsonElement>): HandActResult
}

/**
 * 一次观测。[image] 为空且 [error] 非空表示这一屏没看到；[nodesText] 是可选第二输入。
 */
data class HandObservation(
    val caption: String,
    val image: AgentImage?,
    val nodesText: String? = null,
    val error: String? = null,
)

/** 一次动作的结果（失败不抛异常，用 ok=false + 说明表达）。 */
data class HandActResult(val ok: Boolean, val message: String)
