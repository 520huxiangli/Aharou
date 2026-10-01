package com.aharou.di

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.aharou.feature.agent.data.local.dao.AgentMessageDao
import com.aharou.feature.agent.data.local.dao.ChatSessionDao
import com.aharou.feature.agent.data.local.dao.CheckpointDao
import com.aharou.feature.agent.data.local.dao.LlmCallRecordDao
import com.aharou.feature.agent.data.local.dao.TodoItemDao
import com.aharou.feature.settings.data.local.dao.AIProviderDao
import com.aharou.feature.settings.domain.repository.AIProviderRepository
import com.aharou.feature.agent.data.local.database.AgentDatabase
import dagger.hilt.android.qualifiers.ApplicationContext
import com.aharou.feature.agent.data.remote.anthropic.AnthropicApi
import com.aharou.feature.agent.data.remote.gemini.GeminiApi
import com.aharou.feature.agent.data.remote.openai.OpenAIApi
import com.aharou.feature.agent.domain.container.CommandEngine
import com.aharou.feature.agent.domain.container.DelegatingCommandEngine
import com.aharou.feature.agent.domain.container.LinuxContainerEngine
import com.aharou.feature.agent.domain.container.RemoteSshConnection
import com.aharou.feature.agent.domain.container.RemoteSshEngine
import com.aharou.feature.agent.domain.container.SharedPrefsSshHostKeyStore
import com.aharou.feature.agent.domain.container.SharedPrefsSshLoginKeyStore
import com.aharou.feature.agent.domain.container.SshHostKeyStore
import com.aharou.feature.agent.domain.container.SshLoginKeyStore
import com.aharou.feature.settings.data.repository.ExecutionMode
import com.aharou.feature.settings.data.repository.ExecutionModeHolder
import com.aharou.feature.agent.domain.tool.file.ReadFileTool
import com.aharou.feature.agent.domain.tool.file.SendFileTool
import com.aharou.feature.agent.domain.tool.file.GenerateImageTool
import com.aharou.feature.agent.domain.tool.file.ViewImageTool
import com.aharou.feature.agent.domain.tool.file.WriteFileTool
import com.aharou.feature.agent.domain.tool.editor.EditFileTool
import com.aharou.feature.agent.domain.tool.container.ExecuteCommandTool
import com.aharou.feature.agent.domain.tool.container.TerminalSessionTool
import com.aharou.feature.agent.domain.tool.explorer.ListFilesTool
import com.aharou.feature.agent.domain.tool.explorer.SearchCodeTool
import com.aharou.feature.agent.domain.tool.shizuku.ShizukuTool
import com.aharou.feature.agent.domain.tool.skill.LoadSkillTool
import com.aharou.feature.agent.domain.tool.question.AskUserQuestionTool
import com.aharou.feature.agent.domain.tool.todo.TodoTool
import com.aharou.feature.agent.domain.tool.subagent.TaskTool
import com.aharou.feature.agent.domain.tool.subagent.MessageParentTool
import com.aharou.feature.agent.domain.subagent.SubAgentEventBus
import com.aharou.feature.agent.domain.prompt.SystemPromptProvider
import com.aharou.feature.agent.domain.workflow.AgentWorkflow
import com.aharou.feature.agent.domain.tool.ToolPermissionManager
import com.aharou.feature.agent.domain.permission.ToolPermissionPolicyEngine
import com.aharou.feature.agent.domain.tool.ToolRegistry
import com.aharou.feature.agent.domain.tool.ToolSearchTool
import com.aharou.feature.agent.domain.tool.ToolOutputStore
import com.aharou.feature.settings.data.remote.ModelMetadataService
import com.aharou.feature.terminal.domain.DelegatingTerminalSessionProvider
import com.aharou.feature.terminal.domain.RemoteTerminalSessionManager
import com.aharou.feature.terminal.domain.TerminalSessionManager
import com.aharou.feature.terminal.domain.TerminalSessionProvider
import com.aharou.feature.workspace.domain.FileAccessProvider
import com.aharou.feature.workspace.domain.DelegatingFileAccess
import com.aharou.feature.workspace.domain.LocalFileAccess
import com.aharou.feature.workspace.domain.RemoteSftpFileAccess
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Named
import javax.inject.Singleton

