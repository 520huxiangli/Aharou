package com.aharou.feature.credentials.domain.model

/** 一条 Git 远程仓库凭据（每主机一条，id 即 host 小写）。无 Android 依赖。 */
data class GitCredential(
    val id: String,
    val host: String,
    val username: String,
    val token: String
)