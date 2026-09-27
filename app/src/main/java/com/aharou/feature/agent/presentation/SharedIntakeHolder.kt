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

    fun submit(items: List<PendingUploadAttachment>) {
        if (items.isEmpty()) return
        _pending.value = _pending.value + items
    }

    /** 取走全部待插入附件；取完即清空，避免来回切页时重复插入。 */
    fun consume(): List<PendingUploadAttachment> {
        val items = _pending.value
        if (items.isNotEmpty()) _pending.value = emptyList()
        return items
    }
}

/** Composable 取不到注入单例（没有 ViewModel 入口），只能走 EntryPoint。 */
@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface SharedIntakeEntryPoint {
    fun sharedIntakeHolder(): SharedIntakeHolder
}
