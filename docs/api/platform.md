# 平台端接口（platform）

> 后端：`crdp-platform`；前缀 `/api/platform/`；只访问平台库 `plat`（技术方案“平台库 plat”一行：平台库不存任何医院的用户、患者与业务数据）。
> 页面：`docs/design/pages.md` §11。全部接口路径 **推断，待确认**（仓库只有认证与菜单）。
> 所有写操作写平台审计（PLT-AUD-004）；涉及高敏的操作要求二次认证（PLT-AUTH-012，请求头 `X-Reauth-Token`）。

## 1. 租户（PLT-TEN-001..022）

| 方法 | 路径 | 权限 | 说明 | 需求 |
|---|---|---|---|---|
| GET | `/api/platform/tenants` | `platform:tenant:view` | 分页；筛选 `keyword,status,expireBefore,moduleCode`；列见 pages.md §11 | PLT-TEN-001 |
| POST | `/api/platform/tenants` | `platform:tenant:create` | 创建并启动开通作业；body `{ name, code?, modules[], expireAt? (空=长期), quota{users,storageGb}, admin?{username, displayName, phone} }`；返回 `{ tenantId, provisionJobId }` | PLT-TEN-002 |
| GET | `/api/platform/tenants/{id}/provision` | `platform:tenant:view` | 开通进度：步骤 `RESOURCE / DB_INIT / DATASOURCE / ADMIN / ISOLATION_CHECK`，每步 `status, startedAt, finishedAt, error` | PLT-TEN-003..008 |
| POST | `/api/platform/tenants/{id}/provision/retry` | `platform:tenant:create` | 从失败步骤重试 | PLT-TEN-008 |
| POST | `/api/platform/tenants/{id}/provision/abort` | `platform:tenant:create` | 放弃并回收已开通资源 | PLT-TEN-008 |
| GET | `/api/platform/tenants/{id}` | `platform:tenant:view` | 详情；资源信息（数据源、桶、密钥 ID）**脱敏只读**，不得返回连接串和密码 | PLT-TEN-009、015 |
| PUT | `/api/platform/tenants/{id}` | `platform:tenant:edit` | 基本信息、访问地址 | PLT-TEN-009、015 |
| POST | `/api/platform/tenants/{id}/status` | `platform:tenant:status` | `{ action: ENABLE|DISABLE, reason }`；返回影响面（在线用户、进行中任务） | PLT-TEN-010、016、017 |
| PUT | `/api/platform/tenants/{id}/expire` | `platform:tenant:renew` | 到期设置 / 续期 `{ expireAt | null, reason }` | PLT-TEN-011、012、013 |
| PUT | `/api/platform/tenants/{id}/quota` | `platform:tenant:edit` | 配额 | PLT-TEN-014 |
| POST | `/api/platform/tenants/{id}/cancel` | `platform:tenant:cancel` | 注销申请（上传书面确认 fileId）；后续 `.../cancel/approve`、`.../export`、`.../export/ack`、`.../destroy`、`.../retain` | PLT-TEN-018..021 |
| GET | `/api/platform/tenants/{id}/logs` | `platform:tenant:view` | 租户操作记录 | PLT-TEN-022 |

## 2. 模块（PLT-MOD-001..011）

| 方法 | 路径 | 权限 | 说明 | 需求 |
|---|---|---|---|---|
| GET | `/api/platform/modules` | `platform:module:view` | 模块目录、版本、依赖、菜单与权限码清单 | PLT-MOD-001、003 |
| POST | `/api/platform/modules/sync` | `platform:module:edit` | 从应用启动注册的模块清单同步 | PLT-MOD-002 |
| GET / PUT | `/api/platform/tenants/{id}/modules` | `platform:tenant:edit` | 开通 / 关闭模块（依赖校验失败返回 40901 + 依赖说明） | PLT-MOD-004、005、006 |
| GET / PUT | `/api/platform/tenants/{id}/modules/{code}/config` | `platform:tenant:edit` | 模块级配置，含**功能开关** `cfg_feature`（租户管理员不能改） | PLT-MOD-008 |
| GET / PUT | `/api/platform/tenants/{id}/features` | `platform:tenant:edit` | 功能开关列表 `{ featureCode, enabled }[]`（与上一行二选一实现） | 技术方案“各院功能不同” |
| POST | `/api/platform/upgrades` | `platform:upgrade:execute` | 创建升级批次（灰度租户列表） | PLT-MOD-007 |
| GET | `/api/platform/modules/{code}/open-logs` | `platform:module:view` | 开通记录 | PLT-MOD-009 |

PLT-MOD-010/011 为后续规划，不实现。

## 3. 菜单、接口资源、字段资源（PLT-PERM-001..012）

| 方法 | 路径 | 权限 | 说明 | 需求 |
|---|---|---|---|---|
| GET | `/api/platform/menus/tree?side=HOSPITAL|PLATFORM` | `platform:menu:view` | 全量菜单树（含按钮） | PLT-PERM-001、002、003 |
| POST / PUT / DELETE | `/api/platform/menus[/{id}]` | `platform:menu:edit` | 维护目录 / 菜单 / 按钮；`moduleCode` 必填 | PLT-PERM-001..003 |
| POST | `/api/platform/menus/sync` | `platform:menu:edit` | 菜单清单同步（来自模块清单） | PLT-PERM-011 |
| GET | `/api/platform/apis` | `platform:api:view` | 接口资源（方法 + 路径模式、权限码、动作类型 QUERY/WRITE/EXPORT/DOWNLOAD）；`unbound=true` 只看未绑定 | PLT-PERM-004 |
| PUT | `/api/platform/apis/{id}` | `platform:api:edit` | 绑定权限码、动作类型 | PLT-PERM-004 |
| GET / PUT | `/api/platform/fields` | `platform:field:edit` | 字段资源：敏感分级、默认策略、脱敏规则 | PLT-PERM-009 |
| GET | `/api/platform/resource-logs` | `platform:menu:view` | 资源变更记录 | PLT-PERM-012 |

