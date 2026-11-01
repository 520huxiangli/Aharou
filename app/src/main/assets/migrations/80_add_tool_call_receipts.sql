-- 工具调用回执：会话活动面板的数据源。
-- 与 DecisionAuditLog（写日志文件、只有审批过的调用才有）互补：这张表落库、按会话可查、每次执行都记。
-- 表结构必须与 ToolCallReceiptEntity 严格一致（列名、类型、可空性、索引名），否则 Room 启动校验会失败。
CREATE TABLE IF NOT EXISTS tool_call_receipts (
    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
    sessionId TEXT NOT NULL,
    category TEXT NOT NULL,
    toolName TEXT NOT NULL,
    argsPreview TEXT NOT NULL,
    status TEXT NOT NULL,
    errorCode TEXT,
    resultPreview TEXT NOT NULL,
    outputPath TEXT,
    durationMs INTEGER NOT NULL,
    buildAuthJson TEXT,
    createdAt INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS index_tool_call_receipts_sessionId_createdAt
    ON tool_call_receipts (sessionId, createdAt);

CREATE INDEX IF NOT EXISTS index_tool_call_receipts_createdAt
    ON tool_call_receipts (createdAt);
