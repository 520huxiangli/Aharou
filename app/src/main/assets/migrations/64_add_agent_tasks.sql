-- 子代理任务状态机：一次 task 派发（一个子会话）对应一条记录。
-- 记录持久化后，进程被杀时仍处于 running 的任务可在下次启动时判为 interrupted，
-- 避免「子代理半途而废却被当成没发生过」。
CREATE TABLE IF NOT EXISTS agent_tasks (
    id TEXT NOT NULL PRIMARY KEY,
    parentSessionId TEXT NOT NULL,
    instruction TEXT NOT NULL,
    status TEXT NOT NULL,
    result TEXT,
    createdAt INTEGER NOT NULL,
    updatedAt INTEGER NOT NULL,
    finishedAt INTEGER
);

CREATE INDEX IF NOT EXISTS index_agent_tasks_parentSessionId ON agent_tasks(parentSessionId);

CREATE INDEX IF NOT EXISTS index_agent_tasks_status ON agent_tasks(status);
