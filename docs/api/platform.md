# 平台端接口（platform）

> 后端：`crdp-platform`；前缀 `/api/platform/`；只访问平台库 `plat`（技术方案“平台库 plat”一行：平台库不存任何医院的用户、患者与业务数据）。
> 页面：`docs/design/pages.md` §11。除 §1 标注「已实现」的行外，其余接口路径仍为 **推断，待确认**。
> 所有写操作写平台审计（PLT-AUD-004）；涉及高敏的操作要求二次认证（PLT-AUTH-012，请求头 `X-Reauth-Token`）。

## 1. 租户（PLT-TEN-001..022）

| 方法 | 路径 | 权限 | 说明 | 需求 |
|---|---|---|---|---|
| GET | `/api/platform/tenants` | `platform:tenant:view` | **已实现（E05-T01）**。分页 `{items,total,pageNo,pageSize}`；筛选 `keyword`（名称/编码 LIKE）、`status`（PLT-TEN-010 七态 + `PROVISIONING`）、`expireBefore`（yyyy-MM-dd，只含有到期日的）、`moduleCode`、`sort`（`createdAt/name/code/expireAt`，`-` 前缀降序，默认 `-createdAt`）；行：`id,code,name,shortName,status,expireType,expireAt,modules[{code,name}],userCount(租户库不可达为 null),createdAt`。列表不返回电话 | PLT-TEN-001 |
| POST | `/api/platform/tenants` | `platform:tenant:create` | **已实现（E02-T07；到期设置 E05-T02 接入；配置模板初始化 E12-T05；初始配额 E05-T09）**。body `{ name, code?, adminUsername?, adminDisplayName?, adminInitialPassword?, expireType?, expireAt?, remindDays?, graceDays?, graceReadOnly?, modules[]?, templateId?, userLimit?, storageLimitBytes? }`，同步跑开通编排并返回 `{ tenantId, code, steps[{step,status,error}] }`；到期五项整体省略 = 长期有效，`FIXED` 时 `expireAt` 必填且须晚于当天，未填的提醒节点（默认 `30,7,1`）与宽限期（默认 15 天）由后端取默认值；`modules[]`（勾选的业务模块，base 必开不传，E05-T05 / PLT-MOD-004：落 OPEN 登记、初始化配置默认值并把模块菜单授予租户管理员；下架模块 40901）；`templateId`（E12-T05 / A-FLD-014「新建租户时选择模板初始化」：开通编排新增 `config-template` 步骤，在模块开通**之后**把该模板应用到新租户库——角色菜单取交集需要可分配范围就绪；未选择记 `SKIPPED`；未知模板开工前 40400，不建库不登记；选择落 `plt_tenant.init_template_id`（V54），失败重试未带时沿用登记值）。配额初始值 `userLimit` / `storageLimitBytes`（向导第 2 步可带，E05-T09 / PLT-TEN-014：省略 = 不限，非法值开工前 40001 不建库；重试重入不改写已登记值） | PLT-TEN-002、011、014、PLT-MOD-004、A-FLD-014 |
| GET | `/api/platform/tenants/{id}/provision` | `platform:tenant:view` | **已实现（E02-T08）**。开通进度：步骤 `tenant-row / resource-row / database / migrate-register / admin / config-template`（末步 E12-T05，未选模板 SKIPPED），每步 `status(SUCCESS/FAILED/SKIPPED), error` | PLT-TEN-003..008、A-FLD-014 |
| POST | `/api/platform/tenants/{id}/retry` | `platform:tenant:create` | **已实现（E02-T08）**。从失败步骤重试（编排可重入） | PLT-TEN-008 |
| DELETE | `/api/platform/tenants/{id}` | `platform:tenant:create` | **已实现（E02-T08）**。放弃并回收已开通资源；仅 `PROVISIONING` 租户，已开通完成的走正式注销 | PLT-TEN-008 |
| GET | `/api/platform/tenants/{id}` | `platform:tenant:view` | **已实现（E05-T01；到期设置字段 E05-T02 补齐）**。基本信息 + 联系人 + 备注（含电话明文，供维护编辑，见 OQ-35）+ 到期设置 `remindDays, graceDays, graceReadOnly`（长期有效时前两项为 null）；资源信息（数据源、桶、密钥 ID）**脱敏只读**、不得返回连接串和密码——随 `platform:tenant:resource:view` 页签提供（推断，待确认） | PLT-TEN-009、011、015 |
| PUT | `/api/platform/tenants/{id}` | `platform:tenant:edit` | **已实现（E05-T01）**。body `{ name(必填、全平台唯一，冲突 40901), shortName?, creditCode?, orgType?, region?, contactName?, contactPhone?(信封加密存储，OQ-34), contactEmail?, contractNo?, remark? }`，空白一律清空；有字段变更写一条 `TENANT_UPDATE` 审计（只记字段名，不落明文）；返回更新后详情 | PLT-TEN-009、015 |
| GET | `/api/platform/tenants/{id}/status/impact` | `platform:tenant:view` | **已实现（E05-T02）**。状态变更影响面 `{ tenantId, code, name, status, onlineUserCount, pendingTaskCount }`（在线用户数取「租户→令牌」会话索引；`fu_task` 未建时 `pendingTaskCount` 为 null），供停用 / 启用 / 续期对话框二次确认展示 | PLT-TEN-010 |
| POST | `/api/platform/tenants/{id}/status` | `platform:tenant:status` | **已实现（E05-T02；停用处理 E05-T03 补齐）**。`{ action: ENABLE|DISABLE, reason }`（reason 必填 ≤200 字）；流转严格按规格状态图：`ENABLE` 为转正（试用→启用）或恢复（停用→启用）、`DISABLE` 仅启用→停用（手动停用），其余边一律 40901（试用/宽限期不能直接停用、宽限期走续期恢复、开通/注销各态见相应任务）；成功返回 `{ tenantId, code, previousStatus, status, onlineUserCount }` 并写 `TENANT_STATUS` 审计（含原因）。停用处理（PLT-TEN-016，E05-T03）：`DISABLE` 即吊销该院全部在线会话（吊销数记入审计，返回的 onlineUserCount 为吊销后的剩余在线数 0）并开一段停用时段，登录闸门按租户状态拒绝、定时作业扇出清单不再含停用租户（即「暂停定时任务」），数据不动；`ENABLE`（停用→启用）关闭停用时段 | PLT-TEN-010、016、017 |
| PUT | `/api/platform/tenants/{id}/expire` | `platform:tenant:renew` | **已实现（E05-T02；续期关段 E05-T03 补齐）**。到期设置 / 续期 `{ expireType(FIXED/LONG_TERM), expireAt?, remindDays?, graceDays?, graceReadOnly?, reason }`：`FIXED` 时 `expireAt` 必填且晚于当天（北京时间）、`remindDays` 每项 1..365（去重降序存储，默认 `[30,7,1]`）、`graceDays` 1..365（默认 15）；省略项沿用当前配置，首次配置取默认；`LONG_TERM` 清空到期日与提醒、宽限（长期有效不提醒、不进入宽限期）。**宽限期或停用中的租户续期后立即恢复启用**（PLT-TEN-013；停用中续期同时关闭停用时段，E05-T03）；开通中 / 注销流程中 40901；返回 `{ tenantId, code, previousStatus, status, expiry, changedFields }` 并写 `TENANT_EXPIRE` 审计（只记变化字段与原因）。到期提醒与自动转宽限 / 宽限期满自动停用由每日作业执行（E05-T03，见下） | PLT-TEN-011、012、013 |
| GET | `/api/platform/tenants/{id}/suspensions` | `platform:tenant:view` | **已实现（E05-T03）**。停用时段（PLT-TEN-017 底座）：`[{ id, tenantId, cause(MANUAL 人工停用/AUTO_GRACE_END 宽限期满自动), reason, disabledAt, restoredAt(空=仍在停用中), restoreCause(RENEWAL/MANUAL_ENABLE) }]` 倒序；停用补偿（停用期间本应执行的任务标记“停用期间未执行”、恢复后人工补做或关闭）由业务模块按时段推导（fu_task 创建方式，E14-T06），底座不自动补发任何任务或通知 | PLT-TEN-017 |
| GET | `/api/platform/tenants/{id}/quota` | `platform:tenant:view` | **已实现（E05-T09）**。配额视图 `{ userLimit, storageLimitBytes, enabledUserCount, storageUsedBytes, userWarning, userLimitReached, storageWarning, storageLimitReached }`：上限 null=不限（默认）；用量实时取租户库——启用中用户数（sys_user status=ENABLED，与拦截口径一致）与存储用量（sys_file 未删除行 size_bytes 合计），租户库不可达时用量为 null 不阻塞；用量达 80% 预警（*Warning）、达到上限即满额（*LimitReached） | PLT-TEN-014 |
| PUT | `/api/platform/tenants/{id}/quota` | `platform:tenant:edit` | **已实现（E05-T09）**。维护 `{ userLimit?, storageLimitBytes? }`（null/省略=不限清除；userLimit ≥1 整数、storageLimitBytes ≥1 字节，非法 40001）；上限不强制高于当前用量——调低只挡新增不回收存量；写 `TENANT_QUOTA` 审计（前后值）。租户侧拦截（PLT-ORG-010 / platform-spec §2）：达用户数上限后医院端新建（POST /users）、批量导入行与启用（POST /users/{id}/status ENABLE）一律 40901；达存储上限后上传 40001（见 report.md §1） | PLT-TEN-014、PLT-ORG-010 |
| GET | `/api/platform/tenants/{id}/cancel` | `platform:tenant:view` | **已实现（E05-T08）**。注销与数据处理页总览：`{ flow:{tenantId/Code/Name/Status, stage(PENDING_APPROVAL 待审批/COOLING 注销中·冷静期/EXPORTED 已生成待签收/ACKED 已签收/DESTROYED 已销毁/REJECTED 驳回/REVOKED 已撤销，null=未发起), reason, confirmationFile{id,kind,fileName,sizeBytes,uploadedAt}, appliedByName/At, approvedByName/At, approveNote, coolingUntil, revokedByName/At, revokeReason}, retentionItems[], exports[], certificates[] }` | PLT-TEN-018..021 |
| POST | `/api/platform/tenants/{id}/cancel` | `platform:tenant:cancel` | **已实现（E05-T08）**。发起注销申请 `{ reason(必填≤500), confirmationFileId(必填，先上传书面确认), retentionItems[{scope,legalBasis,retainUntil}] }`；仅 `DISABLED` 租户（其余 40901）；被驳回 / 撤销后可重新发起（重置同一流程行，历史在审计）；每步写 `TENANT_CANCEL` 审计 | PLT-TEN-018 |
| POST | `/api/platform/tenants/{id}/cancel/confirmation-file` | `platform:tenant:cancel` | **已实现（E05-T08）**。上传医院书面确认（multipart `file`，PDF/JPG/PNG ≤20MB），存 `platform/` 前缀不随租户销毁 | PLT-TEN-018 |
| POST | `/api/platform/tenants/{id}/cancel/approve` | `platform:tenant:cancel:approve` | **已实现（E05-T08）**。审批 `{ decision: APPROVE\|REJECT, note? }`；**审批人必须不是发起人（自审 40901，双人复核）**；APPROVE → 租户 `CANCELLING`（登录被拒、在线会话即吊销、作业扇出不再包含）+ 冷静期 30 天（已确认默认）；REJECT → 租户保持停用可重新发起 | PLT-TEN-018 |
| POST | `/api/platform/tenants/{id}/cancel/revoke` | `platform:tenant:cancel` | **已实现（E05-T08）**。冷静期内撤销 `{ reason }`：租户回 `DISABLED`、流程 `REVOKED`；冷静期已满 40901 | PLT-TEN-018 |
| POST | `/api/platform/tenants/{id}/cancel/exports` | `platform:tenant:export` | **已实现（E05-T08）**。生成标准迁出包（冷静期满后；已生成过可重新生成 reissue）：每表 CSV+JSON（二进制列除外，文件单独入包）、数据字典 CSV、原始文件（sys_file）、manifest.json（行数与每文件 SHA-256）；整包 AES-256-GCM 加密（.crdpa，PBKDF2 派生）；解压密码信封加密存档（`plt-export-password`，经短信另行发送，通道随 E09）；链接 7 天 / 5 次（已确认） | PLT-TEN-019 |
| GET | `/api/platform/tenants/{id}/cancel/exports/{exportId}/download` | `platform:tenant:export` + 二次认证 | **已实现（E05-T08）**。限时下载加密包（过期 / 次数用完 40901，重新生成即可）；每次下载计数并写审计 | PLT-TEN-019 |
| POST | `/api/platform/tenants/{id}/cancel/exports/{exportId}/ack` | `platform:tenant:export` | **已实现（E05-T08）**。医院签收（平台账号代录）`{ note(必填), ackFileId? }` → 流程 `ACKED`；签收后才能销毁 | PLT-TEN-019 |
| POST | `/api/platform/tenants/{id}/cancel/ack-file` | `platform:tenant:export` | **已实现（E05-T08）**。上传签收单（multipart `file`，PDF/JPG/PNG ≤20MB） | PLT-TEN-019 |
| PUT | `/api/platform/tenants/{id}/cancel/retention` | `platform:tenant:retain` | **已实现（E05-T08）**。登记法定保留数据 `{ items[{scope,legalBasis,tablesCsv?,retainUntil}] }`（整体替换未封存项；表名在租户库校验存在，40001）；流程结束（DESTROYED）后 40901 | PLT-TEN-021 |
| POST | `/api/platform/tenants/{id}/cancel/retention/seal` | `platform:tenant:retain` | **已实现（E05-T08）**。封存 `{ reason }`：迁出包生成后、销毁前；按登记表导出并用**独立密码**加密封存（`plt-retain-password`），登记保留期满日；无表项只封存登记事实 | PLT-TEN-021 |
| GET | `/api/platform/tenants/{id}/cancel/retention/{itemId}/download?reviewerId=` | `platform:tenant:retain` + 二次认证 | **已实现（E05-T08）**。访问封存包（解密为 zip）：**须两名平台管理员到场**（操作人 + reviewerId 复核人，自访 40901） | PLT-TEN-021 |
| POST | `/api/platform/tenants/{id}/cancel/retention/{itemId}/destroy` | `platform:tenant:retain` | **已实现（E05-T08）**。保留期满销毁 `{ reviewerId, reason }`：期满日之前 40901；删除封存对象并作废独立密码，补充出具销毁证明（kind=RETENTION） | PLT-TEN-021 |
| POST | `/api/platform/tenants/{id}/cancel/destroy` | `platform:tenant:destroy` + 二次认证 | **已实现（E05-T08）**。执行数据销毁 `{ reviewerId(另一名平台管理员，自审 40901), reason }`：须已签收且保留项全部封存（否则 40901）；删除租户库与专属账号（特权连接 DROP）、清理对象存储租户前缀、吊销会话并注销连接池，资源行标记已销毁；租户 → `CANCELLED`（平台库保留档案与审计），作业扇出不再包含；KMS 密钥计划删除与云备份自然失效为云侧人工步骤，写入证明；**出具数据销毁证明**（PDF 平台存档并交付医院） | PLT-TEN-020 |
| GET | `/api/platform/tenants/{id}/cancel/certificates` | `platform:tenant:view` | **已实现（E05-T08）**。销毁证明列表（TENANT 整体销毁 / RETENTION 保留期满销毁） | PLT-TEN-020 |
| GET | `/api/platform/tenants/{id}/cancel/certificates/{certificateId}/download` | `platform:tenant:destroy` + 二次认证 | **已实现（E05-T08）**。下载销毁证明 PDF（证明编号 DEST-{序号}；范围清单、销毁方式、执行 / 复核人、校验结果、保留数据说明） | PLT-TEN-020 |
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
| GET | `/api/platform/resource-logs` | `platform:menu:view` | **已实现（E05-T10）**。资源变更记录分页（新→旧）：`{items[{id,createdAt(ISO-8601 带时区),resourceType(MENU\|API\|FIELD——FIELD 值预留，随字段资源管理接口落地后写入),resourceId,resourceName,resourceIdent,action(CREATE\|UPDATE\|DELETE\|OFFLINE\|REGISTER),channel(PAGE\|SYNC),detail,operatorId,operatorName(「姓名（账号）」快照，系统触发为 null)}],total,pageNo,pageSize}`；`resourceType` / `action` / `channel` / `keyword`（匹配资源名 / 标识 / 摘要，≤64 字）可选过滤 | PLT-PERM-012 |
| GET | `/api/platform/resource-logs/export` | `platform:menu:view` | **已实现（E05-T10）**。导出 CSV（条件与查询同口径）：UTF-8 BOM、首列水印（`姓名（账号）· 平台资源变更记录 · 时间（分钟）`，OQ-22 口径）、行数上限 5 万（超出截断并在尾行提示）；每次导出写 DATA_ACCESS 审计（recordCount 与条件摘要） | PLT-PERM-012 |

