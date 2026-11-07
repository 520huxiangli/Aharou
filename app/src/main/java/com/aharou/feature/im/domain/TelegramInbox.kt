package com.aharou.feature.im.domain

import android.util.AtomicFile
import com.aharou.feature.im.domain.model.ImAttachment
import com.aharou.feature.im.domain.model.ImCallbackQuery
import com.aharou.feature.im.domain.model.ImInboundMessage
import com.aharou.feature.im.domain.model.ImUpdate
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

internal class TelegramInbox(directory: File, accountId: Long) {
    private val file = AtomicFile(File(directory.apply { mkdirs() }, "$accountId.json"))
    private val updates = linkedMapOf<Long, ImUpdate>()
    var nextOffset: Long = 0L
        private set

    init {
        if (file.baseFile.exists() || File(file.baseFile.path + ".bak").exists()) {
            val root = file.openRead().bufferedReader().use { Json.parseToJsonElement(it.readText()).jsonObject }
            nextOffset = root.getValue("next").jsonPrimitive.long
            root.getValue("updates").jsonArray.forEach { value ->
                val update = decode(value.jsonObject)
                updates[update.updateId] = update
            }
        }
    }

    fun pending(): List<ImUpdate> = updates.values.toList()

    fun append(batch: List<ImUpdate>) {
        val added = batch.filter { it.updateId >= nextOffset && it.updateId !in updates }
        if (added.isEmpty()) return
        val next = maxOf(nextOffset, added.maxOf { it.updateId } + 1)
        val merged = LinkedHashMap(updates).apply { added.forEach { put(it.updateId, it) } }
        write(merged.values.toList(), next)
        updates.clear()
        updates.putAll(merged)
        nextOffset = next
    }

    fun remove(ids: List<Long>) {
        if (ids.none { it in updates }) return
        val remaining = updates.filterKeys { it !in ids }
        write(remaining.values.toList(), nextOffset)
        updates.clear()
        updates.putAll(remaining)
    }

    private fun write(batch: List<ImUpdate>, next: Long) {
        val bytes = buildJsonObject {
            put("next", next)
            put("updates", JsonArray(batch.map(::encode)))
        }.toString().toByteArray(Charsets.UTF_8)
        val stream = file.startWrite()
        try {
            stream.write(bytes)
            file.finishWrite(stream)
        } catch (e: Throwable) {
            file.failWrite(stream)
            throw e
        }
    }

    private fun encode(update: ImUpdate): JsonObject = buildJsonObject {
        put("id", update.updateId)
        when (update) {
            is ImUpdate.Callback -> {
                val cb = update.callback
                put("type", "callback")
                put("chat", cb.chatId)
                put("sender", cb.senderId)
                put("message", cb.messageId)
                put("callback", cb.id)
                put("data", cb.data)
            }
            is ImUpdate.Message -> {
                val msg = update.msg
                put("type", "message")
                put("chat", msg.chatId)
                put("sender", msg.senderId)
                put("message", msg.messageId)
                put("senderName", msg.senderName)
                put("title", msg.chatTitle)
                put("text", msg.text)
                put("group", msg.isGroup)
                put("reply", msg.replyToSenderId?.let(::JsonPrimitive) ?: JsonNull)
                put("mentions", msg.mentionsBot)
                put("attachments", JsonArray(msg.attachments.map { att -> buildJsonObject {
                    put("kind", att.kind.name)
                    put("fileId", att.fileId)
                    put("name", att.fileName)
                    put("mime", att.mimeType)
                    put("size", att.sizeBytes)
                } }))
            }
        }
    }

    private fun decode(obj: JsonObject): ImUpdate {
        fun text(key: String) = obj.getValue(key).jsonPrimitive.content
        val id = obj.getValue("id").jsonPrimitive.long
        if (text("type") == "callback") return ImUpdate.Callback(ImCallbackQuery(
            id, text("callback"), text("chat"), text("sender"), text("message"), text("data")
        ))
        return ImUpdate.Message(ImInboundMessage(
            updateId = id,
            chatId = text("chat"),
            senderId = text("sender"),
            senderName = text("senderName"),
            chatTitle = text("title"),
            text = text("text"),
            isGroup = obj.getValue("group").jsonPrimitive.boolean,
            messageId = text("message"),
            replyToSenderId = obj["reply"]?.jsonPrimitive?.contentOrNull,
            mentionsBot = obj.getValue("mentions").jsonPrimitive.boolean,
            attachments = obj.getValue("attachments").jsonArray.map { value ->
                val att = value.jsonObject
                ImAttachment(
                    kind = ImAttachment.Kind.valueOf(att.getValue("kind").jsonPrimitive.content),
                    fileId = att.getValue("fileId").jsonPrimitive.content,
                    fileName = att.getValue("name").jsonPrimitive.content,
                    mimeType = att.getValue("mime").jsonPrimitive.content,
                    sizeBytes = att.getValue("size").jsonPrimitive.long
                )
            }
        ))
    }
}
