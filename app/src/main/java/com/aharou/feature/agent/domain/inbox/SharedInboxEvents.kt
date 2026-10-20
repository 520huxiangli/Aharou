package com.aharou.feature.agent.domain.inbox

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 信箱可能收到新内容的信号源：写入工具（writeFile / editFile）与目录监听都往这里发一声，
 * 由 [SharedInboxDelivery] 扫一遍未读再决定投不投。
 *
 * 只关心「该去看看了」，不带具体路径与消息正文——投递方自己读文件，避免两处解析各写一份。
 */
@Singleton
class SharedInboxEvents @Inject constructor() {

    private val _changed = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    val changed: SharedFlow<Unit> = _changed.asSharedFlow()

    fun notifyChanged() {
        _changed.tryEmit(Unit)
    }
}
