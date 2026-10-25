-- 修 74 号迁移的缺陷：`74_add_provider_custom_icon.sql` 用 `ADD COLUMN customIcon TEXT`
-- 建出来的是可空列，而 Entity 里 customIcon 是非空 String，Room 的表结构校验不通过，
-- App 一启动、打开数据库就崩（v2.39.0 报障现场）：
--     IllegalStateException: Migration didn't properly handle: ai_providers(...AIProviderEntity)
--       Expected: customIcon notNull=true     Found: customIcon notNull=false
--
-- 74 已随 v2.39.0 发布，按「发布即冻结」不能回头改它，所以新增本迁移按 Entity 重建该表
-- （SQLite 改不了列约束，只能重建；不用 ALTER TABLE ... DROP/ALTER COLUMN，那些要 3.35+，
--  Android 12 起才自带，低版本会直接失败）。表结构以 Room 导出的 schema 为准
-- （app/schemas/.../75.json）。旧库里 customIcon 为 NULL 的行一律补成空串。

CREATE TABLE IF NOT EXISTS `ai_providers_new` (
  `id` TEXT NOT NULL,
  `name` TEXT NOT NULL,
  `type` TEXT NOT NULL,
  `apiKey` TEXT NOT NULL,
  `multiKeyEnabled` INTEGER NOT NULL,
  `apiKeys` TEXT NOT NULL,
  `keyRotationStrategy` TEXT NOT NULL,
  `keyFailoverThreshold` INTEGER NOT NULL,
  `keyCooldownMinutes` INTEGER NOT NULL,
  `keySwitchStatusCodes` TEXT NOT NULL,
  `baseUrl` TEXT NOT NULL,
  `defaultModel` TEXT NOT NULL,
  `models` TEXT NOT NULL,
  `selectedModel` TEXT NOT NULL,
  `isEnabled` INTEGER NOT NULL,
  `useFullUrl` INTEGER NOT NULL,
  `useResponseApi` INTEGER NOT NULL,
  `anthropicCacheBreakpoints` INTEGER NOT NULL,
  `openaiChatCacheKey` INTEGER NOT NULL,
  `balanceScriptPath` TEXT NOT NULL,
  `balanceRefreshInterval` INTEGER NOT NULL,
  `customHeaders` TEXT NOT NULL,
  `sortOrder` INTEGER NOT NULL,
  `proxyEnabled` INTEGER NOT NULL,
  `proxyType` TEXT NOT NULL,
  `proxyHost` TEXT NOT NULL,
  `proxyPort` INTEGER NOT NULL,
  `proxyUsername` TEXT NOT NULL,
  `proxyPassword` TEXT NOT NULL,
  `scriptParams` TEXT NOT NULL,
  `customIcon` TEXT NOT NULL,
  PRIMARY KEY(`id`)
);

INSERT INTO `ai_providers_new` (
  `id`, `name`, `type`, `apiKey`, `multiKeyEnabled`, `apiKeys`, `keyRotationStrategy`,
  `keyFailoverThreshold`, `keyCooldownMinutes`, `keySwitchStatusCodes`, `baseUrl`, `defaultModel`,
  `models`, `selectedModel`, `isEnabled`, `useFullUrl`, `useResponseApi`,
  `anthropicCacheBreakpoints`, `openaiChatCacheKey`, `balanceScriptPath`, `balanceRefreshInterval`,
  `customHeaders`, `sortOrder`, `proxyEnabled`, `proxyType`, `proxyHost`, `proxyPort`,
  `proxyUsername`, `proxyPassword`, `scriptParams`, `customIcon`
)
SELECT
  `id`, `name`, `type`, `apiKey`, `multiKeyEnabled`, `apiKeys`, `keyRotationStrategy`,
  `keyFailoverThreshold`, `keyCooldownMinutes`, `keySwitchStatusCodes`, `baseUrl`, `defaultModel`,
  `models`, `selectedModel`, `isEnabled`, `useFullUrl`, `useResponseApi`,
  `anthropicCacheBreakpoints`, `openaiChatCacheKey`, `balanceScriptPath`, `balanceRefreshInterval`,
  `customHeaders`, `sortOrder`, `proxyEnabled`, `proxyType`, `proxyHost`, `proxyPort`,
  `proxyUsername`, `proxyPassword`, `scriptParams`, COALESCE(`customIcon`, '')
FROM `ai_providers`;

DROP TABLE `ai_providers`;

ALTER TABLE `ai_providers_new` RENAME TO `ai_providers`;
