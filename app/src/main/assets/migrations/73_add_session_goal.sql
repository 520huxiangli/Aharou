-- 会话级持续目标（goal）：目标正文、状态、已续跑轮数与 token 预算基线。
ALTER TABLE chat_sessions ADD COLUMN goal TEXT;
ALTER TABLE chat_sessions ADD COLUMN goalStatus TEXT;
ALTER TABLE chat_sessions ADD COLUMN goalTurnCount INTEGER NOT NULL DEFAULT 0;
ALTER TABLE chat_sessions ADD COLUMN goalStartInputTokens INTEGER NOT NULL DEFAULT 0;
ALTER TABLE chat_sessions ADD COLUMN goalStartOutputTokens INTEGER NOT NULL DEFAULT 0;
