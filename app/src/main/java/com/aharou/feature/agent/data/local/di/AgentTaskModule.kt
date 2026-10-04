package com.aharou.feature.agent.data.local.di

import com.aharou.feature.agent.data.local.dao.AgentTaskDao
import com.aharou.feature.agent.data.local.database.AgentDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** 子代理任务表 DAO 的 Hilt 绑定。 */
@Module
@InstallIn(SingletonComponent::class)
object AgentTaskModule {

    @Provides
    @Singleton
    fun provideAgentTaskDao(database: AgentDatabase): AgentTaskDao = database.agentTaskDao()
}
