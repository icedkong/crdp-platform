# 审计日志（audit）

> 后端：`crdp-framework`（审计切面 `@AuditLog` + 写入服务）、`crdp-system` / `crdp-platform`（查询）。表：`aud_operation_log`、`aud_login_log`、`aud_data_access_log`、`aud_platform_access_log`（仅平台库）。
> 页面：`/system/audit`（租户）、`/platform/audit`（平台）；患者详情「操作日志」页签。
> 需求：PLT-AUD-001…012、PLT-VIEW（平台访问记录）。路径 **推断，待确认**。

## 1. 写入（后端约定，所有模块都要遵守）

- **只追加**：审计表只有 INSERT；无 UPDATE / DELETE 接口，应用账号对审计表只授 INSERT、SELECT（PLT-AUD-011）。
- 每条：`id, tenantId, userId, userName, roleCodes, type(OPERATION|LOGIN|DATA_ACCESS|PLATFORM_ACCESS), module, action, objectType, objectId, result(SUCCESS|DENIED|FAIL), errorCode, ip, userAgent, traceId, before, after, reason, createdAt, prevHash, hash`。
- `before/after` 中敏感值只存摘要（如“已变更”标记或哈希），不存明文（PLT-AUD-006）。
- 防篡改：`hash = SHA-256(prevHash + 规范化内容)` 哈希链，或写 WORM 存储（PLT-AUD-008）；定时校验断链告警（PLT-AUD-009，P1）。
- 必须记录的事件：增删改、配置 / 权限 / 状态变更（001）；登录成功失败、登出、锁定、解锁、强制下线、令牌异常（002）；查看明文、导出、打印、下载、批量查询（003）；平台租户视角的全部访问与被拒操作（004）。
- 被拒绝的请求（40300/40301/40302/40303）也要写 `result=DENIED`。
- 审计写入失败时：敏感操作（明文、导出、下载）整体失败返回 50000；普通操作记录告警但不回滚（推断，待确认）。
- 留存：在线 1 年后转归档（PLT-AUD-010）。

## 2. 租户查询（`tenant:audit:view` / `tenant:audit:export`）

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/hospital/system/audit/{type}/search` | `type = operation|login|data-access`；条件：时间、用户、模块、对象、结果、IP、traceId（PLT-AUD-005） |
| GET | `/api/hospital/system/audit/{type}/{id}` | 详情（前后值对比，PLT-AUD-006） |
| POST | `/api/hospital/system/audit/exports` | 异步导出，走 `import-export.md` §3 的审批（PLT-AUD-007）；导出本身也写审计 |
| GET | `/api/hospital/followup/patients/{id}/logs` | 患者「操作日志」页签：该患者相关的操作日志（只读） |

租户侧**看不到**平台访问记录（PLT-AUD-004 仅存平台侧）。

## 3. 平台查询（`platform:audit:view` / `export`）

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
