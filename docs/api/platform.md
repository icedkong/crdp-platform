# 平台端接口（platform）

> 后端：`crdp-platform`；前缀 `/api/platform/`；只访问平台库 `plat`（技术方案“平台库 plat”一行：平台库不存任何医院的用户、患者与业务数据）。
> 页面：`docs/design/pages.md` §11。除 §1 标注「已实现」的行外，其余接口路径仍为 **推断，待确认**。
> 所有写操作写平台审计（PLT-AUD-004）；涉及高敏的操作要求二次认证（PLT-AUTH-012，请求头 `X-Reauth-Token`）。

## 1. 租户（PLT-TEN-001..022）

| 方法 | 路径 | 权限 | 说明 | 需求 |
|---|---|---|---|---|
| GET | `/api/platform/tenants` | `platform:tenant:view` | **已实现（E05-T01）**。分页 `{items,total,pageNo,pageSize}`；筛选 `keyword`（名称/编码 LIKE）、`status`（PLT-TEN-010 七态 + `PROVISIONING`）、`expireBefore`（yyyy-MM-dd，只含有到期日的）、`moduleCode`、`sort`（`createdAt/name/code/expireAt`，`-` 前缀降序，默认 `-createdAt`）；行：`id,code,name,shortName,status,expireType,expireAt,modules[{code,name}],userCount(租户库不可达为 null),createdAt`。列表不返回电话 | PLT-TEN-001 |
| POST | `/api/platform/tenants` | `platform:tenant:create` | **已实现（E02-T07；到期设置 E05-T02 接入）**。body `{ name, code?, adminUsername?, adminDisplayName?, adminInitialPassword?, expireType?, expireAt?, remindDays?, graceDays?, graceReadOnly? }`，同步跑开通编排并返回 `{ tenantId, code, steps[{step,status,error}] }`；到期五项整体省略 = 长期有效，`FIXED` 时 `expireAt` 必填且须晚于当天，未填的提醒节点（默认 `30,7,1`）与宽限期（默认 15 天）由后端取默认值；`modules[]`（勾选的业务模块，base 必开不传，E05-T05 / PLT-MOD-004：落 OPEN 登记、初始化配置默认值并把模块菜单授予租户管理员；下架模块 40901）；`quota` 随 E05-T09 接入 | PLT-TEN-002、011、PLT-MOD-004 |
| GET | `/api/platform/tenants/{id}/provision` | `platform:tenant:view` | **已实现（E02-T08）**。开通进度：步骤 `tenant-row / resource-row / database / migrate-register / admin`，每步 `status(SUCCESS/FAILED/SKIPPED), error` | PLT-TEN-003..008 |
| POST | `/api/platform/tenants/{id}/retry` | `platform:tenant:create` | **已实现（E02-T08）**。从失败步骤重试（编排可重入） | PLT-TEN-008 |
| DELETE | `/api/platform/tenants/{id}` | `platform:tenant:create` | **已实现（E02-T08）**。放弃并回收已开通资源；仅 `PROVISIONING` 租户，已开通完成的走正式注销 | PLT-TEN-008 |
| GET | `/api/platform/tenants/{id}` | `platform:tenant:view` | **已实现（E05-T01；到期设置字段 E05-T02 补齐）**。基本信息 + 联系人 + 备注（含电话明文，供维护编辑，见 OQ-35）+ 到期设置 `remindDays, graceDays, graceReadOnly`（长期有效时前两项为 null）；资源信息（数据源、桶、密钥 ID）**脱敏只读**、不得返回连接串和密码——随 `platform:tenant:resource:view` 页签提供（推断，待确认） | PLT-TEN-009、011、015 |
| PUT | `/api/platform/tenants/{id}` | `platform:tenant:edit` | **已实现（E05-T01）**。body `{ name(必填、全平台唯一，冲突 40901), shortName?, creditCode?, orgType?, region?, contactName?, contactPhone?(信封加密存储，OQ-34), contactEmail?, contractNo?, remark? }`，空白一律清空；有字段变更写一条 `TENANT_UPDATE` 审计（只记字段名，不落明文）；返回更新后详情 | PLT-TEN-009、015 |
| GET | `/api/platform/tenants/{id}/status/impact` | `platform:tenant:view` | **已实现（E05-T02）**。状态变更影响面 `{ tenantId, code, name, status, onlineUserCount, pendingTaskCount }`（在线用户数取「租户→令牌」会话索引；`fu_task` 未建时 `pendingTaskCount` 为 null），供停用 / 启用 / 续期对话框二次确认展示 | PLT-TEN-010 |
| POST | `/api/platform/tenants/{id}/status` | `platform:tenant:status` | **已实现（E05-T02；停用处理 E05-T03 补齐）**。`{ action: ENABLE|DISABLE, reason }`（reason 必填 ≤200 字）；流转严格按规格状态图：`ENABLE` 为转正（试用→启用）或恢复（停用→启用）、`DISABLE` 仅启用→停用（手动停用），其余边一律 40901（试用/宽限期不能直接停用、宽限期走续期恢复、开通/注销各态见相应任务）；成功返回 `{ tenantId, code, previousStatus, status, onlineUserCount }` 并写 `TENANT_STATUS` 审计（含原因）。停用处理（PLT-TEN-016，E05-T03）：`DISABLE` 即吊销该院全部在线会话（吊销数记入审计，返回的 onlineUserCount 为吊销后的剩余在线数 0）并开一段停用时段，登录闸门按租户状态拒绝、定时作业扇出清单不再含停用租户（即「暂停定时任务」），数据不动；`ENABLE`（停用→启用）关闭停用时段 | PLT-TEN-010、016、017 |
| PUT | `/api/platform/tenants/{id}/expire` | `platform:tenant:renew` | **已实现（E05-T02；续期关段 E05-T03 补齐）**。到期设置 / 续期 `{ expireType(FIXED/LONG_TERM), expireAt?, remindDays?, graceDays?, graceReadOnly?, reason }`：`FIXED` 时 `expireAt` 必填且晚于当天（北京时间）、`remindDays` 每项 1..365（去重降序存储，默认 `[30,7,1]`）、`graceDays` 1..365（默认 15）；省略项沿用当前配置，首次配置取默认；`LONG_TERM` 清空到期日与提醒、宽限（长期有效不提醒、不进入宽限期）。**宽限期或停用中的租户续期后立即恢复启用**（PLT-TEN-013；停用中续期同时关闭停用时段，E05-T03）；开通中 / 注销流程中 40901；返回 `{ tenantId, code, previousStatus, status, expiry, changedFields }` 并写 `TENANT_EXPIRE` 审计（只记变化字段与原因）。到期提醒与自动转宽限 / 宽限期满自动停用由每日作业执行（E05-T03，见下） | PLT-TEN-011、012、013 |
| GET | `/api/platform/tenants/{id}/suspensions` | `platform:tenant:view` | **已实现（E05-T03）**。停用时段（PLT-TEN-017 底座）：`[{ id, tenantId, cause(MANUAL 人工停用/AUTO_GRACE_END 宽限期满自动), reason, disabledAt, restoredAt(空=仍在停用中), restoreCause(RENEWAL/MANUAL_ENABLE) }]` 倒序；停用补偿（停用期间本应执行的任务标记“停用期间未执行”、恢复后人工补做或关闭）由业务模块按时段推导（fu_task 创建方式，E14-T06），底座不自动补发任何任务或通知 | PLT-TEN-017 |
| PUT | `/api/platform/tenants/{id}/quota` | `platform:tenant:edit` | 配额 | PLT-TEN-014 |
| POST | `/api/platform/tenants/{id}/cancel` | `platform:tenant:cancel` | 注销申请（上传书面确认 fileId）；后续 `.../cancel/approve`、`.../export`、`.../export/ack`、`.../destroy`、`.../retain` | PLT-TEN-018..021 |
| GET | `/api/platform/tenants/{id}/logs` | `platform:tenant:view` | 租户操作记录 | PLT-TEN-022 |

