package com.aharou.core.storage

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.aharou.feature.agent.data.local.database.AgentDatabase
import com.aharou.feature.agent.data.local.database.AgentDatabase_Impl
import kotlinx.coroutines.Dispatchers

/**
 * 桌面端建库入口。
 *
 * 用生成的 `AgentDatabase_Impl` 直接构造：Room 只在类上标了 `@ConstructedBy` 时才生成
 * `RoomDatabaseConstructor`，而手机端走的是基于 Context 的 builder，没有这个需求。
 *
 * 不传迁移列表：桌面端是全新库，Room 会按当前 schema 一次建到 `SCHEMA_VERSION`，
 * 那 71 个迁移脚本只服务于手机端老库升级。
 */
fun openAgentDatabase(path: String): AgentDatabase =
    Room.databaseBuilder(path) { AgentDatabase_Impl() }
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .build()
