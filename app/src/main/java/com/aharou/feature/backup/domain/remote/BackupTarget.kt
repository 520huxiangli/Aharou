package com.aharou.feature.backup.domain.remote

import java.util.UUID

/** 网络备份目标类型：[WEBDAV] 走 WebDAV 协议，[S3] 走 S3 对象存储（SigV4）。 */
enum class BackupTargetType { WEBDAV, S3 }

/**
 * 网络备份目标领域模型（与 room 的 BackupTargetEntity 解耦）。
 *
 * WebDAV 用 [endpoint]（服务地址）+ [basePath]（目录前缀）+ [username]/[password]。
 * S3 用 [endpoint]（可留空走 AWS 官方地址）+ [bucket]/[region] + [accessKey]/[secretKey]/[sessionToken]，
 * [basePath] 作为对象键前缀，[pathStyle] 决定路径式寻址（MinIO/R2 等自建服务常用）。
 */
data class BackupTarget(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val type: BackupTargetType = BackupTargetType.WEBDAV,
    val endpoint: String = "",
    val basePath: String = "",
    val username: String = "",
    val password: String = "",
    val accessKey: String = "",
    val secretKey: String = "",
    val sessionToken: String = "",
    val bucket: String = "",
    val region: String = "us-east-1",
    val pathStyle: Boolean = false,
    val createdAt: Long = 0
)
