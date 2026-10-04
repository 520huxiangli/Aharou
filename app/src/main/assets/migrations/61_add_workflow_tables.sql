-- 工作流：定义、运行历史与节点运行记录。
-- 节点与边以 JSON 字符串存储（项目无 Room TypeConverter，复杂结构手工序列化）。
CREATE TABLE IF NOT EXISTS workflow_definitions (
    id TEXT NOT NULL PRIMARY KEY,
    name TEXT NOT NULL,
    description TEXT NOT NULL,
    nodesJson TEXT NOT NULL,
    edgesJson TEXT NOT NULL,
    isBuiltin INTEGER NOT NULL DEFAULT 0,
    createdAt INTEGER NOT NULL,
    updatedAt INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS index_workflow_definitions_updatedAt ON workflow_definitions(updatedAt);

CREATE TABLE IF NOT EXISTS workflow_runs (
    id TEXT NOT NULL PRIMARY KEY,
    definitionId TEXT NOT NULL,
    snapshotJson TEXT NOT NULL,
    status TEXT NOT NULL,
    triggerSource TEXT NOT NULL,
    workspacePath TEXT NOT NULL,
    variablesJson TEXT NOT NULL,
    startedAt INTEGER NOT NULL,
    finishedAt INTEGER
);
CREATE INDEX IF NOT EXISTS index_workflow_runs_definitionId ON workflow_runs(definitionId);
CREATE INDEX IF NOT EXISTS index_workflow_runs_startedAt ON workflow_runs(startedAt);

CREATE TABLE IF NOT EXISTS workflow_node_runs (
    id TEXT NOT NULL PRIMARY KEY,
    runId TEXT NOT NULL,
    nodeId TEXT NOT NULL,
    status TEXT NOT NULL,
    exitCode INTEGER,
    outputTail TEXT NOT NULL,
    attempt INTEGER NOT NULL,
    startedAt INTEGER NOT NULL,
    finishedAt INTEGER
);
CREATE INDEX IF NOT EXISTS index_workflow_node_runs_runId ON workflow_node_runs(runId);
