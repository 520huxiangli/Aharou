-- 工作流定义加「流程变量」（名 → 默认值，JSON 文本）：运行时可在表单里覆盖，节点用 ${变量名} 引用。
-- 旧数据取默认 "{}"，行为不变。
ALTER TABLE workflow_definitions ADD COLUMN variablesJson TEXT NOT NULL DEFAULT '{}';
