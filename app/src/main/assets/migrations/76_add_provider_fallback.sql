-- 供应商回退：本供应商请求失败（额度耗尽 / 鉴权 / 限流）时自动切到备用供应商继续本轮请求；
-- 两列都为空串表示不启用回退。
--
-- 两个 ADD COLUMN 必须带 NOT NULL DEFAULT ''：实体里 fallbackProviderId / fallbackModel 是非空
-- String，74 号迁移当初就是把 customIcon 建成了可空列，与实体约束不一致，Room 校验失败导致
-- 老库升级后启动即崩（详见 75 号迁移的注释）。

ALTER TABLE ai_providers ADD COLUMN fallbackProviderId TEXT NOT NULL DEFAULT '';

ALTER TABLE ai_providers ADD COLUMN fallbackModel TEXT NOT NULL DEFAULT '';
