package com.aharou.feature.backup.domain.remote

import java.io.File

/** 远端上的一个备份文件。[name] 是相对 [BackupTarget.basePath] 的文件名。 */
data class RemoteBackup(
    val name: String,
    val size: Long,
    val lastModified: Long
)

/**
 * 网络备份存储的统一抽象：WebDAV 与 S3 各一个实现。
 *
 * 所有方法都返回 [Result]，失败时异常为 [RemoteStorageException]（携带 [RemoteStorageErrorKind]），
 * 供上层映射成字符串资源，避免把底层英文/中文异常信息直接展示给用户。
 * 方法均为阻塞式网络调用，调用方应放到 IO 调度器上执行。
 */
interface BackupRemoteStorage {
    /** 连通性探测（WebDAV 用 PROPFIND Depth:0，S3 用 ListObjectsV2 max-keys=1）。 */
    suspend fun testConnection(): Result<Unit>

    /** 上传 [source] 为远端文件 [name]。 */
    suspend fun upload(name: String, source: File): Result<Unit>

    /** 列出远端备份文件。 */
    suspend fun list(): Result<List<RemoteBackup>>

    /** 下载远端文件 [name] 写入本地 [target]。 */
    suspend fun download(name: String, target: File): Result<Unit>

    /** 删除远端文件 [name]。 */
    suspend fun delete(name: String): Result<Unit>
}

/** 网络备份失败的分类，用于把错误映射成对应的字符串资源。 */
enum class RemoteStorageErrorKind {
    /** 配置不完整（缺地址/桶名等）。 */
    CONFIG,

    /** 认证/授权失败（401/403）。 */
    AUTH,

    /** 目标不存在（404）。 */
    NOT_FOUND,

    /** 其它 HTTP 错误状态。 */
    HTTP,

    /** 网络层失败（连接超时、DNS、断流等 IOException）。 */
    NETWORK,

    /** 未知错误。 */
    UNKNOWN
}

/** 网络备份存储抛出的统一异常，携带分类 [kind]。 */
open class RemoteStorageException(
    val kind: RemoteStorageErrorKind,
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)
