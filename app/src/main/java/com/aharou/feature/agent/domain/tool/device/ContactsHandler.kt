package com.aharou.feature.agent.domain.tool.device

import android.Manifest
import android.content.ContentProviderOperation
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import com.aharou.core.util.FileLogger
import com.aharou.feature.agent.domain.tool.ToolResult
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 联系人查询与新建（系统通讯录，不是应用私有的一份）。 */
@Singleton
class ContactsHandler @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {

    private companion object {
        const val TAG = "ContactsHandler"
        const val DEFAULT_LIMIT = 10
        const val MAX_LIMIT = 50
    }

    private fun has(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    suspend fun search(query: String?, limitRaw: Int?): ToolResult = withContext(Dispatchers.IO) {
        if (!has(Manifest.permission.READ_CONTACTS)) {
            return@withContext ToolResult.Error(
                "没有通讯录权限。到系统设置给 Aharou 开「通讯录」后重试。",
                "CONTACTS_PERMISSION_MISSING",
            )
        }
        val limit = (limitRaw ?: DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
        )
        val selection = query?.takeIf { it.isNotBlank() }?.let {
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ? OR " +
                "${ContactsContract.CommonDataKinds.Phone.NUMBER} LIKE ?"
        }
        val args = query?.takeIf { it.isNotBlank() }?.let { arrayOf("%$it%", "%$it%") }

        runCatching {
            val hits = mutableListOf<Pair<String, String>>()
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection, selection, args,
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC",
            )?.use { cursor ->
                while (cursor.moveToNext() && hits.size < limit) {
                    hits += cursor.getString(0).orEmpty() to cursor.getString(1).orEmpty()
                }
            }
            hits
        }.fold(
            onSuccess = { hits ->
                ToolResult.Success(buildJsonObject {
                    put("count", hits.size)
                    put("contacts", buildJsonArray {
                        hits.forEach { (name, number) ->
                            add(buildJsonObject {
                                put("name", name)
                                put("phone", number)
                            })
                        }
                    })
                })
            },
            onFailure = { e ->
                FileLogger.e(TAG, "查通讯录失败", e)
                ToolResult.Error("查通讯录失败：${e.message}", "CONTACTS_READ_FAILED")
            },
        )
    }

    suspend fun add(name: String, phone: String?, email: String?): ToolResult = withContext(Dispatchers.IO) {
        if (!has(Manifest.permission.WRITE_CONTACTS)) {
            return@withContext ToolResult.Error(
                "没有写入通讯录的权限。到系统设置给 Aharou 开「通讯录」写权限后重试。",
                "CONTACTS_PERMISSION_MISSING",
            )
        }
        if (phone.isNullOrBlank() && email.isNullOrBlank()) {
            return@withContext ToolResult.Error("至少给一个电话或邮箱，否则建出来是空联系人", "MISSING_CONTACT_DATA")
        }
        // account 传 null 表示建到「本机」账户，不假装是某个云账户的联系人。
        val operations = arrayListOf<ContentProviderOperation>()
        operations += ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
            .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null)
            .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null)
            .build()
        operations += ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
            .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
            .withValue(
                ContactsContract.Data.MIMETYPE,
                ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE,
            )
            .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, name)
            .build()
        phone?.takeIf { it.isNotBlank() }?.let { number ->
            operations += ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(
                    ContactsContract.Data.MIMETYPE,
                    ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE,
                )
                .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, number)
                .withValue(ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE)
                .build()
        }
        email?.takeIf { it.isNotBlank() }?.let { address ->
            operations += ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(
                    ContactsContract.Data.MIMETYPE,
                    ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE,
                )
                .withValue(ContactsContract.CommonDataKinds.Email.ADDRESS, address)
                .withValue(ContactsContract.CommonDataKinds.Email.TYPE, ContactsContract.CommonDataKinds.Email.TYPE_HOME)
                .build()
        }

        runCatching { context.contentResolver.applyBatch(ContactsContract.AUTHORITY, operations) }
            .fold(
                onSuccess = { results ->
                    ToolResult.Success(buildJsonObject {
                        put("message", "已保存联系人「$name」")
                        put("name", name)
                        put("raw_contact_id", results.firstOrNull()?.uri?.let { ContentUris.parseId(it) } ?: -1L)
                    })
                },
                onFailure = { e ->
                    FileLogger.e(TAG, "写通讯录失败", e)
                    ToolResult.Error("写通讯录失败：${e.message}", "CONTACTS_WRITE_FAILED")
                },
            )
    }
}