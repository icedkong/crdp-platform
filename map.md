# map.md — 临床科研数据一体化平台（crdp）站点地图与核心文件注释

> 生成时间：2026-11-16。依据仓库当前代码实况（backend 6 模块 + frontend pnpm workspace + db/docs/scripts/iac）。版本号、接口前缀均以代码与迁移脚本为准。

## 0. 如何读这份地图

- **第 1 节**先建立全局认知：系统是什么、技术栈、三库结构。新人从这里开始。
- **第 2 节功能模块地图**按业务域回答"某功能在哪儿"：前端路由 + 后端接口前缀一表可查。
- **第 3 节 REST 接口总表**按控制器粒度列全部 80 个控制器的路径前缀与职责，找接口定义先查这张表再进代码。
- **第 4 节目录树注释**是本文主体：从仓库根展开到包/目录级，核心文件单独一行。后端 Java 路径约定简写 `src/main/java/com/crdp/` → `…`（如 `crdp-followup/…/followup/patient/api/`），测试同理（`src/test/java/com/crdp/`）。
- **第 5、6 节**回答"测试在哪儿、怎么跑起来"。
- 与本文相关的权威文档：`AGENTS.md`（编码规则）、`README.md`(快速启动)、`docs/README.md`（文档索引）、`docs/api/README.md`（接口通用约定与错误码）。冲突时以代码与 ADR 为准。

---

## 1. 系统概览

多租户医院随访 SaaS：平台方开通医院（租户），**每家医院一个独立数据库**（按院分库）；首批三家——医院 A（重症冠心病长期年度随访）、医院 B（A 的配置差异版）、医院 C（心脏起搏器植入与程控随访，含大量纸质资料补录与器械参数对比）。核心能力：患者档案、表单模板驱动的基线/随访录入、随访计划与任务、报告单归档（含手机拍照上传 H5）、站内/短信通知、统计工作台、审计，以及贯穿始终的数据范围管控、字段加密与脱敏。当前为**演示模式**：`demo` 剖面走本地 AES-256-GCM 信封加密 + 模拟短信 + 虚构种子数据；华为云 KMS / 腾讯云短信 / 生产 RDS·OBS 均未接入，**接入 KMS 前禁止导入真实患者数据**，`prod` 剖面启动即失败（ProductionSafetyCheck）。

### 技术栈

| 层 | 技术 |
| --- | --- |
| 后端 | Java 21、Spring Boot 3、Maven 多模块、MyBatis（Spring 就绪，非 MyBatis-Plus ORM）、Flyway、MySQL 8、Redis（会话/验证码） |
| 前端 | Vue 3、官方 Vben Admin 5.7（仅 Ant Design Vue，ADR 0002）、pnpm 10 workspace、Vite、Vitest |
| 手机 H5 | Vite 独立小应用（mobile-upload），无框架外壳 |
| 部署 | Docker（后端 18080 / 前端容器 8080）、华为云 IaC 草稿（Terraform：RDS/CCE/OBS）、GitHub Actions CI |

### 三库结构与 Flyway 版本线

- **平台库 `crdp_platform`**：租户、模块、菜单、平台账号、平台审计、视角规则等。脚本在 `db/platform/`（V1–**V74**）+ 种子 `db/platform/seed/`（V100–**V112**，local/dev/demo 才跑）。
- **租户库 `t_{租户编码}`**（表不带 tenant_id，本地演示建 `t_demoa`/`t_demob`，见 `deploy/mysql/init/`）：
  - `db/tenant/base/`：基础域（用户/组织/角色/字典/参数/病区/加密进度等），V1–**V45**；
  - `db/tenant/followup/`：随访域（患者/计划/任务/记录/报告/植入/通知/对比/导入/统计/工作台），V1–**V139**；
  - `db/tenant/seed/`：租户种子（演示账号、预置角色、A/B/C 预置计划与病区），V100–**V137**。
- 平台线与租户线版本号各自独立不重复，`scripts/check_flyway_versions.py` 在 CI 强制校验；脚本只新增不修改。新租户开通时由 `TenantSchemaMigrator` 对空库按 base→followup 顺序迁移。

---

## 2. 功能模块地图

| 模块 | 一句话功能 | 主要入口（前端路由 → 后端接口前缀） |
| --- | --- | --- |
| 租户开通与生命周期 | 平台端向导式开院：建租户→预置库→迁移→初始化，含暂停/恢复/到期/注销/配额 | web-platform `platform/tenant/*`（含 wizard/provision）→ `/api/platform/tenants/**`（`/provision`、`/status`、`/quota`）、`/tenants/{id}/cancel` |
| 认证与会话 | 租户编码+密码/短信登录、图形验证码、2FA、忘记密码、会话踢出、重认证（reauth） | web-hospital `_core/authentication` → `/api/hospital/auth/**`；平台端 → `/api/platform/auth/**` |
| 权限与数据范围 | 角色管理、权限码 `{模块}:{资源}:{动作}`、组织/病区数据范围（默认拒绝，越界 40400） | `system/role`、`system/user` → `/api/hospital/system/roles|users`；平台侧 `tenants/{id}/scope`、`/scope-template` |
| 加密脱敏与明文查看 | 敏感字段信封加密+盲索引；列表默认脱敏；明文=专用接口+权限+二次确认+审计，60 秒自动恢复 | `masked-field.vue`、`components/followup/masked-field` → `POST /api/hospital/followup/patients/{id}/plaintext`（框架 `crypto/`、`fieldpolicy/`） |
| 审计 | 增删改/登录/明文/导出/视角访问全量落审计表，哈希链防篡改，月度分区+OBS WORM 归档 | `system/audit`、平台 `platform/audit` → `/api/hospital/system/audit/**`、`/api/platform/audit/**` |
| 通知 | 事件→规则→收件人解析→站内信/短信/Webhook，摘要调度、发送日志与重试 | `followup/notice`、顶栏铃铛 → `/api/hospital/notify/**`、`/api/hospital/profile/notify-pref` |
| 文件与报告单 | 上传策略/查重/病毒扫描/缩略图、下载与预览签名 token、报告单录入归档、收件箱、水印预览 | `followup/report`、`report-inbox`、`@crdp/viewer` → `/api/hospital/files/**`、`/api/hospital/followup/reports|report-inbox|report-config` |
| 手机拍照上传 H5 | 电脑端生成一次性令牌二维码，手机扫码免登拍照上传报告单 | mobile-upload（`#/u/{token}`）→ `/api/h5/upload/**`；电脑端 `POST /followup/upload-tokens` |
| 表单引擎 | 字段字典+表单模板驱动渲染/校验/显示条件/派生/进度，版本发布与字段历史 | `followup/field`、`followup/record/form`、`@crdp/form-engine` → `/api/hospital/form/**`（fields、forms） |
| 患者档案 | 登记（访视号）、检索（高级条件树/保存筛选）、总览、基线分节编辑与锁、联系人、转科、状态流转、批量指派、删档 | `followup/patient/*` → `/api/hospital/followup/patients/**`（含 `/baseline`、`/lock`、`/plaintext`） |
| 随访计划 | 计划模板（节点/窗口/ERI 评估）版本化发布、入组暂停恢复、批量入组、计划切换、终止 | `followup/plan` → `/api/hospital/followup/plans/**`（enrollments、device-mapping） |
| 随访任务 | 出院登记触发生成任务、认领/开始/完成、临时任务、改期重算、失访确认、联系记录 | `followup/task` → `/api/hospital/followup/tasks/**`、`/patients/{id}/contacts` |
| 随访记录 | 按时间点录入表单、草稿（30s IndexedDB + 服务器草稿）、补录（backfill）、顺序号、下次随访建议 | `followup/record` → `/api/hospital/followup/records/**`、`/patients/{id}/records` |
| 导入导出 | 宽表/长表 Excel 患者导入（暂存→校验→提交→带批注报告）、5000 行级性能、脱敏样本工具、患者导出审批 | `followup/patient/import` → `/api/hospital/followup/imports/**`、`/exports` |
| 统计与工作台 | 首页看板（KPI/任务表/年度与节点随访率/完整度/病区切换）、卡片配置与角色布局、医生工作量、节点发生率、器械分布 | `views/dashboard`、`followup/stats` → `/api/hospital/followup/dashboard/**`、`/stats/**` |
| 平台视角（代查） | 平台账号签发 60s ticket 进入医院端视角会话（2h/空闲15min），横幅常驻+水印+一键退出+访问审计 | web-platform `platform/tenant/enter-view` + web-hospital `_core/view` → `/api/platform/tenant-view/sessions`、`/view-rules`、医院端 `/api/hospital/view/**` |
| 医院 C 器械链 | 植入/更换/取出登记、器械-计划映射、程控参数趋势、程控报告对比高亮、纸质补录（转录/数字化状态） | `implant-*` 组件、`param-trend`、`compare-*` → `/patients/{id}/implants`、`/implants/{id}/replace|explant`、`/compare-rules`、报告 `/mapping/preview|confirm`、`/digitize-status` |
| 响应式 | 仅 5 个关键页面适配平板/手机（名单 route-meta），其余 MobileBlocker 拦截；触控/键盘安全区 | `@crdp/responsive`（device-matrix 唯一来源）、各页 `responsive-logic.spec.ts` |
| 系统管理 | 用户（导入/重置/预览权限）、组织部门树、病区、字典、参数、品牌主题、Webhook、公告 | `system/*`、`system/settings` → `/api/hospital/system/**`、`/api/hospital/announcements` |

