package com.aharou.feature.agent.domain.tool.pet

import android.content.Context
import android.net.Uri
import com.aharou.core.util.FileLogger
import com.aharou.feature.agent.domain.model.AgentContext
import com.aharou.feature.agent.domain.tool.AgentTool
import com.aharou.feature.agent.domain.tool.ParameterType
import com.aharou.feature.agent.domain.tool.ToolCapability
import com.aharou.feature.agent.domain.tool.ToolParameter
import com.aharou.feature.agent.domain.tool.ToolResult
import com.aharou.feature.pet.PetAvatarImporter
import com.aharou.feature.pet.PetOverlayService
import com.aharou.feature.pet.PetRoles
import com.aharou.feature.pet.PetUserRoles
import com.aharou.feature.pet.VideoImportResult
import com.aharou.feature.workspace.domain.FileAccessProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import javax.inject.Inject

/**
 * 让 AI 把一张图（或一段视频）变成桌宠形象。
 *
 * 存在的理由是配合 `generateImage`：先出图，再用本工具导入并换上，全程不用用户手动操作。
 * 图片走 [PetAvatarImporter]（自动归一化，不需要用户拖）；视频走 [PetUserRoles.importVideo]，
 * 它要求素材是左右拼合格式（宽约为显示宽的两倍），否则渲染时会花屏——所以比例不对时直接拒绝并说明。
 *
 * 只动用户角色目录，内置的小雅和虎鲸娘删不掉。
 */
