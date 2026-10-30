-- ask_user_question 的待答问题落库：进程被杀后靠它把问题面板恢复回来，并留下已回答 / 已作废的记录。
-- 表结构必须与 PendingUserQuestionEntity 严格一致（列名、类型、可空性、索引名），否则 Room 启动校验会失败。
CREATE TABLE IF NOT EXISTS pending_user_questions (
    id TEXT NOT NULL PRIMARY KEY,
    sessionId TEXT NOT NULL,
    questionsJson TEXT NOT NULL,
    status TEXT NOT NULL,
    createdAt INTEGER NOT NULL,
    updatedAt INTEGER NOT NULL,
    answeredAt INTEGER,
    answerJson TEXT,
    resolveReason TEXT
);

CREATE INDEX IF NOT EXISTS index_pending_user_questions_status_createdAt
    ON pending_user_questions (status, createdAt);