> 医院端菜单改名（如 患者档案 → 患者）只能通过**新 Flyway 迁移**修改种子，不能改已执行的迁移（见 `pages.md` §0）。

## 4. 授权范围（PLT-SCOPE-001..008）

| 方法 | 路径 | 权限 | 说明 | 需求 |
|---|---|---|---|---|
| GET / PUT | `/api/platform/tenants/{id}/scope` | `platform:tenant:edit` | 租户可分配的菜单 / 按钮 / 数据范围类型 / 字段策略上限；敏感权限单独放开（需原因 + 二次认证） | PLT-SCOPE-001、002、004、007 |
| POST | `/api/platform/tenants/{id}/scope/preview` | `platform:tenant:edit` | 变更影响分析：受影响角色、用户数 | PLT-SCOPE-005 |
| GET / POST / PUT | `/api/platform/scope-templates[/{id}]` | `platform:scope:template` | 授权范围模板 | PLT-SCOPE-003 |
| GET | `/api/platform/tenants/{id}/scope/logs` | `platform:tenant:view` | 变更记录 | PLT-SCOPE-008 |

收回范围时自动从租户角色中移除并递增 `permissionVersion`（PLT-SCOPE-006、PLT-PERM-010）。

## 5. 平台账号与角色（PLT-ROLE-001..006、PLT-VIEW-005/006）

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET / POST / PUT | `/api/platform/accounts[/{id}]` | `platform:account:view` / `edit` | 手机号必填；负责租户多选（PLT-VIEW-006） |
| POST | `/api/platform/accounts/{id}/reset-password` | `platform:account:reset` | 二次认证 |
| GET / POST / PUT | `/api/platform/roles[/{id}]` | `platform:role:view` / `edit` | 平台预置 / 自定义角色；“租户视角”页签字段：`canEnter, visibleScope, tenantScope, canConfigure, canResetPassword, canEditBusinessData` |
| GET | `/api/platform/role-templates` | `platform:role:view` | 业务角色模板（复制到租户内置角色，PLT-ROLE-004、005） |

## 6. 租户视角（PLT-VIEW-001..018）

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| POST | `/api/platform/tenant-view/sessions` | `platform:view:enter` | `{ tenantId, purpose: TROUBLESHOOT|CONFIG_CHECK|TRAINING|OTHER, note }` → `{ viewToken, hospitalUrl, expiresAt }`；最长 2 小时、空闲 15 分钟（PLT-VIEW-001、004） |
| DELETE | `/api/platform/tenant-view/sessions/{id}` | 登录 | 一键退出（PLT-VIEW-003） |
| GET / PUT | `/api/platform/view-rules` | `platform:view:rule` | 层级规则（全局 / 租户 / 模块 / 字段），版本化；高敏变更进入待复核（PLT-VIEW-007..009、014、015） |
| POST | `/api/platform/view-rules/preview` | `platform:view:rule` | 效果预览 |
| POST | `/api/platform/view-rules/reviews/{id}/approve` | `platform:view:rule` | 复核 |

视角令牌在医院端的执行（PLT-VIEW-010..013、017、018）：

- 视角令牌访问 `/api/hospital/**` 时会话类型为 `TENANT_VIEW`（推断，待确认）；菜单与页面与租户一致（PLT-VIEW-002）。
- 字段脱敏、设盲、只读在**后端**执行；写接口默认返回 40303，除非平台角色允许“代为配置”“代重置密码”“编辑业务数据”。
- 视角审计**只写平台库**，不写租户审计（PLT-VIEW-016）。

## 7. 平台配置（PLT-CFG-001/003/005/006/007/009/010/011）

| 资源 | 路径 | 权限 |
|---|---|---|
| 平台字典 | `/api/platform/dicts`、`/dicts/{code}/items` | `platform:dict:edit` |
| 系统参数 | `/api/platform/params` | `platform:param:edit` |
| 通知渠道与短信模板 | `/api/platform/notice/channels`、`/notice/templates` | `platform:notice:edit` |
| Webhook | `/api/platform/webhooks`、`/webhooks/{id}/deliveries`、`.../deliveries/{id}/redeliver` | `platform:webhook:edit` |
| 系统公告 | `/api/platform/announcements` | `platform:notice:edit` |
| 文件上传配置 | `/api/platform/params/upload`（格式、大小上限） | `platform:param:edit` |
| 安全策略下限 | `/api/platform/security-policy`（密码策略、锁定、会话、二次认证场景） | `platform:security:edit` |
| 配置模板 | `/api/platform/config-templates`（从某租户导出配置 → 应用到新租户；只导出注册表中标注为配置的表，引用用户的字段置空） | `platform:tenant:create` |

配置变更写记录并说明生效方式（PLT-CFG-011）。

## 8. 平台工作台与审计

- `GET /api/platform/dashboard`：租户数、即将到期、开通失败、待复核（推断，待确认）。
- 平台审计见 `audit.md` §3。