---

## 3. REST 接口总表（80 个控制器）

前缀约定：医院端 `/api/hospital/**`、平台端 `/api/platform/**`、手机 H5 `/api/h5/**`。统一响应 `{code,message,data,traceId}`，成功码 0，响应头 `X-Trace-Id`。

### /api/hospital/auth/**（认证，公开口在 SecurityConfig 白名单）

| 控制器 | 前缀 | 职责 |
| --- | --- | --- |
| HospitalAuthController（crdp-server/web） | `/api/hospital/auth` | 登录全家桶：`/login`、`/login/sms`、`/sms`、`/captcha`、`/2fa/verify`、`/password`（修改）、`/password/forgot|reset`、`/reauth`、`/logout`、`/me`、`/codes`（权限码）、`/features`（功能开关）、`/tenant`（租户品牌）、`/tenant-state`、`/sessions`（在线会话）；另有 `GET /api/hospital/menus` 动态菜单 |
| HospitalTenantStateController | `/api/hospital/auth/tenant-state` | 租户暂停/宽限/注销态对登录侧的只读查询（宽限期只读由 GraceReadOnlyFilter 执行） |

### /api/hospital/system/**（租户内系统管理）

| 控制器 | 前缀 | 职责 |
| --- | --- | --- |
| HospitalUserAdminController | `/api/hospital/system/users` | 用户 CRUD、`/import`、`/import-template`、重置口令、权限预览数据源 |
| HospitalRoleController | `/api/hospital/system/roles` | 角色 CRUD、权限/数据范围绑定 |
| HospitalOrgController | `/api/hospital/system/orgs`、`/depts` | 组织树、科室调整影响面（`/{id}/impact`） |
| HospitalDictController | `/api/hospital/system/dicts` | 租户字典（平台默认值+租户覆盖） |
| HospitalParamController | `/api/hospital/system/params` | 租户参数同上 |
| HospitalBrandController | `/api/hospital/system/brand` | 租户品牌（登录页/标题/logo）、`/theme-colors` 主题色 |
| HospitalWebhookController | `/api/hospital/system/webhooks` | Webhook 订阅配置 CRUD（SSRF 防护） |
| HospitalSystemSessionController | `/api/hospital/system/sessions` | 管理会话列表与 `/kick` 踢出 |
| HospitalAuditController | `/api/hospital/system/audit/{type}` | 审计检索（`/search`）、详情、CSV 导出；患者日志 `/followup/patients/{id}/logs` |
| HospitalProfileController | `/api/hospital/profile` | 个人资料、改密、绑手机（`/phone/sms`）、通知偏好 |
| HospitalAnnouncementController | `/api/hospital/announcements` | 平台公告对医院端的只读列表 |

### /api/hospital/followup/**（随访业务，crdp-followup）

