-- MCP 资源变化触发：记录订阅哪条 server 的哪个资源。
-- 两个字段都空的任务不会被订阅，等同于「配了 mcp_resource 触发但还没选资源」。
ALTER TABLE scheduled_tasks ADD COLUMN triggerMcpServer TEXT NOT NULL DEFAULT '';
ALTER TABLE scheduled_tasks ADD COLUMN triggerMcpResourceUri TEXT NOT NULL DEFAULT '';
