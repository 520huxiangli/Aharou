package com.aharou.core.datastore

import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.aharou.core.util.FileLogger

private const val TAG = "PreferencesCorruption"

/**
 * 全部 preference DataStore 共用的损坏处理器。
 *
 * 不配处理器时 [androidx.datastore.core.CorruptionException] 会直接抛给调用方，落在
 * ViewModel/仓库的协程里就是整个应用崩溃。更糟的是读取时任何 IOException 都会被 protobuf
 * 包成 InvalidProtocolBufferException，再被 PreferencesSerializer 当成 proto 损坏抛出，
 * 于是一次瞬时 IO 故障也能崩掉 App（实例：EBADF）。
 *
 * 这里统一改为「丢弃该文件、以默认值重建」：单个设置文件损坏或读不出来只丢该组设置，
 * 不再崩溃，也不会每次启动重复触发；异常写入日志便于排查。
 */
val preferencesCorruptionHandler: ReplaceFileCorruptionHandler<Preferences> =
    ReplaceFileCorruptionHandler { error ->
        FileLogger.e(TAG, "偏好设置文件不可用，已重置为默认值", error)
        emptyPreferences()
    }