**资源变更记录写入点（E05-T10 / PLT-PERM-012）**：`plt_resource_change_log` 只追加——菜单维护（新建 / 维护含逐字段前后值 / 删除，channel=PAGE）、菜单清单同步（新增 / 变更 / 下线，channel=SYNC）、接口标记（channel=PAGE，含标记字段前后值）、接口登记扫描（首次登记 / 登记随代码更新 / 消失清除，channel=SYNC；操作人为空 = 系统触发）。与审计（安全追溯、哈希链）互补，是资源维度的可查变更流水；字段资源（FIELD）随 `/api/platform/fields`（E08+）落地时接入。前端入口在「菜单资源」与「接口资源」页的「变更记录」抽屉。

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
| GET / POST / PUT | `/api/platform/roles[/{id}]` | `platform:role:view` / `edit` | **已实现（E06-T04；SPECIFIED 清单 E20-T01）**。角色视图 `{id, code, name, description, builtIn, status, view{canEnter, visibleScope∈CONFIG_ONLY/CONFIG_AND_BUSINESS, tenantScope∈ALL/RESPONSIBLE/SPECIFIED, canConfigure, canResetPassword, canEditBusiness}, menuIds(详情), specifiedTenantIds(详情，SPECIFIED 清单), holderCount, createdAt, updatedAt}`；新建自定义角色编码服务端生成（`CUSTOM_` 前缀，创建后不可改）；菜单授权只能是平台端启用菜单（40001）；`tenantScope=SPECIFIED` 须带非空 `viewSpecifiedTenantIds`（全部存在，40001，清单落 plt_role_view_tenant，E20-T01 / PLT-VIEW-005，OQ-48 已收敛）；`viewConfigResources` 逐项须在目录内（40001，空 / 缺省 = 全部配置类资源，PLT-VIEW-013 已确认，CSV 落 `plt_role.view_config_resources` V63）；视角六字段、资源范围或清单变化即结束持有者进行中的视角会话（结束方式=权限变更）；不提供删除 |
| GET | `/api/platform/roles/{id}/holders` | `platform:role:view` | **已实现（E06-T04）**。持有人列表（账号、姓名、账号状态） |
| GET | `/api/platform/roles/menus` | `platform:role:view` | **已实现（E06-T04）**。可授权的平台端启用菜单 / 按钮（平铺带 parentId，角色页授权树数据源；不依赖 `platform:menu:view`） |
| GET | `/api/platform/roles/view-config-resources` | `platform:role:view` | **已实现（E20-T06 / PLT-VIEW-013）**。代为配置资源目录 `[{code, label}]`（权限码资源段：user/role/org/dict/param/brand/webhook/session/field/notice/abnormal/dashboard/scan，代码级目录 `PlatformRoleService.CONFIG_RESOURCES`），角色页「代为配置资源范围」多选项；清单为空 = 全部配置类资源（已确认） |
| GET / PUT | `/api/platform/accounts/{id}/responsible-tenants` | `platform:account:view` / `platform:view:grant:edit` | **已实现（E20-T01 / PLT-VIEW-006）**。账号负责租户（含编码与名称）；PUT `{tenantIds}` 整体替换（空清单=清空，未知租户 40001），变更即时生效——该账号进行中的视角会话立即结束（结束方式=权限变更）；写 ACCOUNT_TENANT_UPDATE 审计 |
| GET | `/api/platform/tenants/{id}/responsible-owners` | `platform:account:view` | **已实现（E20-T01 / PLT-VIEW-006）**。按租户反查负责人（账号、姓名、状态） |
| GET | `/api/platform/tenant-view/sessions` 系列 | 见 §6 | **已实现（E20-T01 / PLT-VIEW-001、004；E20-T05 起含台账列表）** |
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
| GET | `/api/platform/view-rules` | `platform:view:rule:view` | **已实现（E20-T02 / PLT-VIEW-007；E20-T05 补状态与待复核标记）**。规则列表（量级小不分页）：`?layer=&status=&tenantId=&moduleCode=&resource=` 过滤（status 可 ACTIVE/DISABLED/PENDING_REVIEW）；行 `{ id, code(服务端生成 VR-00001，种子规则带语义编号), name, layer∈GLOBAL/TENANT/MODULE/FIELD, tenantId/Code/Name(空=全部租户), moduleCode/Name(空=全部模块), resource/field(字段层，field 空=整个资源对象), resourceLabel/fieldLabel, roleCodes(空=全部平台角色), visibility∈PLAIN/MASKED/BLINDED/HIDDEN, maskRule(MASKED 必填：name/phone/idCard/medicalRecordNo/address/generic，沿用第 4 章脱敏规则), operation∈READONLY/EDITABLE, allowExport(默认否), status∈ACTIVE/DISABLED/PENDING_REVIEW(新建即高敏的初始状态，解析只取 ACTIVE), version(每次变更+1), pendingChange(存在待复核变更), updatedAt }` |
| GET | `/api/platform/view-rules/catalog` | `platform:view:rule:view` | **已实现（E20-T02）**。视角字段目录（FIELD 层引用与预览用）：`{ fields[{resource, field(空=资源整体), label, moduleCode(空=不限模块，如 RCT 字段), key}], maskRules[] }`——患者 5 敏感字段 + 用户手机号 / 证件号 + 报告单与电子化资料原件 + 通话录音 + RCT 分组 / 随机表 / 揭盲信息（M1 代码目录，与 SensitiveFieldCatalog 同口径） |
| POST | `/api/platform/view-rules` | `platform:view:rule:edit` | **已实现（E20-T02 / PLT-VIEW-007；E20-T05 起返回复核结果）**。新建：`{ name(必填 ≤128), layer, tenantId?, moduleCode?, resource?, field?, roleIds(空=全部角色), visibility, maskRule?, operation, allowExport, status? }`；层级形状校验（GLOBAL 不带选择器 / TENANT 必带租户 / MODULE 必带模块 / FIELD 必带目录内资源与字段）40001、引用不存在（租户 / 模块 / 角色）40001；响应 `{ rule(规则行), pendingReview, pendingVersionId }`——普通变更即时生效（rule.status=ACTIVE）；新建即放宽（可编辑 / 允许导出）为高敏，规则行置 PENDING_REVIEW、不进解析（PLT-VIEW-014）；写平台访问审计 `VIEW_RULE_CREATE`（描述注明高敏待复核 / 已生效） |
| PUT | `/api/platform/view-rules/{id}` | `platform:view:rule:edit` | **已实现（E20-T02 / PLT-VIEW-007；E20-T05 起高敏变更待复核）**。维护（含停用 / 启用）：响应同 POST（`{ rule, pendingReview, pendingVersionId }`）；普通变更即时写入（版本 = 版本链 MAX+1）并落 APPLIED 版本行；高敏变更（改明文 / 取消设盲或隐藏 / 取消只读 / 允许导出 / 停用限制性规则 / 收窄限制性规则的选择器或适用角色，`ViewRuleReviewPolicy`，OQ-99 ③）**不改动规则行**、只落一条 PENDING_REVIEW 版本行（复核前规则保持原状，PLT-VIEW-014）；同一规则已有待复核变更再变更 40901；未知规则 40400；写审计 `VIEW_RULE_UPDATE` / `VIEW_RULE_DISABLE` |
| POST | `/api/platform/view-rules/preview` | `platform:view:rule:view` | **已实现（E20-T02 / PLT-VIEW-009，验收「预览与实际执行一致」）**。`{ tenantId, roleIds(非空，多选) }` → `{ tenant…, roleCodes[], modules[{moduleCode, moduleName, opened(该院是否已开通), visibility, maskRule, operation, allowExport, matched[](最具体层命中，预览「由哪条规则决定」), overridden[](更低层命中)}], fields[](目录逐项同构) }`；与执行点（E20-T03）共用同一解析器 `ViewRuleResolver`——匹配「字段 > 模块 > 租户 > 全局默认，越具体越优先；同层多条命中（如账号有多个平台角色）取更严：隐藏 > 设盲 > 脱敏 > 明文、只读 > 可编辑、禁止导出 > 允许导出」；无命中回落系统初始默认（可见、只读、禁止导出，与全局默认规则同值）。租户不存在 40400、角色必选 40001 |
| GET | `/api/platform/view-rules/reviews/pending` | `platform:view:rule:view` | **已实现（E20-T05 / PLT-VIEW-014）**。待复核清单：全部 PENDING_REVIEW 版本行（含规则编号 / 名称、前后值快照 JSON、变更人与时间），提交时间倒序 |
| POST | `/api/platform/view-rules/reviews/{versionId}/approve` · `/reject` | `platform:view:rule:review` | **已实现（E20-T05 / PLT-VIEW-014，验收「高敏变更需第二人批准」）**。`{ comment? ≤500 }`；发起人复核自己的变更 40901、已处理的版本再复核 40901；同意 → 按版本快照写入规则行并生效（版本 = 版本行号、写审计 `VIEW_RULE_APPROVE`）；驳回 → 规则行不动（新建即待复核的行置 DISABLED）、版本行 REJECTED（写审计 `VIEW_RULE_REJECT`）；权限码 `platform:view:rule:review`（按钮 149，V62） |
| GET | `/api/platform/view-rules/{id}/versions` | `platform:view:rule:view` | **已实现（E20-T05 / PLT-VIEW-015）**。版本链：每次变更（新建 / 维护 / 回滚）一条版本行——变更人、前后值快照（含适用角色）、复核人与意见、状态（PENDING_REVIEW / APPLIED / REJECTED），版本号倒序 |
| POST | `/api/platform/view-rules/{id}/rollback` | `platform:view:rule:edit` | **已实现（E20-T05 / PLT-VIEW-015「可回滚」）**。`{ toVersion }` 以已生效过（APPLIED）的历史版本内容发起一次新变更（change_type=ROLLBACK）：收严回滚即时生效，放宽回滚（如脱敏回滚到明文）同样进入待复核（PLT-VIEW-014）；目标不存在或未曾生效 40400；响应同 POST |

