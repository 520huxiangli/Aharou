package com.aharou.feature.backup.domain

import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * 备份编排器：从各数据源采集快照并打包，或反向解包还原。
 *
 * 导出：按 [BackupOptions] 采集 → 流式序列化（metadata.json + 各 *.jsonl）→ tar.gz 压缩 → 口令非空则分块 AES-GCM 加密。
 * 导入：口令非空则先流式解密 → 解 tar.gz → 校验 schemaVersion → 分批合并写入各数据源。
 * 全程流式，内存峰值与数据总量解耦（只持有当前分页批次）。
 */
interface BackupManager {
    /**
     * 流式生成备份写入 [output]（调用方负责打开与关闭输出流）。
     * password 为 null 或空时不加密，输出明文 tar.gz。
     */
    suspend fun export(password: CharArray?, options: BackupOptions, output: OutputStream)

    /**
     * 导出单个会话（无密码 tar.gz）：只含该会话 + 关联消息 + todo，可由 [import] 直接还原。
     * @param output 调用方负责打开与关闭输出流
     */
    suspend fun exportSession(sessionId: String, output: OutputStream)

    /**
     * 流式解包并还原备份文件。
     * @param input 调用方负责打开与关闭输入流
     * @param password 备份未加密时传 null 或空；加密文件必须提供正确口令。
     * @param selectedWorkspaces 非 null 时只恢复这些工作区的文件（导入前由 [previewImport] 勾选）；
     *   null 表示全量恢复（备份无工作区数据或旧调用）。
     * @param sourceAlreadyDecrypted 内容是否已由 [prepareImport] 解密过（调用方以明文回放加密备份）：
     *   失败文案用它区分「内容损坏」与「未提供口令」。
     * @return 还原统计（各数据段条目数）；口令错误/格式不符/版本过高时返回失败。
     */
    suspend fun import(
        input: InputStream,
        password: CharArray?,
        selectedWorkspaces: Set<String>? = null,
        providerConflict: ProviderConflictStrategy = ProviderConflictStrategy.OVERWRITE,
        sourceAlreadyDecrypted: Boolean = false
    ): Result<RestoreStats>

    /**
     * 导入前预览：校验口令/格式/版本，并读出备份中包含的工作区列表（名称 + 文件数）。
     * 有工作区数据时 UI 应弹出勾选确认（直接覆盖警告）；无数据时导入无需弹窗。
     * @param input 调用方负责打开与关闭输入流
     * @param sourceAlreadyDecrypted 内容是否已解密（同 [import]）。
     */
    suspend fun previewImport(
        input: InputStream,
        password: CharArray?,
        sourceAlreadyDecrypted: Boolean = false
    ): Result<ImportPreview>

    /**
     * 把备份解密（明文则直拷）到缓存目录的临时文件并返回，供 [previewImport] 与 [import] 复用同一份已校验数据，
     * 避免两次重复解密。调用方负责在预览与恢复全部结束后删除该临时文件（成功、取消、失败路径都要删）。
     * @param input 调用方负责打开与关闭输入流
     */
    suspend fun prepareImport(input: InputStream, password: CharArray?): File

    /**
     * 清理导入暂存残留：删除 cacheDir 下 [java.io.File.createTempFile] 生成的 `backup*.tmp`。
     * 正常路径（成功 / 失败 / 取消 / onCleared）都会删，唯进程被杀时来不及——明文产物（含 API Key、
     * SSH 口令、聊天全文）会一直留在私有缓存，故在应用 / 功能初始化时兜底清一次。
     */
    suspend fun cleanupStaleStagingFiles()
}

/** 导入预览结果：备份中的工作区列表 + 与本机撞 id 的供应商冲突项（供勾选与取舍）。 */
data class ImportPreview(
    val workspaces: List<WorkspaceBackupMeta>,
    val providerConflicts: List<ProviderConflict> = emptyList()
) {
    val hasWorkspaceData: Boolean get() = workspaces.isNotEmpty()
    val hasProviderConflicts: Boolean get() = providerConflicts.isNotEmpty()
}

/** 备份里的供应商与本机现有条目撞了同一个 id（导入时会整行覆盖）。 */
data class ProviderConflict(
    val id: String,
    val name: String
)

/** 导入时对撞 id 供应商的处置方式。 */
enum class ProviderConflictStrategy {
    /** 保留本机现有条目，跳过备份里的冲突项。 */
    KEEP_LOCAL,

    /** 用备份里的条目整行覆盖（导入的默认行为，与历史一致）。 */
    OVERWRITE,

    /** 两者都留：备份里的冲突项换新 id 落库，名字带后缀以便区分。 */
    KEEP_BOTH
}

/** 导出数据范围选项；未勾选的段在快照中保持空值，导入时跳过。 */
data class BackupOptions(
    val providers: Boolean = true,
    val remoteConnections: Boolean = true,
    val chatHistory: Boolean = true,
    val mcpServers: Boolean = true,
    val permissionRules: Boolean = true,
    val appSettings: Boolean = true,
    val workspaceFiles: Boolean = false,
    /** 记忆与灵魂：aharou-global/memory 下的全部文件（SOUL.md / 核心档案 / 全局记忆 / 日志 / facts 等）。 */
    val memoryAndSoul: Boolean = false
)

data class RestoreStats(
    val providers: Int = 0,
    val remoteConnections: Int = 0,
    val remoteMounts: Int = 0,
    val chatSessions: Int = 0,
    val agentMessages: Int = 0,
    val todoItems: Int = 0,
    val mcpServers: Int = 0,
    val globalPermissionRules: Int = 0,
    val workspaceFiles: Int = 0,
    val memoryFiles: Int = 0,
    /** 本次恢复是否真的套用了应用设置段：未含设置的备份不碰本机设置，导入汇总不应声称已覆盖。 */
    val appSettingsRestored: Boolean = false
) {
    operator fun plus(other: RestoreStats) = RestoreStats(
        providers = providers + other.providers,
        remoteConnections = remoteConnections + other.remoteConnections,
        remoteMounts = remoteMounts + other.remoteMounts,
        chatSessions = chatSessions + other.chatSessions,
        agentMessages = agentMessages + other.agentMessages,
        todoItems = todoItems + other.todoItems,
        mcpServers = mcpServers + other.mcpServers,
        globalPermissionRules = globalPermissionRules + other.globalPermissionRules,
        workspaceFiles = workspaceFiles + other.workspaceFiles,
        memoryFiles = memoryFiles + other.memoryFiles,
        appSettingsRestored = appSettingsRestored || other.appSettingsRestored
    )
}
