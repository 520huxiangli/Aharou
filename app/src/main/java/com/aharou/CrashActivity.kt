package com.aharou

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aharou.core.theme.AIEditorTheme
import com.aharou.core.theme.Spacing
import com.aharou.core.theme.semanticColors
import compose.icons.FeatherIcons
import compose.icons.feathericons.AlertCircle
import compose.icons.feathericons.Copy
import compose.icons.feathericons.RefreshCw
import compose.icons.feathericons.X
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 全屏崩溃错误页：全局未捕获异常处理器捕获崩溃后拉起本页面，
 * 独立子进程运行，展示崩溃详情并支持一键复制反馈。
 */
class CrashActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val threadName = intent.getStringExtra(EXTRA_THREAD_NAME) ?: "unknown"
        val stack = intent.getStringExtra(EXTRA_STACK) ?: ""
        val screen = intent.getStringExtra(EXTRA_SCREEN)
        val mode = intent.getStringExtra(EXTRA_WORKSPACE_MODE)
        val logDir = resolveLogDir()
        val appVersion = resolveAppVersion()
        val device = "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        val report = buildReport(threadName, stack, screen, mode, logDir, appVersion, device, time)

        setContent {
            AIEditorTheme {
                CrashScreen(
                    threadName = threadName,
                    stack = stack,
                    screen = screen,
                    mode = mode,
                    logDir = logDir,
                    appVersion = appVersion,
                    device = device,
                    time = time,
                    onCopy = {
                        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("crash", report))
                        Toast.makeText(this, R.string.crash_copied, Toast.LENGTH_SHORT).show()
                    },
                    onRestart = {
                        // 用户主动点击重启：清除崩溃标记与计数，允许主进程尝试冷启动
                        AIEditorApp.resetCrashState(applicationContext)
                        startActivity(
                            Intent(this, MainActivity::class.java).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                            }
                        )
                        finish()
                    },
                    onExit = {
                        finish()
                        Process.killProcess(Process.myPid())
                    }
                )
            }
        }
    }

    private fun resolveAppVersion(): String = runCatching {
        packageManager.getPackageInfo(packageName, 0).let { info ->
            val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                info.versionCode.toLong()
            }
            "${info.versionName ?: "unknown"} ($code)"
        }
    }.getOrDefault("unknown")

    /** 组装可复制的崩溃报告：页面、模式、版本、设备、时间、线程、堆栈、日志位置。 */
    private fun buildReport(
        threadName: String,
        stack: String,
        screen: String?,
        mode: String?,
        logDir: String,
        appVersion: String,
        device: String,
        time: String
    ): String = buildString {
        appendLine(getString(R.string.crash_report_title))
        appendLine(getString(R.string.crash_report_screen, screenName(screen)))
        appendLine(getString(R.string.crash_report_mode, modeName(mode)))
        appendLine(getString(R.string.crash_report_time, time))
        appendLine(getString(R.string.crash_report_version, appVersion))
        appendLine(getString(R.string.crash_report_device, device))
        appendLine(getString(R.string.crash_report_thread, threadName))
        appendLine()
        appendLine(stack)
        appendLine()
        appendLine(getString(R.string.crash_report_log, logDir))
    }

    private fun modeName(mode: String?): String = when (mode) {
        "LOCAL_PROOT" -> getString(R.string.crash_mode_local)
        "REMOTE_SSH" -> getString(R.string.crash_mode_remote)
        null -> getString(R.string.crash_mode_unknown)
        else -> mode
    }

    private fun screenName(route: String?): String = when (route) {
        "chat" -> getString(R.string.crash_screen_chat)
        "settings" -> getString(R.string.crash_screen_settings)
        "terminal" -> getString(R.string.crash_screen_terminal)
        "git" -> getString(R.string.crash_screen_git)
        null -> getString(R.string.crash_screen_unknown)
        else -> route
    }

    private fun resolveLogDir(): String =
        runCatching { getExternalFilesDir(null)?.absolutePath }.getOrDefault(null)
            ?.let { "$it/logs/" } ?: "filesDir/logs/"

    companion object {
        const val EXTRA_THREAD_NAME = "thread_name"
        const val EXTRA_STACK = "stack"
        const val EXTRA_SCREEN = "screen"
        const val EXTRA_WORKSPACE_MODE = "workspace_mode"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CrashScreen(
    threadName: String,
    stack: String,
    screen: String?,
    mode: String?,
    logDir: String,
    appVersion: String,
    device: String,
    time: String,
    onCopy: () -> Unit,
    onRestart: () -> Unit,
    onExit: () -> Unit,
) {
    val pageBg = MaterialTheme.semanticColors.pageBackground
    val cardBg = MaterialTheme.semanticColors.cardSurface
    val context = LocalContext.current

    val screenText = when (screen) {
        "chat" -> stringResource(R.string.crash_screen_chat)
        "settings" -> stringResource(R.string.crash_screen_settings)
        "terminal" -> stringResource(R.string.crash_screen_terminal)
        "git" -> stringResource(R.string.crash_screen_git)
        null -> stringResource(R.string.crash_screen_unknown)
        else -> screen
    }

    val modeText = when (mode) {
        "LOCAL_PROOT" -> stringResource(R.string.crash_mode_local)
        "REMOTE_SSH" -> stringResource(R.string.crash_mode_remote)
        null -> stringResource(R.string.crash_mode_unknown)
        else -> mode
    }

    BackHandler { onExit() }

    Scaffold(
        containerColor = pageBg,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.crash_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onExit) {
                        Icon(
                            imageVector = FeatherIcons.X,
                            contentDescription = stringResource(R.string.crash_exit),
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onCopy) {
                        Icon(
                            imageVector = FeatherIcons.Copy,
                            contentDescription = stringResource(R.string.crash_copy),
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = pageBg,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
                    actionIconContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        },
        bottomBar = {
            Surface(
                color = pageBg,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = Spacing.lg, vertical = Spacing.md),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.md)
                ) {
                    OutlinedButton(
                        onClick = onCopy,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(FeatherIcons.Copy, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(Spacing.xs))
                        Text(stringResource(R.string.crash_copy), maxLines = 1)
                    }
                    Button(
                        onClick = onRestart,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(FeatherIcons.RefreshCw, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(Spacing.xs))
                        Text(stringResource(R.string.crash_restart), maxLines = 1)
                    }
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.lg)
                .padding(bottom = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.md)
        ) {
            // 顶部提示条：温和提示异常已被安全隔离
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f)
            ) {
                Row(
                    modifier = Modifier.padding(Spacing.md),
                    verticalAlignment = Alignment.Top
                ) {
                    Icon(
                        imageVector = FeatherIcons.AlertCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier
                            .size(20.dp)
                            .padding(top = 2.dp)
                    )
                    Spacer(Modifier.width(Spacing.md))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.crash_banner_title),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.height(Spacing.xs))
                        Text(
                            text = stringResource(R.string.crash_message),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // 环境与运行状态卡片
            CrashSectionHeader(text = stringResource(R.string.crash_section_environment))
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = cardBg,
                shadowElevation = 0.dp
            ) {
                Column {
                    CrashInfoRow(
                        label = stringResource(R.string.crash_field_workspace_mode),
                        value = modeText
                    )
                    CrashDivider()
                    CrashInfoRow(
                        label = stringResource(R.string.crash_field_screen),
                        value = screenText
                    )
                    CrashDivider()
                    CrashInfoRow(
                        label = stringResource(R.string.crash_field_thread),
                        value = threadName
                    )
                    CrashDivider()
                    CrashInfoRow(
                        label = stringResource(R.string.crash_field_version),
                        value = appVersion
                    )
                    CrashDivider()
                    CrashInfoRow(
                        label = stringResource(R.string.crash_field_device),
                        value = device
                    )
                    CrashDivider()
                    CrashInfoRow(
                        label = stringResource(R.string.crash_field_time),
                        value = time
                    )
                }
            }

            // 异常调用栈卡片
            CrashSectionHeader(text = stringResource(R.string.crash_section_stack))
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = cardBg,
                shadowElevation = 0.dp
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 140.dp, max = 300.dp)
                        .background(MaterialTheme.semanticColors.mutedSurface, RoundedCornerShape(14.dp))
                        .padding(Spacing.md)
                ) {
                    Text(
                        text = stack,
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState()),
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.5.sp,
                            lineHeight = 16.sp
                        ),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            // 日志文件提示
            Text(
                text = stringResource(R.string.crash_log_hint, logDir),
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp),
                color = MaterialTheme.semanticColors.subtleText,
                modifier = Modifier.padding(horizontal = Spacing.xs)
            )
        }
    }
}

@Composable
private fun CrashSectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp),
        fontWeight = FontWeight.Normal,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = Spacing.xs, top = Spacing.xs)
    )
}

@Composable
private fun CrashInfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(96.dp)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun CrashDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = Spacing.lg),
        thickness = 0.5.dp,
        color = MaterialTheme.semanticColors.subtleBorder
    )
}