E20-T01 落地口径（PLT-VIEW-001、004、005、006）：

- **会话台账** `plt_view_session`（迁移 V50）：会话号 = 视角令牌 SHA-256 前 16 位（与登录会话号同款单向推导）；进入 / 退出 / 被拒进入写平台访问审计 `aud_platform_access_log`（action=`VIEW_ENTER` / `VIEW_EXIT`，OQ-58 ⑥ 写入侧已接）；页面 / 查询 / 被拒 / 代为操作计数列随 E20-T03 起递增。
- **会话时长（PLT-VIEW-004）**：单次最长 2 小时（`SessionPrincipal.absoluteSeconds=7200` 冻结进会话，优先于全局 12 小时）、空闲 15 分钟（平台端口径）——由 Redis TTL 执行，令牌到时即失效（下一次请求 40100）；台账收尾（到时 / 空闲超时）由调度 `TenantViewScheduler` 每分钟补记结束方式。
- **授权（PLT-VIEW-005、006）**：角色六字段 + `plt_role_view_tenant`（SPECIFIED 清单）+ `plt_account_tenant`（账号负责租户，V50）；多角色取并集（任一 ALL → 任意租户；否则 负责租户 ∪ 指定清单）；租户列表 / 详情行带 `viewEnterable`（角色可进入 + 范围内 + 未注销）供前端显隐「进入租户视角」。负责关系、角色视角授权、账号角色 / 状态、密码重置变化 → 进行中的视角会话按「权限变更」立即结束。
- **视角令牌（TENANT_VIEW）**：平台账号以自身身份进入（不借用医院用户）；医院端装壳（菜单 / 横幅 / 退出）已由 **E20-T04** 落地（下节），脱敏 / 设盲 / 只读执行（PLT-VIEW-010..012）已由 **E20-T03** 落地（见「E20-T03 落地口径」）——视角令牌带 `SESSION_VIEW` 端别权限，可调医院端装壳接口与**只读**业务接口（按视角规则脱敏 / 设盲后返回）；写 / 明文 / 导出下载 / 原件预览一律 40303（fail closed），`/api/platform/**` 与 `/api/hospital/auth/**` 仍只接受对应端会话（40300）。权限码 `platform:view:enter`（按钮 143）/ `platform:view:grant:edit`（按钮 144）经 V50 登记、种子 V107 授予全部预置角色（四角色默认「可进入」均为是）。

