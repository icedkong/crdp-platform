# 审计日志（audit）

> 后端：`crdp-framework`（审计切面 `@AuditLog` + 写入服务 `AuditLogger`，E08-T01 已落地；哈希链封存 / 验链 / 日志只读，E08-T02 已落地；按月分区与 OBS WORM 归档，E08-T03 已落地；写入成功事件 `AuditAppendedEvent` 与安全告警入口，E08-T05 已落地）、`crdp-system` / `crdp-platform`（查询，E08-T04）、`crdp-server`（安全告警 `SecurityAlertService`，E08-T05）。
> 表（E08-T01 起按规格字段写入，E08-T03 起按月分区）：租户库 `aud_log`（操作）、`aud_login_log`（登录）、`aud_data_access_log`（数据访问）；平台库 `plt_audit_log`（平台操作 / 租户控制面，含 `tenant_id` 供 PLT-TEN-022 按租户检索）、`aud_login_log`（平台账号登录 + 租户未识别的医院登录失败）、`aud_platform_access_log`（租户视角访问，仅平台库，医院端不可见）。
> 页面：`/system/audit`（租户）、`/platform/audit`（平台）；患者详情「操作日志」页签。
> 需求：PLT-AUD-001…012、PLT-VIEW（平台访问记录）。查询与导出路径已由 E08-T04 定稿（§2、§3）。

## 1. 写入（后端约定，所有模块都要遵守；E08-T01 已实现）

