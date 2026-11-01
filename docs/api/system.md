# 医院端系统管理接口（system）

> 后端：`crdp-system`（用户、角色、组织、字典、参数、通知配置）；病区相关字段在 `crdp-followup` 使用。前缀 `/api/hospital/system/`。
> 页面：`docs/design/pages.md` §10。E06-T02 已落地的组织接口见 §1（原「推断，待确认」已按实现定稿：院区 `/orgs`、科室 `/depts` 分资源；科室批量导入随 E06-T03 落地）；§2 用户、§3 角色、§4 字典参数已随 E06-T05/T07/T09/T10/T11 落地。
> 菜单种子现有权限码：`tenant:user:view`、`tenant:role:view`、`tenant:org:view` / `tenant:org:edit`（view 原种子 `tenant:dept:view`，OQ-11 定论 B 由 V103 改名对齐规格；edit 为平台库 V19 新增按钮 331）。

## 1. 组织：院区、科室、病区（PLT-ORG-001..003、A-WARD-001/008、C-WARD-001/008）

E06-T02 已实现（院区与科室树；病区字典属 `crdp-followup`，科室导入 E06-T03）：

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | `/api/hospital/system/orgs/tree` | `tenant:org:view` | 院区 → 多级科室树（含停用节点）；`{ singleOrg, orgs[] }`，`singleOrg=true` 表示启用院区 ≤ 1，页面可隐藏院区层级（PLT-ORG-001）；节点含 `code/name/status` 与科室的 `deptType/leaderUserId/leaderName/hisCode/sortNo/children`（同级按 `sortNo`、`id` 升序） |
| GET | `/api/hospital/system/orgs/{orgId}/impact` | `tenant:org:edit` | 院区停用影响面：`{ deptCount, enabledDeptCount, memberCount }`（患者数随患者表 E08+ 落地补充） |
| POST / PUT | `/api/hospital/system/orgs[/{orgId}]` | `tenant:org:edit` | 新增 / 编辑院区：`{ code, name }`；编码租户内唯一（重复 40902）、创建后不可改（更新可省略，携带不一致 40001）；写 `ORG_CREATE` / `ORG_UPDATE` 审计 |
| POST | `/api/hospital/system/orgs/{orgId}/status` | `tenant:org:edit` | 停用 / 启用：`{ action: ENABLE\|DISABLE, reason? }`；有启用中的科室时 40901；重复流转 40901；写 `ORG_STATUS` 审计 |
| POST | `/api/hospital/system/depts` | `tenant:org:edit` | 新增科室：`{ orgId, parentDeptId?, code, name, deptType?, leaderUserId?, hisCode?, sortNo? }`；`parentDeptId` 省略或 0 = 院区顶层；写 `DEPT_CREATE` 审计 |
| PUT | `/api/hospital/system/depts/{deptId}` | `tenant:org:edit` | 编辑 / 移动科室（整对象更新）：名称、类型、负责人（必须是本院启用用户）、HIS 编码、排序、所属院区与上级；移入自己子树、上级与院区不一致、上级不存在 → 40901；写 `DEPT_UPDATE` 审计 |
| POST | `/api/hospital/system/depts/{deptId}/status` | `tenant:org:edit` | 停用 / 启用：同院区口径；有启用中的下级科室时 40901；停用科室不再出现在业务选择器（树中保留）；写 `DEPT_STATUS` 审计 |
| GET | `/api/hospital/system/depts/{deptId}/impact` | `tenant:org:edit` | 科室停用影响面：`{ memberCount, childCount, enabledChildCount }` |
| GET | `/api/hospital/system/orgs/import-template` | `tenant:org:edit` | **已实现（E06-T03 / PLT-ORG-003）**。科室导入模板下载：xlsx 单工作表，表头 `院区编码、科室编码、上级科室编码、科室名称、科室类型、HIS科室编码、负责人账号、排序` + 两行示例（全部虚构数据） |
| POST | `/api/hospital/system/orgs/import` | `tenant:org:edit` | **已实现（E06-T03 / PLT-ORG-003）**。科室批量导入（multipart `file`，≤1000 行）：同步一次性模型（OQ-52）——上传即逐行校验并落库，**单行失败不影响其他行**，返回 `{ total, created, updated, failed, rows[{ rowNo, code, action(CREATED\|UPDATED\|FAILED), message }] }`；表头不符 / 无数据行 / 超 1000 行 / 非 xlsx → 40001。规则：院区须已存在；科室编码存在即更新，不存在即新增；上级科室编码可引用库内或**同文件任意位置**的编码（空 = 院区顶层），自引用与循环引用报错；不能借导入跨院区移动；负责人账号须为本院启用用户；写 `ORG_IMPORT` 作业级审计。HIS 同步接口待医院确认（规格原文），首期只做 Excel |