### 1.1 到期处理作业（PLT-TEN-012，E05-T03，无 HTTP 接口）

每日 00:20（Asia/Shanghai）执行，应用启动就绪后补跑一次（停机跨过定时点的兜底）；按租户扇出并写 `job_tenant_run`（job_code=`plt-tenant-expire`，补跑区间 = 该租户最近成功日 + 1 至当天，首次执行从租户创建日起，ADR 0003）：

1. **到期前提醒**：逻辑日命中租户提醒节点（到期前 N 天，默认 30、7、1）时，解析接收人——租户管理员（租户库 `sys_user`·TENANT_ADMIN）与平台负责人（当前回落到全部启用的 PLATFORM_ADMIN，见 OQ-45）——各落一条 `plt_tenant_expire_notice` 派发行（渠道 INBOX+SMS、状态 PENDING；唯一键保证同节点不重发）。站内信 / 短信实际通道随 E09 接入后消费该表（PENDING→SENT/FAILED）。
2. **到期转宽限期**：试用 / 启用且有到期日的租户，到期日不晚于逻辑日（覆盖停机补跑）→ `GRACE`，写 `TENANT_STATUS` 审计（注明“到期自动”）。宽限期覆盖 `[到期日, 到期日 + 宽限天数)`；宽限期允许登录（登录闸门），未配只读时正常使用，配置只读时医院端写请求 40306（读与 `/api/hospital/auth/**` 照常，`GET /api/hospital/auth/tenant-state` 供前端续费提示）。
3. **宽限期满自动停用**：逻辑日到达到期日 + 宽限天数仍未续期 → `DISABLED`：吊销该院全部在线会话、开停用时段（cause=AUTO_GRACE_END）、写 `TENANT_STATUS` 审计；停用租户不再进入作业扇出清单（暂停定时任务）、登录被拒、数据保留。长期有效租户不提醒、不进入宽限期。

## 2. 模块（PLT-MOD-001..011）

| 方法 | 路径 | 权限 | 说明 | 需求 |
|---|---|---|---|---|
| GET | `/api/platform/modules` | `platform:module:view` | **已实现（E05-T04）**。模块目录：`[{code,name,description,baseModule,status(ON 上架/OFF 下架),featureVersion,migrationVersion,dependsOn[],syncedAt,menus[{id,side,type,title}],permissions[],configItems[{code,name,defaultValue,description}],versions[{featureVersion,migrationVersion,changeNote,createdAt}](倒序)}]`。菜单与权限码清单按 `plt_menu.module_code` 归属（随发版由迁移登记），配置项与版本说明来自代码登记（首期只登记 `followup`，`base` 为种子数据），版本历史存 `plt_module_version` | PLT-MOD-001、003 |
| POST | `/api/platform/modules/sync` | `platform:module:edit` | **已实现（E05-T04）**。手动触发注册同步（与启动时自动同步同一套逻辑）：登记模块缺失则注册（默认上架 ON）、版本（功能 / 迁移 / 依赖）变化则更新并写一条版本记录（PLT-MOD-003）、无变化只刷新 `synced_at`；**不覆盖**平台端维护的展示信息与上架状态；返回 `{outcomes:[{code,action(REGISTERED\|UPDATED\|UNCHANGED),featureVersion,migrationVersion}]}`；有注册 / 更新写 `MODULE_SYNC` 审计 | PLT-MOD-002、003 |
| PUT | `/api/platform/modules/{code}` | `platform:module:edit` | **已实现（E05-T04）**。维护展示信息与上下架（平台端对目录仅有的写入口）：`{name(必填 ≤64),description?(≤255),status?(ON\|OFF)}`；基础模块下架 40901、不存在 40400；有变更写 `MODULE_UPDATE` 审计（记字段名与上架状态流转），返回更新后的目录行 | PLT-MOD-002 |
| GET | `/api/platform/tenants/{id}/modules` | `platform:tenant:view` | **已实现（E05-T05）**。租户详情 · 模块页签：`{ tenantId, code, name, tenantStatus, modules[{code,name,description,baseModule,shelfStatus,featureVersion,migrationVersion,dependsOn[],status(OPENING/OPEN/OPEN_FAILED/MAINTENANCE/CLOSED，null=未开通),currentMigrationVersion,openedAt,closedAt,reason}] }` | PLT-MOD-004、005 |
| GET | `/api/platform/tenants/{id}/modules/{code}/close-impact` | `platform:tenant:view` | **已实现（E05-T05）**。关闭影响评估 `{ tenantId, tenantCode, moduleCode, moduleName, onlineUserCount, pendingTaskCount(fu_task 未建为 null), dependentModules[] }`，供二次确认展示；模块未开通 40901 | PLT-MOD-005 |
| POST | `/api/platform/tenants/{id}/modules/{code}/open` | `platform:tenant:module:open` | **已实现（E05-T05）**。开通：`{reason?}`（≤200 留痕）；校验租户状态（试用/启用/宽限期）与上架（下架 40901）、依赖模块全部已开通（40901 带依赖说明）→ 置开通中 → 仅对该租户库执行该模块 Flyway 迁移（失败置开通失败并写 MODULE_OPEN 审计告警，40901）→ 模块医院端菜单授予租户库 TENANT_ADMIN（授权范围上限是 E05-T07）→ 初始化配置默认值 → 递增该院全部用户权限版本（在线会话下一次请求即见菜单）；返回 `{ tenantId, tenantCode, moduleCode, previousStatus, status, migrationVersion }` | PLT-MOD-004、006 |
| POST | `/api/platform/tenants/{id}/modules/{code}/close` | `platform:tenant:module:close` | **已实现（E05-T05）**。关闭：`{reason}`（必填 ≤200）；基础模块 40901、被已开通模块依赖 40901（先关依赖方）；只收回菜单与接口（openedModules 快照 + `@RequireModule` 40301），数据与租户库角色授权保留（重开自动恢复），递增权限版本使在线会话立即生效；模块级定时任务随业务作业接入（E14 起按模块校验）；写 MODULE_CLOSE 审计与开通记录 | PLT-MOD-005、006 |
| GET / PUT | `/api/platform/tenants/{id}/modules/{code}/config` | `platform:tenant:edit` | **已实现（E05-T05）**。模块级配置（PLT-MOD-008）：GET `{ items[{code,name,defaultValue,description,value(租户覆盖，null=用默认),effectiveValue,updatedAt}] }`；PUT `{ values:{配置项编码:取值} }`（未知项 40001、取值必填 ≤255、未开通模块 40901），只记变化项写 MODULE_CONFIG 审计；**功能开关 `cfg_feature` 的平台端读写接口不在本行**（各院开关值由租户库种子 / 配置模板承载，管理界面随后续任务） | PLT-MOD-008 |
| GET / PUT | `/api/platform/tenants/{id}/features` | `platform:tenant:edit` | 功能开关列表 `{ featureCode, enabled }[]`（各院功能差异用，值在租户库 cfg_feature；平台端管理界面随后续任务接入） | 技术方案“各院功能不同” |
| POST | `/api/platform/upgrades` | `platform:upgrade:execute` | 创建升级批次（灰度租户列表） | PLT-MOD-007 |
| GET | `/api/platform/modules/{code}/open-logs` | `platform:module:view` | **已实现（E05-T05）**。开通记录（PLT-MOD-009）：`[{id,tenantId,tenantCode,tenantName,moduleCode,action(OPEN/CLOSE，UPGRADE 随 PLT-MOD-007),reason,detail,operatorId,createdAt}]` 倒序；`?tenantId=` 过滤 | PLT-MOD-009 |

