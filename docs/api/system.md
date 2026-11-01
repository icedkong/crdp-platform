# 医院端系统管理接口（system）

> 后端：`crdp-system`（用户、角色、组织、字典、参数、通知配置）；病区相关字段在 `crdp-followup` 使用。前缀 `/api/hospital/system/`。
> 页面：`docs/design/pages.md` §10。E06-T02 已落地的组织接口见 §1（原「推断，待确认」已按实现定稿：院区 `/orgs`、科室 `/depts` 分资源）；其余路径仍为 **推断，待确认**。
> 菜单种子现有权限码：`tenant:user:view`、`tenant:role:view`、`tenant:org:view` / `tenant:org:edit`（view 原种子 `tenant:dept:view`，OQ-11 定论 B 由 V103 改名对齐规格；edit 为平台库 V19 新增按钮 331）。

## 1. 组织：院区、科室、病区（PLT-ORG-001..003、A-WARD-001/008、C-WARD-001/008）

E06-T02 已实现（院区与科室树；病区字典属 `crdp-followup`，科室导入 E06-T03）：

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | `/api/hospital/system/orgs/tree` | `tenant:org:view` | 院区 → 多级科室树（含停用节点）；`{ singleOrg, orgs[] }`，`singleOrg=true` 表示启用院区 ≤ 1，页面可隐藏院区层级（PLT-ORG-001）；节点含 `code/name/status` 与科室的 `deptType/leaderUserId/leaderName/hisCode/sortNo/children`（同级按 `sortNo`、`id` 升序） |
| GET | `/api/hospital/system/orgs/{orgId}/impact` | `tenant:org:edit` | 院区停用影响面：`{ deptCount, enabledDeptCount, memberCount }`（患者数随患者表 E08+ 落地补充） |
| GET | `/api/hospital/system/orgs/user-options?keyword=` | `tenant:org:view` | 负责人候选（本院启用用户，暂行；PLT-ORG-012 共用选择器落地于 E06-T09 后替换） |
| POST / PUT | `/api/hospital/system/orgs[/{orgId}]` | `tenant:org:edit` | 新增 / 编辑院区：`{ code, name }`；编码租户内唯一（重复 40902）、创建后不可改（更新可省略，携带不一致 40001）；写 `ORG_CREATE` / `ORG_UPDATE` 审计 |
| POST | `/api/hospital/system/orgs/{orgId}/status` | `tenant:org:edit` | 停用 / 启用：`{ action: ENABLE\|DISABLE, reason? }`；有启用中的科室时 40901；重复流转 40901；写 `ORG_STATUS` 审计 |
| POST | `/api/hospital/system/depts` | `tenant:org:edit` | 新增科室：`{ orgId, parentDeptId?, code, name, deptType?, leaderUserId?, hisCode?, sortNo? }`；`parentDeptId` 省略或 0 = 院区顶层；写 `DEPT_CREATE` 审计 |
| PUT | `/api/hospital/system/depts/{deptId}` | `tenant:org:edit` | 编辑 / 移动科室（整对象更新）：名称、类型、负责人（必须是本院启用用户）、HIS 编码、排序、所属院区与上级；移入自己子树、上级与院区不一致、上级不存在 → 40901；写 `DEPT_UPDATE` 审计 |
| POST | `/api/hospital/system/depts/{deptId}/status` | `tenant:org:edit` | 停用 / 启用：同院区口径；有启用中的下级科室时 40901；停用科室不再出现在业务选择器（树中保留）；写 `DEPT_STATUS` 审计 |
| GET | `/api/hospital/system/depts/{deptId}/impact` | `tenant:org:edit` | 科室停用影响面：`{ memberCount, childCount, enabledChildCount }` |

未实现（后续任务）：

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| POST | `/api/hospital/system/orgs/import` | `tenant:org:edit` | 科室导入（Excel，见 `import-export.md`；E06-T03） |
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

## 3. 角色与数据范围（PLT-ORG-004/005、PLT-ROLE-004/005、PLT-PERM-007..009、A-WARD-003、C-WARD-004；E06-T05 已实现）

