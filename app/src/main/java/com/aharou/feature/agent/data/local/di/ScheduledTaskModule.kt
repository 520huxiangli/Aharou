package com.aharou.feature.agent.data.local.di

import com.aharou.feature.agent.data.local.dao.ScheduledTaskDao
import com.aharou.feature.agent.data.local.database.AgentDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** 定时任务表 DAO 的 Hilt 绑定。 */
@Module
@InstallIn(SingletonComponent::class)
object ScheduledTaskModule {

    @Provides
    @Singleton
    fun provideScheduledTaskDao(database: AgentDatabase): ScheduledTaskDao = database.scheduledTaskDao()
}