E20-T04 落地口径（PLT-VIEW-002、003）：

- **进入医院端**：平台端弹窗「打开医院端界面」→ 签发 60 秒一次性凭据（Redis `crdp:view-ticket:*`，取出即作废）→ 浏览器打开 `hospitalUrl + /view/entry#ticket=…` → 医院端公开口换取视角令牌（只在换取响应出现一次；`TenantViewEnterIpHourlyLimit` 按 IP 每小时限流，超限 42900）。
- **与租户一致的菜单（PLT-VIEW-002）**：`GET /api/hospital/menus` 对视角令牌实时计算「启用医院端菜单 ∩ 该院已开通模块 ∩ 平台角色可见范围」；可见范围取账号全部可进入角色的并集（任一 CONFIG_AND_BUSINESS 即含业务），仅配置类时隐藏顶级「随访」目录（path=/followup）的全部后代——患者 / 计划 / 任务 / 报告单等业务页与按钮；`/system` 下的字典、通知模板等配置页保留（module_code 同为 followup）。租户开通 / 停用模块的下一次菜单请求即生效。
- **横幅与水印（PLT-VIEW-003）**：视角中医院端顶部常驻 `TenantViewBanner`（components.md §13，橙色、不可关闭）：「租户视角：{租户名称}」+ 会话号 + 剩余时长（`remainingSeconds` 倒计时，到时自动退出）+ 一键退出按钮；全页水印 `平台账号显示名（账号）· 视角会话 {会话号}`（watermark-js-plus）。退出（横幅 / 到时 / 空闲 15 分钟 / 权限变更）后令牌即失效（40100），回到医院端退出页。

