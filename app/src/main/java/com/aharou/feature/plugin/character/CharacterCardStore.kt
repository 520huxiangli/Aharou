package com.aharou.feature.plugin.character

import android.content.Context
import com.aharou.core.util.FileLogger
import com.aharou.feature.plugin.domain.PluginHostException
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * 角色卡持久化：一张卡一份数据的全集都存在 `filesDir/character-cards/character-cards.json`。
 *
 * 不进 Room 是刻意的：角色卡是插件的自由格式数据（含任意长度的设定文本），
 * 与 Agent 的会话库没有关联查询需求，放 Room 只会多出一组没人查的迁移。
 *
 * 写入沿用 [com.aharou.feature.plugin.runtime.PluginEnvStore] 的原子写：
 * 先写 `.tmp` 并 `fd.sync()`，再 `ATOMIC_MOVE` 覆盖。进程在写盘途中被杀时，
 * 旧文件仍完整可读——角色卡是用户手写的内容，读到一半的 JSON 等于全丢。
 */
@Singleton
class CharacterCardStore @Inject constructor(
    @param:ApplicationContext context: Context
) {

    private val dir = File(context.filesDir, CARD_DIR)
    private val file = File(dir, CARD_FILE)
    private val mutex = Mutex()
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    suspend fun cards(): List<CharacterCard> = read().cards

    suspend fun card(id: String): CharacterCard? = read().cards.firstOrNull { it.id == id }

    suspend fun activeId(): String? = read().activeCharacterCardId

    suspend fun create(draft: CharacterCard): CharacterCard = mutate { snapshot ->
        val now = System.currentTimeMillis()
        val id = draft.id.trim().ifEmpty { UUID.randomUUID().toString() }
        if (snapshot.cards.any { it.id == id }) {
            throw PluginHostException("ALREADY_EXISTS", "角色卡已存在：$id")
        }
        val card = draft.copy(
            id = id,
            createdAt = if (draft.createdAt > 0L) draft.createdAt else now,
            updatedAt = now,
        )
        snapshot.copy(cards = snapshot.cards + card) to card
    }

    /** 就地更新；[transform] 拿到的永远是当前盘上那份，不会覆盖别处的并发修改。 */
    suspend fun update(id: String, transform: (CharacterCard) -> CharacterCard): CharacterCard =
        mutate { snapshot ->
            val index = snapshot.cards.indexOfFirst { it.id == id }
            if (index < 0) throw PluginHostException("NOT_FOUND", "角色卡不存在：$id")
            val updated = transform(snapshot.cards[index]).copy(id = id, updatedAt = System.currentTimeMillis())
            val cards = snapshot.cards.toMutableList().also { it[index] = updated }
            snapshot.copy(cards = cards) to updated
        }

    /** 返回被删掉的卡；不存在返回 null（调用方据此决定报错还是静默）。 */
    suspend fun delete(id: String): CharacterCard? = mutate { snapshot ->
        val target = snapshot.cards.firstOrNull { it.id == id }
            ?: return@mutate snapshot to null
        val cards = snapshot.cards.filterNot { it.id == id }
        // 删掉的恰好是激活卡时一并清空，否则「当前激活」会指向一张不存在的卡
        val active = if (snapshot.activeCharacterCardId == id) null else snapshot.activeCharacterCardId
        snapshot.copy(cards = cards, activeCharacterCardId = active) to target
    }

    suspend fun setActive(id: String?): String? = mutate { snapshot ->
        if (id != null && snapshot.cards.none { it.id == id }) {
            throw PluginHostException("NOT_FOUND", "角色卡不存在：$id")
        }
        snapshot.copy(activeCharacterCardId = id) to id
    }

    private suspend fun read(): CharacterCardSnapshot = withContext(Dispatchers.IO) {
        mutex.withLock { load() }
    }

    private suspend fun <T> mutate(block: (CharacterCardSnapshot) -> Pair<CharacterCardSnapshot, T>): T =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val current = load()
                val (next, result) = block(current)
                if (next != current) save(next)
                result
            }
        }

    private fun load(): CharacterCardSnapshot {
        if (!file.isFile) return CharacterCardSnapshot()
        val parsed = try {
            json.decodeFromString(CharacterCardSnapshot.serializer(), file.readText(Charsets.UTF_8))
        } catch (e: Exception) {
            // 解析失败按空库处理而不是抛错：一张坏文件不该让整个插件 API 永久不可用。
            // 文件本身保留在盘上，用户可以自己捞回来。
            FileLogger.e(TAG, "角色卡文件无法解析，按空库处理: ${file.absolutePath}", e)
            return CharacterCardSnapshot()
        }
        if (parsed.schema != CHARACTER_CARD_SCHEMA) {
            FileLogger.w(TAG, "角色卡文件版本不支持（${parsed.schema}），按空库处理")
            return CharacterCardSnapshot()
        }
        // 激活 id 指向已不存在的卡（外部改过文件）时视为未激活
        val active = parsed.activeCharacterCardId?.takeIf { id -> parsed.cards.any { it.id == id } }
        return parsed.copy(activeCharacterCardId = active)
    }

    private fun save(snapshot: CharacterCardSnapshot) {
        try {
            dir.mkdirs()
            val tmp = File(dir, "$CARD_FILE.tmp")
            tmp.outputStream().use { out ->
                out.write(json.encodeToString(CharacterCardSnapshot.serializer(), snapshot).toByteArray(Charsets.UTF_8))
                out.fd.sync()
            }
            try {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE)
            } catch (e: AtomicMoveNotSupportedException) {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (e: IOException) {
            FileLogger.e(TAG, "写入角色卡失败: ${file.absolutePath}", e)
            throw PluginHostException("STORAGE_FAILED", "无法写入角色卡文件")
        }
    }

    private companion object {
        const val TAG = "CharacterCardStore"
        const val CARD_DIR = "character-cards"
        const val CARD_FILE = "character-cards.json"
    }
}
