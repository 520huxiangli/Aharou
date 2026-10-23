package com.aharou.feature.settings.data.repository

import android.app.AppOpsManager
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import android.provider.Settings
import com.aharou.feature.voice.assistant.AharouVoiceInteractionService
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 「本应用是不是系统数字助理」这件事的唯一事实来源。
 *
 * 判定只认系统里真实记着的东西（`Settings.Secure.ASSISTANT`），不靠 RoleManager：
 * 国产 ROM 基本不开放 ASSISTANT 角色给第三方，但把 `assistant` 指向我们之后系统照样会派发，
 * 只看角色会一直显示「未设置」。
 */
@Singleton
class AssistantStatusRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {

    data class Snapshot(
        /** 本应用注册给系统的助手组件（包名/类全名）。 */
        val selfComponent: String,
        /** 系统当前记着的助手组件；没设过就是 null。 */
        val currentComponent: String?,
        /** 当前助手的应用名，用于在界面上说清「现在是别人」。 */
        val currentLabel: String?,
        /** 当前助手是不是本应用。 */
        val isSelf: Boolean,
        /** 系统是否允许助手读取屏幕上的文字。 */
        val assistStructureAllowed: Boolean,
        /** 系统是否允许助手截屏。 */
        val assistScreenshotAllowed: Boolean,
    )

    fun snapshot(): Snapshot {
        val self = selfComponent()
        val roleHeld = isRoleHeld()
        val current = readCurrentComponent()
        return Snapshot(
            selfComponent = self,
            currentComponent = current,
            currentLabel = current?.let { labelOf(it.substringBefore('/')) },
            // 以角色为准：系统页选的是角色持有者，`secure.assistant` 可能是直接写进去的残留值。
            isSelf = roleHeld || current?.substringBefore('/') == context.packageName,
            assistStructureAllowed = screenFlagEnabled("assist_structure_enabled", OP_ASSIST_STRUCTURE),
            assistScreenshotAllowed = screenFlagEnabled("assist_screenshot_enabled", OP_ASSIST_SCREENSHOT),
        )
    }

    private fun isRoleHeld(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val roleManager = context.getSystemService(RoleManager::class.java) ?: return false
        return runCatching { roleManager.isRoleHeld(RoleManager.ROLE_ASSISTANT) }.getOrDefault(false)
    }

    /**
     * 打开系统里选「默认数字助理应用」的页面。
     *
     * 用 `android.settings.VOICE_INPUT_SETTINGS`：它落到 permissioncontroller 的角色页
     * （ColorOS 上就是「默认数字助理应用」那个列表），ChatBoost 跳的也是这个。
     * 实测 ColorOS 自带的 `SettingsSpeechAssistantActivity` 拒绝第三方启动（`am start` 也拉不起来），
     * 而 `createRequestRoleIntent` 弹的不是这个列表，所以都不用。
     */
    fun openAssistantSettings(): Intent? {
        val pm = context.packageManager
        val candidates = listOf(
            Intent(Settings.ACTION_VOICE_INPUT_SETTINGS),
            Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS),
            Intent(Settings.ACTION_SETTINGS),
        )
        return candidates.firstOrNull { pm.resolveActivity(it, 0) != null }
    }

    fun selfComponent(): String =
        "${context.packageName}/${AharouVoiceInteractionService::class.java.name}"

    private fun readCurrentComponent(): String? =
        runCatching {
            Settings.Secure.getString(context.contentResolver, SECURE_ASSISTANT)
        }.getOrNull()?.takeIf { it.isNotBlank() }

    private fun labelOf(packageName: String?): String? {
        if (packageName.isNullOrBlank()) return null
        return runCatching {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
        }.getOrDefault(packageName)
    }

    /**
     * 系统是否允许助手读屏幕。
     *
     * 优先读 ColorOS 自己的两个开关（`assist_structure_enabled` / `assist_screenshot_enabled`），
     * 它们才是系统真实生效的值——只查 appop 会看到“默认允许”而误判成已开启。
     * 非 ColorOS 机型没有这两个键时（读到 -1）回退到 appop。
     */
    private fun screenFlagEnabled(secureKey: String, fallbackOp: String): Boolean {
        val value = runCatching {
            Settings.Secure.getInt(context.contentResolver, secureKey, -1)
        }.getOrDefault(-1)
        if (value == 0 || value == 1) return value == 1
        return opAllowed(fallbackOp)
    }

    /** 只读自己这条 appop：MODE_DEFAULT 视为按系统策略允许，只有被明确禁用才算拒绝。 */
    private fun opAllowed(op: String): Boolean {
        val manager = context.getSystemService(AppOpsManager::class.java) ?: return true
        val mode = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                manager.unsafeCheckOpNoThrow(op, Process.myUid(), context.packageName)
            } else {
                @Suppress("DEPRECATION")
                manager.checkOpNoThrow(op, Process.myUid(), context.packageName)
            }
        }.getOrDefault(AppOpsManager.MODE_ALLOWED)
        return mode != AppOpsManager.MODE_ERRORED && mode != AppOpsManager.MODE_IGNORED
    }

    private companion object {
        const val TAG = "AssistantStatus"

        /** 系统没把这两个 appop 的字符串常量公开出来，名字以 `appops` 命令的输出为准。 */
        const val OP_ASSIST_STRUCTURE = "android:assist_structure"
        const val OP_ASSIST_SCREENSHOT = "android:assist_screenshot"

        /** `Settings.Secure.ASSISTANT` 是 @hide，只能写字符串。 */
        const val SECURE_ASSISTANT = "assistant"
    }
}
