-- 定时任务：按固定周期在指定会话（或每次新建会话）里跑一轮 AI。
-- 只落「任务定义 + 下次运行时间 + 运行计数」，实际执行由 WorkManager 周期唤醒后认领。
-- 认领用 compare-and-set（只看 nextRunAt 是否还是读到的旧值），避免重复触发。
CREATE TABLE IF NOT EXISTS scheduled_tasks (
    id TEXT NOT NULL PRIMARY KEY,
    name TEXT NOT NULL,
    prompt TEXT NOT NULL,
    targetSessionId TEXT,
    workspacePath TEXT,
    intervalMinutes INTEGER NOT NULL,
    enabled INTEGER NOT NULL,
    nextRunAt INTEGER NOT NULL,
    lastRunAt INTEGER,
    lastOutcome TEXT,
    runCount INTEGER NOT NULL,
    maxRuns INTEGER NOT NULL,
    createdAt INTEGER NOT NULL,
    updatedAt INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS index_scheduled_tasks_enabled_nextRunAt ON scheduled_tasks(enabled, nextRunAt);