- **只追加**：审计表只有 INSERT；无 UPDATE / DELETE 接口。数据库层由只读触发器保证（E08-T02，迁移 V17 / V26）：任何账号对六张审计表 UPDATE / DELETE 直接报错 45000（PLT-AUD-011）。唯一逃生口是连接级会话变量 `@crdp_audit_maintenance`（仅测试自清理与运维清算使用，用完即清）；在线保留清理走分区维护（E08-T03，`DROP PARTITION` 不触发行级触发器）。
- 统一入口 `AuditLogger.append(AuditEntry)`，四类 `type`：`OPERATION | LOGIN | DATA_ACCESS | PLATFORM_ACCESS`。规格通用字段：操作者（`userId/userName/displayName/userType/roleCodes`）、`module`、`action`、`objectType/objectId`（不记患者明文）、描述、变更前后值（`before_json/after_json`，敏感值只存摘要）、`result(SUCCESS|DENIED|FAIL)`、`errorCode/reason`、`ip/userAgent`、`traceId`、`viewSessionId`；扩展字段按类型：登录（`login_method/tenant_identify_method/second_factor_result/fail_reason/session_id`）、数据访问（`access_method(VIEW_PLAIN|EXPORT|PRINT|DOWNLOAD|BATCH_QUERY)/record_count/query_digest/file_hash`）、平台访问（`page` 及数据访问扩展）。操作者与请求上下文（IP 取 X-Forwarded-For 首段、终端、traceId）缺省由写入服务自动补全。
- 声明式操作审计：app 层方法标 `@AuditLog(module, action, objectType, objectId="#id")`，切面按返回 / 异常写 `SUCCESS/DENIED/FAIL`（40300/40301/40302/40303/40304/40306 记 DENIED）；登录、数据访问、平台访问走编程式 `append`。
- 兜底：`GlobalExceptionHandler` 与 `ApiResourceGuardFilter`（40300）、`GraceReadOnlyFilter`（40306）对已登录会话的拒绝请求补写 `result=DENIED` 的操作日志（描述含方法与路径）；匿名请求与 40101 不写。
- **防篡改（PLT-AUD-008，E08-T02 已实现）**：每条写入在独立新事务内封存 `hash = SHA-256(prev_hash + 规范化内容)`（经密码服务组件 `DigestService`，NFR-CRY-006）。链的定义：每表独立一条链，按主键升序、只计已封存行，首条封存行 `prev_hash` 为 64 个 0（GENESIS）；E08-T01 遗留的未封存行（hash NULL）是链前缀。`id` 与 `created_at` 不参与哈希（前者 INSERT 前不可知，后者由数据库时钟填充），位置完整性由「prev_hash 衔接 + 主键序」保证。并发写入由 `aud_chain_head` 链头行锁（`SELECT ... FOR UPDATE`）按表串行化，避免分叉。
- **完整性校验（PLT-AUD-009，E08-T02 已实现）**：验链作业（每日 03:40 + 启动补跑，`AuditChainVerifier`）逐表逐行重算哈希并核对衔接，篡改内容 → `BAD_HASH`；删行 / 调序 → `BROKEN_LINK`；链开始后直插未封存行 → `MISSING_HASH`。结果按「租户 × 表」追加写平台库 `aud_chain_check`（PASS / BROKEN + 首个断点定位），断链即 ERROR 告警并让该租户本趟作业 FAILED（站内信通道随 E08-T05 / E09 接入）。E08-T03 起验链支持**归档桥接**：删分区产生的「归档洞」按 `aud_archive.last_hash` 登记（对象 WORM 锁定）桥接为合法衔接，登记查无仍报 `BROKEN_LINK`。
- **留存与归档（PLT-AUD-010，E08-T03 已实现）**：六张审计表 `RANGE(TO_DAYS(created_at))` 按月分区（主键扩为 `(id, created_at)`），静态建到 2027-12 + `pmax`，未来月份由归档作业 `REORGANIZE pmax` 预建（本月 + 3，幂等）。归档作业（每日 04:10 + 启动补跑，`AuditArchiveService` 按租户扇出后跑平台库）：上界早于「今天 − 12 个月」的分区导出**压缩 JSONL + 清单**（行数、首尾序号、首尾链值、摘要），**单次 PUT** 写入审计归档 WORM 桶并设**对象级保留 = 归档日 + 15 年**，读回校验通过后登记 `aud_archive`（只追加）再 `DROP PARTITION`；空到期分区登记行数 0 后删除。失败安全：上传 / 校验失败分区保留在线（作业 FAILED）；登记后中断可凭对象摘要续跑删除；同键不同内容 → CONFLICT 转人工。对象键 `audit/tenant/{租户编码}/{表}/{yyyy-MM}.jsonl.gz` / `.manifest.json`，**只写北京四一份**（不做异地副本，已确认）。桶级 WORM、生命周期（30 天归档 / 180 天深度归档）与归档专用账号由运维落地（E01-T08 / E22-T08，ADR 0005）；本机 / CI 无云凭证时作业只做分区维护。更早数据的按需取回与恢复查询随 E08-T04 及后续查询任务。
- 必须记录的事件：增删改、配置 / 权限 / 状态变更（001）；登录成功失败、登出、锁定（连续失败达阈值）、解锁、强制下线（002，落登录日志 `action=LOGIN/LOGOUT/LOCK/UNLOCK/KICK`）；查看明文、导出、打印、下载、批量查询（003）；平台租户视角的全部访问与被拒操作（004，仅平台库）。令牌刷新异常（002）随会话链路任务接入。配置模板导出 / 下载（A-FLD-014，E12-T01）落平台库操作日志 `CONFIG_TEMPLATE_EXPORT` / `CONFIG_TEMPLATE_DOWNLOAD`（objectType=`plt_config_template`，含包 SHA-256；导出描述含用户引用列置空计数）；模板应用到目标租户（E12-T02）落 `CONFIG_TEMPLATE_APPLY`（tenantId=目标租户，含包哈希与分表插入 / 合并 / 跳过 / 丢弃计数）；敏感信息拦截（E12-T03，验收「含敏感信息的包被拦截」）：导出自检或应用前检查命中 `SensitiveConfigChecker` 规则时，同一事件名写 FAIL 审计（errorCode=40901，reason 只含表名 / 规则 / 计数摘要，不含数据明文）。
- 写入失败：先重试一次；仍失败时登录、平台访问与敏感数据访问（明文 / 导出 / 打印 / 下载）整体失败返回 **50000**；普通操作与批量查询只记 ERROR 告警、业务不回滚。
- 留存：在线 1 年后转归档（PLT-AUD-010）。
- 运维注意：只读触发器要求迁移账号能建触发器——binlog 开启时非 SUPER 账号需 `log_bin_trust_function_creators=1`（MySQL 1419）。本地 / CI 的 `docker-compose.yml` 已用 `--skip-log-bin` 关闭 binlog；生产由 RDS 参数组处置（运维 E22）。