| 控制器 | 前缀 | 职责 |
| --- | --- | --- |
| PatientRegistrationController | `…/patients` | 患者登记（访视号分配、入组） |
| PatientSearchController | `…/patients` | `POST /search` 条件树检索、`/filter-options`、`/search-fields` |
| SavedFilterController | `…/saved-filters` | 检索条件保存/调用的 CRUD |
| PatientOverviewController | `…/patients/{id}` | 患者总览（概要、随访时间线聚合） |
| PatientStatusController | `…/patients` | 状态流转（失访/死亡/恢复）、状态历史 |
| PatientContactController | `…/patients/{id}/contacts` | 联系人 CRUD 与版本 |
| PatientBatchController | `…/patients/batch-assign` | 批量指派医生 |
| PatientTransferController | `…/patients` | 转科/转病区（科室与病区历史） |
| PatientDeletionController | `…/patients` | 患者删除（审批与级联口径） |
| BaselineSectionController | `…/patients/{id}/baseline`、`/baseline/{patientId}/sections/{code}` | 基线分节读取、草稿与正式保存（乐观锁） |
| BaselineLockController | `…/patients/{id}/lock` | 基线整体锁定/解锁 |
| PatientPlaintextController | `…/patients/{id}/plaintext` | 明文字段专用口（权限+二次确认+限流+审计） |
| PlanController | `…/plans` | 计划 CRUD、`/publish` 版本发布、`/{id}/nodes` 节点编辑、enrollments（单个/批量/暂停/恢复/移除）、`/device-mapping`、终止 |
| FollowupTaskController | `…/tasks` | 任务列表/详情、`/claim`、`/start`、`/acknowledge`、临时任务、改期、`/confirm-lost`、`/copy`、批量操作、队列 |
| PatientRecordController | `…/patients/{id}/records` | 患者随访记录列表与详情 |
| RecordEntryController | `…/records` | 记录录入/编辑/删除、补录、下次随访建议 |
| RecordDraftController | `…/records` | 服务器草稿存取 |
| ImplantController | `…/patients/{id}/implants`、`/implants/{id}` | 植入登记与编辑、`/replace` 更换、`/explant` 取出、`/implants/options` 选项 |
| ReportController | `…/patients/{id}/reports`、`/reports` | 报告单 CRUD、`/batch-create`、位置管理 `/locations`、映射 `/mapping/preview|confirm`、数字化状态 `/digitize-status`、遗留位置迁移 |
| ReportSearchController | `…/reports` | 报告检索（条件树 POST search） |
| ReportCatalogController | `…/report-names`、`/report-types` | 报告名称/类型目录 |
| ReportConfigController | `…/report-config` | 报告类型-名称-位置模板配置 |
| ReportInboxController | `…/report-inbox` | 收件箱：`/search`、`/files`、`/{id}/archive` |
| MobileUploadController | `…/upload-tokens` + `/api/h5/upload/{token}` | 电脑端签发/查询一次性上传令牌；H5 侧 `…/context`（引导）、`…/files`（上传）、`…/submit`（提交） |
| ContactLogController | `…/patients/{id}/contacts`（联系记录） | 电话随访联系日志（结果/时长/录音范围守护） |
| WardController | `…/wards` | 病区 CRUD |
| CompareRuleController | `…/compare-rules` | 医院 C 程控对比规则 CRUD（差异高亮开关 `compare_highlight`） |
| DashboardCardController | `…/dashboard/cards/{code}` 等 | 工作台各统计卡片数据（KPI/任务/随访率/完整度/器械分布/异常/ERI/扫描进度） |
| DashboardLayoutController | `…/dashboard/layout`、`/user-pref`、`/role-layouts` | 看板布局与角色预设、用户偏好 |
| DoctorWorkloadStatsController | `…/stats/doctor-workload` | 医生工作量统计 |
| ImportJobController | `…/imports` | Excel 导入：`/template` 下载、创建任务、进度、校验报告、暂存提交 |
| PatientExportController（crdp-server/web） | `…/exports`、`/export-approvals` | 患者数据导出申请/审批/下载 |
| FollowupPingController（crdp-server/web） | `…/followup/ping` | followup 模块健康探针 |
| FileController | `/api/hospital/files` | 上传（`POST /`）、`/policy` 上传策略、`/preview-url` |
| FileDownloadController | `/api/hospital/files/{fileId}/download-url` + 公开 `/files/download/{token}` | 申请下载签名 token；凭 token 公开下载（token 一次性/短时效） |
| FilePreviewController | 公开 `/api/hospital/files/preview/{token}` | 预览签名 token 换流式预览（水印 viewer 用） |
| NoticeInboxController | `/api/hospital/notify/messages` | 站内信列表、未读数、已读 |
| NotifyRuleController | `/api/hospital/notify/rules`、`/sms-templates` | 事件→渠道通知规则、短信模板目录 |
| NotifySettingsController | `/api/hospital/notify/settings` | 租户级通知设置（静默期等） |
| NotifyPreferenceController | `/api/hospital/profile/notify-pref` | 个人通知偏好 |
| NotifySendLogController | `/api/hospital/notify/send-logs` | 发送日志检索与 `/retry` |
| HospitalViewController（crdp-server/web） | `/api/hospital/view` | 平台视角在医院端的进入（`/enter` 凭 ticket）、会话查询/一键退出 |

### /api/hospital/form/**（表单引擎，crdp-form）

| 控制器 | 前缀 | 职责 |
| --- | --- | --- |
| HospitalFormFieldController | `/api/hospital/form/fields` | 字段字典 CRUD、`/import`、`/import-template`、`/{key}/logs` 字段历史 |
| HospitalFormController | `/api/hospital/form/forms` | 表单模板目录、`/{code}` 详情、`/publish` 发布、`/{code}/progress` 进度计算 |

### /api/platform/**（平台端，crdp-server/web）

| 控制器 | 前缀 | 职责 |
| --- | --- | --- |
| PlatformAuthController | `/api/platform/auth` | 平台登录、`/captcha`、`/2fa/verify`、reauth |
| PlatformTenantController | `/api/platform/tenants` | 租户 CRUD、`/{id}/provision` 开通向导、`/retry`、`/status` 状态机、`/expire`、`/quota`、`/responsible-owners` |
| PlatformTenantCancelController | `/api/platform/tenants/{id}/cancel` | 注销流程（凭据/冷静期/数据销毁/证明 PDF） |
| PlatformTenantModuleController | `/api/platform/tenants/{id}/modules` | 租户模块开通与开关（`wide_import`、`implant` 等） |
| PlatformTenantScopeController | `/api/platform/tenants/{id}/scope` | 租户默认数据范围模板下发 |
| PlatformTenantViewController | `/api/platform/tenant-view/sessions` | 视角会话签发/列表/撤销、`/enter-ticket` |
| PlatformViewRuleController | `/api/platform/view-rules` | 视角规则（可见字段/菜单）版本化、评审、预览 |
| PlatformModuleController | `/api/platform/modules` | 模块注册表管理 |
| PlatformMenuController | `/api/platform/menus` | 平台菜单树管理（HOSPITAL/PLATFORM 双侧） |
| PlatformRoleController | `/api/platform/roles` | 平台角色与授权 |
| PlatformRoleTemplateController | `/api/platform/role-templates` | 预置角色模板（新租户初始化用） |
| PlatformScopeTemplateController | `/api/platform/scope-template` | 数据范围模板 |
| PlatformAccountController | `/api/platform/accounts` | 平台账号 CRUD |
| PlatformAnnouncementController | `/api/platform/announcements` | 平台公告发布 |
| PlatformDictController / PlatformParamController | `/api/platform/dicts`、`/params` | 平台级字典/参数默认值管理 |
| PlatformConfigTemplateController | `/api/platform/config-templates` | 租户配置包导出/导入（B 院复用 A 配置） |
| PlatformApiResourceController | `/api/platform/apis` | API 资源登记表（扫描器自动同步） |
| PlatformResourceLogController | `/api/platform/resource-logs` | 菜单/权限/开关等资源变更流水 |
| PlatformAuditController | `/api/platform/audit/{type}` | 平台审计检索、详情、`/integrity` 哈希链校验、导出 |

### 其他公开口 / 辅助口

| 控制器 | 路径 | 职责 |
| --- | --- | --- |
| MockSmsPageController | `GET /dev/mock-sms` | 演示剖面模拟短信收件箱 HTML 页（手机号脱敏） |
| SmsReceiptController | `POST /api/notify/sms/receipt` | 腾讯云短信回执回调（Basic Auth） |
| HospitalHelloController | `GET /api/hospital/hello` | M0 骨架连通性样例 |
| （框架）GlobalExceptionHandler | — | 统一异常→`{code,message,data,traceId}`，错误码表见 docs/api/README.md |
| （白名单）actuator | `/actuator/health|info`、swagger | 运维与 OpenAPI 文档 |