import com.aharou.core.db.MigrationLoader
import com.aharou.feature.agent.domain.checkpoint.CheckpointManager
import com.aharou.feature.agent.domain.notification.AgentEventInjector
import com.aharou.feature.agent.domain.notification.AgentNotificationCenter
import com.aharou.feature.agent.domain.notification.DefaultAgentEventInjector
import com.aharou.feature.agent.domain.session.MessagePersistenceUseCase
import com.aharou.feature.agent.domain.session.SessionUseCase
import com.aharou.feature.agent.domain.tool.config.ConfigTool
import com.aharou.feature.agent.domain.tool.a11y.A11yTool
import com.aharou.feature.agent.domain.tool.vdisplay.VdTool
import com.aharou.feature.agent.domain.tool.mcp.ManageMcpTool
import com.aharou.feature.agent.domain.tool.memory.MemoryTool
import com.aharou.feature.agent.domain.tool.mode.PlanApprovalManager
import com.aharou.feature.agent.domain.tool.mode.PlanModeTool
import com.aharou.feature.agent.domain.tool.search.WebFetchTool
import com.aharou.feature.agent.domain.tool.search.WebSearchTool
import com.aharou.feature.browser.BrowserTool
import com.aharou.feature.agent.domain.workflow.ContextCompactor
import com.aharou.feature.agent.domain.ocr.TesseractOcrEngine
import com.aharou.feature.agent.domain.workflow.StatefulAgentWorkflow
import com.aharou.feature.settings.data.repository.CompactionModelSettingsRepository
import com.aharou.feature.settings.data.repository.DefaultModelSettingsRepository
import com.aharou.feature.settings.data.repository.GeneralSettingsRepository
import com.aharou.feature.settings.data.repository.ProviderKeyRotator
import com.aharou.feature.settings.data.repository.TitleModelSettingsRepository
import com.aharou.feature.workspace.data.local.dao.RemoteConnectionDao
import com.aharou.feature.workspace.data.repository.WorkspaceRepository

@Module
@InstallIn(SingletonComponent::class)
object AgentModule {

    @Provides
    @Singleton
    fun provideAgentDatabase(@ApplicationContext context: Context): AgentDatabase {
        return Room.databaseBuilder(
            context,
            AgentDatabase::class.java,
            AgentDatabase.DATABASE_NAME
        ).addMigrations(*MigrationLoader.loadMigrations(context))
            .build()
    }

    @Provides
    @Singleton
    fun provideCheckpointDao(database: AgentDatabase): CheckpointDao {
        return database.checkpointDao()
    }

    @Provides
    @Singleton
    fun provideAgentMessageDao(database: AgentDatabase): AgentMessageDao {
        return database.agentMessageDao()
    }

    @Provides
    @Singleton
    fun provideChatSessionDao(database: AgentDatabase): ChatSessionDao {
        return database.chatSessionDao()
    }

    @Provides
    @Singleton
    fun provideAIProviderDao(database: AgentDatabase): AIProviderDao {
        return database.aiProviderDao()
    }

    @Provides
    @Singleton
    fun provideRemoteConnectionDao(database: AgentDatabase): RemoteConnectionDao {
        return database.remoteConnectionDao()
    }

    @Provides
    @Singleton
    fun provideLlmCallRecordDao(database: AgentDatabase): LlmCallRecordDao {
        return database.llmCallRecordDao()
    }

    @Provides
    @Singleton
    fun provideTodoItemDao(database: AgentDatabase): TodoItemDao {
        return database.todoItemDao()
    }