未实现（后续任务）：

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET / POST / PUT | `/api/hospital/followup/wards[/{id}]` | `followup:ward:edit` | 病区字典：名称、编码、默认随访负责人、停用（A-WARD-001、008；C-WARD-001） |
| GET / PUT | `/api/hospital/followup/wards/aliases` | `followup:ward:edit` | 病区值映射（导入时原值 → 病区，`ward_alias`，A-WARD-006） |
| GET | `/api/hospital/followup/wards/{id}/members` | `followup:ward:edit` | 成员与患者数 |

## 2. 用户（PLT-ORG-006..012、A-WARD-002、C-WARD-003；E06-T07 列表 / 详情 / 新建 / 编辑 / 状态 / 重置 / 解锁，E06-T09 权限预览与用户选择器）

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | `/api/hospital/system/users` | `tenant:user:view` | 分页（`pageNo` 从 1、`pageSize` 默认 20 上限 100）；筛选 `keyword`（账号 / 姓名 / 工号）、`deptId`（主科室或负责科室命中）、`wardId`、`roleId`、`status`；手机号脱敏（`139****1001`），附 `mainDeptName` 与启用角色的 `roleNames` |
| GET | `/api/hospital/system/users/{id}` | `tenant:user:view` | 编辑回显：`roleIds[]`、`wardIds[]`（负责病区，A-WARD-002）、`deptScopeIds[]`（负责科室，C-WARD-003）、`mainDeptId`、`staffNo`；未知用户 40400 |
| POST | `/api/hospital/system/users` | `tenant:user:edit` | `{ username, displayName, staffNo?, phone, mainDeptId, roleIds[], wardIds?, deptScopeIds? }`；账号 / 手机号本院唯一（40902）；模板角色不可直接分配（40901）；科室 / 病区 / 角色不存在 40400；初始密码由系统生成、**只随本次响应下发一次**（`temporaryPassword`），首登强制改密；写 `USER_CREATE` 审计。用户数配额（PLT-ORG-010）随后续任务接入 |
| PUT | `/api/hospital/system/users/{id}` | `tenant:user:edit` | 全量覆盖 `{ displayName, staffNo?, phone?, mainDeptId, roleIds[], wardIds[], deptScopeIds[] }`；账号不可改；`phone` 省略＝保持不变、传值＝换绑（本院唯一 40902）；不能移除最后一名启用租户管理员的租户管理员角色（40901）；保存后递增 `permissionVersion`（负责病区 / 科室 / 角色变更立即生效）；写 `USER_UPDATE` 审计 |
| POST | `/api/hospital/system/users/{id}/status` | `tenant:user:edit` | `action=ENABLE\|DISABLE\|RESIGN`，reason ≤200；启用 ⇄ 停用、启用 → 离职（**终态**，不能再启用）；停用与离职立即吊销全部会话；最后一名启用的租户管理员不可停用 / 离职（40901）；写 `USER_STATUS` 审计 |
| POST | `/api/hospital/system/users/{id}/reset-password` | `tenant:user:reset` | 生成一次性临时密码（响应只返回一次），置「首登强制改密」、旧哈希入历史表、该账号全部会话失效并解除登录锁定；写 `USER_RESET_PASSWORD` 审计。二次认证（PLT-AUTH-012）随敏感操作框架落地，本接口暂以权限码 + 确认弹窗把关（推断，待确认） |
| POST | `/api/hospital/system/users/{id}/unlock` | `tenant:user:reset` | 解锁（E04-T05 落地，清登录失败计数桶）；写 `USER_UNLOCK` 审计 |
| GET | `/api/hospital/system/wards/options` | `tenant:user:view` | 病区候选（用户编辑页「负责病区」多选，只读启用项；病区字典维护属病区管理任务，届时替换） |
| GET | `/api/hospital/system/users/{id}/effective-permissions` | `tenant:user:view` | **已实现（E06-T09 / PLT-ORG-009、A-WARD-009、C-WARD-008）**。权限预览：`{ userId, username, displayName, status, roles[]（含停用行，带 roleType）, openedModules[], menus[]（树含按钮，逐项带 sourceRoles）, permissionCodes[], scopeAll, scopeTypes[]（带 sourceRoles）, wards[]（可见病区，带 sourceRoles）, depts[]（负责科室，带 sourceRoles） }`；与登录会话同一计算入口（EffectivePermissionService 三层交集 + 快照同口径范围归并），实时不缓存、只读不写审计；字段策略（PLT-PERM-009）随 E07-T04 / T06 落地后补充 |
| POST | `/api/hospital/system/users/import` | `tenant:user:import` | 批量导入（PLT-ORG-007）——**随 E06-T08 落地** |
| GET | `/api/hospital/system/users/options?keyword=&deptId=` | 登录（@PublicApi） | **已实现（E06-T09 / PLT-ORG-012）**。用户选择器：本院启用用户，`[{ id, username, displayName, deptName }]`（不含手机号等敏感字段；账号与科室名用于同名人区分）；keyword 命中账号 / 姓名 / 工号，deptId 命中主科室或负责科室，最多 50 条按姓名排序；原 §1 `/orgs/user-options`（暂行，tenant:org:view）已移除（未登记 40307），科室负责人与角色成员选择器均改走本接口 |