E20-T02 落地口径（PLT-VIEW-007、008、009）：

- **规则存储** `plt_view_rule` + `plt_view_rule_role`（迁移 V56）：层级 GLOBAL/TENANT/MODULE/FIELD 与租户 / 模块 / 资源字段选择器（空 = 全部）；可见性 PLAIN/MASKED/BLINDED/HIDDEN + 脱敏规则码（沿用第 4 章 `SensitiveValueMasker`）；操作 READONLY/EDITABLE；允许导出默认否；版本每次变更 +1（完整版本链与回滚随 E20-T05）。规则编号服务端生成（VR-00001），种子默认规则带语义编号（VR-GLOBAL-DEFAULT 等）便于幂等补种。
- **默认规则（PLT-VIEW-008）**：种子 V109 预置 16 条——全局默认（可见、只读、禁止导出）、配置类（base）与业务数据（followup）模块默认只读、患者 5 敏感字段脱敏、用户手机号 / 证件号脱敏、报告单与电子化资料原件隐藏、通话录音隐藏、RCT 分组 / 随机表 / 揭盲信息设盲（模块留空 = 全部模块，rct 上线即命中）；平台管理员预置一条 followup「可编辑」规则（绑 PLATFORM_ADMIN）。默认规则即普通规则行，可修改 / 停用。同层「更严合并」会吞掉同层的放宽（OQ-99），故业务数据只读默认只绑「实施 + 客服」——平台管理员经模块层可编辑规则（模块 > 全局默认）生效，其余角色回落全局默认。
- **优先级与预览（PLT-VIEW-009）**：解析只有 `ViewRuleResolver` 一个实现，效果预览接口与 E20-T03 执行点（脱敏 / 设盲 / 只读）喂同一批 ACTIVE 规则即得同一效果（PlatformViewRuleMySqlIT 逐维比对锁定）；无命中回落系统初始默认（与全局默认规则同值，停用全部规则不放开权限）。
- **生效规则版本**：进入视角时 `plt_view_session.rule_version` 写 ACTIVE 规则集内容哈希（SHA-256 前 16 位，V50 预留列写实值），规则一变即换值供审计对账；规则变更不结束进行中的会话（下一次请求按新规则执行，platform-spec §8）。
- **权限码**：`platform:view:rule:view`（菜单 147）/ `platform:view:rule:edit`（按钮 148）经 V56 登记、种子 V109 授予 PLATFORM_ADMIN（默认仅平台管理员配置，V104 职责口径）；变更写平台访问审计 `VIEW_RULE_*`（审计中心「视角规则变更」页签，OQ-56 ④）。

