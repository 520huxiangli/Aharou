/*
 * 移植自 AAswordman/Operit（原文件 ui/common/composedsl/ComposeDslScrollStateStore.kt，
 * LGPL-3.0）。本文件随 Aharou 以 GPL-3.0 分发。
 *
 * 改动：注释改为中文说明，逻辑逐行保留。
 */

package com.aharou.feature.plugin.dsl

/**
 * DSL 树在每次 JS 状态更新后整体重建。LazyColumn/LazyRow 的 LazyListState 只 remember 在
 * 当前组合位置，列表节点因条件分支、弹层重建离开组合时状态就会跟着丢，滚动位置回到顶部。
 *
 * store 由宿主持有、生命周期长于界面树本身：渲染器按稳定的节点身份从 store 取回同一个
 * LazyListState，普通状态更新、弹层重建与页面切换都不会再丢滚动位置。
 *
 * 节点身份优先用 DSL 显式传入的 key，没有 key 时退回节点路径。
 */

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable

internal class ComposeDslScrollStateStore(
    restoredStates: Map<String, Pair<Int, Int>> = emptyMap()
) {
    private val lazyListStates =
        restoredStates.mapValuesTo(mutableMapOf()) { (_, position) ->
            LazyListState(position.first, position.second)
        }

    fun lazyListState(key: String): LazyListState = lazyListStates.getOrPut(key) { LazyListState() }

    fun saveState(): Map<String, List<Int>> =
        lazyListStates.mapValues { (_, state) ->
            listOf(state.firstVisibleItemIndex, state.firstVisibleItemScrollOffset)
        }

    companion object {
        val Saver: Saver<ComposeDslScrollStateStore, Map<String, List<Int>>> =
            Saver(
                save = { store -> store.saveState() },
                restore = { saved ->
                    ComposeDslScrollStateStore(
                        restoredStates =
                            saved.mapValues { (_, position) ->
                                position[0] to position[1]
                            }
                    )
                }
            )
    }
}

internal val LocalComposeDslScrollStateStore =
    compositionLocalOf<ComposeDslScrollStateStore?> { null }

@Composable
internal fun rememberComposeDslScrollStateStore(routeInstanceId: String): ComposeDslScrollStateStore =
    rememberSaveable(routeInstanceId, saver = ComposeDslScrollStateStore.Saver) {
        ComposeDslScrollStateStore()
    }

/**
 * 计算列表滚动状态的稳定身份：路径 + 节点类型，显式 key 参与计算，让同一路径下的不同视图
 * 可以各自维护滚动位置。
 */
internal fun composeDslLazyListStateKey(
    parentNodePath: String,
    nodeType: String,
    nodeKey: Any?
): String {
    val normalizedType = normalizeToken(nodeType)
    val explicitKey = nodeKey?.toString()?.trim()?.ifBlank { null }
    return if (explicitKey != null) {
        "$parentNodePath:$normalizedType:key:$explicitKey"
    } else {
        "$parentNodePath:$normalizedType"
    }
}

@Composable
internal fun rememberComposeDslLazyListState(
    nodePath: String,
    nodeType: String,
    nodeKey: Any?
): LazyListState {
    val stateKey = composeDslLazyListStateKey(nodePath, nodeType, nodeKey)
    val store = LocalComposeDslScrollStateStore.current
    return if (store != null) {
        store.lazyListState(stateKey)
    } else {
        rememberSaveable(stateKey, saver = LazyListState.Saver) { LazyListState() }
    }
}