用户状态取值 `ENABLED / DISABLED / RESIGNED`（「锁定」是登录侧 Redis 计数的瞬时态，不落 `sys_user.status`，列表不展示，解锁按钮常显）。`sys_user` 新增 `staff_no`、`main_dept_id`（租户库 V12）；负责病区 `sys_user_ward` 与负责科室 `sys_user_dept_scope` 沿用 V1 表，按用户全量覆盖。七种数据范围类型的生效计算已由 E07-T02 落地（主科室 / 负责病区 / 负责科室 / 角色自定义科室并集进会话快照，见 `docs/api/README.md` §2.5）；兼职科室、岗位 / 职称、账号有效期仍未落地（随字典与后续任务）；离职转办事件随 E06-T03。

## 3. 角色与数据范围（PLT-ORG-004/005、PLT-ROLE-004/005、PLT-PERM-007..009、A-WARD-003、C-WARD-004；E06-T05 已实现）

角色类型（`roleType`）：`BUILT_IN` 内置（租户管理员，不可删除、不可修改权限、不可停用，也不能移除最后一名启用成员）/ `TEMPLATE` 模板（模块开通时由平台下发，只读）/ `CUSTOM` 自定义（可编辑、复制、停用、删除——删除仅限无成员时）。

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | `/api/hospital/system/roles` | `tenant:role:view` | 列表（内置 / 模板 / 自定义，附 `memberCount` / `menuCount` / `moduleCode` / `description`） |
| POST / PUT / DELETE | `/api/hospital/system/roles[/{id}]` | `tenant:role:edit` | 角色基本信息：新建自定义角色（编码租户内唯一 40902、创建后不可改）、编辑（仅自定义，编码不可改 40001）、删除（仅无成员的自定义角色 40901） |
| POST | `/api/hospital/system/roles/{id}/copy` | `tenant:role:edit` | 复制自定义角色：菜单、数据范围与自定义科室一并复制，写 `ROLE_COPY` 审计 |
| POST | `/api/hospital/system/roles/{id}/status` | `tenant:role:edit` | 停用 / 启用（`action=ENABLE|DISABLE`，reason ≤200）；内置与模板 40901 |
| GET / PUT | `/api/hospital/system/roles/{id}/menus` | `tenant:role:view` / `edit` | 菜单与按钮；**只能选平台授权范围内的项**（超出返回 40001），返回树带 `assignable` 标记；仅自定义角色可改（内置不可修改权限、模板只读 40901） |
| GET / PUT | `/api/hospital/system/roles/{id}/scope` | `tenant:role:view` / `edit` | 数据范围类型（多选取并集）：`ALL / WARD / RESPONSIBLE_DEPT / RESPONSIBLE_PATIENT / DEPT / SELF / CUSTOM_DEPT`（平台定义七种，与 `HospitalDirectory` 同口径；生效计算已由 E07-T02 落地：WARD/RESPONSIBLE_DEPT 取用户负责清单、DEPT 取主科室、CUSTOM_DEPT 取本角色科室清单，多角色并集，病区与科室在 SQL 改写中 OR 组合）与 `RESEARCH_PROJECT`（研究项目扩展类型，E07-T03 / PLT-PERM-008：成员项目经扩展点 `ResearchProjectScopeProvider` 冻结进快照，规则由 rct 模块实现；空实现下该分支不放宽任何数据，见 `README.md` §2.5 与 OQ-53）；`CUSTOM_DEPT` 必须带 `customDeptIds[]`（本院科室，不存在 40400） |
| GET / PUT | `/api/hospital/system/roles/{id}/members` | `tenant:role:view` / `edit` | 成员（全量覆盖 `userIds[]`）；模板角色不可直接分配（40901，先复制）；不能移除最后一名启用的租户管理员（40901）；加入的用户必须是启用状态 |
| POST | `/api/hospital/system/roles/from-template` | `tenant:role:edit` | 应用业务角色模板（PLT-ORG-005）：`{ templateId, code, name, description? }` → 复制生成自定义角色（菜单与范围随模板带来） |
| GET | `/api/hospital/system/roles/{id}/field-policies` | `tenant:role:edit` | 字段策略：`VISIBLE / MASKED / HIDDEN`，不能宽于平台上限（PLT-SCOPE-007）——**随 E07-T04 / T06 落地** |