PLT-MOD-010/011 为后续规划，不实现。

## 3. 菜单、接口资源、字段资源（PLT-PERM-001..012）

| 方法 | 路径 | 权限 | 说明 | 需求 |
|---|---|---|---|---|
| GET | `/api/platform/menus/tree?side=HOSPITAL\|PLATFORM` | `platform:menu:view` | **已实现（E05-T06）**。全量菜单树（含按钮与停用行；软删行不返回） | PLT-PERM-001、002、003 |
| POST | `/api/platform/menus` | `platform:menu:edit` | **已实现（E05-T06）**。新建目录 / 菜单 / 按钮：`moduleCode` 必填且须已登记（PLT-PERM-002）；按钮必填全局唯一权限码（PLT-PERM-003，40001 校验）；菜单必填路由名 / 路径 / 视图；平台端新建的项 `source=CUSTOM` | PLT-PERM-001..003 |
| PUT | `/api/platform/menus/{id}` | `platform:menu:edit` | **已实现（E05-T06）**。维护标题、图标、排序、隐藏、路由字段、停用；端别 / 类型 / 模块 / 权限码 / 来源不可改（权限码创建后不可改名，OQ-11）；启用 / 停用切换递增受影响用户权限版本——平台账号直接查 `plt_role_menu`，医院端菜单逐租户按「经启用角色持有该菜单」递增（E06-T06 / PLT-PERM-010） | PLT-PERM-001、003 |
| DELETE | `/api/platform/menus/{id}` | `platform:menu:edit` | **已实现（E05-T06）**。软删（status=DELETED）：移除 `plt_role_menu` 授予、逐租户清理 `sys_role_menu`（不可达跳过）、递增受影响平台账号与租户用户权限版本（E06-T06：租户用户在清理前收集，删除后统一递增）；有子项 40001 | PLT-PERM-001 |
| GET | `/api/platform/menus/sync/diff` | `platform:menu:view` | **已实现（E05-T06）**。清单差异（只读）：随包模块清单 vs 库内 `source=MANIFEST` 行——ADDED / CHANGED（结构字段）/ OFFLINE | PLT-PERM-011 |
| POST | `/api/platform/menus/sync` | `platform:menu:edit` | **已实现（E05-T06）**。平台确认后应用差异：新增插入（停用 / 软删行按新增复活）、变更改结构字段（标题 / 排序等平台端维护值不覆盖）、下线置停用；写 MENU_SYNC 审计 | PLT-PERM-011 |
| GET | `/api/platform/apis` | `platform:api:view` | **已实现（E05-T06）**。接口资源（方法 + 路径模式、权限码、模块、公共 / 审计级别 / 敏感级别 / 动作类型 QUERY/WRITE/EXPORT/DOWNLOAD、来源）；`moduleCode` / `permCode` 可选过滤，`unbound=true` 只看未绑定 | PLT-PERM-004 |
| PUT | `/api/platform/apis/{id}` | `platform:api:edit` | **已实现（E05-T06）**。标记公共接口、审计级别（NONE/OPERATION/DATA_ACCESS）、敏感级别（NORMAL/SENSITIVE）、动作类型；可给未加注解的接口补绑权限码；未绑定又非公共 40001；写 API_UPDATE 审计并即时刷新默认拒绝闸门 | PLT-PERM-004 |
| POST | `/api/platform/apis/sync` | `platform:api:edit` | **已实现（E05-T06）**。手动触发登记同步（与启动同一套扫描逻辑），返回每条 REGISTERED / UPDATED / UNCHANGED / REMOVED | PLT-PERM-004 |
| GET / PUT | `/api/platform/fields` | `platform:field:edit` | 字段资源：敏感分级、默认策略、脱敏规则（E08+） | PLT-PERM-009 |
| GET | `/api/platform/resource-logs` | `platform:menu:view` | 资源变更记录（E05-T10，P2） | PLT-PERM-012 |

