# 接口契约总览（docs/api）

> 本目录给出每个后端模块的接口**契约草案**，供前后端并行开发。
> 只有两类接口来源确定：**仓库已有**（认证、菜单，见 `auth.md`）和**规格已定**（服务器草稿 `.../draft`，见技术方案「服务器自动保存与版本冲突」）。
> 其余路径、字段、错误码一律标注 **推断，待确认**：后端实现时可以调整，但必须同步改本目录和 `docs/design/pages.md`、`components.md` 中的引用，并在 PR 描述里写明。
> 实现以 springdoc 生成的 OpenAPI 为最终事实；前端 `frontend/packages/api` 的类型由 OpenAPI 生成（技术方案 §15 前端结构），不要手写重复类型。

## 1. 文件索引

| 文件 | 模块（后端 Maven 模块） | 主要需求编号 |
|---|---|---|
| `auth.md` | 登录、会话、菜单、权限码（crdp-server / crdp-framework） | PLT-AUTH-*、PLT-PERM-005/006 |
| `platform.md` | 平台端：租户、模块、菜单与接口资源、授权范围、平台账号、租户视角、平台配置（crdp-platform） | PLT-TEN、PLT-MOD、PLT-PERM、PLT-SCOPE、PLT-ROLE、PLT-VIEW、PLT-CFG |
| `system.md` | 医院端系统管理：院区科室病区、用户、角色、数据范围、字典、参数、个人中心（crdp-system） | PLT-ORG、A-WARD、C-WARD、PLT-CFG-002/004/008 |
| `form.md` | 表单引擎：字段字典、表单模板、校验与派生规则（crdp-form） | A-FLD、C-FLD |
| `patient.md` | 患者、基线分节、草稿、明文查看、时间轴、参数趋势（crdp-followup） | A-PAT、C-PAT、C-FU-012、NFR-SAVE-* |
| `implant.md` | 医院 C 植入记录、电极、当前器械、器械→计划映射（crdp-followup） | C-PAT-004..008、C-FU-003/006/007 |
| `followup.md` | 随访计划、入组、任务、随访记录、沟通记录、预警（crdp-followup） | A-FU、C-FU、A-ALR |
| `report.md` | 报告单 / 资料、文件、H5 上传、映射带入、全院报告单（crdp-followup + 文件服务） | A-ATT、C-SCAN |
| `notify.md` | 站内信、短信、通知规则、个人偏好、Webhook（crdp-system） | A-NTF、C-NTF、PLT-CFG-005..007 |
| `stats.md` | 首页卡片、统计页（crdp-followup） | A-DASH、C-DASH |
| `import-export.md` | Excel 导入、宽表导入、导出与审批（crdp-followup / crdp-system） | A-PAT-008/009/018、C-PAT-008/014、PLT-ORG-007 |
| `audit.md` | 操作日志、登录日志、数据访问日志、平台访问日志（两端） | PLT-AUD-* |

## 2. 通用约定

### 2.1 路径前缀与鉴权（仓库已有）

| 端 | 前缀 | 说明 |
|---|---|---|
| 医院端 | `/api/hospital/...` | Bearer token；token 解析后把请求绑定到该院租户库（`TenantContext.useTenant`） |
| 平台端 | `/api/platform/...` | Bearer token；只访问平台库（`TenantContext.usePlatform`） |
| H5 上传 | `/api/h5/upload/{token}/**`（E16-T10 已实现） | 一次性上传令牌（15 分钟，提交即消费），只能调上传相关接口（见 `report.md` §5）；会话上下文由 H5UploadTokenFilter 按令牌装载签发人实时会话 |

- 请求头：`Authorization: Bearer <token>`。登录接口与 `/actuator/health`、`/v3/api-docs/**`、`/swagger-ui/**` 免登录（`SecurityConfig`）。
- 响应头带 `X-Trace-Id`（CORS 已暴露）；响应体也带 `traceId`。
- 医院端接口按模块加 `@RequireModule("followup")` 等注解（未开通返回 40301），按权限码加 `@RequiresPermission("followup:patient:view")`（无权返回 40300）。**每个新接口都必须声明权限码**，并在平台库「接口资源」登记（PLT-PERM-004）。
- 业务模块 Controller 统一放在各自 Maven 模块的 `web` 包（推断，待确认；目前仓库只有 crdp-server 下的认证 Controller）。

