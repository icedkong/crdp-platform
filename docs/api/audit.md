# 审计日志（audit）

> 后端：`crdp-framework`（审计切面 `@AuditLog` + 写入服务 `AuditLogger`，E08-T01 已落地）、`crdp-system` / `crdp-platform`（查询，E08-T04）。
> 表（E08-T01 起按规格字段写入）：租户库 `aud_log`（操作）、`aud_login_log`（登录）、`aud_data_access_log`（数据访问）；平台库 `plt_audit_log`（平台操作 / 租户控制面，含 `tenant_id` 供 PLT-TEN-022 按租户检索）、`aud_login_log`（平台账号登录 + 租户未识别的医院登录失败）、`aud_platform_access_log`（租户视角访问，仅平台库，医院端不可见）。
> 页面：`/system/audit`（租户）、`/platform/audit`（平台）；患者详情「操作日志」页签。
> 需求：PLT-AUD-001…012、PLT-VIEW（平台访问记录）。查询路径 **推断，待确认**（E08-T04 定稿）。

## 1. 写入（后端约定，所有模块都要遵守；E08-T01 已实现）

- **只追加**：审计表只有 INSERT；无 UPDATE / DELETE 接口，应用账号对审计表只授 INSERT、SELECT（PLT-AUD-011；收紧数据库授权随 E08-T02「日志只读」落地）。
- 统一入口 `AuditLogger.append(AuditEntry)`，四类 `type`：`OPERATION | LOGIN | DATA_ACCESS | PLATFORM_ACCESS`。规格通用字段：操作者（`userId/userName/displayName/userType/roleCodes`）、`module`、`action`、`objectType/objectId`（不记患者明文）、描述、变更前后值（`before_json/after_json`，敏感值只存摘要）、`result(SUCCESS|DENIED|FAIL)`、`errorCode/reason`、`ip/userAgent`、`traceId`、`viewSessionId`；扩展字段按类型：登录（`login_method/tenant_identify_method/second_factor_result/fail_reason/session_id`）、数据访问（`access_method(VIEW_PLAIN|EXPORT|PRINT|DOWNLOAD|BATCH_QUERY)/record_count/query_digest/file_hash`）、平台访问（`page` 及数据访问扩展）。操作者与请求上下文（IP 取 X-Forwarded-For 首段、终端、traceId）缺省由写入服务自动补全。
- 声明式操作审计：app 层方法标 `@AuditLog(module, action, objectType, objectId="#id")`，切面按返回 / 异常写 `SUCCESS/DENIED/FAIL`（40300/40301/40302/40303/40304/40306 记 DENIED）；登录、数据访问、平台访问走编程式 `append`。
- 兜底：`GlobalExceptionHandler` 与 `ApiResourceGuardFilter`（40300）、`GraceReadOnlyFilter`（40306）对已登录会话的拒绝请求补写 `result=DENIED` 的操作日志（描述含方法与路径）；匿名请求与 40101 不写。
- 防篡改：表已含 `prev_hash/hash` 列，E08-T02 计算 `hash = SHA-256(prevHash + 规范化内容)` 哈希链并定时校验断链告警（PLT-AUD-008/009）。
- 必须记录的事件：增删改、配置 / 权限 / 状态变更（001）；登录成功失败、登出、锁定（连续失败达阈值）、解锁、强制下线（002，落登录日志 `action=LOGIN/LOGOUT/LOCK/UNLOCK/KICK`）；查看明文、导出、打印、下载、批量查询（003）；平台租户视角的全部访问与被拒操作（004，仅平台库）。令牌刷新异常（002）随会话链路任务接入。
- 写入失败：先重试一次；仍失败时登录、平台访问与敏感数据访问（明文 / 导出 / 打印 / 下载）整体失败返回 **50000**；普通操作与批量查询只记 ERROR 告警、业务不回滚。
- 留存：在线 1 年后转归档（PLT-AUD-010）。

## 2. 租户查询（`tenant:audit:view` / `tenant:audit:export`；E08-T04 实现）

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/hospital/system/audit/{type}/search` | `type = operation|login|data-access`；条件：时间、用户、模块、对象、结果、IP、traceId（PLT-AUD-005） |
| GET | `/api/hospital/system/audit/{type}/{id}` | 详情（前后值对比，PLT-AUD-006） |
| POST | `/api/hospital/system/audit/exports` | 异步导出，走 `import-export.md` §3 的审批（PLT-AUD-007）；导出本身也写审计 |
| GET | `/api/hospital/followup/patients/{id}/logs` | 患者「操作日志」页签：该患者相关的操作日志（只读） |

租户侧**看不到**平台访问记录（PLT-AUD-004 仅存平台侧）。

## 3. 平台查询（`platform:audit:view` / `export`；E08-T04 实现）

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/platform/audit/{type}/search` | `type = operation|login|tenant-view|view-rule|control-plane`，可按租户筛选 |
| GET | `/api/platform/audit/{type}/{id}` | 详情；同 traceId 关联 |
| POST | `/api/platform/audit/exports` | 平台账号导出由平台管理员审批 |
| GET | `/api/platform/audit/integrity?tenantId=` | 最近一次哈希链校验结果（P1） |

安全告警（PLT-AUD-012，P1）：异常登录、批量导出、权限变更、平台访问实时告警，经 outbox 发站内信给平台管理员。

## 4. 测试要点

- 对审计表执行 UPDATE / DELETE 在数据库层面失败。
- 篡改一条记录后，完整性校验能定位断点。
- 查看明文、下载、导出各有一条 `DATA_ACCESS` 记录；被拒请求有 `DENIED` 记录。
- 审计 `before/after` 中检索不到手机号、证件号明文。