E20-T05 落地口径（PLT-VIEW-014、015、016，验收「高敏变更需第二人批准」）：

- **高敏判定（`ViewRuleReviewPolicy`，OQ-99 ③）**：以「行的生效内容」按严格序比较（可见性 隐藏>设盲>脱敏>明文、只读>可编辑、禁止导出>允许导出）；生效内容 = 规则行 ACTIVE 时为行内容，否则（DISABLED / PENDING_REVIEW / 不存在）为系统初始默认（可见、只读、禁止导出）。因此规格四类（改明文、取消设盲、取消只读、允许导出）之外，停用脱敏 / 设盲 / 隐藏规则、重新启用可编辑或允许导出的规则、新建即放宽、以及**把生效中的限制性规则（可见性非明文）改选择器（层级 / 租户 / 模块 / 资源 / 字段）或收窄适用角色**（原覆盖目标失去限制）同样属高敏；收严、改文案、角色只增不减等普通变更即时生效。
- **版本链（PLT-VIEW-015）**：`plt_view_rule_version`（V62）——每次变更一条版本行，记录变更人、前后值快照（`before_json`/`after_json`，含适用角色 id 与编码；新建的前值为 null=初始默认）与复核人 / 意见；版本号按版本链 MAX+1 分配、驳回不复用；普通变更提交即 APPLIED（applied_at=提交时刻）。回滚 = 以 APPLIED 历史版本内容发起一次新变更（ROLLBACK），是否待复核按同一高敏判定。
- **复核流（PLT-VIEW-014）**：高敏变更只落 PENDING_REVIEW 版本行，规则行一字不动（解析继续按原内容执行）；复核人须为发起人以外的平台账号（40901，权限 `platform:view:rule:review`，按钮 149 / V62，默认仅平台管理员）；同意按 after_json 写回规则行（状态取快照值）并 APPLIED，驳回版本行 REJECTED（新建即待复核的规则行置 DISABLED）；同一规则同时只允许一条待复核变更（40901）。写审计 `VIEW_RULE_APPROVE` / `VIEW_RULE_REJECT`（审计中心「视角规则变更」页签可见）。
- **视角审计（PLT-VIEW-016）**：VIEW_QUERY 补「对象与条数」——`object_type` = 接口登记权限码的资源段（如 patient）、`record_count` = 响应条数（分页取 total、数组取长度、单对象记 1）、`page` = 请求路径；条数解析失败只缺 count 不影响放行。会话台账列表 / 详情（`platform:view:session:view`，按钮 1293 / V62）供审计中心「视角会话」页签，明细按 `viewSessionId` 钻取（audit.md §3）；代为操作与编辑前后值随 E20-T06。