### 2.2 统一响应（仓库已有 `ApiResponse`）

```json
{ "code": 0, "message": "ok", "data": { }, "traceId": "a1b2c3" }
```

- `code = 0` 成功；`data` 为空时字段省略（`@JsonInclude(NON_NULL)`）。
- 失败时 HTTP 状态码与业务码同时设置，前端以 `code` 为准。

### 2.3 错误码

| code | HTTP | 含义 | 来源 |
|---|---|---|---|
| 0 | 200 | 成功 | 仓库 |
| 40001 | 400 | 参数校验失败（`字段 原因；字段 原因`） | 仓库 `GlobalExceptionHandler` |
| 40100 | 401 | 未登录或登录已失效 | 仓库 `SecurityConfig` |
| 40101 | 401 | 租户、账号或密码不正确（三种情况同一句，防探测） | 仓库 `AuthService` |
| 40102 | 401 | 验证码错误或已过期（短信 / 图形验证码错误、过期、输错超限作废，同一句，防探测） | 仓库 `SmsCodeStore`、`CaptchaStore`（E04-T04、E04-T05） |
| 40103 | 401 | 回执回调未授权（短信状态回执公开口 Basic Auth 校验失败；缺头与凭据错误同一响应，不探测配置） | 仓库 `SmsReceiptController`（E22-T10） |
| 40300 | 403 | 没有权限（缺权限码） | 仓库 |
| 40301 | 403 | 模块未开通，请联系平台 | 仓库 `ModuleAuthorizationAspect` |
| 40302 | 403 | 查询受控数据但未声明数据范围（后端编程错误，测试中出现即为缺陷） | 仓库 `DataScopeDeniedException` |
| 50000 | 500 | 系统繁忙，请稍后再试 | 仓库 |
| 50002 | 500 | 租户数据源未就绪 | 仓库 `AuthService` |
| 50300 | 503 | 病毒扫描服务暂不可用，请稍后重试（上传同步扫描 fail closed，不放行；E22-T15 / 等保 D-01） | 仓库 `ClamdVirusScanner` |
| 40400 | 404 | 资源不存在**或不在数据范围内**（两者不区分） | 推断，待确认 |
| 40900 | 409 | 版本冲突（草稿 / 提交 / 保存修改），`data` 带冲突详情 | 规格已定 409；业务码推断，待确认 |
| 40901 | 409 | 业务状态不允许（如计划已发布不能改、记录已提交不能自动保存） | 推断，待确认 |
| 40902 | 409 | 唯一性冲突（病案号 / 病历号重复等），`data` 带已存在患者的脱敏摘要；随访编号（C 手填纸质原编号）重复同码，`message` 提示核对纸质原编号（E21-T10 / C-PAT-003） | 推断，待确认 |
| 42200 | 422 | 业务校验失败（表单规则、跨字段校验），`data.fieldErrors[]` | 推断，待确认 |
| 42900 | 429 | 尝试次数过多，请稍后再试（登录连续失败 5 次锁定 30 分钟，按「端 + 租户编码 + 账号」；`data` 带剩余锁定秒数） | 仓库 `LoginRateLimiter`（E04-T05） |
| 40303 | 403 | 租户视角只读：写 / 明文 / 导出与下载 / 原件预览一律拒绝（PLT-VIEW-012：新增、修改、删除与批量接口对视角令牌拒绝；导出、打印、下载按规则单独控制，默认禁止；文案区分「禁止写入」「禁止导出与下载」「原件不提供预览与下载」） | 仓库 `TenantViewGuardFilter`（E20-T03） |
| 40304 | 403 | 敏感操作需二次认证，请重新验证身份（PLT-AUTH-012：`@RequireReauth` 接口缺 / 过期 / 换会话的 `X-Reauth-Token`） | 仓库 `ReauthInterceptor`（E04-T08） |
| 40305 | 403 | 首次登录或密码过期，必须先修改密码（除改密 / 登出 / me 外的接口都返回此码） | 仓库 `MustChangePasswordFilter`（E04-T05） |
| 40306 | 403 | 宽限期只读，禁止写入（PLT-TEN-011/012：宽限期配置为只读的租户，医院端写请求一律拒绝；读与 `/auth/**` 照常） | 仓库 `GraceReadOnlyFilter`（E05-T03） |
| 40307 | 403 | 接口未登记或未绑定权限码，默认拒绝（PLT-PERM-004：`/api/**` 必须登记在平台库「接口资源」，公共或绑定权限码；已登记但两者皆无同样拒绝） | 仓库 `ApiResourceGuardFilter`（E05-T06） |