---

## 4. 目录树注释（核心部分）

约定：`…` = `src/main/java/com/crdp/`；`…test…` = `src/test/java/com/crdp/`。只列核心文件，`*Controller` 已在第 3 节覆盖的不再重复注文件行。

```
crdp-platform/
├── AGENTS.md — 编码代理说明：规则红线（租户隔离/数据范围/脱敏/审计/Flyway/开关/表单驱动/权限码/乐观锁）、命令、DoD
├── README.md — 项目自述：结构、本地启动、演示账号、常用检查、镜像构建、演示边界
├── docker-compose.yml — 本地依赖：MySQL8（13306，utf8mb4，关 binlog，时区+08:00 对齐 RDS 基线）+ Redis（16379）
├── lefthook.yml — git 钩子配置（当前为模板注释，未启用 job）
├── .trivyignore — CI 镜像扫描的受控例外清单（配合 Trivy --ignorefile）
├── .github/workflows/ci.yml — CI 三 job：backend（Flyway/基线/文档防漂移校验+compose 起库+mvn test+演练脚本）、frontend（format/lint/test/build）、security（pnpm audit 高危阻断+Trivy 镜像 HIGH/CRITICAL 阻断）
├── map.md — 本文件
│
├── backend/ — Maven 多模块（JDK21/SpringBoot3/MyBatis），依赖方向 followup→form→framework；platform、system→framework
│   ├── pom.xml — 聚合 POM；spotless 格式化、surefire 配置
│   ├── Dockerfile — 后端镜像（18080，默认 SPRING_PROFILES_ACTIVE=demo）
│   │
│   ├── crdp-server/ — 唯一可部署包：装配所有模块、安全链、控制器、Flyway 脚本
│   │   ├── …/server/CrdpApplication.java — Spring Boot 入口（@MapperScan 扫全部 infra.mapper 包）
│   │   ├── …/server/config/ — 数据源与开通装配
│   │   │   ├── DataSourceConfig.java / DataSourceFactory.java — 平台库+租户连接池工厂
│   │   │   ├── TenantDataSourceRegistry.java — 租户编码→连接池注册表（与 TenantRoutingDataSource 配合）
│   │   │   ├── TenantSchemaMigrator.java — 平台库/租户库 Flyway 迁移编排（base→followup→seed）
│   │   │   ├── TenantOnboardingService.java — 开通向导编排：建库→迁移→种子→初始化（V2/V3 步骤表）
│   │   │   ├── TenantUpgradeBatchService.java — 批量升级存量租户库
│   │   │   ├── ProductionSafetyCheck.java / ProductionSafetyEnvironmentPostProcessor — prod 剖面未接 KMS 即拒绝启动
│   │   │   ├── DemoPasswordInitializer / DemoPhoneInitializer — demo 种子口令/手机号初始化
│   │   │   ├── OpenApiConfig / WebMvcConfig / CorsProperties — swagger、MVC 拦截器、CORS
│   │   │   └── PlatformDatabaseHealthIndicator / ProvisioningProperties / DatasourceSecrets — 健康指示器与配置绑定
│   │   ├── …/server/auth/ — 认证应用服务（AuthService 登录主流程、EffectivePermissionService、ReauthService、SessionAdminService、PasswordChange/ResetService、TenantLookupCache 租户缓存）
│   │   ├── …/server/security/ — 安全过滤链
│   │   │   ├── SecurityConfig.java — Spring Security 主配置：公开白名单（两个 login、captcha、2fa、tenant、密码找回、/api/h5/upload/*、files preview|download token、sms receipt、view/enter、actuator、/dev/mock-sms）与端别隔离
│   │   │   ├── TokenAuthenticationFilter.java — 会话令牌认证
│   │   │   ├── H5UploadTokenFilter.java — H5 一次性上传令牌校验
│   │   │   ├── MustChangePasswordFilter.java — 首登强制改密拦截
│   │   │   ├── GraceReadOnlyFilter.java — 宽限期租户只读化
│   │   │   ├── TenantViewGuardFilter.java / ReauthInterceptor.java — 视角会话守护 / 敏感操作重认证
│   │   │   └── ApiResourceGuardFilter / ApiResourceRegistry — API 资源登记缺失守卫（先于 401/403 暴露部署缺陷）
│   │   ├── …/server/platform/ — 平台域应用服务（50+ 类）
│   │   │   ├── TenantLifecycleService / TenantSuspensionService / TenantExpiryService / TenantCancelService / TenantDestroyService — 状态机：开通→暂停→到期→注销销毁
│   │   │   ├── TenantQuotaService / TenantQuotaGuard / TenantStorageQuotaAdapter — 配额与存储配额
│   │   │   ├── TenantViewService / TenantViewProxyService / TenantViewRuleExecutor / ViewRuleService — 平台视角会话、代理与规则执行
│   │   │   ├── AuditArchiveService / AuditChainVerificationService + 两个 Scheduler — 审计归档与哈希链校验调度
│   │   │   ├── MenuAdminService / MenuSyncService / ModuleCatalogService / ApiResourceScanner — 菜单/模块/API 资源同步
│   │   │   ├── ConfigTemplateExport|ImportService、RoleTemplateService、ScopeTemplateService — 配置/角色/范围模板
│   │   │   ├── PlatformAccountService / PlatformAuditService / PlatformAnnouncementService / ResourceChangeLogService — 账号/审计/公告/变更流水
│   │   │   └── *ParamDefaultsAdapter / *DictPortAdapter / FileUploadParamDefaultsAdapter — 平台↔租户参数默认值端口适配
│   │   ├── …/server/system/ — 租户系统域服务（audit/ 审计查询导出、role/ RoleAdminService、user/ 用户与导入、权限预览）
│   │   ├── …/server/web/ — 39 个控制器（见第 3 节）+ 请求/响应 DTO（HospitalLoginRequest、MeResponse 等）
│   │   ├── …/server/notify/ — OutboxDispatchScheduler（outbox 派发）、WebhookDeliveryScheduler、NotifyDigestScheduler、SmsReceiptController
│   │   ├── …/server/followup/ — FollowupTaskScheduler（定时生成任务，租户逐个执行）、ReportLocationFallbackScheduler、VirusScanRetroScheduler、export/PatientExportService
│   │   ├── …/server/configtemplate/ — 配置模板导出导入与敏感项检查（SensitiveConfigChecker）
│   │   ├── src/main/resources/
│   │   │   ├── application.yml + application-{local,dev,test,demo,prod}.yml — 六剖面；server.port=18080；prod 无默认口令且 KMS 前启动失败
│   │   │   ├── logback-spring.xml — 日志（审计走独立 marker）
│   │   │   └── db/ — Flyway 脚本三线（见第 1 节版本线）
│   │   │       ├── platform/ — 平台库 V1–V74（租户/模块/菜单/权限资源/审计链与分区/视角/公告/配额/向导）
│   │   │       ├── platform/seed/ — 平台种子 V100–V112（演示平台账号、角色模板、视角规则、菜单授予）
│   │   │       ├── tenant/base/ — 租户基础库 V1–V45（用户/组织/角色/字典/参数/会话/病区/品牌/加密进度/策略）
│   │   │       ├── tenant/followup/ — 租户随访库 V1–V139（患者/基线/计划/任务/记录/报告/植入/通知/对比/导入/统计/工作台/A·B·C 预置）
│   │   │       └── tenant/seed/ — 租户种子 V100–V137（演示账号、预置角色/计划/病区、A 266 字段与 C 97 字段字典种子）
│   │   └── src/test/…test… — 165 个 MySQL 门控 IT + 单测（见第 5 节）
│   │
│   ├── crdp-framework/ — 技术底座（无业务）：被其余四业务模块依赖
│   │   ├── …/framework/tenant/ — TenantContext（ThreadLocal 租户上下文）、TenantRoutingDataSource（按上下文选连接池，缺失即拒）、TenantContextTaskDecorator（异步/定时透传）、MissingTenantContextException
│   │   ├── …/framework/security/ — 注解与设施：@RequiresPermission/@RequireModule/@RequireReauth + 两个 Aspect（40300/40301）、SessionStore/SessionPrincipal/CurrentSession、LoginRateLimiter、CaptchaStore、SmsCodeStore、ReauthStore、PasswordPolicy、PublicApi
│   │   ├── …/framework/datascope/ — 数据范围：@DataScope/@IgnoreScope + DataScopeAspect、DataScopeGuard（默认拒绝 40302）、DataScopeInnerInterceptor（空范围 AND 1=0）、ControlledTables（受控表登记）、RestrictedScopeRewriter
│   │   ├── …/framework/crypto/ — CryptoService 门面 + EnvelopeCryptoService（AES-256-GCM 信封）、LocalEnvelopeKeyProvider（demo）/KmsKeyProvider（预留）、TenantDataKeyService（每租户 DEK）、KeyRotationService、BlindIndexNormalizer（盲索引）、CryptoTypeHandler（MyBatis 密文列）、DigestService
│   │   ├── …/framework/fieldpolicy/ — 字段脱敏策略：SensitiveFieldCatalog、PolicyMaskedSerializer（Jackson 序列化期脱敏）、SensitiveValueMasker、TenantViewFieldGuard（平台视角字段裁剪）
│   │   ├── …/framework/audit/ — AuditLogger/JdbcAuditLogger（只追加 INSERT）、AuditLogAspect、AuditChainWriter/Verifier（哈希链）、AuditArchiver（月度分区+归档）、DeniedOperationLogger（被拒操作也留痕）
│   │   ├── …/framework/outbox/ — OutboxPublisher/JdbcOutboxPublisher/OutboxDispatcher（事件投递，通知/Webhook 用）
│   │   ├── …/framework/notify/ + notify/sms/ — NotifySender 接口 + MockSmsChannel（模拟短信收件箱）/TencentSmsChannel（预留）、SmsTemplateStore、SmsSensitiveGuard（短信脱敏）
│   │   ├── …/framework/storage/ — ObjectStorage 接口：LocalObjectStorage（本地演示）/ObsObjectStorage（预留）、AuditArchiveStorage、StorageKeys
│   │   ├── …/framework/excel/ — StreamingXlsxGateway（流式 xlsx，ADR 0007）+ ExcelWorkbook/Sheet 抽象
│   │   ├── …/framework/job/ — JobScheduler + JdbcTenantJobScheduler（按租户逐个执行作业，ADR 0003）、TenantDirectory
│   │   └── …/framework/web/ — ApiResponse/ApiException（统一响应）、GlobalExceptionHandler、TraceIdFilter（X-Trace-Id）、RequestLoggingFilter
│   │
│   ├── crdp-platform/ — 平台库领域（被 crdp-server 的 platform 服务使用）
│   │   ├── …/platform/tenant/ — JdbcTenantDirectory、TenantRecord（租户登记表读写）
│   │   ├── …/platform/menu/ — MenuNode/MenuTreeBuilder/MenuManifest（菜单树与清单）
│   │   ├── …/platform/module/ — ModuleDefinition/ModuleRegistry（base、followup 模块目录）
│   │   ├── …/platform/configtemplate/ — ConfigTableRegistry/ConfigImportSpec（配置包要搬哪些表）
│   │   ├── …/platform/{apires,resource}/ — API 资源行、资源变更流水行
│   │   └── …/platform/infra/mapper/ — PlatformMapper、PlatformAnnouncementMapper、PlatformConfigMapper（MyBatis）
│   │
│   ├── crdp-system/ — 租户库系统域
│   │   ├── …/system/user/ — HospitalAccount/HospitalDirectory（用户目录抽象）
│   │   ├── …/system/org/ — OrgAdminService、OrgTreeAssembler、DeptImportService（科室导入）
│   │   ├── …/system/dict|param/ — TenantDictService、TenantParamService（平台默认+租户覆盖双层）
│   │   ├── …/system/profile/ — ProfileService（个人资料）
│   │   ├── …/system/brand/ — BrandAdminService、BrandThemeColor（租户品牌/主题色）
│   │   ├── …/system/webhook/ — WebhookAdminService、WebhookDeliveryService、WebhookSigner、WebhookSsrfGuard、HttpWebhookTransport
│   │   ├── …/system/event/ — OrgUserEvents（组织/用户领域事件，供通知订阅）
│   │   └── …/system/infra/mapper/ — Org/Dept/HospitalUser/Role/Webhook 等 MyBatis mapper（15 个）
│   │
│   ├── crdp-form/ — 表单引擎域
│   │   ├── …/form/api/FormEngine.java + app/PlaceholderFormEngine.java — 表单引擎 SPI 与占位实现
│   │   ├── …/form/field/ — 字段字典子域：HospitalFormFieldController（/api/hospital/form/fields）、FieldDictionaryService、FieldImportService、FormDictPort（平台字典端口）
│   │   ├── …/form/record/ — 表单模板与数据子域：HospitalFormController（/forms）、FormService（模板发布/版本）、FormDataValidator
│   │   ├── …/form/rule/ — 规则引擎：FormRuleEngine（校验）、FormConditionEngine（显示条件）、FormDerivedEngine（派生字段）、FormProgressEngine（完整度进度）
│   │   └── …/form/history/ — FieldHistoryService（字段级变更历史，ChangeSource）
│   │
│   └── crdp-followup/ — 随访业务域（15 个子域，均按 api/app/domain/infra 四层）
│       ├── …/followup/patient/ — 患者档案：12 控制器 + PatientRegistrationService（登记/访视号）、PatientSearchService（高级检索）、BaselineSectionService/LockService（基线分节与锁）、PlaintextRevealService+PlaintextRateLimiter（明文揭示）、PatientBlindIndexService、PatientDeleteService、SavedFilterService、AdvancedConditionCompiler（domain）
│       ├── …/followup/plan/ — 随访计划：PlanService（版本发布）、PlanEnrollmentService、BatchEnrollmentService、PlanSwitchService、FollowupTerminationService、PlanDateCalculator（domain 窗口日期）、EriEval（C 院 ERI 评估）
│       ├── …/followup/task/ — 任务：FollowupTaskCreationService（生成）、AssignService、StartService、StatusService、TempService（临时）、FollowupTaskTerminator、StartDateRecalculationService、TaskStatusFlow（domain 状态机）
│       ├── …/followup/record/ — 随访记录：RecordEntryService、RecordDraftService（服务器草稿）、RecordBackfillService（补录）、FollowupSequenceService（顺序号）、TimepointSuggestionService
│       ├── …/followup/implant/ — C 院植入：ImplantService（登记/更换/取出）、ImplantGateway、DeviceContextService、ImplantCodes
│       ├── …/followup/report/ — 报告单：ReportService、ReportMappingService（患者-报告映射）、ReportTranscribeService（转录）、MobileUploadService+MobileUploadTokenStore（H5 令牌）、ReportInboxService、ReportConfigService、ReportSearchService、CallReportService（导入报告生成）
│       ├── …/followup/file/ — 文件：FileUploadService、FilePolicyService（类型/大小策略）、FileDuplicateCheckService（查重）、FileDownloadService+FileDownloadSigner（签名 token）、FilePreviewService、ClamdVirusScanner/DisabledVirusScanner、ImageThumbnailGenerator、FileTypeDetector
│       ├── …/followup/notice/ — 通知：NotifyEventSubscriber（outbox 事件消费）、NotifyRuleService、NoticeInboxService、NotifyDigestService、NotifyRecipientResolver、NotifySendLogService
│       ├── …/followup/importer/ — 导入：ImportJobService（任务调度）、ImportStagingService（暂存）、ImportValidationService（校验）、ImportCommitService（提交）、StaticImportFieldCatalog + Wide/Front/Long LayoutAdapter（宽表/长表适配）、AnnotatedReportXlsx（带批注错误报告）、StreamingImportWorkbook
│       ├── …/followup/compare/ — C 院程控对比：CompareRuleService、CompareHighlightService、CompareEvaluator
│       ├── …/followup/stats/ — 统计：CompletenessStatsService、NodeOccurrenceStatsService（节点发生率）、DeviceDistService、EriListService、ScanProgressService、AbnormalRecordsService、DoctorWorkloadStatsService
│       ├── …/followup/dashboard/ — 工作台：DashboardKpiService、DashboardTaskService、DashboardTrendService、DashboardLayoutService
│       ├── …/followup/contact/ — 联系记录：ContactLogService、ContactAudioScopeGuard
│       └── …/followup/ward/ — 病区：WardService
│
├── frontend/ — pnpm workspace（官方 Vben Admin 5，仅 AntD Vue）
│   ├── package.json — workspace 根：dev/build/test(vitest run --dom)/lint(vsh lint)/format:check
│   ├── pnpm-workspace.yaml — apps/* 与 packages/*
│   ├── internal/ — Vben 构建设施：vite-config（@vben/vite-config）、lint-configs、tsconfig、tailwind-config、node-utils
│   ├── scripts/ — vsh（lint 工具）、turbo-run、clean.mjs、deploy
│   ├── apps/
│   │   ├── web-hospital/（@crdp/web-hospital，端口 14173）— 医院端
│   │   │   ├── .env.development — VITE_PORT=14173、VITE_GLOB_API_URL=/api
│   │   │   ├── vite.config.ts / Dockerfile / nginx.conf — 构建（@vben/vite-config）与镜像（8080，/api→crdp-server）
│   │   │   └── src/
│   │   │       ├── router/routes/ — core.ts（登录/回退等核心路由）+ index.ts（动态路由来自后端菜单，component 指向 views 路径）
│   │   │       ├── store/ — auth.ts（登录/权限/permissionVersion）、view.ts（视角会话横幅）、proxy-reason.ts
│   │   │       ├── api/ — request.ts（fetch 封装）、core/*.ts 按域 28 个接口客户端（auth/patient/task/plan/report/…）
│   │   │       ├── views/_core/authentication/ — login.vue（租户编码+密码）、code-login、sms-login-form、qrcode-login、forget/change-password、tenant-brand（登录页品牌）
│   │   │       ├── views/_core/profile/ — 个人中心：资料、改密、绑手机、会话管理、通知偏好
│   │   │       ├── views/_core/view/{entry,exit}.vue — 平台视角 ticket 换令牌进入/退出
│   │   │       ├── views/_core/fallback|about/ — 403/404/500/离线/建设中、关于
│   │   │       ├── views/dashboard/ — 首页工作台（路由 /dashboard）：index.vue 病区切换全页联动，分区组件 task-area（看板/任务表）、rate-area（年度+节点随访率）、completeness-area、device-dist-area、eri-list-area、abnormal-records-area、scan-progress-area、card-settings
│   │   │       ├── views/followup/patient/ — 患者档案页族（菜单路由 /followup/patient）：index 列表+高级检索、new 登记、detail 总览、baseline-edit 基线分节、import 导入、batch-enroll 批量入组；逻辑全部抽 *-logic.ts 配 spec
│   │   │       ├── views/followup/task/index.vue — 随访任务工作台（/followup/task：认领/开始/完成/临时任务/改期）
│   │   │       ├── views/followup/plan/{index,detail}.vue — 计划模板与入组管理（/followup/plan）
│   │   │       ├── views/followup/record/form.vue — 随访记录表单页（新标签打开，Ctrl+S 暂存）
│   │   │       ├── views/followup/report/{index,config}.vue — 报告单列表与报告配置（/followup/report）
│   │   │       ├── views/followup/report-inbox/index.vue — 报告收件箱（归档/下载）
│   │   │       ├── views/followup/field/index.vue — 字段字典管理（/followup/field）
│   │   │       ├── views/followup/notice/ — 通知中心（index 消息、send-logs、send-settings）与 rules
│   │   │       ├── views/followup/rule/index.vue — 程控对比规则（C 院，/followup/rule）
│   │   │       ├── views/followup/stats/{index,doctor-workload}.vue — 统计页（随访率/完整度/医生工作量）
│   │   │       ├── views/followup/dashboard/layout.vue — 工作台布局配置页
│   │   │       ├── views/system/user|role|dept|ward|audit|settings/ — 系统管理六页（settings 含品牌/字典/参数/Webhook 四面板）
│   │   │       ├── components/followup/ — 80+ 业务组件（masked-field 脱敏字段、watermark-preview 水印预览、report-tree、param-trend、implant-section、mobile-upload-qr、batch-upload-dialog、conflict-banner 乐观锁冲突条…；逻辑均抽 *-logic.ts + spec）
│   │   │       ├── components/{notify,system,view}/ — 公告/通知铃铛、科室/用户选择器、视角横幅与原因弹窗
│   │   │       ├── reference/ — 样板页参考实现（fixtures、use-permission/use-feature/use-breakpoint 组合式）
│   │   │       └── adapter/component/ — Vben 组件适配器注册
│   │   ├── web-platform/（@crdp/web-platform，端口 14174）— 平台端
│   │   │   └── src/views/
│   │   │       ├── dashboard/index.vue — 平台首页
│   │   │       ├── platform/tenant/ — 租户管理大页（路由 /platform/tenant）：index/detail/new（向导 wizard.ts）/provision/cancel、modules-tab/quota-tab/scope-tab、enter-view-dialog（进入视角）、renew/status-dialog
│   │   │       ├── platform/{menu,module,role,account,dict,param,notice,api,audit}/ — 平台菜单/模块/角色/账号/字典/参数/公告/API 资源/审计各一页
│   │   │       ├── platform/view-rule/ + scope-template/ + role-template(并入 role)/config-template/ — 视角规则、范围模板、配置模板
│   │   │       └── _core/authentication/ — 平台登录（平台端关闭强制 2FA）
│   │   └── mobile-upload/（@crdp/mobile-upload，端口 14175，--host 0.0.0.0）— 手机上传 H5
│   │       └── src/ — App.vue（#/u/{一次性令牌} 流程页）、upload-flow.ts（纯逻辑+spec）、api.ts（/api/h5/upload/* 客户端，C 院可要求登录）
│   ├── packages/ — workspace 包
│   │   ├── api/（@crdp/api）— 与后端契约的 TS 类型 + 客户端逻辑：按域 40+ 文件（patient.ts、task.ts、tenant-*.ts、view-rule.ts…）+ vben-menu.ts（后端菜单→Vben 路由转换，隐藏子路由提升）
│   │   ├── form-engine/（@crdp/form-engine）— 表单渲染器：form-renderer.vue、auto-save.ts（30s IndexedDB/2min 服务器草稿）、carry-over.ts（上次值带出）、next-visit.ts、multiselect、layout
│   │   ├── rules/（@crdp/rules）— 前端规则（与后端同口径的纯函数）：has-permission、form-validation/condition/derived/progress/compare、plan-date、login-redirect、permission-refresh、external-link
│   │   ├── viewer/（@crdp/viewer）— 报告单查看器：file-preview.vue、fullscreen-viewer.vue、watermark.ts（姓名·医院·分钟级时间）、pinch.ts（双指缩放）、renderers.ts
│   │   ├── photo/（@crdp/photo）— 图片压缩（长边≤2560、转 JPEG、去 EXIF）
│   │   ├── responsive/（@crdp/responsive）— 响应式：device-matrix.ts（六档真机矩阵唯一来源）、mobile-blocker.vue、use-breakpoint、use-responsive-shell、route-meta（5 页放行名单）、use-keyboard-safe-bottom
│   │   ├── status-tag/（@crdp/status-tag）— 状态标签组件（状态→颜色/文案映射）
│   │   ├── tokens/（@crdp/tokens）— 设计令牌（再导出 docs/design/tokens.ts：主色 #3A75FF、圆角 8/6/4、断点 768/1280）
│   │   ├── @core|effects|icons|locales|preferences|stores|styles|types|utils|constants（@vben/*）— Vben 上游框架包（仅保留 antd 路径；effects 含 access/request/layouts/common-ui 等基建）
│   │   └── （各包 *.spec.ts / *.test.ts 由根 vitest 收集）
│
├── db 说明 — 迁移脚本实体位于 backend/crdp-server/src/main/resources/db/（三目录+种子，见第 1 节与 crdp-server 树内注释）
│
├── docs/ — 全部需求与过程文档（索引见 docs/README.md）
│   ├── README.md — 文档索引与编号规则（需求编号 PLT-*/A-*/C-*/NFR-*、优先级、冲突裁决顺序）
│   ├── open-questions.md — 未决事项与暂行做法（OQ-xx，优先级最高）
│   ├── requirements/ — 四本规格：tech-plan.md（整体技术方案 V0.2）、platform-spec.md（平台底座 V0.3）、hospital-a-spec.md（A 院 V0.3，第 14 章 B 差异，附录 266 字段）、hospital-c-spec.md（C 院 V0.1，附录 97 字段）
│   ├── design/ — design-system.md（令牌/布局/文案）、components.md（业务组件规格）、pages.md（逐页规格：路由/权限/接口/线框）、reference-pages.md（样板页）、tokens.ts（设计令牌源）
│   ├── api/ — README.md（通用约定+错误码）+ 12 个模块契约：auth/platform/system/form/patient/implant/followup/report/notify/stats/import-export/audit
│   ├── adr/ — 7 个架构决定：0001 M0 骨架、0002 官方 Vben5、0003 租户作业、0004 信封数据密钥、0005 审计分区+WORM、0006 OBS SSE-KMS、0007 流式 xlsx
│   ├── ops/ — 运维 runbook：demo-mode、tenant-restore、cross-region-drill（+drill-records/ 演练报告存档）、rds-prod、cce-prod、audit-worm-archive、pentest*（渗透）、mlps/（等保 8 件）、hospital-{a,b,c}-*（上线/保障/预置/回滚）、hypercare*
│   ├── testing/ — 测试文档：device-matrix（真机矩阵）、responsive-walkthrough、hospital-{a,b,c}-* 验收与冒烟、uat-*（A/C UAT 手册/整改/复测）+ 模板 csv
│   ├── tasks/ — tasks.md（里程碑 M0–M6 执行说明）、tasks.csv（277 任务全表：依赖/关联需求/验收）、m1/m2-acceptance.md
│   └── reviews/ — m3-code-review.md、hospital-c-acceptance-review.md
│
├── scripts/ — 开发/校验/演练/交付脚本（均带中文 docstring）
│   ├── dev.sh — 一键演示：compose 起依赖→demo 剖面打包启动后端→三前端并打印地址与账号
│   ├── check_flyway_versions.py — 平台/租户两套迁移版本号查重（CI 必过）
│   ├── check_rds_baseline.py / check_cce_baseline.py / check_worm_baseline.py — 生产 RDS/CCE/WORM 基线 JSON 与 IaC 不漂移校验
│   ├── restore_tenant.sh + check_restore_script.py — 单院 PITR 恢复脚本 + 防漂移与本地演练
│   ├── cross_region_drill.sh + check_cross_region_drill.py + drill_recovery_check.py — 跨区域恢复演练三件套（RPO≤5min，报告入 docs/ops/drill-records）
│   ├── check_frontend_audit.py — pnpm audit --prod 高危阻断（带例外白名单）
│   ├── pentest_intake.py / check_pentest_docs.py / check_pentest_remediation{,_frontend}.py — 渗透测试发现入库与整改手册防漂移
│   ├── uat_intake.py / check_uat_docs.py / check_uat_remediation{,_fullstack1,_c_fullstack1}.py / uat_retest.py / check_uat_retest.py — UAT（A/C）发现入库、整改与复测闭环
│   ├── mlps_onsite_intake.py / check_mlps_docs.py / check_mlps_onsite.py / check_mlps_remediation{,_frontend}.py — 等保测评资料、现场配合与整改
│   ├── check_tenant_b_onboarding.py / check_hospital_b_{acceptance,golive,regression}.py — B 院配置初始化、验收、上线、回归防漂移
│   ├── check_golive_smoke.py / golive_import_verify.py / hypercare_verify{,_fullstack2}.py — A 院上线冒烟、正式数据导入核对、上线保障 SLA
│   ├── check_hospital_c_acceptance_review.py — C 院验收用例评审防漂移
│   ├── gen_a_field_dict.py / gen_c_field_dict.py — 从规格附录生成 A（266）/C（97）字段字典迁移脚本
│   └── deidentify_sample.py — 医院历史样本脱敏工具（入仓前处理）
│
├── iac/ — 华为云 IaC 草稿（Terraform，真实云操作由持账号运维执行）
│   ├── rds-prod/ — 生产 RDS（主备/备份/跨区域；params-baseline.json 为参数基线）
│   ├── cce-prod/ — 生产 CCE + K8s 清单（k8s/；cce-baseline.json 三级基线）
│   └── obs-archive/ — 审计归档 OBS WORM 桶（worm-baseline.json：15 年合规锁）
│
├── deploy/mysql/init/ — 01-tenants.sql：本地首启创建演示租户库 t_demoa/t_demob
├── data/storage/ — 本地对象存储目录（LocalObjectStorage 运行时产物，按平台/租户分桶；不入库）
├── drill-work/、restore-work/ — 恢复/演练脚本的本地演练工作目录（时间戳产物，不入库）
└── node_modules/（根）— 仓库根跑 vitest 的缓存残留（无对应根 package.json，可忽略）
```

