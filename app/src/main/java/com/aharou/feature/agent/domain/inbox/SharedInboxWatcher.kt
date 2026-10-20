package com.aharou.feature.agent.domain.inbox

import android.os.FileObserver
import com.aharou.core.util.FileLogger
import com.aharou.feature.agent.domain.container.ContainerInstaller
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 常驻监听共享区信箱目录：容器内写入、手动改文件、外部脚本写入都能在落盘后立刻发出投递信号。
 *
 * 只监听 `inbox` 目录本身（FileObserver 不递归）；目录不存在时先建一个空目录，
 * 信箱文件仍然按需懒创建。App 进程被杀时监听随之结束——那种情况下也没有会话可投递。
 */
@Singleton
class SharedInboxWatcher @Inject constructor(
    private val containerInstaller: ContainerInstaller,
    private val events: SharedInboxEvents
) {

    @Volatile
    private var observer: FileObserver? = null

    @Synchronized
    fun start() {
        if (observer != null) return
        val dir = File(containerInstaller.sharedDir, INBOX_DIR).apply { mkdirs() }
        observer = object : FileObserver(dir.absolutePath, MASK) {
            override fun onEvent(event: Int, path: String?) {
                events.notifyChanged()
            }
        }.also { it.startWatching() }
        FileLogger.d(TAG, "开始监听共享区信箱: ${dir.absolutePath}")
    }

    private companion object {
        const val TAG = "SharedInboxWatcher"
        const val INBOX_DIR = "inbox"
        const val MASK = FileObserver.CREATE or FileObserver.MODIFY or
            FileObserver.CLOSE_WRITE or FileObserver.MOVED_TO
    }
}