新增错误码时：在本表登记 → 后端常量 → 前端 `packages/api` 错误处理。前端统一处理规则见 `docs/design/pages.md` §0。

### 2.4 分页、排序、检索（推断，待确认）

- 简单列表用 `GET`，参数 `pageNo`（从 1 开始）、`pageSize`（默认 20，最大 200）、`sort=field,asc|desc`。
- 带高级检索条件树的列表用 `POST .../search`，body：
```json
{ "pageNo": 1, "pageSize": 20, "sort": [{"field": "dischargeDate", "order": "desc"}],
  "keyword": "...", "filters": {"wardIds": [1]}, "condition": { "logic": "AND", "items": [] } }
```
- 分页响应：`{ "items": [], "total": 123, "pageNo": 1, "pageSize": 20 }`（对齐 Vben 表格习惯）。
- 条件树结构与 `components.md` §11 `AdvancedSearch` 一致（`{logic: AND|OR, items: [行 | 组]}`，行 = `{field, op, value?}`，`op` ∈ EQ/NE/GT/LT/BETWEEN/CONTAINS/IS_NULL/NOT_NULL）；后端把字段码映射为白名单列，**禁止拼接任意列名**，取值全部走预编译参数位（E13-T06 患者列表已落地，A-NFR-009）。

### 2.5 数据范围（必须遵守）

- 所有读写患者及其下级数据（基线、记录、任务、报告单、沟通、植入）的查询都经过 `@DataScope` + `DataScopeInnerInterceptor`；**默认拒绝**：未声明范围直接 40302，空范围返回空结果。
- **启动扫描（E07-T01）**：`DataScopeStartupScanner` 在应用启动阶段遍历全部 MyBatis 语句，访问受控表的 SELECT 未声明 `@DataScope` / `@IgnoreScope` 时启动直接失败（`DataScopeUndeclaredException`），不等运行时才 40302；SQL 无法解析求值时同样拒绝。
- **受限范围改写（E07-T02）**：非「全部」的受限快照由 `RestrictedScopeRewriter` 用 JSqlParser 改写进 WHERE——患者表直接落 `(ward_id IN (..) OR dept_id IN (..) OR responsible_user_id = 本人)`；子表统一 `EXISTS (SELECT 1 FROM pt_patient crdp_p WHERE crdp_p.id = <别名>.patient_id AND crdp_p.deleted = 0 AND (..同上.. OR fu_task.assignee_id = 本人))`（任务表含负责人分支，A-WARD-003「责任医生或任务负责人为本人的患者」）。病区与科室两分支恒为 OR（病区字典不挂科室树下，医院已确认）。改写覆盖外层、UNION 分支、WITH 项与 FROM/JOIN/WHERE 子查询；覆盖不到的位置（如 SELECT 项标量子查询）拒绝执行，不放行。
- 快照口径（登录时冻结，多角色取并集，任一角色为 ALL 即不过滤，A-WARD-007）：WARD=本人负责病区（`sys_user_ward`）；DEPT=主科室（`sys_user.main_dept_id`）；RESPONSIBLE_DEPT=本人负责科室（`sys_user_dept_scope`）；CUSTOM_DEPT=角色自定义科室（`sys_role_scope_dept`，跨角色并集）；SELF / RESPONSIBLE_PATIENT=本人分支；只有 WARD 但未分配病区 → 空范围，看不到任何患者（A-WARD-003 边界情况）。
- **研究项目类型（E07-T03 / PLT-PERM-008）**：`RESEARCH_PROJECT` 为可分配的第 8 种范围类型，按项目成员制控制——底座提供扩展点 `ResearchProjectScopeProvider`（crdp-framework），登录装配快照时取「用户作为成员的研究项目」冻结进 `ScopeSnapshot.projectIds`；项目内角色与「项目 → 可见患者」的 SQL 规则由 rct 模块实现（首期不建，tech-plan「不做」清单）。底座默认注册空实现 `NoopResearchProjectScopeProvider`（恒空清单）：该分支不放宽任何数据，只有此类型的用户为空范围（看不到患者，默认拒绝）；快照带项目清单而改写分支未落地时，改写器拒绝执行（fail closed），不会静默少过滤（OQ-53）。
- 单条资源不在范围内：返回 **40400**，不返回 40300（不暴露存在性）。
- 列表、统计、导出、首页卡片使用同一套范围（A-WARD-004、C-WARD-005）。
- `@IgnoreScope` 只允许用于平台元数据或系统作业，代码评审必须说明理由；标在 Mapper 接口上可覆盖 MyBatis-Plus 注入的继承语句。