---

## 5. 测试布局

### 单元测试（mvn test 恒跑，无外部依赖）

- 后端共 **479 个**测试类，分布：crdp-followup 123、crdp-framework 63、crdp-server 90（另 165 个 IT 也在 server）、crdp-system 13、crdp-form 12、crdp-platform 4。
- 代表性单测：`crdp-framework/…test…/tenant/TenantRoutingDataSourceTest`（双租户连接池不混用、缺上下文拒绝）、`…/datascope/DataScopeGuardTest`（默认拒绝/空范围 AND 1=0）。
- 前端：`frontend` 根 `pnpm test` = `vitest run --dom`，收集 workspace 内全部 `*.spec.ts`/`*.test.ts`（packages/api、rules、form-engine、viewer、responsive、photo 及各 app 的 `*-logic.spec.ts`）。

### MySQL 门控集成测试（IT）

- 门控变量 **`CRDP_IT_MYSQL_PORT`**（`@EnabledIfEnvironmentVariable`），共 **165 个** IT 类受控；CI 在 backend job 里 `docker compose up -d --wait` 后注入端口随 `mvn test` 运行，本地不设变量则跳过。IT 自建自清（`IT-` 前缀病案号、`PLAN-IT-` 编码等虚构数据）。
- 代表性链路 IT（约 12 个）：
  1. `server/TenantIsolationAcceptanceIT` / `ProvisionedTenantIsolationIT` — 开通后双租户分库隔离验收
  2. `server/config/TenantOnboardingServiceMySqlIT` — 租户开通向导全流程（建库→迁移→种子）
  3. `server/PlatformTenantWizardMySqlIT` — 平台端向导路径端到端
  4. `server/followup/M2DischargeToNotifyChainIT` — 出院→登记→入组→任务→通知主链
  5. `server/followup/CFullChainAcceptanceIT` — 医院 C 器械链（植入→程控→对比→报告）验收
  6. `server/followup/PatientPlaintextIT` — 明文查看（权限/二次确认/审计/60s）
  7. `server/auth/DataScopeMaskingIT` — 数据范围+脱敏组合
  8. `server/AuditChainTamperMySqlIT` — 审计哈希链防篡改
  9. `server/followup/ImportScale5000IT` — 5000 行宽表导入性能与正确性
  10. `server/followup/MobileUploadIT` — 手机扫码上传 H5 全链
  11. `server/TenantBOnboardingMySqlIT` — B 院复用 A 配置模板初始化
  12. `server/followup/GoliveSmokeIT` — A 院上线冒烟集

