-- 网络备份目标（WebDAV / S3 对象存储）：一个目标一行，凭据列由 Android Keystore 加密后落库。
-- 全新建表，无历史行，故可自由摆放列；Room 建表 DDL 与本脚本保持一致（列名按属性名，仅 created_at 用下划线）。
CREATE TABLE IF NOT EXISTS `backup_targets` (
    `id` TEXT NOT NULL,
    `name` TEXT NOT NULL,
    `type` TEXT NOT NULL,
    `endpoint` TEXT NOT NULL,
    `basePath` TEXT NOT NULL,
    `username` TEXT NOT NULL,
    `password` TEXT NOT NULL,
    `accessKey` TEXT NOT NULL,
    `secretKey` TEXT NOT NULL,
    `sessionToken` TEXT NOT NULL,
    `bucket` TEXT NOT NULL,
    `region` TEXT NOT NULL,
    `pathStyle` INTEGER NOT NULL,
    `created_at` INTEGER NOT NULL,
    PRIMARY KEY(`id`)
);
