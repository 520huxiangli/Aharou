-- 自动化任务支持「每天固定时刻」与「单次」两种调度方式。
-- 旧数据按默认值落成 interval，行为与升级前完全一致。

ALTER TABLE scheduled_tasks ADD COLUMN scheduleType TEXT NOT NULL DEFAULT 'interval';

ALTER TABLE scheduled_tasks ADD COLUMN dailyMinuteOfDay INTEGER NOT NULL DEFAULT 540;

ALTER TABLE scheduled_tasks ADD COLUMN onceAtMillis INTEGER NOT NULL DEFAULT 0;