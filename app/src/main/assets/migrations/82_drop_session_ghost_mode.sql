-- 隐身会话已下线，重建 chat_sessions 去掉 ghostMode 列；SQLite 低于 3.35 不支持 DROP COLUMN，
-- 只能建新表搬数据再改名。列必须与 ChatSessionEntity 严格一致（列名、类型、可空性、索引名），否则 Room 启动校验会失败。
CREATE TABLE chat_sessions_new (
    id TEXT NOT NULL PRIMARY KEY,
    title TEXT NOT NULL,
    createdAt INTEGER NOT NULL,
    updatedAt INTEGER NOT NULL,
    workspacePath TEXT NOT NULL,
    mode TEXT NOT NULL,
    modeBeforePlan TEXT,
    reasoningEffort TEXT NOT NULL,
    providerId TEXT,
    model TEXT,
    totalInputTokens INTEGER NOT NULL,
    totalOutputTokens INTEGER NOT NULL,
    lastInputTokens INTEGER NOT NULL,
    isPinned INTEGER NOT NULL,
    parentId TEXT,
    subagentType TEXT,
    goal TEXT,
    goalStatus TEXT,
    goalTurnCount INTEGER NOT NULL,
    goalStartInputTokens INTEGER NOT NULL,
    goalStartOutputTokens INTEGER NOT NULL
);
INSERT INTO chat_sessions_new SELECT id, title, createdAt, updatedAt, workspacePath, mode, modeBeforePlan, reasoningEffort, providerId, model, totalInputTokens, totalOutputTokens, lastInputTokens, isPinned, parentId, subagentType, goal, goalStatus, goalTurnCount, goalStartInputTokens, goalStartOutputTokens FROM chat_sessions;
DROP TABLE chat_sessions;
ALTER TABLE chat_sessions_new RENAME TO chat_sessions;
CREATE INDEX IF NOT EXISTS index_chat_sessions_workspacePath ON chat_sessions (workspacePath);
CREATE INDEX IF NOT EXISTS index_chat_sessions_parentId_isPinned_updatedAt ON chat_sessions (parentId, isPinned, updatedAt);
