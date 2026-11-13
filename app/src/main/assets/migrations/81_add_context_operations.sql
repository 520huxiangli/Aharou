-- 上下文压缩留痕：一行对应一次压缩，撤销时据此把被压缩掉的原始消息恢复回来。
-- 表结构必须与 ContextOperationEntity 严格一致（列名、类型、可空性、索引名与默认值），否则 Room 启动校验会失败。
-- snapshotJson 允许为空：快照超过上限时只记 snapshotBytes，撤销入口据此说明「这次压缩不可撤销」。
CREATE TABLE IF NOT EXISTS context_operations (
    id TEXT PRIMARY KEY NOT NULL,
    sessionId TEXT NOT NULL,
    createdAt INTEGER NOT NULL,
    kind TEXT NOT NULL,
    summaryMessageId TEXT,
    markerMessageId TEXT,
    removedMessageIds TEXT NOT NULL,
    snapshotJson TEXT,
    snapshotBytes INTEGER NOT NULL,
    revoked INTEGER NOT NULL DEFAULT 0,
    revokedAt INTEGER
);

CREATE INDEX IF NOT EXISTS index_context_operations_sessionId_createdAt
    ON context_operations (sessionId, createdAt);

CREATE INDEX IF NOT EXISTS index_context_operations_sessionId_revoked
    ON context_operations (sessionId, revoked);
