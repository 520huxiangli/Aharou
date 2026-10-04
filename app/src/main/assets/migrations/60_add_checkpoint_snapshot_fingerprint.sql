-- 给检查点文件快照加「AI 写入后内容摘要」：回退前拿它和磁盘当前内容比对，
-- 判断文件是否被检查点之外的操作改过，避免回退时把别人的改动覆盖掉。
-- 旧数据该列为 NULL，此时不做冲突判定，行为与旧版一致。
ALTER TABLE checkpoint_file_snapshots ADD COLUMN writtenHash TEXT;