角色类型（`roleType`）：`BUILT_IN` 内置（租户管理员，不可删除、不可修改权限、不可停用，也不能移除最后一名启用成员）/ `TEMPLATE` 模板（模块开通时由平台下发，只读）/ `CUSTOM` 自定义（可编辑、复制、停用、删除——删除仅限无成员时）。

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | `/api/hospital/system/roles` | `tenant:role:view` | 列表（内置 / 模板 / 自定义，附 `memberCount` / `menuCount` / `moduleCode` / `description`） |
| POST / PUT / DELETE | `/api/hospital/system/roles[/{id}]` | `tenant:role:edit` | 角色基本信息：新建自定义角色（编码租户内唯一 40902、创建后不可改）、编辑（仅自定义，编码不可改 40001）、删除（仅无成员的自定义角色 40901） |
| POST | `/api/hospital/system/roles/{id}/copy` | `tenant:role:edit` | 复制自定义角色：菜单、数据范围与自定义科室一并复制，写 `ROLE_COPY` 审计 |
| POST | `/api/hospital/system/roles/{id}/status` | `tenant:role:edit` | 停用 / 启用（`action=ENABLE|DISABLE`，reason ≤200）；内置与模板 40901 |
| GET / PUT | `/api/hospital/system/roles/{id}/menus` | `tenant:role:view` / `edit` | 菜单与按钮；**只能选平台授权范围内的项**（超出返回 40001），返回树带 `assignable` 标记；仅自定义角色可改（内置不可修改权限、模板只读 40901） |
| GET / PUT | `/api/hospital/system/roles/{id}/scope` | `tenant:role:view` / `edit` | 数据范围类型（多选取并集）：`ALL / WARD / RESPONSIBLE_DEPT / RESPONSIBLE_PATIENT / DEPT / SELF / CUSTOM_DEPT`（平台定义七种，与 `HospitalDirectory` 同口径；生效计算在 E07-T02）；`CUSTOM_DEPT` 必须带 `customDeptIds[]`（本院科室，不存在 40400） |
| GET / PUT | `/api/hospital/system/roles/{id}/members` | `tenant:role:view` / `edit` | 成员（全量覆盖 `userIds[]`）；模板角色不可直接分配（40901，先复制）；不能移除最后一名启用的租户管理员（40901）；加入的用户必须是启用状态 |
| POST | `/api/hospital/system/roles/from-template` | `tenant:role:edit` | 应用业务角色模板（PLT-ORG-005）：`{ templateId, code, name, description? }` → 复制生成自定义角色（菜单与范围随模板带来） |
| GET | `/api/hospital/system/roles/{id}/field-policies` | `tenant:role:edit` | 字段策略：`VISIBLE / MASKED / HIDDEN`，不能宽于平台上限（PLT-SCOPE-007）——**随 E07-T04 / T06 落地** |

保存角色授权 / 成员 / 状态后递增受影响用户的 `permissionVersion`（PLT-PERM-010），在线会话下一次请求即按新权限重算；全部写租户库审计（`ROLE_CREATE` / `ROLE_UPDATE` / `ROLE_COPY` / `ROLE_STATUS` / `ROLE_DELETE` / `ROLE_MENUS` / `ROLE_SCOPE` / `ROLE_MEMBERS`）。租户管理员全院可见（A-WARD-007、C-WARD-007）。

### 3.1 业务角色模板（PLT-ROLE-005，E06-T05）

- 模板定义在平台库 `plt_role_template`（+ `plt_role_template_menu` 默认授权），种子 V105 登记随访模块五个模板：随访员 `FOLLOWUP_NURSE`、责任医生 `FOLLOWUP_DUTY_DOCTOR`、研究者 `FOLLOWUP_RESEARCHER`、CRC `FOLLOWUP_CRC`、资料录入员 `FOLLOWUP_DATA_CLERK`。
- 模块开通流程第 4 步（PLT-MOD-004）与新建租户向导都会把启用模板下发到该租户库（`sys_role` 的 `TEMPLATE` 行 + 菜单授权，幂等；开通记录 detail 写明下发数量）。模板菜单矩阵是暂行口径（OQ-50），敏感按钮不进模板。
- 重新开通模块自动恢复模板；演示租户库由种子 V106 对齐兜底。

## 4. 字典与参数（PLT-CFG-002/004、A-FLD-012、C-FLD-002/003）

