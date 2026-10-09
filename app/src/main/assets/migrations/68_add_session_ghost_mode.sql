-- 会话级「隐身模式」开关：开启后该会话的消息不落库（只在内存存活）、记忆不写入，
-- 关闭开关或切走会话后不残留任何内容。列只能追加在表末尾（Room 按列名映射，无需位置对齐）。
ALTER TABLE chat_sessions ADD COLUMN ghostMode INTEGER NOT NULL DEFAULT 0;