### 2.6 脱敏与明文

- 敏感字段（姓名、手机号、身份证号、地址等，以字段策略为准）在**后端序列化时脱敏**；列表、详情、导出默认都返回脱敏值。
- 明文只能通过专门的明文接口按字段取（见 `patient.md` §5），每次写审计；前端只在内存保存 60 秒。
- 前端不得缓存明文到 IndexedDB / localStorage / 草稿。

### 2.7 审计

- 写操作（新建、修改、删除、状态变更、导出、下载、明文查看、覆盖他人版本）在**同一事务**写操作日志（`aud_log`）。
- 自动保存草稿不逐次写审计；提交、修改已提交记录、覆盖冲突版本写审计（技术方案「服务器自动保存与版本冲突」）。
- 审计内容不记录明文敏感值，只记字段码与“已脱敏的前后值”或摘要。

### 2.8 幂等、并发与时间

- 有版本号的资源（随访记录、基线分节、计划）请求带 `baseVersion` / `version`，不一致返回 409。
- 创建类接口支持请求头 `Idempotency-Key`（推断，待确认），用于防止重复点击与弱网重发（H5 上传尤其需要）。
- 时间统一 ISO-8601 带时区（`2026-10-31T14:05:00+08:00`）；日期字段用 `YYYY-MM-DD`；展示时区 Asia/Shanghai。
- ID：仓库现有 `me.userId` 为数字；业务表若使用雪花等超过 2^53 的 ID，必须序列化为字符串，防止 JS 精度丢失（推断，待确认）。

### 2.9 功能开关

- 租户库 `cfg_feature(feature_code, enabled)`；关闭时：菜单不进授权范围、接口返回 40301（或 404，推断，待确认）、定时作业跳过（技术方案“各院功能不同怎么办”）。
- 已有开关码：`report`、`wide_import`、`node_follow_rate`、`scan_progress`、`implant`、`eri_cycle`、`compare_highlight`；推断新增：`param_trend`、`next_visit_as_plan`、`baseline_lock`、`report_inbox`（见 `components.md` §1.3）。

### 2.10 测试要求

- 每个接口至少：成功、无权限（40300）、模块未开通（40301）、越范围（40400）、跨租户（用 demoa 的 token 访问 demob 数据必须不可见）各一条集成测试（Testcontainers MySQL）。
- 涉及版本号的接口加 409 用例；涉及明文 / 导出 / 下载的接口断言审计已写入。