    @Provides
    @Singleton
    fun provideOkHttpClient(@ApplicationContext context: Context): OkHttpClient {
        // 统一 UA 为 aicode/<版本> (Android)；请求已带显式 UA（如用户自定义头）时不覆盖。
        // 版本号走 PackageManager（项目未开启 BuildConfig），dev 构建为 1.x.y-dev.N+hash 天然可溯源。
        val userAgent = runCatching {
            val name = context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
            "aharou/$name (Android)"
        }.getOrDefault("aharou (Android)")
        // 流式 SSE 下读超时是「相邻数据块之间」的等待上限，设为 0（无限制），
        // 慢生成不会因块间隔超时被掐断；首字节前的卡死由上层 watchdog（RetryPolicy）兜底。
        return OkHttpClient.Builder()
            .connectTimeout(120, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val request = chain.request()
                val finalRequest = if (request.header("User-Agent") != null) {
                    request
                } else {
                    request.newBuilder().header("User-Agent", userAgent).build()
                }
                chain.proceed(finalRequest)
            }
            .build()
    }

    @Provides
    @Singleton
    @Named("OpenAI")
    fun provideOpenAIRetrofit(client: OkHttpClient): Retrofit {
        return Retrofit.Builder()
            .baseUrl("https://api.openai.com/")
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }

    @Provides
    @Singleton
    @Named("Anthropic")
    fun provideAnthropicRetrofit(client: OkHttpClient): Retrofit {
        return Retrofit.Builder()
            .baseUrl("https://api.anthropic.com/")
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }

    @Provides
    @Singleton
    fun provideOpenAIApi(@Named("OpenAI") retrofit: Retrofit): OpenAIApi {
        return retrofit.create(OpenAIApi::class.java)
    }

    @Provides
    @Singleton
    fun provideAnthropicApi(@Named("Anthropic") retrofit: Retrofit): AnthropicApi {
        return retrofit.create(AnthropicApi::class.java)
    }

    @Provides
    @Singleton
    @Named("Gemini")
    fun provideGeminiRetrofit(client: OkHttpClient): Retrofit {
        return Retrofit.Builder()
            .baseUrl("https://generativelanguage.googleapis.com/")
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }

    @Provides
    @Singleton
    fun provideGeminiApi(@Named("Gemini") retrofit: Retrofit): GeminiApi {
        return retrofit.create(GeminiApi::class.java)
    }

    @Provides
    @Singleton
    fun provideCommandEngine(delegate: DelegatingCommandEngine): CommandEngine = delegate

    @Provides
    @Singleton
    fun provideFileAccess(delegate: DelegatingFileAccess): FileAccessProvider = delegate

    @Provides
    @Singleton
    fun provideTerminalSessionProvider(delegate: DelegatingTerminalSessionProvider): TerminalSessionProvider = delegate

    @Provides
    @Singleton
    fun provideDelegatingTerminalSessionProvider(
        modeHolder: ExecutionModeHolder,
        local: TerminalSessionManager,
        remote: RemoteTerminalSessionManager
    ): DelegatingTerminalSessionProvider = DelegatingTerminalSessionProvider(modeHolder, local, remote)

    @Provides
    @Singleton
    fun provideRemoteSftpFileAccess(
        connection: RemoteSshConnection,
        workspaceRepository: WorkspaceRepository
    ): RemoteSftpFileAccess = RemoteSftpFileAccess(connection, workspaceRepository)

    @Provides
    @Singleton
    fun provideSshHostKeyStore(impl: SharedPrefsSshHostKeyStore): SshHostKeyStore = impl

    @Provides
    @Singleton
    fun provideSshLoginKeyStore(impl: SharedPrefsSshLoginKeyStore): SshLoginKeyStore = impl

