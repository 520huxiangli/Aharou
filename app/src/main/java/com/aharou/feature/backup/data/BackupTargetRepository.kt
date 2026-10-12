package com.aharou.feature.backup.data

import com.aharou.feature.backup.data.local.dao.BackupTargetDao
import com.aharou.feature.backup.data.local.entity.BackupTargetEntity
import com.aharou.feature.backup.data.remote.S3BackupStorage
import com.aharou.feature.backup.data.remote.WebDavBackupStorage
import com.aharou.feature.backup.domain.remote.BackupRemoteStorage
import com.aharou.feature.backup.domain.remote.BackupTarget
import com.aharou.feature.backup.domain.remote.BackupTargetType
import com.aharou.feature.backup.domain.remote.RemoteSecretCipher
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

/**
 * 网络备份目标仓储：读写 [BackupTargetEntity] 并把敏感字段加解密集中在这里。
 *
 * 加密/解密全部收敛在 [toEntity] / [toModel]：写库前加密 [BackupTarget.password]/[secretKey]/[sessionToken]，
 * 读出后解密。密文口子走 [RemoteSecretCipher]（生产为 Android Keystore）。复用全局单例 OkHttpClient。
 */
@Singleton
class BackupTargetRepository @Inject constructor(
    private val dao: BackupTargetDao,
    private val okHttpClient: OkHttpClient,
    private val cipher: RemoteSecretCipher
) {
    val targets: Flow<List<BackupTarget>> = dao.getAll()
        .map { list -> list.map(::toModel) }
        .flowOn(Dispatchers.IO)

    suspend fun getTarget(id: String): BackupTarget? = withContext(Dispatchers.IO) {
        dao.getById(id)?.let(::toModel)
    }

    suspend fun save(target: BackupTarget) = withContext(Dispatchers.IO) {
        dao.upsert(toEntity(target))
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        dao.deleteById(id)
    }

    /** 按目标类型构造对应的远端存储实现。 */
    fun storageFor(target: BackupTarget): BackupRemoteStorage = when (target.type) {
        BackupTargetType.WEBDAV -> WebDavBackupStorage(target, okHttpClient)
        BackupTargetType.S3 -> S3BackupStorage(target, okHttpClient)
    }

    private fun toEntity(target: BackupTarget) = BackupTargetEntity(
        id = target.id,
        name = target.name,
        type = target.type.name,
        endpoint = target.endpoint,
        basePath = target.basePath,
        username = target.username,
        password = cipher.encrypt(target.password),
        accessKey = target.accessKey,
        secretKey = cipher.encrypt(target.secretKey),
        sessionToken = cipher.encrypt(target.sessionToken),
        bucket = target.bucket,
        region = target.region.ifBlank { DEFAULT_REGION },
        pathStyle = target.pathStyle,
        createdAt = if (target.createdAt == 0L) System.currentTimeMillis() else target.createdAt
    )

    private fun toModel(entity: BackupTargetEntity) = BackupTarget(
        id = entity.id,
        name = entity.name,
        type = runCatching { BackupTargetType.valueOf(entity.type) }.getOrDefault(BackupTargetType.WEBDAV),
        endpoint = entity.endpoint,
        basePath = entity.basePath,
        username = entity.username,
        password = cipher.decrypt(entity.password),
        accessKey = entity.accessKey,
        secretKey = cipher.decrypt(entity.secretKey),
        sessionToken = cipher.decrypt(entity.sessionToken),
        bucket = entity.bucket,
        region = entity.region,
        pathStyle = entity.pathStyle,
        createdAt = entity.createdAt
    )

    companion object {
        private const val DEFAULT_REGION = "us-east-1"
    }
}