**接口默认拒绝（E05-T06 验收）**：启动扫描把全部 `/api/**` 处理方法按 `@RequiresPermission`（权限码）、`@RequireModule`（模块，否则按权限码前段匹配 plt_module，兜底 base）、`@PublicApi`（公共接口，登录即可；与权限码同标时以权限码为准）登记进 `plt_api_resource`；`ApiResourceGuardFilter` 对 `/api/**` 按「方法 + 路径」核对——未登记或「未绑定权限码且非公共」一律 **40307**，绑定权限码的会话无码 **40300**（与 `@RequiresPermission` 同码同口径），模块未开通放行给 `@RequireModule` 返回 40301（校验链顺序：登录态 → 租户状态 → 模块开通 → 权限码）。同步不覆盖平台端标记的审计级别 / 敏感级别 / 动作类型；SCAN 来源行随接口消失自动清除，SEED / MANUAL 行保留。

> 医院端菜单改名（如 患者档案 → 患者）只能通过**新 Flyway 迁移**修改种子，不能改已执行的迁移（见 `pages.md` §0）。

## 4. 授权范围（PLT-SCOPE-001..008）

> 已实现（E05-T07，`PlatformTenantScopeMySqlIT`）。授权范围不落表：= 该租户已开通模块（`plt_tenant_module.status='OPEN'`）下的医院端启用菜单与按钮，随模块开通 / 关闭自动扩张 / 收缩（PLT-SCOPE-001）。生效可分配集 = base 固定能力 ∪（模板默认 ∩ 非敏感，按租户覆盖 `plt_tenant_scope` 调整）∩ 授权范围；医院端会话草稿按「已开通模块 ∩ 可分配范围 ∩ 角色并集」求交（`AuthService.grant`）。

| 方法 | 路径 | 权限 | 说明 | 需求 |
|---|---|---|---|---|
| GET | `/api/platform/tenants/{id}/scope` | `platform:tenant:scope:view` | **已实现（E05-T07）**。按模块分组的医院端菜单 / 按钮清单：`{tenantId,tenantCode,tenantName,tenantStatus,modules[{code,name,baseModule,opened,items[{id,parentId,type,title,permCode,moduleCode,sensitive,defaultAssignable,assignable,fixed,state(CLOSED/NOT_RELEASED/ASSIGNABLE),releaseReason,setAt}]}]}`；base 项 `fixed=true` 恒可分配（组织、用户、角色管理固定能力，平台规格 §5） | PLT-SCOPE-001、002、004 |
| POST | `/api/platform/tenants/{id}/scope/preview` | `platform:tenant:scope:edit` | **已实现（E05-T07）**。变更影响分析（只读）：body 与 PUT 相同；返回 `{removed[],added[],sensitiveReleases[],affectedRoles[{roleId,roleCode,roleName,removedMenuCount,removedPermCodes[],userCount}],affectedUserCount}`；租户库不可达 40901 | PLT-SCOPE-005 |
| PUT | `/api/platform/tenants/{id}/scope` | `platform:tenant:scope:edit` | **已实现（E05-T07）**。body `{assignableMenuIds[](期望全集，base 固定项必须全带，缺失 40001),sensitiveReasons{menuId:原因}(本次放开敏感项必填，≤200),reason(必填 ≤200)}`；勾选项必须是授权范围内的医院端启用菜单（未开通模块 / 未知 / 平台端菜单 40001）；保存即**自动收回**：`DELETE sys_role_menu WHERE menu_id IN (移除项)`（租户库不可达整体 40901，不留中间态）+ 受影响用户 `permissionVersion` 递增（在线会话下一次请求即重算）；写 `plt_scope_log`（前后差异）与 SCOPE_UPDATE 审计；范围扩大不自动授予任何角色 | PLT-SCOPE-002、004、006、008 |
| GET | `/api/platform/tenants/{id}/scope/logs` | `platform:tenant:scope:view` | **已实现（E05-T07）**。变更记录倒序：`[{id,tenantId,reason,detail(前后差异),operatorId,createdAt}]` | PLT-SCOPE-008 |
| GET | `/api/platform/scope-template` | `platform:scope:template:edit` | **已实现（E05-T07）**。模板视图：`{modules[{code,name,baseModule,items[{menuId,parentId,title,permCode,sensitive,inTemplate}]}],sensitivePerms[{id,permCode,name}]}` | PLT-SCOPE-003、004 |
| PUT | `/api/platform/scope-template/{moduleCode}` | `platform:scope:template:edit` | **已实现（E05-T07）**。保存模块模板（替换式）：`{menuIds[]}`；base 模块 40001（固定能力不进模板）、跨模块菜单 40001、敏感项 40001（只能按租户单独放开）；写 SCOPE_TEMPLATE_UPDATE 审计。模板只改默认值：对已开通租户的生效集在下次会话重算 / 登录时收敛，存量角色收回须走租户级 PUT scope（影响分析 + 确认） | PLT-SCOPE-003 |
| PUT | `/api/platform/scope-template/sensitive-perms` | `platform:scope:template:edit` | **已实现（E05-T07）**。敏感权限清单整表替换：`{items[{permCode,name}]}`；权限码须符合 `{模块}:{资源}:{动作}`（未绑定菜单的码先登记后生效，如 rct 后续项），清单内唯一、说明必填 ≤128；写 SENSITIVE_PERM_UPDATE 审计。清单增减只影响默认值，已放开租户的覆盖不受影响 | PLT-SCOPE-004 |

敏感清单种子为规格确认的 9 项中的 7 项（RCT 揭盲 / 锁库随 rct 模块开发时登记）。站内信通知租户管理员（规格 §5 范围变更流程第 5 步）随 E09 通知通道接入（届时补派发行），当前以变更记录 + 审计留痕。数据范围与字段策略上限（PLT-SCOPE-007）为 P2 后续规划，不在 `/scope` 响应中。

## 5. 平台账号与角色（PLT-ROLE-001..006、PLT-VIEW-005/006）