## 2. 租户查询与导出（E08-T04 已实现）

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| POST | `/api/hospital/system/audit/{type}/search` | `tenant:audit:view` | `type = operation\|login\|data-access`；条件体：`startTime/endTime`（ISO-8601 带时区，必填，跨度 ≤ 3 个月，超出 40001）、`userId/userName/module/action/objectType/objectId/result/ip/traceId`（可选）、`pageNo/pageSize`（PLT-AUD-005） |
| GET | `/api/hospital/system/audit/{type}/{id}` | `tenant:audit:view` | 详情：前后值（敏感值为摘要）、`prevHash/hash`、`traceLinks[]`（同 traceId 其他日志，PLT-AUD-006） |
| GET | `/api/hospital/followup/patients/{id}/logs` | `followup:patient:view` | 患者「操作日志」页签：`object_type='PATIENT' AND object_id={id}` 的操作 + 数据访问日志（只读；患者模块随 E13 以该口径写入） |
| POST | `/api/hospital/system/audit/exports` | `tenant:audit:export` + 二次认证（`X-Reauth-Token`，E04-T08；缺头 40304） | `{ type, condition（与查询同构）, reason }` → `{ exportId, status }`；租户管理员（角色含 TENANT_ADMIN）免审批直接 `GENERATING`，普通用户 `PENDING_APPROVAL`（PLT-AUD-007 已确认口径） |
| GET | `/api/hospital/system/audit/exports` | `tenant:audit:export` | 我的导出（状态、行数、文件哈希、下载截止） |
| GET | `/api/hospital/system/export-approvals` | `tenant:audit:export:approve` | 待审批列表（租户管理员，import-export.md §3） |
| POST | `/api/hospital/system/export-approvals/{id}/approve` · `/reject` | `tenant:audit:export:approve` | `{ comment }`；非待审批状态再操作 40901 |
| GET | `/api/hospital/system/audit/exports/{id}/download` | `tenant:audit:export` + 二次认证（`X-Reauth-Token`，E04-T08；缺头 40304） | 仅申请人；未审批 / 生成中 / 已结束 40901，超 24 小时 40901；返回 CSV 附件（UTF-8 BOM，首列水印） |

导出实现（暂行做法见 OQ-56）：条件在提交时校验并 JSON 固化到 `job_export.condition_json`（base V19），审批通过后按固化条件重放生成；文件为带水印列的 CSV，行数上限 50 000（超出文件尾附截断提示），文件体暂存 `job_export.file_body`（LONGBLOB，M1 单实例，E10 对象存储落地后迁走），24 小时后过期。审计点：提交 `DATA_ACCESS/EXPORT`、审批 `OPERATION/AUDIT_EXPORT_APPROVE|REJECT`（前后值 = 状态迁移）、每次下载 `DATA_ACCESS/DOWNLOAD`（含文件哈希）。

租户侧**看不到**平台访问记录（PLT-AUD-004 仅存平台侧）。

## 3. 平台查询与导出（E08-T04 已实现）

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| POST | `/api/platform/audit/{type}/search` | `platform:audit:view` | `type = operation\|login\|tenant-view\|view-rule\|control-plane`；页签映射：operation → `plt_audit_log` 全量、control-plane → `plt_audit_log AND tenant_id IS NOT NULL`（租户控制面，PLT-TEN-022）、login → 平台库 `aud_login_log`、tenant-view → `aud_platform_access_log` 全量、view-rule → `aud_platform_access_log AND action LIKE 'VIEW_RULE%'`（写入侧随 E20-T01）；条件同 §2 另加 `tenantId` |
| GET | `/api/platform/audit/{type}/{id}` | `platform:audit:view` | 详情；同 traceId 关联（跨平台库三张表） |
| GET | `/api/platform/audit/integrity?tenantId=` | `platform:audit:view` | 最近一次哈希链校验结果（`aud_chain_check` 按表取最新；`tenantId` 为空 = 平台库自身，PLT-AUD-009） |
| POST | `/api/platform/audit/exports` | `platform:audit:export` + 二次认证（`X-Reauth-Token`，E04-T08；缺头 40304） | 平台账号导出一律 `PENDING_APPROVAL`，由**另一名**平台管理员审批（申请人自审 40901，PLT-AUD-007 已确认） |
| GET | `/api/platform/audit/exports` | `platform:audit:export` | 我的导出 |
| GET | `/api/platform/audit/export-approvals` | `platform:audit:export:approve` | 待审批列表 |
| POST | `/api/platform/audit/export-approvals/{id}/approve` · `/reject` | `platform:audit:export:approve` | `{ comment }`；申请人不能审批自己的申请（40901） |
| GET | `/api/platform/audit/exports/{id}/download` | `platform:audit:export` + 二次认证（`X-Reauth-Token`，E04-T08；缺头 40304） | 仅申请人；限 24 小时；CSV 附件（水印 = `姓名（账号）· 平台审计中心 · 时间`） |

平台侧导出作业表在平台库（V28，含 `tenant_id` 列）；提交 `DATA_ACCESS/EXPORT`、审批 `OPERATION`、下载 `DATA_ACCESS/DOWNLOAD` 均按平台上下文落 `aud_platform_access_log`（PLT-AUD-004 仅存平台侧）。水印记申请人（导出人），不是审批人。

