# ADR 0004：信封加密与每租户数据密钥（KeyProvider 缝）

日期：2026-10-31

状态：已接受

## 背景

E03-T02 验收：每租户独立数据密钥，明文密钥不落盘。技术方案第 4 章定死密钥分级：KMS 每院一个主密钥做信封加密，按用途的数据密钥以**密文**存租户库，运行时解密后只在内存缓存。但仓库没有华为云账号（E01-T12，人负责），真实 DEW/KMS 适配器无法实现与验证。

## 决定

1. **`KeyProvider` 是主密钥操作的唯一缝**：`generateDataKey()` 返回（包装后的密钥、明文密钥），`unwrap(wrapped)` 解开。信封内层完全复用 E03-T01 的密文格式与按算法分派。
   - `LocalEnvelopeKeyProvider`：local/dev 用。主密钥从 `crdp.crypto.local-key` 派生（进程内），数据密钥随机生成、用主密钥 AES-GCM 包装。**每院的数据密钥各自独立随机**，即使主密钥相同。生产剖面继续被 `ProductionSafetyCheck` 拒绝 `crypto.mode=local`。
   - `KmsKeyProvider`：占位，调用即 50100（沿用 M0 `KmsCryptoService` 的做法）。云账号就绪后在此接华为云 DEW（generateDataKey / decrypt），本 ADR 不改接口。
2. **数据密钥存租户库表 `crypto_data_key`（迁移 V4）**：`(purpose, key_version)` 唯一，只存包装后的密钥。解密时按密文里的 `keyVersion` 取对应版本——为 E03-T05 轮换（旧版本保留解密）预留，本期只会有版本 1。
3. **`TenantDataKeyService` 负责按租户解析数据密钥**：内存缓存 `租户:用途:版本 → SecretKey`（明文只在内存）；缺当前版本则生成并落库，唯一键冲突即并发竞争，重查用既有行；查不到指定版本明确 40002。
4. **`EnvelopeCryptoService` 取代 `LocalCryptoService` / `KmsCryptoService`**：加密、解密、盲索引一律走数据密钥（AES-256-GCM / HMAC-SHA256）；密文格式、按算法分派、按密钥版本选钥沿用 E03-T01。 kms 模式下首次密钥操作会因 `KmsKeyProvider` 抛错，行为等价于原占位。

## 后果

- `LocalCryptoService`、`KmsCryptoService` 及其按配置分支选择实现的写法删除；`CryptoConfiguration` 改为组装 KeyProvider + EnvelopeCryptoService。
- 加密首次调用会触发一次数据密钥的生成与落库（按租户按用途），因此**要求租户上下文存在**——与"无租户上下文不得碰库"一致。
- TypeHandler 透明加解密（E03-T03）、盲索引规范化（E03-T04）、轮换与重加密作业（E03-T05）都在本结构上叠加。
