package com.aharou.feature.credentials.data.repository

/**
 * 凭据文件整份读不到时，写操作（save/delete）以本异常拒绝执行。
 *
 * 写操作是「内存列表过滤后整体重写」：读失败时内存里只剩空表，继续写就会把文件里其余主机的凭据
 * 静默抹掉。只读期间由调用方提示用户修复文件或恢复 [backupName] 副本。
 *
 * @param reason 读失败原因（IO 异常 / 整份无法解析），只用于日志与提示文案的补充说明。
 * @param backupName 同目录下另存的副本文件名；备份也失败时为空串。
 */
class CredentialStorageReadOnlyException(
    val reason: String,
    val backupName: String
) : IllegalStateException("凭据文件读取失败，只读保护中：$reason")
