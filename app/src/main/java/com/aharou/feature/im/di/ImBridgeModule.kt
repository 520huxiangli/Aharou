package com.aharou.feature.im.di

import android.content.Context
import com.aharou.feature.agent.domain.runner.AgentTurnRunner
import com.aharou.feature.agent.domain.session.SessionUseCase
import com.aharou.feature.agent.domain.tool.ToolPermissionManager
import com.aharou.feature.im.data.repository.FeishuSettingsRepository
import com.aharou.feature.im.data.repository.NapCatSettingsRepository
import com.aharou.feature.im.domain.NapCatChannelClient
import com.aharou.feature.im.domain.NapCatMessagePolicy
import com.aharou.feature.im.domain.ImOwnerStore
import com.aharou.feature.im.data.repository.QqSettingsRepository
import com.aharou.feature.im.data.repository.TelegramSettingsRepository
import com.aharou.feature.im.data.repository.WeixinSettingsRepository
import com.aharou.feature.im.domain.FeishuChannelClient
import com.aharou.feature.im.domain.ImBotBridge
import com.aharou.feature.im.domain.ImWorkspaceResolver
import com.aharou.feature.im.domain.QqChannelClient
import com.aharou.feature.im.domain.TelegramBotClient
import com.aharou.feature.im.domain.WeixinIlinkClient
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Named
import javax.inject.Singleton

/**
 * 给每个渠道各提供一个 [ImBotBridge]。
 *
 * 桥接要在构造时绑定「哪个渠道 + 哪份配置来源」，不是无参单例，
 * 所以用 `@Named` 显式提供而不是让 Hilt 自动构造——否则两个渠道会争同一个 `ImChannel` 绑定。
 */
@Module
@InstallIn(SingletonComponent::class)
object ImBridgeModule {

    @Provides
    @Singleton
    @Named("telegram")
    fun provideTelegramBridge(
        channel: TelegramBotClient,
        settings: TelegramSettingsRepository,
        runner: AgentTurnRunner,
        sessionUseCase: SessionUseCase,
        permissions: ToolPermissionManager,
        workspaceResolver: ImWorkspaceResolver,
        @ApplicationContext context: Context
    ): ImBotBridge =
        ImBotBridge("telegram", channel, settings, runner, sessionUseCase, permissions, context,
            fallbackWorkspacePath = { workspaceResolver.fallbackPath() })

    @Provides
    @Singleton
    @Named("weixin")
    fun provideWeixinBridge(
        channel: WeixinIlinkClient,
        settings: WeixinSettingsRepository,
        runner: AgentTurnRunner,
        sessionUseCase: SessionUseCase,
        permissions: ToolPermissionManager,
        workspaceResolver: ImWorkspaceResolver,
        @ApplicationContext context: Context
    ): ImBotBridge =
        ImBotBridge("weixin", channel, settings, runner, sessionUseCase, permissions, context,
            fallbackWorkspacePath = { workspaceResolver.fallbackPath() })

    @Provides
    @Singleton
    @Named("feishu")
    fun provideFeishuBridge(
        channel: FeishuChannelClient,
        settings: FeishuSettingsRepository,
        runner: AgentTurnRunner,
        sessionUseCase: SessionUseCase,
        permissions: ToolPermissionManager,
        workspaceResolver: ImWorkspaceResolver,
        @ApplicationContext context: Context
    ): ImBotBridge =
        ImBotBridge("feishu", channel, settings, runner, sessionUseCase, permissions, context,
            fallbackWorkspacePath = { workspaceResolver.fallbackPath() })

    @Provides
    @Singleton
    @Named("qq_napcat")
    fun provideNapCatBridge(
        channel: NapCatChannelClient,
        settings: NapCatSettingsRepository,
        runner: AgentTurnRunner,
        sessionUseCase: SessionUseCase,
        permissions: ToolPermissionManager,
        workspaceResolver: ImWorkspaceResolver,
        owners: ImOwnerStore,
        @ApplicationContext context: Context
    ): ImBotBridge = ImBotBridge(
        "qq_napcat", channel, settings, runner, sessionUseCase, permissions, context,
        owners = owners, policy = NapCatMessagePolicy(),
        fallbackWorkspacePath = { workspaceResolver.fallbackPath() }
    )

    @Provides
    @Singleton
    @Named("qq")
    fun provideQqBridge(
        channel: QqChannelClient,
        settings: QqSettingsRepository,
        runner: AgentTurnRunner,
        sessionUseCase: SessionUseCase,
        permissions: ToolPermissionManager,
        workspaceResolver: ImWorkspaceResolver,
        @ApplicationContext context: Context
    ): ImBotBridge =
        ImBotBridge("qq", channel, settings, runner, sessionUseCase, permissions, context,
            fallbackWorkspacePath = { workspaceResolver.fallbackPath() })
}
