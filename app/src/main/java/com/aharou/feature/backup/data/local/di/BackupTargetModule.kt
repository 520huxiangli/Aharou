package com.aharou.feature.backup.data.local.di

import com.aharou.feature.agent.data.local.database.AgentDatabase
import com.aharou.feature.backup.data.local.dao.BackupTargetDao
import com.aharou.feature.backup.domain.remote.KeystoreRemoteSecretCipher
import com.aharou.feature.backup.domain.remote.RemoteSecretCipher
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** 网络备份目标表 DAO 与敏感字段加解密口子的 Hilt 绑定。 */
@Module
@InstallIn(SingletonComponent::class)
object BackupTargetModule {

    @Provides
    @Singleton
    fun provideBackupTargetDao(database: AgentDatabase): BackupTargetDao = database.backupTargetDao()

    @Provides
    @Singleton
    fun provideRemoteSecretCipher(): RemoteSecretCipher = KeystoreRemoteSecretCipher
}
