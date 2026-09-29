-- 统一 remote_connections.authType 的取值。
--
-- 背景：写入端（RemoteRepository.addConnection）存的是 "PASSWORD"/"PRIVATE_KEY"（大写），
-- 而实体默认值与配置通道用 "password"/"key"（小写），两边各按各的写法比较字面量。后果：
-- ActiveRemoteConnectionResolver 直读连接表，判断 `authType == "PASSWORD"` 对密钥连接不成立，
-- 解出空密码后仍构造 RemoteAuth.Password("")，密钥通道必然认证失败。
--
-- 做法：存量一律归一成小写（与实体默认值、配置通道一致）。`lower()` 覆盖任意大小写写法，
-- 再把历史别名 private_key 折成 key。

UPDATE `remote_connections` SET `authType` = lower(`authType`);
UPDATE `remote_connections` SET `authType` = 'key' WHERE `authType` = 'private_key';
