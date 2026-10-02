ALTER TABLE agent_messages ADD COLUMN isContextExcluded INTEGER NOT NULL DEFAULT 0;
ALTER TABLE agent_messages ADD COLUMN compactedBySummaryId TEXT;

UPDATE agent_messages
SET isContextSummary = 1
WHERE role = 'ASSISTANT'
  AND content LIKE '【系统提示：早期的对话已被压缩，以下是之前的核心状态摘要】%';

-- 旧版仅用 isCompacted 排除 /usage；普通压缩原文保留该标记，回退时再恢复。
-- 旧数据没有逐条摘要归属，不能按时间推断 compactedBySummaryId。
UPDATE agent_messages
SET isContextExcluded = 1,
    isCompacted = 0
WHERE isCompacted = 1
  AND isContextSummary = 0
  AND isCompactionMarker = 0
  AND role = 'ASSISTANT'
  AND toolCallsJson IS NULL
  AND content NOT LIKE '【系统提示：早期的对话已被压缩%'
  AND content LIKE '| 项目 | 今日 | 累计 |
|---|---|---|
| 调用次数 | % | % |
| 输入 tokens | % | % |
| 输出 tokens | % | % |
| 缓存命中 tokens | % | % |
| 预估费用 | % | %'
  AND LENGTH(content) - LENGTH(REPLACE(content, CHAR(10), '')) = 6
  AND LENGTH(content) - LENGTH(REPLACE(content, '|', '')) = 28;
