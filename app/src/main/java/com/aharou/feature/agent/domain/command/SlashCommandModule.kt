package com.aharou.feature.agent.domain.command

import dagger.Module
import dagger.Binds
import dagger.multibindings.IntoSet
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * 斜杠命令 multibinding：将每个内置 [ActionCommandHandler] 实现汇集为 Set，
 * 供 [SlashCommandRegistry] 构造注入（技能由注册表另行从 SkillRepository 读取）。
 *
 * 新增内置命令时在此追加一行 `@Binds @IntoSet` 绑定即可。
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class SlashCommandModule {

    @Binds
    @IntoSet
    abstract fun bindCompressCommandHandler(handler: CompressCommandHandler): ActionCommandHandler

    @Binds
    @IntoSet
    abstract fun bindInitCommandHandler(handler: InitCommandHandler): ActionCommandHandler

    @Binds
    @IntoSet
    abstract fun bindUsageCommandHandler(handler: UsageCommandHandler): ActionCommandHandler
}
