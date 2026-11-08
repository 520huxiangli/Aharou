package com.aharou.feature.im.domain.napcat

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class NapCatInstallationTask(private val scope: CoroutineScope) {
    private val gate = Mutex()
    private var active: Deferred<Unit>? = null
    private var updating = false

    suspend fun run(update: Boolean, block: suspend () -> Unit) {
        val task = gate.withLock {
            active?.takeIf { !it.isCompleted }?.let {
                check(updating == update) { "Another NapCat installation operation is running" }
                return@withLock it
            }
            updating = update
            scope.async(start = CoroutineStart.LAZY) { block() }.also {
                active = it
                it.start()
            }
        }
        // 页面和工具只等待结果，取消等待者不能取消运行时持有的安装任务。
        task.await()
    }

    suspend fun cancel() = gate.withLock {
        active?.cancelAndJoin()
        active = null
    }
}
