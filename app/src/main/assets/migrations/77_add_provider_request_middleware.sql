-- 声明式请求中间件：供应商级的 JSON 配置（模型映射 / 参数覆盖 / 系统提示注入）。
-- 空串表示不启用。带 NOT NULL DEFAULT ''，与实体里的非空 String 对齐（74 号迁移的教训）。

ALTER TABLE ai_providers ADD COLUMN requestMiddleware TEXT NOT NULL DEFAULT '';
