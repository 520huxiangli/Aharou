-- 可视化工作流功能下线：回收迁移 61/62 建的三张表。
-- 保留 61/62 迁移文件不动（已装过的设备记录在册），靠本条清理，避免版本号回退导致降级失败。
DROP TABLE IF EXISTS workflow_node_runs;
DROP TABLE IF EXISTS workflow_runs;
DROP TABLE IF EXISTS workflow_definitions;