保存角色授权 / 成员 / 状态后递增受影响用户的 `permissionVersion`（PLT-PERM-010），在线会话下一次请求即按新权限重算；全部写租户库审计（`ROLE_CREATE` / `ROLE_UPDATE` / `ROLE_COPY` / `ROLE_STATUS` / `ROLE_DELETE` / `ROLE_MENUS` / `ROLE_SCOPE` / `ROLE_MEMBERS`）。租户管理员全院可见（A-WARD-007、C-WARD-007）。

### 3.1 业务角色模板（PLT-ROLE-005，E06-T05）

- 模板定义在平台库 `plt_role_template`（+ `plt_role_template_menu` 默认授权），种子 V105 登记随访模块五个模板：随访员 `FOLLOWUP_NURSE`、责任医生 `FOLLOWUP_DUTY_DOCTOR`、研究者 `FOLLOWUP_RESEARCHER`、CRC `FOLLOWUP_CRC`、资料录入员 `FOLLOWUP_DATA_CLERK`。
- 模块开通流程第 4 步（PLT-MOD-004）与新建租户向导都会把启用模板下发到该租户库（`sys_role` 的 `TEMPLATE` 行 + 菜单授权，幂等；开通记录 detail 写明下发数量）。模板菜单矩阵是暂行口径（OQ-50），敏感按钮不进模板。
- 重新开通模块自动恢复模板；演示租户库由种子 V106 对齐兜底。

## 4. 字典与参数（PLT-CFG-002/004、A-FLD-012、C-FLD-002/003）