E20-T03 落地口径（PLT-VIEW-010、011、012，验收「只读视角写接口全部拒绝」）：

- **只读执行（PLT-VIEW-012）**：视角令牌对 `/api/hospital/**` 的新增、修改、删除与批量接口一律 **40303**（`TenantViewGuardFilter`，文案「租户视角只读，禁止写入」）——规则 `operation=EDITABLE` 的受控放行（代为配置 / 代重置密码 / 编辑业务数据的二次确认与前后值记录，PLT-VIEW-013、017、018）随 E20-T06，在此之前不开任何口子（fail closed）。导出 / 下载接口按规则 `allowExport` 单独控制（默认禁止，40303「租户视角禁止导出与下载」）；明文查看专用接口（`followup:patient:plain`）对视角一律 40303；文件预览 / 下载**签发口**（`GET /api/hospital/files/{id}/preview-url`、`/download-url`）一律 40303（报告单与电子化资料原件对视角隐藏，PLT-VIEW-008）。
- **读放行**：GET / HEAD 与只读动作（权限码第三段 `view`，含复杂查询 `POST .../search`，登记多候选码任一 view 即读）放行；医院权限码对视角会话不适用（平台账号无医院授权，`ApiResourceGuardFilter` / `PermissionAuthorizationAspect` 的视角分支），`@RequireReauth` 接口（如审计敏感查询）仍按 40304 自行拦截；模块开关按**该院实时**开通状态判 40301（与视角菜单同口径）。放行读写平台访问审计 `VIEW_QUERY` 并递增台账 `query_count`（`GET /api/hospital/menus` 记 `page_count`）；被拒写 `VIEW_DENIED`（result=DENIED + 原因）并递增 `denied_count`。
- **脱敏 / 设盲执行（PLT-VIEW-010、011）**：响应装配点经 `TenantViewFieldGuard`（实现 `ViewRuleFieldGuard`，与效果预览共用 `ViewRuleResolver`——预览值即执行值）按视角规则落值：`MASKED` / `PLAIN` 保留（已脱敏的）值——列表只输出脱敏值是平台铁律（AGENTS.md §5.3），视角规则的 PLAIN 不放宽列表明文；`BLINDED` 替换为「已设盲」占位（结构字段如录音清单等同隐藏）；`HIDDEN` 不返回。已接入装配点：患者列表与概览的姓名 / 病案号（`PatientSearchService` / `PatientOverviewService`）、报告单列表的姓名 / 病案号与首附件缩略（`ReportSearchService`，原件隐藏时 `firstFile=null`、条数 `fileCount` 保留）、联系记录的录音清单（`ContactLogService`，隐藏时空列表）；用户手机号本就只回脱敏值（`phoneMasked`）。RCT 分组 / 随机表 / 揭盲信息字段目录先行（模块留空），rct 模块上线后接口落地即自动命中。规则变更即时生效（下一次请求按新规则执行，platform-spec §8）。
- **视角审计只写平台库**，不写租户审计（PLT-VIEW-016；「对象与条数」明细已由 E20-T05 补齐：VIEW_QUERY 带 object_type 与 record_count，会话台账见上文 E20-T05 落地口径）。

视角令牌在医院端的后续执行（PLT-VIEW-013、017、018）——已由 **E20-T06** 落地，见「E20-T06 落地口径」。

E20-T06 落地口径（PLT-VIEW-013、017、018，验收「默认关闭，开启需授权并记审计」）：

