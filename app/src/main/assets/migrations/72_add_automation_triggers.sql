-- 自动化任务支持事件触发（收到通知 / 电量变化 / 网络变化 / 开机）+ 条件 + 防抖 + 运行模式。
-- 旧数据全部落成「时间触发、无条件、防抖 10 分钟、single」，行为与升级前完全一致。

ALTER TABLE scheduled_tasks ADD COLUMN triggerType TEXT NOT NULL DEFAULT 'time';

ALTER TABLE scheduled_tasks ADD COLUMN triggerPackage TEXT NOT NULL DEFAULT '';

ALTER TABLE scheduled_tasks ADD COLUMN triggerBatteryBelow INTEGER NOT NULL DEFAULT -1;

ALTER TABLE scheduled_tasks ADD COLUMN triggerOnCharging INTEGER NOT NULL DEFAULT 0;

ALTER TABLE scheduled_tasks ADD COLUMN triggerSsid TEXT NOT NULL DEFAULT '';

ALTER TABLE scheduled_tasks ADD COLUMN conditionWindowFrom INTEGER NOT NULL DEFAULT -1;

ALTER TABLE scheduled_tasks ADD COLUMN conditionWindowTo INTEGER NOT NULL DEFAULT -1;

ALTER TABLE scheduled_tasks ADD COLUMN condRequireCharging INTEGER NOT NULL DEFAULT 0;

ALTER TABLE scheduled_tasks ADD COLUMN condMinBattery INTEGER NOT NULL DEFAULT -1;

ALTER TABLE scheduled_tasks ADD COLUMN debounceMinutes INTEGER NOT NULL DEFAULT 10;

ALTER TABLE scheduled_tasks ADD COLUMN runMode TEXT NOT NULL DEFAULT 'single';