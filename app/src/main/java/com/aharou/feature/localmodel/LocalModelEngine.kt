package com.aharou.feature.localmodel

import com.aharou.core.util.FileLogger
import com.aharou.feature.localmodel.data.LocalModelCatalog
import com.aharou.feature.localmodel.data.LocalModelStore
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 本地推理的入口：管模型加载、串行生成、释放。
 *
 * 同一时刻只留一个模型在内存里——手机内存经不起两个 0.6B 权重，
 * 换模型要先把上一个释放掉。
 */
@Singleton
internal class LocalModelEngine @Inject constructor(
    private val store: LocalModelStore,
    private val catalog: LocalModelCatalog,
) {

    private val lock = Mutex()
    private var loadedId: String? = null
    private var handle: Long = 0L

    init {
        instance = this
    }

    /** 原生库在不在。缺库时界面提示「本地模型暂不可用」，不要当成加载失败。 */
    val available: Boolean get() = LlamaJni.available

    /** 当前加载的模型 id；没加载为空。 */
    fun loaded(): String? = loadedId

    suspend fun load(id: String): Result<Unit> = withContext(Dispatchers.IO) {
        lock.withLock {
            if (loadedId == id && handle != 0L) return@withLock Result.success(Unit)
            val entry = catalog.find(id) ?: return@withLock Result.failure(IllegalStateException("清单里没有这个模型"))
            if (!store.isInstalled(id)) return@withLock Result.failure(IllegalStateException("模型还没安装"))
            releaseLocked()
            val path = store.primaryFile(entry)
            if (!path.isFile) return@withLock Result.failure(IllegalStateException("模型文件不见了，重新安装一次"))
            val newHandle = LlamaJni.loadModel(path.absolutePath, defaultThreads())
            if (newHandle == 0L) {
                val reason = runCatching { LlamaJni.lastError() }.getOrDefault("加载失败")
                return@withLock Result.failure(IllegalStateException(reason))
            }
            handle = newHandle
            loadedId = id
            FileLogger.i(TAG, "本地模型已加载：$id")
            Result.success(Unit)
        }
    }

    /**
     * 一问一答，返回完整文本（不是流式）。模型没加载返回 null。
     */
    suspend fun chat(
        system: String,
        user: String,
        maxTokens: Int = 256,
        temperature: Float = 0.7f,
        topK: Int = 40,
        topP: Float = 0.95f,
    ): String? = withContext(Dispatchers.Default) {
        lock.withLock {
            if (handle == 0L) return@withLock null
            runCatching {
                LlamaJni.chat(handle, system, user, maxTokens, temperature, topK, topP)
            }.onFailure { FileLogger.w(TAG, "本地推理失败", it) }.getOrNull()
        }
    }

    fun release() {
        // 释放要走同一把锁：正在生成时把句柄置零会让 native 侧悬空
        if (handle == 0L) return
        releaseLocked()
    }

    private fun releaseLocked() {
        if (handle != 0L) {
            runCatching { LlamaJni.freeModel(handle) }
            handle = 0L
            loadedId = null
        }
    }

    /** 留两个核给界面：全占满时滑动会卡。 */
    private fun defaultThreads(): Int =
        Runtime.getRuntime().availableProcessors().let { if (it > 2) it - 1 else it }.coerceIn(1, 6)

    private companion object {
        @Volatile
        var instance: LocalModelEngine? = null

        /** 给 [LocalProvider] 用：provider 由工作流在任意处构造，拿不到构造注入。 */
        val current: LocalModelEngine? get() = instance

        const val TAG = "LocalModelEngine"
    }
}
