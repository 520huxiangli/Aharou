package com.aharou.feature.pet

import android.content.Context
import com.aharou.core.util.FileLogger
import java.util.Locale
import org.json.JSONArray

/** 一个可选的桌宠角色：显示名 + 素材目录名（`pet/<id>/`）。 */
data class PetRole(val id: String, val name: String)

/**
 * 角色清单：内置的来自 `assets/pet/roles.json`，用户自建的来自 `filesDir/pet/<id>/`，
 * 内置的排在前面。
 *
 * 加内置角色不用改代码：把素材放进 `assets/pet/<id>/`（至少要有 front.png），
 * 再到 roles.json 里加一行。缺的姿态（side / sleep / wave…）会自动复用 front。
 */
object PetRoles {

    const val DEFAULT_ID = "xiaoran"

    private const val TAG = "PetRoles"
    private const val ASSET_PATH = "pet/roles.json"

    /** 读角色清单；内置清单缺失或内容坏了照样能用用户角色，绝不抛给调用方。 */
    fun load(context: Context): List<PetRole> {
        val builtin = builtin(context)
        val user = PetUserRoles.list(context)
        if (user.isEmpty()) return builtin
        return builtin + user.filterNot { item -> builtin.any { it.id == item.id } }
    }

    /** 角色素材是不是真能用（设置里存了旧 id、素材被删掉时用来兜底）。 */
    fun exists(context: Context, id: String): Boolean =
        PetUserRoles.isValid(context, id) || PetAssetStore(context).hasEntries("pet/$id")

    private fun builtin(context: Context): List<PetRole> = runCatching {
        val raw = PetAssetStore(context).open(ASSET_PATH).use { it.readBytes().decodeToString() }
        val arr = JSONArray(raw)
        val english = Locale.getDefault().language == "en"
        buildList {
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val id = obj.optString("id").trim()
                if (id.isEmpty()) continue
                val zh = obj.optString("name").trim().ifEmpty { id }
                val en = obj.optString("name_en").trim().ifEmpty { zh }
                add(PetRole(id, if (english) en else zh))
            }
        }
    }.getOrElse {
        FileLogger.w(TAG, "读取角色清单失败，只用默认角色", it)
        emptyList()
    }.ifEmpty { listOf(PetRole(DEFAULT_ID, DEFAULT_ID)) }
}
