-- 定时任务「已派发但没跑」修复：总线投递失败时把渲染后的 prompt 落库，会话层就绪后捡起。
-- 只加一列，不迁移数据；旧行 pendingRunPrompt 为空 = 无挂起。
ALTER TABLE scheduled_tasks ADD COLUMN pendingRunPrompt TEXT DEFAULT NULL;
