package com.aharou.feature.agent.presentation

import com.aharou.feature.agent.presentation.component.PendingUploadAttachment
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 外部分享（ACTION_SEND / SEND_MULTIPLE）进入聊天输入框的中转站。
 *
 * 附件状态原本是聊天面板的 Composable 局部状态（`remember`），Activity 够不着，故用这个
 * 单例搭桥：MainActivity 收下分享的文件、拷进工作区，投递到这里；聊天面板消费后并入附件列表。
 */
@Singleton
internal class SharedIntakeHolder @Inject constructor() {

    private val _pending = MutableStateFlow<List<PendingUploadAttachment>>(emptyList())
    val pending: StateFlow<List<PendingUploadAttachment>> = _pending.asStateFlow()

    /** 已落地、等用户确认怎么处理的分享文件；确认或取消后才进 [pending]。 */
    private val _awaitingConfirm = MutableStateFlow<List<PendingUploadAttachment>>(emptyList())
    val awaitingConfirm: StateFlow<List<PendingUploadAttachment>> = _awaitingConfirm.asStateFlow()

    /** 收下已落地的分享文件等用户确认。重复分享追加而不是覆盖，否则前一个会被顶掉。 */
    fun awaitConfirm(items: List<PendingUploadAttachment>) {
        if (items.isEmpty()) return
        _awaitingConfirm.value = _awaitingConfirm.value + items
        com.aharou.core.util.FileLogger.i(TAG, "待确认 ${items.size} 项")
    }

    fun clearAwaitingConfirm() {
        _awaitingConfirm.value = emptyList()
    }

    fun submit(items: List<PendingUploadAttachment>) {
        if (items.isEmpty()) return
        _pending.value = _pending.value + items
        com.aharou.core.util.FileLogger.i(TAG, "submit ${items.size} 项，pending 现有 ${_pending.value.size} 项")
    }

    /** 取走全部待插入附件；取完即清空，避免来回切页时重复插入。 */
    fun consume(): List<PendingUploadAttachment> {
        val items = _pending.value
        if (items.isNotEmpty()) _pending.value = emptyList()
        com.aharou.core.util.FileLogger.i(TAG, "consume 取走 ${items.size} 项")
        return items
    }

    private companion object {
        const val TAG = "SharedIntake"
    }
}

/** Composable 取不到注入单例（没有 ViewModel 入口），只能走 EntryPoint。 */
@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface SharedIntakeEntryPoint {
    fun sharedIntakeHolder(): SharedIntakeHolder
}
