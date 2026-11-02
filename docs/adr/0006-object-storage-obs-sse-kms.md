# ADR 0006：业务文件对象存储端口（OBS SSE-KMS、按租户前缀隔离）

- 状态：已接受（E10-T02 / C-NFR-003）
- 日期：2026-11-02
- 关联：`docs/requirements/tech-plan.md` §2（OBS Java SDK、SSE-KMS、修订「每院前缀隔离」）、`docs/requirements/hospital-c-spec.md` §11 C-NFR-003、ADR 0005（审计归档桶与本业务桶分离）、OQ-56（暂存 LONGBLOB 的收敛口径）

## 背景与决策

E10-T02 把 E10-T01 暂存在 `sys_file.body`（LONGBLOB）的文件体迁到对象存储，并落地 C-NFR-003 的「对象存储加密」与任务验收的「按租户前缀隔离」。按 AGENTS §6，`ObjectStorage` 端口签名变更在本 ADR 立案。

### 1. 端口：相对键进出，前缀由端口强制

- `ObjectStorage` 从单一 `put` 扩展为 `put / get / delete`，删除占位实现 `UnsupportedObjectStorage`。
- 调用方只提供**相对键**（如 `files/202611/{uuid}.pdf`）；端口用 `TenantContext`（登录令牌解析，不信任请求参数）拼出完整键：
  - 医院租户上下文 → `t/{租户编码}/{相对键}`（tech-plan §2 修订：一个业务桶、每院一个**前缀**，不再每院一桶）；
  - 平台上下文 → `platform/{相对键}`（E08 导出迁桶随 E10-T05，此处预留）。
- 隔离是**构造性**的：相对键先过 `StorageKeys` 校验（非空、总长 ≤ 1024、不以 `/` 开头、不含 `\`、无 `..`/`.` 路径段、无控制字符；租户编码来自服务端会话，同样拒绝分隔符与 `..`），之后只能落在当前租户前缀下——调用方从 API 上无法寻址另一租户的对象。
- `put` 返回完整键（含前缀）入库（`sys_file.object_key`），`get`/`delete` 收相对键再自行加前缀；对象不存在 `get` 返回 `null`，`delete` 幂等。

### 2. 两个实现，按 `crdp.storage.type` 装配（默认 `local`）

- **`ObsObjectStorage`（生产，type=obs）**：华为云 OBS Java SDK（`esdk-obs-java`，tech-plan §2 的选型）。每次 PUT 强制 SSE-KMS（`SseKmsHeader`：encryption=`OBS_KMS`，可配 `kms-key-id` 指定密钥，留空用桶默认 KMS 密钥）——应用不接触加密密钥，明文不落盘，密钥轮换由 KMS 承担。适配器面向 `IObsClient` 编程，单测注入 Mockito 桩即可离线验证请求（桶、键、SSE 头）与 404 语义。
- **`LocalObjectStorage`（本机 / CI，type=local）**：文件系统替身，目录结构 `t/{租户}/…` 与 OBS 一致，保证同样的前缀隔离与双租户用例可离线跑；**不模拟 SSE 加密**（本地无 KMS，服务端加密是 OBS 的能力，替身只替「桶」不替「加密」）。
- 配置在 `crdp.storage.*`；OBS 的 endpoint / AK / SK / 桶名 / KMS 密钥 ID 只经环境变量注入（`CRDP_OBS_*`），不进仓库、不写日志；type=obs 时启动即校验必填项（fail-fast）。

### 3. 依赖取舍

- 引入 `com.huaweicloud:esdk-obs-java:3.26.9`（tech-plan §2 定的 SDK；签名与 SSE 头的正确性由华为侧保证，不自研 REST 签名）。
- **排除其声明的 log4j-core / log4j-api**：核查该版本 jar 内 `com/obs`、`com/oef` 全部类零引用 `org.apache.logging`（2026-11-02 以 3.26.9 反编译核对），属其 POM 的冗余声明；排除后避免与 logback 双日志栈。
- jackson 版本由 Spring Boot BOM 管理。

### 4. 上传写桶顺序与失败语义

- 顺序：校验（白名单 / 大小 / 真实类型）→ `PUT` 对象 → `INSERT sys_file` → 审计。`INSERT` 失败会留下**孤儿对象**（键含随机 UUID、台账无引用），反向顺序（先插行）失败则会留下无对象可取的 PENDING 台账行，更糟；孤儿对象的清理策略随 E10-T05 / E16 关联落点一并做。
- 存储侧任何失败统一转 50000（系统繁忙），不向调用方暴露 OBS 错误明细；日志只记 errorCode / requestId，不记 AK/SK 与文件内容。

### 5. 数据库收敛

- 租户库 followup V26：`sys_file` 删除 `body` LONGBLOB 列（E10-T01 迁移注释中预告的「迁桶并清理本列」），文件体只存对象存储；`object_key` 注释同步改为含租户前缀的完整键。V25 上线期间暂存的 dev 文件体随本迁移丢弃（无可下载路径，属开发数据）。
- OQ-56②的 `job_export.file_body`（平台库 V28 / 租户库 base V19）**不随本任务迁移**：它与短时签名链接同属下载链路，统一随 E10-T05 收敛（在 OQ-56 追加说明）。

## 后果

- 上传接口的响应、SHA-256 口径、权限与审计不变（E10-T01 契约不动）；`sys_file` 不再携带文件体，DB 容量与备份窗口与文件量解耦。
- 双租户隔离回归（`t/demoa` 与 `t/demob` 前缀互不可见）进 `LocalObjectStorageTest`；SSE-KMS 请求头进 `ObsObjectStorageTest`。
- 生产切 OBS 前必须由运维完成：建桶（多可用区、版本控制）、KMS 主密钥、AK/SK 注入（E22 / E01-T08 口径，不在本任务内）。
