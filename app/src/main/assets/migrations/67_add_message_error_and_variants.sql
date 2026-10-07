-- 错误持久化 + 消息变体（「重新生成」不覆盖旧回答）：
--   error：仅 ASSISTANT 行。本轮运行失败时写入的错误文本，重载/切会话后据此恢复错误横幅与一键重试；
--          失败时若本轮还没产出助手行，会补插一条 content 为空的助手行专门承载它。
--   variantGroupId / variantIndex：同一轮「重新生成」产生的多个回答共用一个分组 id，
--          variantIndex 为组内版本序号（0 起）；只有当前展示的版本参与上下文回放。
-- 三列都只能追加在表末尾：备份 DTO 按位置参数映射消息字段，插到中间会错位。
ALTER TABLE agent_messages ADD COLUMN error TEXT;
ALTER TABLE agent_messages ADD COLUMN variantGroupId TEXT;
ALTER TABLE agent_messages ADD COLUMN variantIndex INTEGER NOT NULL DEFAULT 0;