| 资源 | 路径 | 权限 |
|---|---|---|
| 租户字典 | `/api/hospital/system/dicts`、`/dicts/{code}/items`（`cfg_dict`、`cfg_dict_item`；条目停用不删除） | `tenant:dict:edit` |
| 租户参数 | `/api/hospital/system/params`（`cfg_param`） | `tenant:param:edit` |
| 个性化 | `/api/hospital/system/brand`（`cfg_brand`，E06-T11 已实现，见 §4.1） | `tenant:brand:edit` |
| 安全设置 | `/api/hospital/system/security-policy`（不得低于平台下限） | `tenant:security:edit` |
| Webhook | `/api/hospital/system/webhooks`（`cfg_webhook`，见 `notify.md`） | `tenant:webhook:edit` |

### 4.1 个性化（E06-T11 / PLT-CFG-008 已实现）

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | `/api/hospital/system/brand` | `tenant:brand:edit` | `{ displayName, defaultName, logoUrl, loginBgUrl, themeColor, themeColorHex, copyright, updatedAt }`；`displayName` 为空表示未配置（前端回落 `defaultName`＝平台租户名称） |
| GET | `/api/hospital/system/brand/theme-colors` | `tenant:brand:edit` | 预设色板 `[{ key, hex, label }]`：BLUE #3A75FF（默认）/ TEAL #1DC29E / ORANGE #FA8C16 / RED #FF4D4F（OQ-23：主色固定，租户只选预设辅助色） |
| PUT | `/api/hospital/system/brand` | `tenant:brand:edit` | `{ displayName, logoUrl, loginBgUrl, themeColor, copyright }` 全量覆盖。显示名称必填（≤128）；素材地址只放行 `http(s)://` 或站内 `/` 路径（≤512，拒绝 `data:` / `javascript:`），素材对象存储在 E10 接入前先存素材 URL（OQ-49）；版权信息 ≤255。保存即时生效（登录页 `GET /auth/tenant?code=` 现读）并写审计 `BRAND_UPDATE` |

`me.tenantName` 与登录页显示名称同口径：个性化 `displayName` 优先，未配置回落平台租户名称；改名后重新登录或会话权限重算时生效。

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

> E06-T11 已实现基本信息、换绑手机号与登录记录（登录即可访问，接口资源表标「公共接口」）； 修改密码与在线会话沿用 `auth.md` §1 的
> `/auth/password`、`/auth/sessions`。通知偏好（A-NTF-007 / C-NTF-007，`ntf_user_pref`）随通知任务落地。

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/hospital/profile` | `{ username, displayName, phoneMasked, roles[], tenantName, createdAt }`；手机号只返回脱敏值（`139****0002`），不提供明文口 |
| PUT | `/api/hospital/profile` | `{ displayName }` 修改本人姓名（账号不可改；1–64 字符）；写审计 `USER_PROFILE_UPDATE` |
| POST | `/api/hospital/profile/phone/sms` | `{ phone }` 换绑第一步：验证码发到**新**手机号（`^1\d{10}$`；与登录 / 重置验证码分命名空间 `rebind:租户:账号:新号`；60 秒冷却与每日上限超限 42900；新号与当前号相同 40001） |
| PUT | `/api/hospital/profile/phone` | `{ phone, code }` 换绑第二步：验证码通过后落新密文与盲索引（本院唯一，被他人绑定 40902）；验证码错误 / 过期 40102；写审计 `USER_PHONE_REBIND`（手机号脱敏） |
| GET | `/api/hospital/profile/login-logs` | 本人最近 20 条登录记录 `[{ success, message, createdAt }]`（按用户名过滤，北京时间 ISO-8601；不含 IP） |

## 8. 在线会话（PLT-AUTH-008/009）

> E04-T06 已实现（权限码挂用户管理菜单下隐藏按钮，平台库 V11 `plt_menu` 312 / 313；`/system/session` 页面属 E06+）。

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | `/api/hospital/system/sessions` | `tenant:session:view` | 本院全部在线会话：`{ sessionId, userId, username, displayName, ip, issuedAt, lastActiveAt }`；`sessionId` 由令牌哈希单向推导，接口不返回令牌 |
| POST | `/api/hospital/system/sessions/{sessionId}/kick` | `tenant:session:kick` | 强制下线：被下线令牌下次请求 40100；写审计 `SESSION_KICK`；会话号不存在 40400 |

## 9. 组织与用户变更事件（PLT-ORG-013）

用户停用、转科、角色变化通过 `sys_outbox` 发布领域事件；随访模块订阅后处理责任人重分配提示（不自动改派，推断，待确认）。
