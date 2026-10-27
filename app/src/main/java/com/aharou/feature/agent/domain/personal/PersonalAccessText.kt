package com.aharou.feature.agent.domain.personal

import android.content.Context
import com.aharou.R
import com.aharou.feature.agent.domain.tool.ToolResult
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 能力状态的文案层：能力显示名、权限不足时的指路文案、工具错误的拼装。
 *
 * 这里是本功能唯一引用 `R.string` 的地方（能力 id / 状态码仍是机器可读的英文词组）。
 * 用到的键集中在文件末尾的注释里列出，需与中英两份 `strings.xml` 保持一致。
 */
@Singleton
class PersonalAccessText @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {

    /** 能力的用户可读名称，用于「去开哪个权限」的句子。始终可用（无权限也拿到的是名字）。 */
    fun label(capability: PersonalCapability): String = when (capability) {
        PersonalCapability.SMS -> context.getString(R.string.personal_capability_sms)
        PersonalCapability.CALL_LOG -> context.getString(R.string.personal_capability_call_log)
        PersonalCapability.APP_USAGE -> context.getString(R.string.personal_capability_app_usage)
        PersonalCapability.CONTACTS_READ, PersonalCapability.CONTACTS_WRITE ->
            context.getString(R.string.personal_capability_contacts)
        PersonalCapability.CALENDAR_READ, PersonalCapability.CALENDAR_WRITE ->
            context.getString(R.string.personal_capability_calendar)
        PersonalCapability.LOCATION -> context.getString(R.string.personal_capability_location)
        PersonalCapability.PHOTOS -> context.getString(R.string.personal_capability_photos)
        PersonalCapability.NOTIFICATIONS_READ -> context.getString(R.string.personal_capability_notifications_read)
        PersonalCapability.NOTIFICATIONS_POST -> context.getString(R.string.personal_capability_notifications_post)
        else -> capability.id
    }

    /**
     * 「现在是什么状态 + 去哪开」。不可用时的四类情形分开写：
     * 被策略限制（用户开不了）、服务缺失（开了也没用）、部分授权（能用但不全）、普通未授权（用户自己能开）。
     * 文案里明确写「不会反复弹授权框」，避免模型自己去重试或反复骚扰用户。
     */
    fun guidance(state: PersonalAccessSnapshot): String {
        val name = label(state.capability)
        return when {
            state.status == PersonalAccessStatus.RESTRICTED ->
                context.getString(R.string.personal_access_restricted, name)
            state.status == PersonalAccessStatus.UNAVAILABLE ->
                context.getString(R.string.personal_access_unavailable, name)
            state.status == PersonalAccessStatus.PARTIAL ->
                context.getString(R.string.personal_access_partial, name)
            state.settingsRoute == PersonalSettingsRoute.USAGE_ACCESS ->
                context.getString(R.string.personal_access_special_usage, name)
            state.settingsRoute == PersonalSettingsRoute.NOTIFICATION_LISTENER ->
                context.getString(R.string.personal_access_special_notification, name)
            else -> context.getString(R.string.personal_access_denied, name)
        }
    }

    /** 能力不可用时直接给出的工具错误：机器码固定，文案里带 capabilityId 供上层对照。 */
    fun error(state: PersonalAccessSnapshot): ToolResult.Error = ToolResult.Error(
        message = stateNote(state) + "\n" + guidance(state),
        code = "PERSONAL_ACCESS_" + state.status.wire.uppercase(Locale.US),
    )

    /** 给已有错误追加「capabilityId + 状态 + 去哪开」，保留原有可读信息。 */
    fun appendHint(message: String, state: PersonalAccessSnapshot): String =
        message + "\n" + stateNote(state) + " " + guidance(state)

    private fun stateNote(state: PersonalAccessSnapshot): String =
        context.getString(R.string.personal_access_state_note, state.capability.id, state.status.wire)
}
