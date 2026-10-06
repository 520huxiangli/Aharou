-- 补上高频查询列缺失的索引，消除长会话下的全表扫描 + 临时排序：
--   agent_messages：DAO 全部按 sessionId 过滤并 ORDER BY timestamp
--   todo_items：DAO 全部按 sessionId 过滤并 ORDER BY `order`, priority
--   chat_sessions：DAO 按 parentId 查子会话，getAllRootSessions 按 parentId IS NULL + isPinned/updatedAt 排序
-- 索引名必须与 Entity @Index 生成的默认名（index_<表>_<列...>）完全一致，否则 Room 的 schema 校验会失败。
CREATE INDEX IF NOT EXISTS index_agent_messages_sessionId_timestamp ON agent_messages(sessionId, timestamp);
CREATE INDEX IF NOT EXISTS index_todo_items_sessionId_order ON todo_items(sessionId, `order`);
CREATE INDEX IF NOT EXISTS index_chat_sessions_parentId_isPinned_updatedAt ON chat_sessions(parentId, isPinned, updatedAt);
