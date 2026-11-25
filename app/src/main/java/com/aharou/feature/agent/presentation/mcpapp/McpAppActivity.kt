package com.aharou.feature.agent.presentation.mcpapp

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.aharou.core.theme.AIEditorTheme
import com.aharou.feature.agent.domain.mcp.McpAppGateway
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * MCP App 的独立宿主。
 *
 * 用独立 Activity 而不是往主界面加路由：MCP 应用是外部内容，跑在单独的窗口里更清楚——
 * 退出即销毁 WebView，不会把外部页面的生命周期绑在聊天页上。
 *
 * 缺少 server 或资源 uri 时直接结束，不留一个空壳界面。
 */
@AndroidEntryPoint
class McpAppActivity : ComponentActivity() {

    @Inject
    lateinit var gateway: McpAppGateway

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val server = intent.getStringExtra(EXTRA_SERVER).orEmpty()
        val resource = intent.getStringExtra(EXTRA_RESOURCE).orEmpty()
        if (server.isBlank() || resource.isBlank()) {
            finish()
            return
        }
        val workspace = intent.getStringExtra(EXTRA_WORKSPACE)
        setContent {
            AIEditorTheme {
                McpAppScreen(
                    gateway = gateway,
                    server = server,
                    entryUri = resource,
                    workspacePath = workspace,
                    onClose = { finish() },
                )
            }
        }
    }

    companion object {
        const val EXTRA_SERVER = "mcp_server"
        const val EXTRA_RESOURCE = "mcp_resource"
        const val EXTRA_WORKSPACE = "mcp_workspace"

        /** 从工具或界面拉起 MCP 应用；外部上下文没有 Activity 栈，必须带 NEW_TASK。 */
        fun start(context: Context, server: String, resourceUri: String, workspacePath: String?) {
            val intent = Intent(context, McpAppActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(EXTRA_SERVER, server)
                putExtra(EXTRA_RESOURCE, resourceUri)
                if (!workspacePath.isNullOrBlank()) putExtra(EXTRA_WORKSPACE, workspacePath)
            }
            context.startActivity(intent)
        }
    }
}
