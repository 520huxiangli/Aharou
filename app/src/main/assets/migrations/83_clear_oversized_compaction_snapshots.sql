-- 压缩快照上限从 4MB 降到 1.5MB：超过 CursorWindow 单行上限（约 2MB）的记录，
-- 一旦被聊天页的「撤销这次压缩」入口整行读出，就会抛 SQLiteBlobTooBigException 把进程带崩。
-- 只清快照正文并保留 snapshotBytes，撤销入口据此提示「这次压缩没有快照」；消息表与压缩标记一律不动。
UPDATE context_operations
SET snapshotBytes = LENGTH(CAST(snapshotJson AS BLOB))
WHERE snapshotJson IS NOT NULL AND LENGTH(CAST(snapshotJson AS BLOB)) > 1500000;

UPDATE context_operations
SET snapshotJson = NULL
WHERE snapshotJson IS NOT NULL AND LENGTH(CAST(snapshotJson AS BLOB)) > 1500000;