安全告警（PLT-AUD-012，P1，E08-T05 已实现）：实时评估每条写入成功的审计记录（`JdbcAuditLogger` 写入成功即发 `AuditAppendedEvent`，同步、保留租户上下文），命中规则就在平台库 `plt_security_alert`（V30）按「接收人 × 触发」落站内信派发行（channel=INBOX、send_status=PENDING），规则与暂行口径见 OQ-58：

| 规则 | 触发（审计记录口径，且 result=SUCCESS） | 说明 |
|---|---|---|
| `ABNORMAL_LOGIN` 异常登录 | 登录日志 `action=LOCK` | 连续失败达到阈值锁定（E04-T05，一次锁定只告警一次） |
| `BATCH_EXPORT` 批量导出 | 数据访问 `access_method=EXPORT` | 导出即成批数据离站，一律告警；后续患者导出（E13）等自动覆盖 |
| `PERMISSION_CHANGE` 权限变更 | 操作日志 event_type / action 前缀 `ROLE_` | 角色、菜单授权、成员、字段策略调整 |
| `PLATFORM_ACCESS` 平台访问 | 平台访问日志 action 前缀 `VIEW_` | 进入租户视角与视角规则变更；写入侧随 E20-T01，规则已就绪 |
| `PLAINTEXT_ABUSE` 明文查看滥用 | 数据访问 `VIEW_PLAIN` 且 `result=DENIED`、`reason=RATE_LIMITED` | 患者明文接口频控拒绝（每分钟 30 次上限，patient.md §5）——「只看 SUCCESS」的唯一例外，被拒本身就是信号（E07-T05） |

接收人 = 全部启用的 PLATFORM_ADMIN（按租户指定平台负责人随 PLT-VIEW-006，回落口径同 OQ-44）；唯一键 `(rule_code, alert_key=traceId, recipient_id)` 同一触发不重发。站内信收件箱与 PENDING → SENT/FAILED 投递随 E09 通知域消费本表（`NotifySender` 仍为占位实现，与 `plt_tenant_expire_notice` 同一消费契约）。告警派发失败只记 ERROR 日志不阻断业务（事实源是审计日志，可对账）；断链告警（PLT-AUD-009）复用本通道，随 E09 一并触达。

## 4. 测试要点

- 对审计表执行 UPDATE / DELETE 在数据库层面失败。
- 篡改一条记录后，完整性校验能定位断点。
- 篡改检测矩阵（E08-T06，验收「篡改检测用例通过」）：六张审计表逐表「真实写入 → 篡改后一条 → 验链在该行报 `BAD_HASH`」（`AuditChainTamperMySqlIT`，真库）；对抗了解算法的攻击者的单元强化用例（`AuditChainTamperDetectionTest`）——重封攻击（改内容并按算法重算本条 hash，本行自洽）由后继行 `BROKEN_LINK` 检出、两行连同 prev/hash 整体对调至少一处断裂、直插编造 hash 行报 `BAD_HASH`、断点落在翻页第二批（批大小 500 之后）也要检出；篡改后验链作业把该租户本趟记 `FAILED`（`job_tenant_run`）并在 `aud_chain_check` 记 `BROKEN`（`/integrity` 数据源）。
- 查看明文、下载、导出各有一条 `DATA_ACCESS` 记录；被拒请求有 `DENIED` 记录（E07-T05 明文接口：成功 / 越范围 40400 / 频控 42900 三态各有断言，见 `PatientPlaintextIT`）。
- E04-T08：导出提交与下载（两端）缺 `X-Reauth-Token` 一律 40304，并按 §1 写 result=DENIED 操作日志（`ReauthFlowIT`）。
- 审计 `before/after` 中检索不到手机号、证件号明文。
- 组合查询（时间 / 模块 / 结果 / IP / traceId）与详情前后值、traceId 关联（E08-T04，`AuditQueryExportMySqlIT`）。
- 导出审批：租户管理员免审批、普通用户待审批（未审批下载 40901）、平台申请人不能自审（40901）、审批通过后异步生成并限时下载、导出 / 审批 / 下载全程记审计（验收原文：导出需审批，导出本身记审计）。
- E08-T05：触发规则后站内信告警（验收原文）——连续失败锁定 → `ABNORMAL_LOGIN`、导出提交 → `BATCH_EXPORT` 各自落 `plt_security_alert` 派发行（INBOX、PENDING、接收人含种子 `platform.admin`）；权限变更 `ROLE_*` 同口径，普通操作与被拒（DENIED / FAIL）不告警（`SecurityAlertMySqlIT`，真库端到端 + `SecurityAlertServiceTest` 规则矩阵）。