> 字典与参数已实现（E06-T10，`DictParamOverrideIT`；页面：机构设置「参数 / 字典」页签，pages.md §10）。字典是「平台定义、租户增量覆盖」模型：`cfg_dict` 只存本院自有字典，`cfg_dict_item` 的 PLATFORM 行是平台项在本院的停用标记、TENANT 行是本院自有项；生效集 =（平台项 − 本院停用）∪ 本院自有项，平台新增项自动出现。参数覆盖存 `cfg_param`（租户覆盖值），每次覆盖 / 恢复写 `cfg_param_log` 变更记录与租户审计（验收：租户覆盖平台默认值并留记录）。查看与维护同码。

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | `/api/hospital/system/dicts` | `tenant:dict:edit` | **已实现（E06-T10）**。字典类型列表（平台继承 + 本院自有，按编码排序）：`{code,name,description,source(PLATFORM/TENANT),status,itemCount(生效项数),tenantItemCount,disabledPlatformCount,updatedAt}` |
| POST | `/api/hospital/system/dicts` | `tenant:dict:edit` | **已实现**。新建本院字典 `{code,name,description?}`；编码本院唯一（含与平台字典冲突 40902）、`[a-z][a-z0-9_]` 2–64 位、创建后不可改；写 DICT_CREATE 审计 |
| PUT | `/api/hospital/system/dicts/{code}` | `tenant:dict:edit` | **已实现**。维护本院字典（名称 / 说明 / 停用启用）；平台字典不可修改（40001）；未知 40400；写 DICT_UPDATE 审计 |
| GET | `/api/hospital/system/dicts/{code}/items` | `tenant:dict:edit` | **已实现**。字典项（管理视角保留停用行可恢复，按 sortNo、itemCode 升序）：`{itemCode,label,sortNo,status,source,editable(平台项 false),updatedAt}` |
| POST | `/api/hospital/system/dicts/{code}/items` | `tenant:dict:edit` | **已实现**。新增本院自有项 `{itemCode,label,sortNo?}`（加进平台字典或本院字典）；项编码在生效集内唯一（含平台项，40902）；写 DICT_ITEM_ADD 审计 |
| PUT | `/api/hospital/system/dicts/{code}/items/{itemCode}` | `tenant:dict:edit` | **已实现**。本院自有项可改文字 / 排序 / 停用启用；平台项**只能停用 / 恢复**（只传 `status`；改文字或排序 40001，PLT-CFG-002「租户不能删除或修改平台字典项」）；写 DICT_ITEM_UPDATE 审计 |
| GET | `/api/hospital/system/params` | `tenant:param:edit` | **已实现（E06-T10）**。参数列表（平台定义 + 本院覆盖状态，按参数键排序）：`{paramKey,name,description,valueType,platformValue,tenantOverridable,valueRange,overridden,tenantValue,effectiveValue,stale,updatedAt}`；`stale=true` 表示参数被平台锁定、本院覆盖失效（生效值回落平台值并提示，覆盖行保留） |
| PUT | `/api/hospital/system/params/{paramKey}` | `tenant:param:edit` | **已实现**。覆盖平台默认值 `{value,reason?}`：取值须过类型与范围校验（NUMBER 闭区间 / ENUM 选项 / TEXT 长度 / BOOLEAN / JSON，40001）；平台锁定参数不可覆盖（40001）；未知 40400；reason ≤200 写记录；写 `cfg_param_log`（OVERRIDE）与 PARAM_OVERRIDE 审计 |
| DELETE | `/api/hospital/system/params/{paramKey}` | `tenant:param:edit` | **已实现**。恢复平台默认值（删覆盖行，幂等）；有覆盖时写 `cfg_param_log`（RESET）与 PARAM_RESET 审计 |
| GET | `/api/hospital/system/params/logs` | `tenant:param:edit` | **已实现**。覆盖变更记录（倒序，最近 100 条）：`[{paramKey,action(OVERRIDE/RESET),oldValue,newValue,reason,operatorId,createdAt}]` |

平台字典 / 系统参数的平台端维护接口见 `platform.md` §7；平台保存即作废定义缓存（`PlatformConfigCache`），本院下一次读取即见新值（PLT-CFG-011 即时生效；平台参数被锁定后本院已覆盖的值失效并提示，重新放开自动恢复）。

| 资源 | 路径 | 权限 |
|---|---|---|
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

## 9. 组织与用户变更事件（PLT-ORG-013；E06-T03 已实现落表，分发随 E09）

用户停用 / 离职、调岗（主科室变化）、角色变化、科室停用、院区停用在**同一事务**内写入租户库 `sys_outbox`（base 迁移 V14：`event_type / payload(JSON) / status='PENDING' / created_at`；框架 `JdbcOutboxPublisher` 替换 M0 占位，只能在租户上下文调用）；分发（PENDING → SENT、投递订阅方）属 E09 通知任务，随访模块订阅后提示责任人转交、**不自动改派**（OQ-10）。信封统一 `tenantCode / operatorId / occurredAt(ISO-8601 北京时间)`，另带 `reason`（停用 / 离职时如填）：

| 事件类型 | 触发 | 专有负载 |
|---|---|---|
| `user.disabled` / `user.resigned` | 用户停用 / 离职（会话已下线） | `userId / username / displayName` |
| `user.dept_changed` | 编辑后主科室变化 | `previousMainDept / newMainDept（{id,name}）` |
| `user.roles_changed` | 编辑后角色集合变化 | `previousRoleCodes / newRoleCodes` |
| `dept.disabled` | 科室停用 | `deptId / deptCode / deptName / orgId / orgName` |
| `org.disabled` | 院区停用 | `orgId / orgCode / orgName` |

病区停用事件随病区管理任务（crdp-followup ward）同表发布。
