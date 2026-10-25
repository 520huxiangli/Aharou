-- 供应商自定义图标：存本地文件绝对路径或 http(s) 链接，空表示按品牌名匹配内置图标。
ALTER TABLE ai_providers ADD COLUMN customIcon TEXT;
