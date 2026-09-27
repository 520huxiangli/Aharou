-- 删掉 ai_providers 上遗留的 userAgent 列。
--
-- 背景：迁移 40 用 `ALTER TABLE ai_providers ADD COLUMN userAgent ...` 加过该列（当时无条件执行、
-- 没有 IF NOT EXISTS），后来「提供商支持自定义请求头与脚本参数」用 customHeaders + scriptParams
-- 取代了它，但**当时只改了 Entity、没配套写删列迁移**。后果：凡旧库升级上来，表里都会多出
-- userAgent 这一列，而新版 Entity 不认识它，Room 的表结构校验（列集合严格比对）直接失败：
--     IllegalStateException: Migration didn't properly handle:
--       ai_providers(...)  Expected: ...(无 userAgent)  Found: ...(多 userAgent)
-- 即 App 一启动、打开数据库就崩。
--
-- 做法：按当前 Entity 重建该表再改名（而不是 ALTER TABLE ... DROP COLUMN ——
-- 后者要求 SQLite 3.35+，Android 12 起才自带，低版本设备会直接失败）。
-- 表结构以 Room 导出的 schema 为准（app/schemas/.../57.json）。

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
  PRIMARY KEY(`id`)
);

INSERT INTO `ai_providers_new` (
  `id`, `name`, `type`, `apiKey`, `multiKeyEnabled`, `apiKeys`, `keyRotationStrategy`,
  `keyFailoverThreshold`, `keyCooldownMinutes`, `keySwitchStatusCodes`, `baseUrl`, `defaultModel`,
  `models`, `selectedModel`, `isEnabled`, `useFullUrl`, `useResponseApi`,
  `anthropicCacheBreakpoints`, `openaiChatCacheKey`, `balanceScriptPath`, `balanceRefreshInterval`,
  `customHeaders`, `sortOrder`, `proxyEnabled`, `proxyType`, `proxyHost`, `proxyPort`,
  `proxyUsername`, `proxyPassword`, `scriptParams`
)
SELECT
  `id`, `name`, `type`, `apiKey`, `multiKeyEnabled`, `apiKeys`, `keyRotationStrategy`,
  `keyFailoverThreshold`, `keyCooldownMinutes`, `keySwitchStatusCodes`, `baseUrl`, `defaultModel`,
  `models`, `selectedModel`, `isEnabled`, `useFullUrl`, `useResponseApi`,
  `anthropicCacheBreakpoints`, `openaiChatCacheKey`, `balanceScriptPath`, `balanceRefreshInterval`,
  `customHeaders`, `sortOrder`, `proxyEnabled`, `proxyType`, `proxyHost`, `proxyPort`,
  `proxyUsername`, `proxyPassword`, `scriptParams`
FROM `ai_providers`;

DROP TABLE `ai_providers`;

ALTER TABLE `ai_providers_new` RENAME TO `ai_providers`;