- **默认关闭与授权**：三类代为操作全部默认关闭（自定义角色三字段缺省否；预置角色按规格 §8 预置表 V104——平台管理员全开、实施可配置、客服可重置、运维全关）。开启 = 平台管理员（`platform:role:edit`）在角色上配置对应字段 + `view_config_resources`（未配置 = 全部配置类资源，已确认）；多角色取并集（`TenantViewProxyService.capabilitiesOf` 实时计算），横幅会话 `GET /view/session` 与凭据换取响应随行下发 `capabilities{canConfigure, configResources, canResetPassword, canEditBusiness, businessEditable}`（前端按钮显隐依据，执行点逐请求判定）。
- **受控放行（`TenantViewGuardFilter` 写分支）**：写请求按登记分类（`classify`：base/tenant:* 为配置类；followup 内 action=config 与资源 field 为配置类，其余为业务数据；后续模块按业务数据默认 fail closed）。配置类写 = 可代为配置 ∧ 资源在范围内放行（`VIEW_PROXY_CONFIG`）；业务数据写 = 可编辑业务数据 ∧ 该资源命中操作为可编辑的视角规则（任一不满足即只读，`VIEW_PROXY_EDIT`）；动作 plain / reset / export / download / approve 永不放行（明文是平台铁律、重置只走专用代理口、导出按 allowExport）。放行须带 `X-View-Reason`（二次确认填写的操作原因，必填 2–500；HTTP 头不承载非 ASCII，取值按 encodeURIComponent 百分号编码），缺失 40001 并写 VIEW_DENIED。
- **前后值审计（PLT-VIEW-016）**：放行成功写平台访问审计 VIEW_PROXY_*（object_type=权限码资源段、object_id=路径尾段、reason；before_json=写前同路径 GET 内部快照（FORWARD 分发，未登记 GET 或失败为 null）、after_json=请求载荷（multipart 不记录），限长 32k 截断）并递增台账 `proxy_count`。**医院侧稽查匿名**：视角会话写租户侧可见审计（操作 / 数据访问）时操作者一律匿名「平台支持人员 会话号」（`platform_view`，不落平台账号与姓名，platform-spec §8 已确认）；字段历史来源归一为 `PLATFORM_SUPPORT`（OQ-66 ③ 收敛）；平台访问日志（VIEW_*）保留真实平台账号身份（仅平台侧可见）。
- **代重置密码（PLT-VIEW-017）**：见 §6 端点表 `/api/hospital/view/users/{id}/reset-password`；`SmsCodeSender` 新增 `sendTemporaryPassword`（场景 `AUTH_TEMP_PASSWORD`，参数白名单 = 系统生成的临时密码字符集，与验证码 / 汇总场景分开）。短信先发、成功才落库——通道不可用时重置不生效并如实报 50000。
- **@RequireReauth 接口（如患者删除 / 恢复）对视角仍按 40304 收口**（fail closed，OQ-107 ⑥ 原口径不变）：前端视角模式不显示这些按钮；如需放开须先设计视角下的二次认证流程。
- **权限码 `platform:view:config:operate` / `platform:view:user:reset` / `platform:view:business:edit` 未登记**（规格 §8 权限点表）：执行口径以角色六字段为唯一事实源（与 PLT-VIEW-005 落地一致），登记按钮码属重复配置面，暂不落（若产品要求按钮级独立控制再登记迁移）。

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
| POST | `/api/platform/announcements` | `platform:notice:edit` | **已实现（E05-T11）**。发布公告（发布即生效，无草稿 / 排期）：`{title(1–128),content(1–5000),scope(ALL/TENANTS),tenantIds?}`；scope=ALL 不得携带 tenantIds、TENANTS 至少 1 个（≤50，去重，未知租户 40400）；发布后不可编辑，更正走撤下 + 重新发布；写 ANNOUNCEMENT_PUBLISH 审计 | PLT-CFG-009 |
| GET | `/api/platform/announcements` | `platform:notice:edit` | **已实现**。公告分页（新→旧）：`?pageNo&pageSize&keyword(标题模糊,≤64)` → `{items[{id,title,scope,scopeText,status(PUBLISHED/REVOKED),tenants[{tenantId,tenantCode,tenantName}],publishedAt,publishedByName,revokedAt}],total,pageNo,pageSize}` | PLT-CFG-009 |
| GET | `/api/platform/announcements/{id}` | `platform:notice:edit` | **已实现**。公告详情（含定向租户名单）；未知 40400 | PLT-CFG-009 |
| PUT | `/api/platform/announcements/{id}/revoke` | `platform:notice:edit` | **已实现**。撤下公告（医院端即刻不可见，不可恢复）；未知 40400、已撤下 40901；写 ANNOUNCEMENT_REVOKE 审计 | PLT-CFG-009 |
| GET | `/api/hospital/announcements` | 登录即可（无权限码，同个人中心一类） | **已实现**。本院可见的生效公告（PUBLISHED 且 scope=ALL 或定向命中；新→旧，上限 50 条）：`[{id,title,content,publishedAt}]`；只读平台库，不写租户库 | PLT-CFG-009 |

| 资源 | 路径 | 权限 |
|---|---|---|
| 通知渠道与短信模板 | `/api/platform/notice/channels`、`/notice/templates` | `platform:notice:edit` |
| Webhook | `/api/platform/webhooks`、`/webhooks/{id}/deliveries`、`.../deliveries/{id}/redeliver` | `platform:webhook:edit` |
| 系统公告 | `/api/platform/announcements`（**已实现，见上表**；医院端读取 `/api/hospital/announcements`） | `platform:notice:edit` |
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

**用 A 配置初始化测试租户演练（E12-T05，验收「新租户表单、计划、报告单配置与 A 一致」）**：新建租户向导第 2 步可选配置模板（`POST /api/platform/tenants` 带 `templateId`），开通编排新增 `config-template` 步骤在模块开通后直接复用上面的 apply（同一套 ID 重映射、敏感拦截、授权取交集与审计）；医院 B 正式初始化（E23-T15）走同一路径。演练的自动化承载是 `ConfigTemplateTenantInitMySqlIT`：从 demoa（医院 A 的本地化身）导出模板 → 带 `templateId` 开通测试租户 → 表单（fe_* 七表）、计划（fu_plan / fu_plan_node）、报告单（rpt_type / rpt_name / rpt_indicator_tpl / rpt_indicator_item）三域配置业务列逐表与 A 比对一致（数值引用 join 到本院行取值比较），并断言患者 / 随访业务数据为空、配置行不带源租户用户引用（暂行口径见 OQ-99）。

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