class PetAvatarTool @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val fileAccess: FileAccessProvider
) : AgentTool() {

    private companion object {
        const val TAG = "PetAvatarTool"
        const val ACTION_CREATE = "create"
        const val ACTION_LIST = "list"
        const val ACTION_REMOVE = "remove"
        const val ACTION_SET = "set"
        const val MAX_BYTES = 32L * 1024 * 1024
    }

    override val name = "petAvatar"

    override val description =
        "管理桌宠形象：create 把一张图片或一段视频导入成新角色（默认顺便换上），list 列出自己导入的角色，" +
            "remove 删除一个，set 切换当前角色。配合 generateImage 可以先出图再直接换上去。"

    override val capabilities = setOf(ToolCapability.MODIFY_DEVICE_STATE)

    override val parameters: Map<String, ToolParameter> = mapOf(
        "action" to ToolParameter(
            name = "action",
            type = ParameterType.STRING,
            description = "create（导入新角色）/ list（列出用户角色）/ remove（删除）/ set（切换）。",
            required = true,
            enum = listOf(ACTION_CREATE, ACTION_LIST, ACTION_REMOVE, ACTION_SET)
        ),
        "path" to ToolParameter(
            name = "path",
            type = ParameterType.STRING,
            description = "create 必填：图片（PNG/JPEG）或视频（mp4）的路径，工作区相对路径或绝对路径皆可。",
            required = false
        ),
        "name" to ToolParameter(
            name = "name",
            type = ParameterType.STRING,
            description = "create 用：角色显示名，同名会自动加序号，不会覆盖已有角色。",
            required = false
        ),
        "id" to ToolParameter(
            name = "id",
            type = ParameterType.STRING,
            description = "remove / set 必填：角色 id（list 返回的那个）。",
            required = false
        ),
        "set" to ToolParameter(
            name = "set",
            type = ParameterType.STRING,
            description = "create 用：导入后是否立即换上，默认 true。",
            required = false,
            enum = listOf("true", "false")
        )
    )

    override suspend fun executeWithContext(
        args: Map<String, JsonElement>,
        context: AgentContext
    ): ToolResult = withContext(Dispatchers.IO) {
        val action = args.string("action") ?: return@withContext ToolResult.Error(
            "缺少参数 action（create / list / remove / set）", "MISSING_ACTION"
        )
        when (action) {
            ACTION_CREATE -> create(args, context)
            ACTION_LIST -> list()
            ACTION_REMOVE -> remove(args)
            ACTION_SET -> set(args)
            else -> ToolResult.Error("未知 action：$action", "INVALID_ACTION")
        }
    }

    private fun create(args: Map<String, JsonElement>, agentContext: AgentContext): ToolResult {
        val path = args.string("path")
            ?: return ToolResult.Error("create 需要 path（图片或视频路径）", "MISSING_PATH")
        val name = args.string("name") ?: "我的形象"
        val local = runCatching { fileAccess.forWorkspace(agentContext.projectRoot).copyToLocal(path) }
            .getOrElse { e ->
                return ToolResult.Error("读不到素材「$path」：${e.message ?: e.javaClass.simpleName}", "FILE_UNREADABLE")
            }
        if (!local.isFile) return ToolResult.Error("不是文件：$path", "NOT_A_FILE")
        if (local.length() > MAX_BYTES) return ToolResult.Error("素材超过 32MB，请先压缩", "FILE_TOO_LARGE")

        val roleId = if (local.name.lowercase().endsWith(".mp4")) {
            when (val result = PetUserRoles.importVideo(context, Uri.fromFile(local), name)) {
                is VideoImportResult.Ok -> result.id
                is VideoImportResult.BadRatio -> return ToolResult.Error(
                    "视频宽高比 %.2f 不是左右拼合素材（应约为 3.56，即宽是显示宽的 2 倍：左半彩色、右半灰度遮罩）。" +
                        "普通视频导进去会花屏。".format(result.ratio),
                    "BAD_RATIO"
                )
                VideoImportResult.Unreadable -> return ToolResult.Error("读不出这段视频的宽高，可能不是 mp4 或文件损坏", "VIDEO_UNREADABLE")
                VideoImportResult.SaveFailed -> return ToolResult.Error("视频保存失败", "SAVE_FAILED")
            }
        } else {
            val bytes = runCatching { local.readBytes() }.getOrElse { e ->
                return ToolResult.Error("读取失败：${e.message}", "FILE_UNREADABLE")
            }
            PetAvatarImporter(context).importImage(bytes, name).getOrElse { e ->
                return ToolResult.Error("导入图片失败：${e.message ?: e.javaClass.simpleName}", "IMPORT_FAILED")
            }
        }

        val active = args.bool("set") != false
        if (active) PetOverlayService.applyRole(context, roleId)
        FileLogger.i(TAG, "导入桌宠形象：$roleId（$name），立即换上=$active")
        return ToolResult.Success(
            JsonObject(
                mapOf(
                    "id" to JsonPrimitive(roleId),
                    "name" to JsonPrimitive(name),
                    "active" to JsonPrimitive(active)
                )
            )
        )
    }

    private fun list(): ToolResult {
        val user = PetUserRoles.list(context)
        val builtin = PetRoles.load(context)
            .filter { PetUserRoles.isBuiltin(context, it.id) }
            .map { JsonPrimitive(it.id) }
        return ToolResult.Success(
            JsonObject(
                mapOf(
                    "roles" to JsonArray(
                        user.map { role ->
                            JsonObject(
                                mapOf(
                                    "id" to JsonPrimitive(role.id),
                                    "name" to JsonPrimitive(role.name)
                                )
                            )
                        }
                    ),
                    "builtin" to JsonArray(builtin)
                )
            )
        )
    }

    private fun remove(args: Map<String, JsonElement>): ToolResult {
        val id = args.string("id") ?: return ToolResult.Error("remove 需要 id", "MISSING_ID")
        if (PetUserRoles.isBuiltin(context, id)) {
            return ToolResult.Error("「$id」是内置角色，删不掉", "BUILTIN_ROLE")
        }
        if (!PetAvatarImporter(context).remove(id)) {
            return ToolResult.Error("没找到角色「$id」", "ROLE_NOT_FOUND")
        }
        // 删掉的可能正是当前角色：回落到默认，别让桌宠直接消失
        PetOverlayService.applyRole(context, PetRoles.DEFAULT_ID)
        return ToolResult.Success(JsonObject(mapOf("removed" to JsonPrimitive(id))))
    }

    private fun set(args: Map<String, JsonElement>): ToolResult {
        val id = args.string("id") ?: return ToolResult.Error("set 需要 id", "MISSING_ID")
        if (!PetRoles.exists(context, id)) {
            return ToolResult.Error("角色「$id」的素材不存在", "ROLE_NOT_FOUND")
        }
        PetOverlayService.applyRole(context, id)
        return ToolResult.Success(JsonObject(mapOf("active" to JsonPrimitive(id))))
    }

    private fun Map<String, JsonElement>.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

    private fun Map<String, JsonElement>.bool(key: String): Boolean? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.lowercase()?.let { it == "true" }
}
