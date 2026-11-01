package com.aharou.feature.agent.presentation.component

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.aharou.R
import com.aharou.feature.agent.domain.model.ChatSession
import com.aharou.feature.agent.presentation.AgentUIState
import compose.icons.FeatherIcons
import compose.icons.feathericons.ArrowLeft

/**
 * 子代理工作流全屏页：内容就是侧栏原来的「工作流」Tab（[WorkflowTab]）。
 *
 * 侧栏收敛成会话列表后，工作流树改成顶栏常驻入口 + 本页——树本身需要整屏宽度才看得清层级。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkflowScreen(
    sessions: List<ChatSession>,
    subSessionsByParent: Map<String, List<ChatSession>>,
    agentStates: Map<String, AgentUIState>,
    currentSessionId: String?,
    onSelect: (ChatSession) -> Unit,
    onNavigateBack: () -> Unit
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.drawer_tab_workflow)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            FeatherIcons.ArrowLeft,
                            contentDescription = stringResource(R.string.common_back)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                )
            )
        }
    ) { padding ->
        WorkflowTab(
            sessions = sessions,
            subSessionsByParent = subSessionsByParent,
            agentStates = agentStates,
            currentSessionId = currentSessionId,
            onSelect = onSelect,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        )
    }
}