> 角色与账号接口已实现（E06-T04，`PlatformRoleAccountMySqlIT`）：四个平台预置角色（平台管理员 / 实施人员 / 运维人员 / 客服支持）由种子 `V104` 预置默认权限（规格 §1 角色定义与 §8 默认视角权限），预置角色不可删除（不提供 DELETE），权限（菜单授权、视角六字段、状态）可由平台管理员调整；授权或状态变化即递增持有者权限版本（PLT-PERM-010，在线会话下一次请求重算），角色停用后其菜单不再进入持有者会话。账号手机号只回脱敏值；新建账号强制绑定手机号并置首次登录强制改密。

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET / POST / PUT | `/api/platform/accounts[/{id}]` | `platform:account:view` / `edit` | **已实现（E06-T04）**。列表不分页（平台账号量级小）。新建：`{username, displayName, phone(必填，双因素), password(初始密码按平台策略校验 42200), roleIds}`；维护：`{displayName, phone?(留空不换绑，平台库内唯一 40901), roleIds(全部存在且启用), status}`；停用 / 摘除角色导致启用的 PLATFORM_ADMIN 持有账号少于 2 个时 40901（规格 §1「平台管理员至少 2 人，已确认」）；角色或状态变化递增权限版本并结束该账号进行中的视角会话（结束方式=权限变更，E20-T01） |
| POST | `/api/platform/accounts/{id}/reset-password` | `platform:account:reset` | **已实现（E06-T04）**。生成一次性临时密码（响应 `data.temporaryPassword` 只返回一次），置首次登录强制改密、旧哈希入历史表、该账号全部会话失效并解除登录锁定；写 ACCOUNT_RESET 审计（不落口令）。敏感操作需二次认证：`X-Reauth-Token`（PLT-AUTH-012，E04-T08 已落地；缺头 40304） |
| POST | `/api/platform/accounts/{id}/unlock` | `platform:account:reset` | **已实现（E06-T04）**。解除登录锁定（连续失败 5 次锁 30 分钟，可提前解锁）；写 ACCOUNT_UNLOCK 审计 |
| GET / POST / PUT | `/api/platform/roles[/{id}]` | `platform:role:view` / `edit` | **已实现（E06-T04；SPECIFIED 清单 E20-T01）**。角色视图 `{id, code, name, description, builtIn, status, view{canEnter, visibleScope∈CONFIG_ONLY/CONFIG_AND_BUSINESS, tenantScope∈ALL/RESPONSIBLE/SPECIFIED, canConfigure, canResetPassword, canEditBusiness}, menuIds(详情), specifiedTenantIds(详情，SPECIFIED 清单), holderCount, createdAt, updatedAt}`；新建自定义角色编码服务端生成（`CUSTOM_` 前缀，创建后不可改）；菜单授权只能是平台端启用菜单（40001）；`tenantScope=SPECIFIED` 须带非空 `viewSpecifiedTenantIds`（全部存在，40001，清单落 plt_role_view_tenant，E20-T01 / PLT-VIEW-005，OQ-48 已收敛）；视角六字段或清单变化即结束持有者进行中的视角会话（结束方式=权限变更）；不提供删除 |
| GET | `/api/platform/roles/{id}/holders` | `platform:role:view` | **已实现（E06-T04）**。持有人列表（账号、姓名、账号状态） |
| GET | `/api/platform/roles/menus` | `platform:role:view` | **已实现（E06-T04）**。可授权的平台端启用菜单 / 按钮（平铺带 parentId，角色页授权树数据源；不依赖 `platform:menu:view`） |
| GET / PUT | `/api/platform/accounts/{id}/responsible-tenants` | `platform:account:view` / `platform:view:grant:edit` | **已实现（E20-T01 / PLT-VIEW-006）**。账号负责租户（含编码与名称）；PUT `{tenantIds}` 整体替换（空清单=清空，未知租户 40001），变更即时生效——该账号进行中的视角会话立即结束（结束方式=权限变更）；写 ACCOUNT_TENANT_UPDATE 审计 |
| GET | `/api/platform/tenants/{id}/responsible-owners` | `platform:account:view` | **已实现（E20-T01 / PLT-VIEW-006）**。按租户反查负责人（账号、姓名、状态） |
| GET | `/api/platform/tenant-view/sessions` 系列 | 见 §6 | **已实现（E20-T01 / PLT-VIEW-001、004）** |
| GET | `/api/platform/role-templates?moduleCode=` | `platform:role:view` | **已实现（E06-T05）**。业务角色模板清单（PLT-ROLE-005）：`{id, moduleCode, code, name, description, sortNo}`，只读；模块开通时下发到租户库成 `TEMPLATE` 角色（见 `system.md` §3.1），租户在医院端「从模板复制」生成自定义角色（PLT-ORG-005） |

## 6. 租户视角（PLT-VIEW-001..018）

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| POST | `/api/platform/tenant-view/sessions` | `platform:view:enter` | **已实现（E20-T01 / PLT-VIEW-001）**。`{ tenantId, purpose: TROUBLESHOOT\|CONFIG_CHECK\|TRAINING\|OTHER, note(必填 ≤500) }` → `{ sessionId, sessionNo, tenantId/Code/Name, purpose, startedAt, expiresAt(开始+2h), expiresInSeconds, hospitalUrl }`（`hospitalUrl` 取 `crdp.tenant-view.hospital-base-url`，默认 `http://127.0.0.1:14173`；视角令牌本体只存 Redis、响应不再回显）。闸门：角色 `view_can_enter`（多角色并集，40300）→ 租户范围（ALL / RESPONSIBLE=负责租户 / SPECIFIED=指定清单，越范围 40300）→ 已注销租户 40001；同一账号同时只能有一个视角会话（40901）。被拒的进入尝试也写平台访问审计（VIEW_ENTER / DENIED） |
| GET | `/api/platform/tenant-view/sessions/current` | 登录 | **已实现（E20-T01）**。本人进行中的视角会话 `{ sessionId, sessionNo, tenant…, purpose, note, roleCodes, startedAt, expiresAt, remainingSeconds }`（无则 data=null）；顺带把空闲超时 / 到时的会话按实际结束方式收尾 |
| DELETE | `/api/platform/tenant-view/sessions/{id}` | 登录 | **已实现（E20-T01）**。平台端一键退出：只允许退自己的进行中会话（他人 / 已结束 40400）；台账 ENDED / 主动退出，写 VIEW_EXIT 审计；到时可重新进入 |
| POST | `/api/platform/tenant-view/sessions/current/entry-ticket` | 登录 | **已实现（E20-T04 / PLT-VIEW-002）**。为本人的进行中视角会话签发** 60 秒一次性进入凭据** `{ ticket, hospitalUrl, expiresInSeconds }`；无进行中会话 40001。前端拼 `hospitalUrl + /view/entry#ticket=…` 打开医院端视角页（凭据只放 URL 片段，不进服务端日志） |
| POST | `/api/hospital/view/enter` | 公开（按 IP 限流 60/时） | **已实现（E20-T04 / PLT-VIEW-002）**。`{ ticket }` 换视角令牌（取出即作废、伪造 / 过期 / 会话已结束统一 40001）→ `{ token, tokenType=Bearer, expiresInSeconds, session{…同 /view/session} }`；**视角令牌只在本次响应出现一次**（与双因素第二步同口径） |
| GET | `/api/hospital/view/session` | 视角令牌 | **已实现（E20-T04 / PLT-VIEW-003）**。横幅与水印口径：`{ sessionId, sessionNo, tenantId/Code/Name, purpose, accountName, accountDisplayName, startedAt, expiresAt, remainingSeconds }`；会话已收尾（令牌仍在）时先按实际结束方式关账再 40100 |
| DELETE | `/api/hospital/view/session` | 视角令牌 | **已实现（E20-T04 / PLT-VIEW-003）**。医院端横幅「一键退出」：只退自己的会话；台账 ENDED / 主动退出，写 VIEW_EXIT 审计 |
| GET / PUT | `/api/platform/view-rules` | `platform:view:rule` | 层级规则（全局 / 租户 / 模块 / 字段），版本化；高敏变更进入待复核（PLT-VIEW-007..009、014、015）——**随 E20-T02** |
| POST | `/api/platform/view-rules/preview` | `platform:view:rule` | 效果预览——随 E20-T02 |
| POST | `/api/platform/view-rules/reviews/{id}/approve` | `platform:view:rule` | 复核——随 E20-T02 |