    @Provides
    @Singleton
    fun provideToolRegistry(
        readFileTool: ReadFileTool,
        sendFileTool: SendFileTool,
        viewImageTool: ViewImageTool,
        writeFileTool: WriteFileTool,
        editFileTool: EditFileTool,
        generateImageTool: GenerateImageTool,
        executeCommandTool: ExecuteCommandTool,
        shizukuTool: ShizukuTool,
        terminalSessionTool: TerminalSessionTool,
        listFilesTool: ListFilesTool,
        searchCodeTool: SearchCodeTool,
        loadSkillTool: LoadSkillTool,
        askUserQuestionTool: AskUserQuestionTool,
        manageMcpTool: ManageMcpTool,
        toolSearchTool: ToolSearchTool,
        webSearchTool: WebSearchTool,
        webFetchTool: WebFetchTool,
        planModeTool: PlanModeTool,
        todoTool: TodoTool,
        memoryTool: MemoryTool,
        taskTool: TaskTool,
        messageParentTool: MessageParentTool,
        browserTool: BrowserTool,
        configTool: ConfigTool,
        vdTool: VdTool,
        a11yTool: A11yTool
    ): ToolRegistry {
        return ToolRegistry().apply {
            register("readFile", readFileTool)
            register("sendFile", sendFileTool)
            register("viewImage", viewImageTool)
            register("writeFile", writeFileTool)
            register("editFile", editFileTool)
            register("generateImage", generateImageTool)
            register("Bash", executeCommandTool)
            register("Shizuku", shizukuTool)
            register("terminal", terminalSessionTool)
            register("list", listFilesTool)
            register("search", searchCodeTool)
            register("loadSkill", loadSkillTool)
            register("askUserQuestion", askUserQuestionTool)
            register("manageMcp", manageMcpTool)
            register("tool_search", toolSearchTool)
            register("websearch", webSearchTool)
            register("webfetch", webFetchTool)
            register("planMode", planModeTool)
            register("todo", todoTool)
            register("memory", memoryTool)
            register("task", taskTool)
            register("messageParent", messageParentTool)
            register("browser", browserTool)
            register("config", configTool)
            register("vscreen", vdTool)
            register("a11y", a11yTool)
        }
    }

    @Provides
    @Singleton
    fun provideAgentEventInjector(): AgentEventInjector = DefaultAgentEventInjector()

    @Provides
    @Singleton
    fun provideAgentWorkflow(
        toolRegistry: ToolRegistry,
        aiProviderRepository: AIProviderRepository,
        openAIApi: OpenAIApi,
        anthropicApi: AnthropicApi,
        geminiApi: GeminiApi,
        promptProvider: SystemPromptProvider,
        permissionManager: ToolPermissionManager,
        policyEngine: ToolPermissionPolicyEngine,
        contextCompactor: ContextCompactor,
        planApprovalManager: PlanApprovalManager,
        toolOutputStore: ToolOutputStore,
        modelMetadataService: ModelMetadataService,
        compactionModelSettingsRepository: CompactionModelSettingsRepository,
        titleModelSettingsRepository: TitleModelSettingsRepository,
        defaultModelSettingsRepository: DefaultModelSettingsRepository,
        generalSettingsRepository: GeneralSettingsRepository,
        sessionUseCase: SessionUseCase,
        messagePersistenceUseCase: MessagePersistenceUseCase,
        checkpointManager: CheckpointManager,
        llmCallRecordDao: LlmCallRecordDao,
        keyRotator: ProviderKeyRotator,
        agentNotificationCenter: AgentNotificationCenter,
        eventInjector: AgentEventInjector,
        fileAccess: FileAccessProvider,
        ocrEngine: TesseractOcrEngine
    ): AgentWorkflow {
        return StatefulAgentWorkflow(
            toolRegistry,
            aiProviderRepository,
            openAIApi,
            anthropicApi,
            geminiApi,
            promptProvider,
            permissionManager,
            policyEngine,
            contextCompactor,
            planApprovalManager,
            toolOutputStore,
            modelMetadataService,
            compactionModelSettingsRepository,
            titleModelSettingsRepository,
            defaultModelSettingsRepository,
            generalSettingsRepository,
            sessionUseCase,
            messagePersistenceUseCase,
            checkpointManager,
            llmCallRecordDao,
            keyRotator,
            agentNotificationCenter,
            eventInjector,
            fileAccess,
            ocrEngine
        )
    }
}