### CI（.github/workflows/ci.yml，三 job）

- **backend**：全部防漂移 py 校验（Flyway 版本、RDS/CCE/WORM 基线、渗透/UAT/等保/B 院/上线/保障）→ compose 起 MySQL/Redis → 打包并预迁移 → `mvn spotless:check test`（含全部 IT）→ 恢复/跨区演练脚本 `--drill`。
- **frontend**：`pnpm install --frozen-lockfile` → `format:check` → `lint && test && build`。
- **security**：`check_frontend_audit.py`（生产依赖 HIGH/CRITICAL 阻断）+ buildx 构建后端镜像 + Trivy `--severity HIGH,CRITICAL --exit-code 1`（`.trivyignore` 收纳已评估例外）。

---

## 6. 运行入口

### 一键演示

```bash
./scripts/dev.sh        # MySQL+Redis → 后端(demo 剖面) → 医院端/平台端/H5 三前端
```

`demo` 剖面：本地 AES-256-GCM 信封加密（`CRDP_CRYPTO_LOCAL_KEY`，默认 `dev-only-not-a-production-key`）+ 模拟短信 + 虚构种子（含平台视角默认规则）。无 Docker 时自行监听 13306/16379 即可。手机扫码需 `CRDP_H5_UPLOAD_MOBILE_BASE_URL=http://<局域网IP>:14175`。