E20-T01 落地口径（PLT-VIEW-001、004、005、006）：

- **会话台账** `plt_view_session`（迁移 V50）：会话号 = 视角令牌 SHA-256 前 16 位（与登录会话号同款单向推导）；进入 / 退出 / 被拒进入写平台访问审计 `aud_platform_access_log`（action=`VIEW_ENTER` / `VIEW_EXIT`，OQ-58 ⑥ 写入侧已接）；页面 / 查询 / 被拒 / 代为操作计数列随 E20-T03 起递增。
- **会话时长（PLT-VIEW-004）**：单次最长 2 小时（`SessionPrincipal.absoluteSeconds=7200` 冻结进会话，优先于全局 12 小时）、空闲 15 分钟（平台端口径）——由 Redis TTL 执行，令牌到时即失效（下一次请求 40100）；台账收尾（到时 / 空闲超时）由调度 `TenantViewScheduler` 每分钟补记结束方式。
- **授权（PLT-VIEW-005、006）**：角色六字段 + `plt_role_view_tenant`（SPECIFIED 清单）+ `plt_account_tenant`（账号负责租户，V50）；多角色取并集（任一 ALL → 任意租户；否则 负责租户 ∪ 指定清单）；租户列表 / 详情行带 `viewEnterable`（角色可进入 + 范围内 + 未注销）供前端显隐「进入租户视角」。负责关系、角色视角授权、账号角色 / 状态、密码重置变化 → 进行中的视角会话按「权限变更」立即结束。
- **视角令牌（TENANT_VIEW）**：平台账号以自身身份进入（不借用医院用户）；医院端装壳（菜单 / 横幅 / 退出）已由 **E20-T04** 落地（下节），脱敏 / 设盲 / 只读执行（PLT-VIEW-010..012）随 E20-T03——视角令牌带 `SESSION_VIEW` 端别权限，只能调医院端装壳接口（me / codes / menus / view/session），对其余 `/api/hospital/**` 业务接口与 `/api/platform/**` 一律 40300（fail closed）。权限码 `platform:view:enter`（按钮 143）/ `platform:view:grant:edit`（按钮 144）经 V50 登记、种子 V107 授予全部预置角色（四角色默认「可进入」均为是）。

E20-T04 落地口径（PLT-VIEW-002、003）：

- **进入医院端**：平台端弹窗「打开医院端界面」→ 签发 60 秒一次性凭据（Redis `crdp:view-ticket:*`，取出即作废）→ 浏览器打开 `hospitalUrl + /view/entry#ticket=…` → 医院端公开口换取视角令牌（只在换取响应出现一次；`TenantViewEnterIpHourlyLimit` 按 IP 每小时限流，超限 42900）。
- **与租户一致的菜单（PLT-VIEW-002）**：`GET /api/hospital/menus` 对视角令牌实时计算「启用医院端菜单 ∩ 该院已开通模块 ∩ 平台角色可见范围」；可见范围取账号全部可进入角色的并集（任一 CONFIG_AND_BUSINESS 即含业务），仅配置类时隐藏顶级「随访」目录（path=/followup）的全部后代——患者 / 计划 / 任务 / 报告单等业务页与按钮；`/system` 下的字典、通知模板等配置页保留（module_code 同为 followup）。租户开通 / 停用模块的下一次菜单请求即生效。
- **横幅与水印（PLT-VIEW-003）**：视角中医院端顶部常驻 `TenantViewBanner`（components.md §13，橙色、不可关闭）：「租户视角：{租户名称}」+ 会话号 + 剩余时长（`remainingSeconds` 倒计时，到时自动退出）+ 一键退出按钮；全页水印 `平台账号显示名（账号）· 视角会话 {会话号}`（watermark-js-plus）。退出（横幅 / 到时 / 空闲 15 分钟 / 权限变更）后令牌即失效（40100），回到医院端退出页。

视角令牌在医院端的执行（PLT-VIEW-010..013、017、018）：

- 视角令牌访问 `/api/hospital/**` 时会话类型为 `TENANT_VIEW`（OQ-09 已定论）；菜单与页面与租户一致（PLT-VIEW-002，E20-T04）。
- 字段脱敏、设盲、只读在**后端**执行；写接口默认返回 40303，除非平台角色允许“代为配置”“代重置密码”“编辑业务数据”。
- 视角审计**只写平台库**，不写租户审计（PLT-VIEW-016）。

## 7. 平台配置（PLT-CFG-001/003/005/006/007/009/010/011）

> 字典与系统参数已实现（E06-T10，`DictParamOverrideIT`）：字典与项编码、参数键创建后不可改；字典项停用不删除；保存即作废定义缓存（租户端下一次读取即见新值，PLT-CFG-011 即时生效），全部写操作写平台审计。租户侧继承与覆盖接口见 `system.md` §4。

