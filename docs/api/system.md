# 医院端系统管理接口（system）

> 后端：`crdp-system`（用户、角色、组织、字典、参数、通知配置）；病区相关字段在 `crdp-followup` 使用。前缀 `/api/hospital/system/`（推断，待确认）。
> 页面：`docs/design/pages.md` §10。全部路径 **推断，待确认**。
> 菜单种子现有权限码：`tenant:user:view`、`tenant:role:view`、`tenant:dept:view`（规格写作 `tenant:org:view`，见 OQ-11）。

## 1. 组织：院区、科室、病区（PLT-ORG-001..003、A-WARD-001/008、C-WARD-001/008）

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | `/api/hospital/system/orgs/tree` | `tenant:dept:view` | 院区 → 科室 → 病区树（`sys_org`、`sys_dept`；A 只有病区一层也用同一树） |
| POST / PUT | `/api/hospital/system/orgs[/{id}]` | `tenant:org:edit`（推断） | 新建 / 修改；停用前返回影响（成员数、患者数） |
| POST | `/api/hospital/system/orgs/import` | `tenant:org:edit` | 科室导入（Excel，见 `import-export.md`） |
| GET / POST / PUT | `/api/hospital/followup/wards[/{id}]` | `followup:ward:edit` | 病区字典：名称、编码、默认随访负责人、停用（A-WARD-001、008；C-WARD-001） |
| GET / PUT | `/api/hospital/followup/wards/aliases` | `followup:ward:edit` | 病区值映射（导入时原值 → 病区，`ward_alias`，A-WARD-006） |
| GET | `/api/hospital/followup/wards/{id}/members` | `followup:ward:edit` | 成员与患者数 |

## 2. 用户（PLT-ORG-006..012、A-WARD-002、C-WARD-003）

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | `/api/hospital/system/users` | `tenant:user:view` | 分页；筛选 `keyword, deptId, wardId, roleId, status`；手机号脱敏 |
| POST | `/api/hospital/system/users` | `tenant:user:edit` | 超过用户数配额返回 40901「已达用户数上限」（PLT-ORG-010） |
| PUT | `/api/hospital/system/users/{id}` | `tenant:user:edit` | 含 `roleIds[]`、`wardIds[]`（A 负责病区）/ `deptScopeIds[]`（C 负责科室或病区） |
| POST | `/api/hospital/system/users/{id}/status` | `tenant:user:edit` | 启用 / 停用；停用同时下线其会话 |
| POST | `/api/hospital/system/users/{id}/reset-password` | `tenant:user:reset` | 生成临时密码，首次登录强制改密；二次认证 |
| POST | `/api/hospital/system/users/{id}/unlock` | `tenant:user:reset` | 解锁 |
| GET | `/api/hospital/system/users/{id}/effective-permissions` | `tenant:user:view` | 权限预览：菜单、按钮、数据范围（含病区）、字段策略（PLT-ORG-009、A-WARD-009） |
| POST | `/api/hospital/system/users/import` | `tenant:user:import` | 批量导入（PLT-ORG-007） |
| GET | `/api/hospital/system/users/options?keyword=&deptId=` | 登录 | 用户选择器（只返回 id、显示名、科室；PLT-ORG-012） |

## 3. 角色与数据范围（PLT-ORG-004/005、PLT-PERM-007..009、A-WARD-003、C-WARD-004）

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | `/api/hospital/system/roles` | `tenant:role:view` | 列表（内置角色不可删） |
| POST / PUT / DELETE | `/api/hospital/system/roles[/{id}]` | `tenant:role:edit` | 角色基本信息 |
| GET / PUT | `/api/hospital/system/roles/{id}/menus` | `tenant:role:edit` | 菜单与按钮；**只能选平台授权范围内的项**（超出返回 40001） |
| GET / PUT | `/api/hospital/system/roles/{id}/scope` | `tenant:role:edit` | 数据范围类型：`ALL / DEPT / DEPT_AND_CHILDREN / WARD / SELF / CUSTOM`（以平台定义为准）；`wardIds[]` / `deptIds[]` |
| GET / PUT | `/api/hospital/system/roles/{id}/field-policies` | `tenant:role:edit` | 字段策略：`VISIBLE / MASKED / HIDDEN`，不能宽于平台上限（PLT-SCOPE-007） |
| GET / PUT | `/api/hospital/system/roles/{id}/members` | `tenant:role:edit` | 成员 |
| POST | `/api/hospital/system/roles/from-template` | `tenant:role:edit` | 应用业务角色模板 |

保存角色后递增相关用户的 `permissionVersion`（PLT-PERM-010）。租户管理员全院可见（A-WARD-007、C-WARD-007）。

## 4. 字典与参数（PLT-CFG-002/004、A-FLD-012、C-FLD-002/003）

| 资源 | 路径 | 权限 |
|---|---|---|
| 租户字典 | `/api/hospital/system/dicts`、`/dicts/{code}/items`（`cfg_dict`、`cfg_dict_item`；条目停用不删除） | `tenant:dict:edit` |
| 租户参数 | `/api/hospital/system/params`（`cfg_param`） | `tenant:param:edit` |
| 个性化 | `/api/hospital/system/brand`（名称、Logo fileId、预设主题色，`cfg_brand`） | `tenant:brand:edit` |
| 安全设置 | `/api/hospital/system/security-policy`（不得低于平台下限） | `tenant:security:edit` |
| Webhook | `/api/hospital/system/webhooks`（`cfg_webhook`，见 `notify.md`） | `tenant:webhook:edit` |

## 5. 首页卡片配置（A-DASH-013/014）

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | `/api/hospital/followup/dashboard/role-layouts?roleId=` | `followup:dashboard:config` | 角色卡片布局（`dash_role_layout`） |
| PUT | `/api/hospital/followup/dashboard/role-layouts/{roleId}` | `followup:dashboard:config` | `{ cards: [{ code, order, visible }] }`；恢复默认 = `DELETE` |
| PUT | `/api/hospital/followup/dashboard/user-pref` | 登录 | 用户隐藏 / 显示卡片（`dash_user_pref`，P1） |

## 6. 报告单设置、对比规则、通知设置

- 报告单设置：见 `report.md` §6。
- 对比规则 / 异常阈值：见 `followup.md` §7。
- 通知设置：见 `notify.md` §2。

## 7. 个人中心（PLT-ORG-011、A-NTF-007、C-NTF-007）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET / PUT | `/api/hospital/profile` | 基本信息（显示名、手机号修改需短信验证） |
| GET | `/api/hospital/profile/login-logs` | 本人登录记录 |
| GET / PUT | `/api/hospital/profile/notice-pref` | 通知偏好（`ntf_user_pref`） |

## 8. 在线会话（PLT-AUTH-008/009）

> E04-T06 已实现（权限码挂用户管理菜单下隐藏按钮，平台库 V11 `plt_menu` 312 / 313；`/system/session` 页面属 E06+）。

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | `/api/hospital/system/sessions` | `tenant:session:view` | 本院全部在线会话：`{ sessionId, userId, username, displayName, ip, issuedAt, lastActiveAt }`；`sessionId` 由令牌哈希单向推导，接口不返回令牌 |
| POST | `/api/hospital/system/sessions/{sessionId}/kick` | `tenant:session:kick` | 强制下线：被下线令牌下次请求 40100；写审计 `SESSION_KICK`；会话号不存在 40400 |

## 9. 组织与用户变更事件（PLT-ORG-013）

用户停用、转科、角色变化通过 `sys_outbox` 发布领域事件；随访模块订阅后处理责任人重分配提示（不自动改派，推断，待确认）。
