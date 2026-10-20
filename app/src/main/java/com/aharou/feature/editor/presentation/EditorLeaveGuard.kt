package com.aharou.feature.editor.presentation

import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 待确认的离开动作：确认保存后执行。
 *
 * [confirmLabelRes] 是未保存提示里确认键的文案：关编辑页是「保存并退出」，
 * 大屏右栏换个文件接着看是「保存并切换」。
 */
internal data class PendingLeave(@StringRes val confirmLabelRes: Int, val action: () -> Unit)

/**
 * 大屏右栏「换文件前先确认」的通道。
 *
 * 右栏编辑器与聊天并排常驻，宿主想换文件时（[com.aharou.MainActivity] 的 openFile）既看不到
 * 编辑器内部的脏标记，也没法让编辑器先弹保存提示——直接改路径会把未保存的修改静默丢掉。
 * 这个对象把两边接起来：编辑器实时上报脏状态并注册离开处理，宿主换文件前调 [requestLeave]。
 *
 * 编辑器不在组合中（右栏没开、开的是终端等）时没有注册处理，[requestLeave] 一律直接放行。
 */
class EditorLeaveGuard {

    /** 编辑器内的当前文件是否有未保存修改。编辑器离开组合时复位，避免拿旧状态拦下切换。 */
    internal var isDirty by mutableStateOf(false)

    /** 编辑器注册的离开处理；为 null 表示当前没有编辑器在等这个请求。 */
    private var handler: ((onLeave: () -> Unit) -> Unit)? = null

    internal fun register(handler: ((onLeave: () -> Unit) -> Unit)?) {
        this.handler = handler
    }

    /**
     * 请求离开当前文件：[onLeave] 在确认可以离开时执行。没有脏数据（或没有编辑器挂载）时同步
     * 立即执行；有脏数据时交给编辑器先弹保存提示——用户选「保存」则存盘成功后执行，选
     * 「不保存」则立即执行，取消则永不执行。
     */
    fun requestLeave(onLeave: () -> Unit) {
        val current = handler
        if (current == null || !isDirty) onLeave() else current(onLeave)
    }
}