| 方法 | 路径 | 权限 | 说明 | 需求 |
|---|---|---|---|---|
| GET | `/api/platform/dicts` | `platform:dict:edit` | **已实现（E06-T10）**。字典类型列表（含停用行）：`{code,name,description,status,itemCount,enabledItemCount,updatedAt}`；查看与维护同码（规格权限点表只有 edit 一个码） | PLT-CFG-001 |
| POST | `/api/platform/dicts` | `platform:dict:edit` | **已实现**。新建：`{code,name,description?}`；编码全局唯一（40902）、小写字母开头 2–64 位 `[a-z][a-z0-9_]`、创建后不可改；写 DICT_CREATE 审计 | PLT-CFG-001 |
| PUT | `/api/platform/dicts/{code}` | `platform:dict:edit` | **已实现**。维护名称 / 说明 / 停用启用（`status` 只能 ENABLED/DISABLED）；未知 40400；写 DICT_UPDATE 审计 | PLT-CFG-001 |
| GET | `/api/platform/dicts/{code}/items` | `platform:dict:edit` | **已实现**。字典项（含停用行，按 sortNo、itemCode 升序）：`{itemCode,label,sortNo,status,updatedAt}` | PLT-CFG-001 |
| POST | `/api/platform/dicts/{code}/items` | `platform:dict:edit` | **已实现**。新增项：`{itemCode,label,sortNo?}`；项编码字典内唯一（40902）、创建后不可改；新增项即时下发全部租户；写 DICT_ITEM_CREATE 审计 | PLT-CFG-001 |
| PUT | `/api/platform/dicts/{code}/items/{itemCode}` | `platform:dict:edit` | **已实现**。维护文字 / 排序 / 停用启用（停用不删除）；写 DICT_ITEM_UPDATE 审计 | PLT-CFG-001 |
| GET | `/api/platform/params` | `platform:param:edit` | **已实现（E06-T10）**。参数列表：`{paramKey,name,description,valueType(TEXT/NUMBER/BOOLEAN/ENUM/JSON),platformValue,tenantOverridable,valueRange,updatedAt}` | PLT-CFG-003 |
| POST | `/api/platform/params` | `platform:param:edit` | **已实现**。新建：全部字段必填（说明可空）；参数键全局唯一（40902）、小写字母开头 3–64 位 `[a-z][a-z0-9.]`、创建后不可改；取值范围 JSON（NUMBER `{min,max}` 至少一端 / TEXT `{maxLength}` 1–512 / ENUM `{options[]}` 非空）可覆盖时必填（BOOLEAN/JSON 除外，范围本身非法 40001）；平台默认值也要过类型与范围校验（40001）；写 PARAM_CREATE 审计 | PLT-CFG-003 |
| PUT | `/api/platform/params/{paramKey}` | `platform:param:edit` | **已实现**。维护名称 / 说明 / 值类型 / 平台默认值 / 层级（tenantOverridable）/ 取值范围（参数键不可改）；改为「平台锁定」不回写租户库——租户已覆盖的值即时失效并提示（`system.md` §4 stale），覆盖行保留、重新放开自动恢复；写 PARAM_UPDATE 审计（锁定流转在 detail 注明） | PLT-CFG-003、011 |

| 资源 | 路径 | 权限 |
|---|---|---|
| 通知渠道与短信模板 | `/api/platform/notice/channels`、`/notice/templates` | `platform:notice:edit` |
| Webhook | `/api/platform/webhooks`、`/webhooks/{id}/deliveries`、`.../deliveries/{id}/redeliver` | `platform:webhook:edit` |
| 系统公告 | `/api/platform/announcements` | `platform:notice:edit` |
| 文件上传配置 | `/api/platform/params/upload`（格式、大小上限） | `platform:param:edit` |
| 安全策略下限 | `/api/platform/security-policy`（密码策略、锁定、会话、二次认证场景） | `platform:security:edit` |
| 配置模板 | 见 §7.1（导出 E12-T01、应用 E12-T02、敏感检查 E12-T03 均已实现；管理页与导入预览已随 E12-T04 落地，见 `docs/design/pages.md` §11） | 导出 `platform:tenant:create`，读 `platform:tenant:view` |

配置变更写记录并说明生效方式（PLT-CFG-011）：字典 / 参数保存即作废平台定义缓存（`PlatformConfigCache`，60 秒 TTL + 保存即 evict，M1 单实例），租户端下一次读取即见新值；全部写操作写平台审计（DICT_* / PARAM_*）。

### 7.1 配置模板（A-FLD-014，E12-T01 导出 / E12-T02 导入 / E12-T03 敏感检查）

从源租户导出**只含配置**的模板包（gzip JSON 存平台库 `plt_config_template`，V49）：只导出配置表注册表（`ConfigTableRegistry`，随包代码）显式标注的表，未标注的一律不导出；`COPY` 表带全量行、`STRUCTURE_ONLY`（术者、病区 / 中心）只带结构不带取值（术者 = `cfg_dict_item` 里 `dict_code='surgeon'` 的行，经行排除谓词剔除）。患者与业务数据表另有 `NEVER_EXPORT_TABLES` 自检（登记即启动失败，双保险）。

**包含与排除规则（E12-T03）**：租户库每张表都必须显式分类——登记（COPY / STRUCTURE_ONLY）或排除（`NEVER_EXPORT_TABLES`：患者与业务数据、账号、审计、作业、文件、日志、密钥、outbox，以及组织架构 `sys_org` / `sys_dept`、租户品牌 `cfg_brand`、Webhook 凭据 `sys_webhook`）。完备性由 `ConfigTableCoverageTest` 把关：迁移里每张 CREATE TABLE 的表都必须有归属，新增表不分类即测试失败；排除清单里也不允许悬空表名。行排除谓词为结构化定义（`excludeWhen`：列 → 值），导出 SQL（参数化绑定）与敏感检查共用同一份规则。

**敏感信息检查（E12-T03，验收「含敏感信息的包被拦截」）**：导出前把配置行里引用用户的字段（`created_by / updated_by / published_by`）一律置空（账号不随模板复制，置空计数写进导出审计）；建包后自检、应用前拦截共用 `SensitiveConfigChecker` 五条规则——患者 / 业务数据 / 显式排除表（`FORBIDDEN_TABLE`）、未在注册表标注的表（`UNREGISTERED_TABLE`）、STRUCTURE_ONLY 表携带取值（`STRUCTURE_ROWS`）、被排除规则剔除的行如术者取值（`EXCLUDED_ROW`）、疑似患者敏感值即独立手机号或校验位正确的身份证号（`SENSITIVE_VALUE`）。命中即 40901：导出侧不落库、应用侧不进导入事务，两侧都写失败审计（reason 含规则摘要，不含数据明文）。

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| POST | `/api/platform/config-templates` | `platform:tenant:create` | **已实现（E12-T01）**。导出：`{tenantId, name?}`（name ≤128 字，缺省「{租户名} 配置模板」）→ `{id,tenantId,tenantCode,tenantName,name,version(同租户递增),formatVersion,tableCount,rowCount,packageHash,exportedAt}`；未知租户 40400、租户库未注册 40901；含敏感信息 40901（自检拦截，不落库、写失败审计，E12-T03）；写 `CONFIG_TEMPLATE_EXPORT` 审计（含包哈希与用户引用置空计数） |
| GET | `/api/platform/config-templates` | `platform:tenant:view` | **已实现**。模板分页（新→旧）：`{items[同导出视图],total,pageNo,pageSize}` |
| GET | `/api/platform/config-templates/{id}` | `platform:tenant:view` | **已实现**。详情：`{template, tables[{table,module,mode,category,present,rowCount,excludedRowCount,sourceRowCount,columns}]}`（不带行数据；未知 40400） |
| GET | `/api/platform/config-templates/{id}/package` | `platform:tenant:view` | **已实现**。读回完整包（gzip 解压后的 JSON 结构，`formatVersion/tenantCode/tenantName/exportedAt/tables[]`，行值已归一化）；写 `CONFIG_TEMPLATE_DOWNLOAD` 审计 |
| GET | `/api/platform/config-templates/registry` | `platform:tenant:view` | **已实现**。配置表注册表清单（table / module / mode / category / description / excludeWhen），预览「会导出什么」用（管理页「导出范围」抽屉，E12-T04） |
| POST | `/api/platform/config-templates/{id}/apply` | `platform:tenant:create` | **已实现（E12-T02）**。应用到目标租户：`{tenantId}`（不能是源租户 40001；租户库未注册 40901；未知租户 / 模板 40400；包结构版本不支持 40901；含敏感信息 40901——`SensitiveConfigChecker` 应用前拦截，见上，E12-T03）→ `{templateId,templateName,templateVersion,templateHash,sourceTenantId,sourceTenantCode,tenantId,tenantCode,tenantName,appliedAt,insertedRows,mergedRows,skippedRows,droppedRows,tables[{table,mode,imported,packageRows,inserted,merged,skipped,dropped,note}]}`；写 `CONFIG_TEMPLATE_APPLY` 审计（含包哈希），并递增目标租户全部用户权限版本（角色授权可能变化） |

