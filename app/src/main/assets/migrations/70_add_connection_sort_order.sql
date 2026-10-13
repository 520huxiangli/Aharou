-- 为远程连接配置记录用户拖拽排序的显式顺序。
--
-- 「连接配置」页此前只能按创建时间排，用户无法把常用的通道提到前面；而该顺序有真实语义——
-- 挂载弹窗与容器 SSH 通道下拉都默认取列表第一项。
-- 默认 0：存量行全部为 0，查询用 `sort_order ASC, created_at ASC, rowid ASC` 兜底，升级后顺序与升级前一致；
-- 用户首次拖拽时按 0..n-1 整体回写，此后以显式顺序为准。列只能追加在表末尾（Room 按列名映射，无需位置对齐）。
ALTER TABLE remote_connections ADD COLUMN sort_order INTEGER NOT NULL DEFAULT 0;