### 演示账号（仅 local/dev/demo 种子，口令统一 `Demo@2026`）

| 入口 | 租户 | 用户名 | 虚构手机号 | 能看到 |
| --- | --- | --- | --- | --- |
| 医院端 14173 | `demoa` 示例医院甲 | nurse.demo | 13900000001 | 工作台、随访、系统管理 |
| 医院端 14173 | `demoa`/`demob` | doctor.demo | 13900000002 | 工作台、患者档案、随访任务 |
| 医院端 14173 | `demob` 示例医院乙 | nurse.demo | — | 同甲院菜单，库为 t_demob |
| 平台端 14174 | 无 | platform.admin | 13900000003 | 平台管理（演示关强制 2FA） |

### 端口表

| 服务 | 地址/端口 |
| --- | --- |
| 后端 API / swagger / 健康 | http://127.0.0.1:18080（`/swagger-ui.html`、`/actuator/health`） |
| 医院端 | http://127.0.0.1:14173 |
| 平台端 | http://127.0.0.1:14174 |
| 手机上传 H5 | http://127.0.0.1:14175（vite `--host 0.0.0.0`，`/api` 代理到本机后端） |
| 模拟短信页 | http://127.0.0.1:18080/dev/mock-sms |
| MySQL（compose） | 127.0.0.1:13306（crdp_app / DevOnly#app2026） |
| Redis（compose） | 127.0.0.1:16379 |

### 本地常用检查（与 CI 一致）

```bash
python3 scripts/check_flyway_versions.py
mvn -B -f backend/pom.xml spotless:check test
cd frontend && pnpm format:check && pnpm lint && pnpm test && pnpm build
```