**管理页与导入预览（E12-T04，验收「导入前预览差异」）**：`/platform/config-template`（菜单 V52：菜单行不带独立权限码——`plt_menu.perm_code` 全库唯一、`platform:tenant:view` 已被 111 占用，可见性由角色授予决定；接口读 `platform:tenant:view`，导出 / 应用按钮按 `platform:tenant:create` 显隐）。应用前预览基于**模板详情（分表元信息）+ 上面的导入规则在前端推演**——逐表给出「将导入 n 行（自然键相同被覆盖、其余新增） / 只复制结构 / 空节跳过」，并列出导入口径（源值胜、整组替换、取交集、用户引用置空、权限版本递增），勾选确认后才能提交；**无后端 dry-run 接口**，确切的新增 / 覆盖 / 跳过 / 丢弃计数以 apply 结果回显为准（暂行口径见 OQ-94）。

应用（导入）口径（A-FLD-014「导入时重新分配 ID 并重写引用」，验收「导入后引用关系正确」）：

- **ID 重映射**：逐表维护「源 ID → 目标 ID」并改写数值引用列——`sys_role_menu` / `sys_role_scope` / `sys_role_field_policy` 的 `role_id`、`fe_form_version_field.version_id`、`fu_plan_node.plan_id` 与 `parent_id`（自引用）；解析不到目标行的行丢弃并在结果计数。编码引用（`form_code` / `option_set_code` / `dict_code` / `set_code` 等字符串列）不改写，随整包导入自然一致。
- **用户引用置空**：`created_by / updated_by / published_by` 一律置 NULL（账号不随模板复制）；导出侧同样置空（E12-T03，tech-plan §6「配置中引用用户的字段一律置空」），列清单以 `ConfigImportSpecs.USER_REFERENCE_COLUMNS` 为唯一定义。
- **角色权限取交集**：`sys_role_menu.menu_id` 须落在目标租户生效可分配范围（`TenantScopeService.assignableMenuIds`：base 固定能力 ∪ 模板调整 ∩ 已开通模块）内，超出即丢弃；`sys_role_menu` 本身取并集（INSERT IGNORE）。
- **合并语义（源值胜，重放幂等）**：自然键相同的行用包值更新；`fu_plan_node` 无自然键，按计划版本（`plan_id` 分组）整组替换（先删后插），重放不叠加；`ntf_setting` 单行表按固定 `id=1` upsert。
- **整表策略**：`sys_role_scope_dept` 行全部丢弃（`sys_dept` 组织架构不在复制清单，`dept_id` 无法重映射）；STRUCTURE_ONLY（术者、病区 / 中心）、`present=false` 与目标库缺表的节跳过（结果 note 说明）。
- **事务**：导入在目标租户库单事务内全成或全败；平台库读取（包、租户、可分配范围）在事务外完成。

包值口径：DATETIME → `yyyy-MM-dd HH:mm:ss[.SSS]`、DATE → `yyyy-MM-dd`、二进制 → Base64，其余原样；模块未开通的表以 `present=false` 空节入包，导入侧按迁移存在性对照。

## 8. 平台工作台与审计

- `GET /api/platform/dashboard`：租户数、即将到期、开通失败、待复核（推断，待确认）。
- 平台审计见 `audit.md` §3。

## 9. 测试要点

- 各接口的 正常 / 无权限（40300）/ 校验失败（40001）/ 冲突（40901）已随 E05-T01..T06 的 IT 覆盖（`PlatformTenantDirectoryMySqlIT`、`PlatformTenantLifecycleMySqlIT`、`PlatformTenantExpiryProcessingMySqlIT`、`PlatformModuleRegistryMySqlIT`、`PlatformTenantModuleMySqlIT`）。
- E05-T12（租户生命周期与模块开通用例，`PlatformControlPlaneLifecycleMySqlIT`，CRDP_IT_MYSQL_PORT 存在时运行）：
  - **租户生命周期主用例**：一个虚构租户从向导开通（勾选 followup、到期日 +1 天、宽限 2 天）走完 试用 → 转正 → 到期自动转宽限 → 宽限期满自动停用 → 续期恢复 全程；租户管理员的同一医院会话贯穿——宽限期不失效（PLT-TEN-012 停用处理只发生在宽限期满）、自动停用立即 40100（PLT-TEN-016）。
  - **生命周期 × 模块开通交叉契约**：试用与宽限期可通过 open 端点开通模块（此前仅启用态与向导内开通有用例）；停用 / 开通中 / 注销中 / 已注销一律 40901 且文案含当前状态；停用不拦平台侧 close（被拦的是医院侧访问）；开通记录（PLT-MOD-009）倒序齐全、`TENANT_STATUS` 审计链（人工转正 / 服务到期自动 / 宽限期结束自动各一条）与 `TENANT_EXPIRE`（DISABLED→ENABLED）完整留痕。
  - **向导与负向**：向导勾选下架模块 40901（半途租户可回收）；未知模块 / 未知租户 40400。
  - 测试技巧：到期日只允许未来（E05-T02 校验），「已到期」初态直改平台库模拟；租户查找缓存 60 秒正负都缓存，停用后的重新登录不做成功断言；医院名称全平台唯一，多次建租户须逐次取名。
