# 待确认问题（open-questions）

> 规则：本文件列出交接时**没有定论**的事项。编码代理遇到这里的问题时，**按“暂行做法”实现并在代码 / PR 中注明 OQ 编号**；暂行做法也没有的，停下来问人，不要自己发明需求。新发现的问题追加到末尾（编号顺延），写明来源与影响。
> 优先级：🔴 阻塞（不确认就可能返工）、🟡 重要、⚪ 一般。“推断，待确认”字样在 `docs/api/`、`docs/design/` 中随处出现，凡未单列的，均按该文档的建议实现。

## 定论记录（2026-11-01 产品负责人批量确认）

以下编号已确认**按各自“暂行做法”作为定论执行**，后续任务直接引用，不再作为开口问题：

- **OQ-01**：重申官方 Vben Admin 5（仅 Ant Design Vue，见 ADR 0002）。
- **OQ-02～OQ-10、OQ-13～OQ-28、OQ-34～OQ-47**：按各行暂行做法定论。其中含后续动作的：OQ-36 维持 CI 平台级闸门（不做编排第六步，不单开任务）；OQ-37 双因素只做短信（`methods[]` 已预留）；OQ-41 忘记密码只做医院端；**OQ-42 生产上线前须由部署侧确认反代/WAF 覆盖 `X-Forwarded-For`（或网关层限流）——列入上线检查单**。
- **OQ-12**：定论＝菜单 `followup:report:view`；上传 / 下载 / 删除 `followup:scan:upload` / `followup:scan:download` / `followup:scan:delete`。
- **OQ-21**：定论＝按 UI 定稿（基线资料 / 随访记录 / 报告单 /（C 参数趋势）/ 沟通记录 / 操作日志；C 植入记录并入基线资料）。
- **OQ-29～OQ-33**（排期类）：仍由人决定，本次未确认，不因本记录变化。
- **OQ-11**：随后补拍板，**定论 B**——对齐规格改 `tenant:org:view`（种子 V103 迁移改名，E06+ 用 org 码），详见该行。

## 一、架构与工程

| 编号 | 级别 | 问题 | 现状 / 冲突 | 暂行做法 |
|---|---|---|---|---|
| OQ-01 | 🔴 | 前端外壳：技术方案写 Vben Admin 5，M0 曾用自研 `@crdp/admin-shell` | 产品负责人已决定改用官方 Vben 5 | **已决定：官方 Vben Admin 5.7.0，仅 Ant Design Vue**（`apps/web-hospital`、`apps/web-platform`）。不要再引入 Element Plus、Naive UI、TDesign、antdv-next，也不要恢复 `@crdp/admin-shell`。见 ADR 0002 |
| OQ-02 | 🟡 | 报告单位置表名 | 技术方案不同章节有 `rpt_report_location` / `rpt_report_link` 两种写法 | 统一用 `rpt_report_link` |
| OQ-03 | 🟡 | `me` 不返回功能开关 | 前端需要按开关隐藏菜单与卡片 | 新增 `GET /api/hospital/auth/features`，或扩展 `me`；二选一后在 `auth.md` 定稿 |
| OQ-04 | 🟡 | 新增错误码 40400 / 40900 / 40901 / 40902 / 42200 / 40303 / 40304 / 40305 | 仓库只有 0、40001、40100、40101、40300、40301、40302、42900、50000、50002 | 按 `docs/api/README.md` 错误码表实现，加到后端 `ErrorCode` 枚举与前端 `packages/api` |
| OQ-05 | 🟡 | 功能开关关闭时返回 40301 还是 404 | 仓库 `@RequireModule` 返回 40301 | 返回 40301；前端隐藏入口 |
| OQ-06 | 🟡 | 新功能开关 `param_trend`、`next_visit_as_plan`、`baseline_lock`、`report_inbox` | 仓库只有 7 个开关 | 用 Flyway 新增到平台库开关表，A/C 默认值按规格 |
| OQ-07 | 🟡 | 菜单改名、新增“我的任务”是否顶级菜单 | UI 定稿与种子菜单不一致 | 新写 Flyway 迁移改菜单（不改旧脚本）；“我的任务”放顶级（推断） |
| OQ-08 | ⚪ | 图表库 | 规格未指定 | ECharts（按需引入）；仓库若已有其他库则沿用 |
| OQ-09 | 🟡 | 平台账号数据范围交集 | 仓库 DataScopeGuard 空范围改写与启动默认拒绝扫描已落地（E07-T01），受限范围按病区 / 科室 / 本人（OR、EXISTS 关联患者）的条件改写与七种类型快照归并已落地（E07-T02）；平台账号交集计算未做 | 按 PLT-SCOPE 实现；平台租户视角会话类型（`TENANT_VIEW`）为推断 |
| OQ-10 | ⚪ | 组织架构事件（病区停用、人员调离）是否自动改派 | 规格未写 | 不自动改派，只提示管理员（推断） |
| OQ-42 | 🟡 | 取图接口按 IP 限流（60 张/小时）信任请求头 `X-Forwarded-For` 首段（`HospitalAuthController.clientIp`），未配置可信代理清单：应用可直连时攻击者可逐请求伪造该头轮换 IP 绕过限流（来源 E04-T09 安全用例） | local/dev 与 CI 直连应用，头即客户端自报；生产经 ELB/WAF 时通常由代理改写为真实链路，但仓库未约定 | 暂行维持「首段 + remoteAddr 兜底」；生产上线前由部署侧确认反代 / WAF 会覆盖 `X-Forwarded-For`（或直接在网关层做按 IP 限流，NFR-SEC-007）。若测评要求应用侧配置可信代理（如 `server.forward-headers-strategy` + 代理白名单），另开运维任务 |

| OQ-43 | 🟡 | E05-T02 落地中的三个暂行口径：①转正（试用→启用）只改状态，试用到期日不重置——规格状态图没有「转正须设服务到期日」的边，到期日含义随状态（试用态=试用到期日、启用态=服务到期日），转正后如需调整在「到期设置」页签改；②宽限期的登录与只读语义留给 E05-T03（PLT-TEN-012）；③存量会话在停用后不主动失效，会话失效与暂停定时任务是 E05-T03（PLT-TEN-016） | 暂行：①若产品要求转正时强制设置服务到期日，在向导与详情页加一步即可；②③**已由 E05-T03 落地（2026-11）**——`routable()` 放开 GRACE、宽限只读拦截 40306、`tenant-state` 数据源、停用即吊销会话（人工与自动）、作业扇出清单含试用/启用/宽限期（停用即暂停），状态变更同步失效本节点租户查找缓存（多节点仍靠 60 秒 TTL 收敛）。①维持暂行 |
| OQ-44 | 🟡 | E05-T03 到期提醒（PLT-TEN-012）的接收人与通道：规格写「向租户管理员与该租户的平台负责人发送站内信与短信」，但①站内信（ntf_message）与短信通道属 E09 / PLT-CFG-005，尚未实现；②「平台负责人按租户指定」依赖 PLT-VIEW-006（负责租户多选），plt_account 尚无该字段（来源 E05-T03） | 暂行：每日作业只解析接收人并落 `plt_tenant_expire_notice` 派发行（INBOX+SMS、状态 PENDING、唯一键同节点不重发），不实际触达（调用占位 `NotifySender` 只会抛错）；租户管理员=租户库 TENANT_ADMIN 角色启用用户，平台负责人回落到全部启用的 PLATFORM_ADMIN。E09 通道接入后消费该表（PENDING→SENT/FAILED），PLT-VIEW-006 落地后把平台负责人改为按租户指定 |
| OQ-46 | 🟡 | E05-T05 落地中的三个暂行做法：①开通流程第 3 步「可分配范围取模块模板默认项（敏感权限除外）」依赖授权范围模型（E05-T07）与业务角色模板（E06-T04），本任务暂行把模块医院端菜单 / 按钮授予租户库 TENANT_ADMIN（INSERT IGNORE 幂等，关闭不回收）；②「停止定时任务」——模块级定时作业尚不存在（随访作业随 E14+ 出现），底座已提供 `openedModules` 口径（plt_tenant_module.status='OPEN'），后续模块作业扇出按它过滤即可；③关闭 / 开通的即时生效依赖 E04-T06 的权限版本递增（在线会话下一次请求重算 openedModules 快照），租户库不可达时跳过递增、下次登录收敛（来源 E05-T05） | 暂行：按上述实现；**①已由 E05-T07 落地（2026-11）**——授权范围模型（`plt_scope_template` 模板白名单 ∩ 非敏感为默认可分配，`plt_tenant_scope` 租户覆盖，`plt_sensitive_perm` 敏感清单）生效后，开通的模块菜单自动按模板默认进入可分配范围（无需回写），TENANT_ADMIN 直授保留为兜底，且医院端会话按「已开通模块 ∩ 可分配范围 ∩ 角色并集」求交（未放开敏感项即时过滤）；②③维持暂行，E14 起模块作业接入扇出过滤 |
| OQ-47 | 🟡 | E05-T06 落地中的四个暂行做法：①接口「默认拒绝」的公共口径由代码注解 `@PublicApi` 决定（个人中心、菜单、自查会话、匿名认证口），平台端可在接口资源页改标，但下次启动扫描会按注解改回——要永久放开某个未加注解的接口只能改代码或绑定权限码；②菜单清单（PLT-PERM-011）首期只登记随访模块的医院端菜单（与种子 V100 同构），清单是随包代码（`MenuManifest`），「模块发版」即发新包；③菜单删除的「异步清理各租户库角色授权」按同步逐租户执行（不可达跳过、下次收敛），不做异步任务；④新增菜单项「按模块模板默认进入可分配范围，平台确认后对租户生效」中的授权范围模型属 E05-T07，本任务先把清单行登记进 plt_menu（CUSTOM 来源不参与清单同步） | 来源 E05-T06 | 暂行：按上述实现；**④已由 E05-T07 落地（2026-11）**——授权范围模板是白名单（`plt_scope_template`），清单同步新增的菜单项不在模板内即默认不进入可分配范围，平台确认（改模板或租户级放开）后对租户生效，无需改清单同步逻辑；①②③维持暂行；若产品要求「公共接口标记只能由平台端决定」，把 `@PublicApi` 的推导降级为仅初始化默认值 |
| OQ-48 | 🟡 | E06-T04 落地中的暂行口径：①平台角色租户范围枚举含「指定租户」（platform-spec §8），但指定租户清单的存储与维护属 E20-T01（PLT-VIEW-005），本任务提交 `tenantScope=SPECIFIED` 一律 40001（「指定租户清单由租户视角任务提供，暂不可选」），角色页该选项禁用；②平台账号的「负责租户多选」是 PLT-VIEW-006（E20-T01），plt_account 本任务不加字段；③预置角色的默认菜单授予是执行时快照（种子 V104），之后新增的平台端菜单不自动跟随，由平台管理员在角色页调整（与 V13 同口径） | 来源 E06-T04 | 暂行：按上述实现；E20-T01 落地 PLT-VIEW-005/006 时放开 SPECIFIED 选项与指定租户清单、补 plt_account 负责租户字段，并把平台负责人等回落逻辑改为按租户指定（OQ-44 ②同步收敛） |
| OQ-49 | 🟡 | E06-T11 落地中的三个暂行做法：①租户个性化素材（Logo / 登录页背景）规格要求「存该租户对象存储桶」，但对象存储（`ObjectStorage`）是占位实现、在 E10 接入，文件上传配置（PLT-CFG-010）也未落地——本任务 `cfg_brand.logo_url / login_bg_url` 先存素材 URL（http(s) 或站内路径），前端表单填地址 + 预览，无上传控件；②预设主题色色板（规格「仅系统预设」未给清单）取 `docs/design/tokens.ts` 四色：BLUE #3A75FF（默认）/ TEAL / ORANGE / RED，按 OQ-23 主色固定、租户只选预设辅助色，色板登记为常量（后端枚举 + 前端 `@crdp/api`），产品后续要扩充即改两处；③登录页租户识别接口 `GET /auth/tenant?code=`（原 auth.md「推断，待确认」）按本实现定稿：未识别出租户返回通用默认值（防探测）、按 IP 每小时限流 120 次 | 来源 E06-T11 | 暂行：按上述实现；E10 对象存储 + PLT-CFG-010 上传配置落地后，把机构设置页换成上传控件（大小 ≤1MB、PNG/SVG 白名单在服务端校验），`cfg_brand` 只换写引用地址，接口不变；色板扩充属产品拍板，改枚举即生效 |
| OQ-50 | 🟡 | E06-T05 落地中的四个暂行做法：①随访模块业务角色模板的菜单矩阵（platform-spec §1 只给角色名清单「如随访员、责任医生、研究者、CRC、资料录入员」）按当前已登记的随访菜单推断：随访员 / 责任医生 / 研究者 / CRC = 目录+患者档案+随访任务+报告单，资料录入员 = 目录+患者档案+报告单（不做随访任务），敏感按钮一律不进模板；②模板定义在平台库 `plt_role_template`（种子 V105 登记、编码全局唯一），平台端暂只提供只读清单 `GET /api/platform/role-templates`，维护界面（模块目录详情展示模板、按规格调整矩阵）未做——随 E12+ 随访菜单逐步登记时由平台用 SQL / 后续任务补 UI；③角色数据范围类型采用代码已落地口径七种（ALL / WARD / RESPONSIBLE_DEPT / RESPONSIBLE_PATIENT / DEPT / SELF / CUSTOM_DEPT，与 `HospitalDirectory` 会话装配一致），docs/api/system.md 原 `DEPT_AND_CHILDREN / CUSTOM` 未采用；CUSTOM_DEPT 的科室清单存租户库 `sys_role_scope_dept`，生效计算（含病区与科室 OR）已由 E07-T02 落地（登录侧 `HospitalDirectory.scopeOf` 归并快照、查询侧 `RestrictedScopeRewriter` 改写 SQL）；④角色页「字段策略」页签 E06-T05 时按 pages.md §10 占位禁用，**E07-T06 已落地**（接口 `GET/PUT /system/roles/{id}/field-policies`、存储 `sys_role_field_policy` V16；目录 = framework `SensitiveFieldCatalog` 患者身份五位字段，未配置的角色按平台默认 MASKED），多角色「取最宽松」合并已由 E07-T04 落地（framework `FieldPolicySnapshot`，随登录装配冻结进会话、预览 `fields[]` 同口径展示），脱敏序列化仍随 E07-T05 | 来源 E06-T05 | 暂行：按上述实现；E12+ 随访菜单登记后由平台调整 `plt_role_template_menu` 矩阵（或补维护界面）并重新开通 / 触发下发；E07-T02 落地七种范围的拦截器生效计算后，`sys_role_scope_dept` 直接参与过滤；矩阵如有产品定论（如 CRC 不看报告单）改种子脚本新增 V 版本即可 |
| OQ-51 | 🟡 | E06-T10 落地中的三个暂行口径：①系统参数「取值范围」的形状规格未定义（只写「租户值不得超出」），本任务定为 JSON：NUMBER `{min,max}`（至少一端、闭区间）/ TEXT `{maxLength}`（1–512）/ ENUM `{options[]}`（非空），BOOLEAN 与 JSON 无范围（取值集合即类型本身）；「可覆盖时范围必填」对 BOOLEAN / JSON 豁免，平台默认值也过同一校验；②租户字典的继承采用**增量覆盖模型**（与 `plt_tenant_scope` 同思路）：`cfg_dict` 只存本院自有字典，`cfg_dict_item` 的 PLATFORM 行只是平台项在本院的停用标记（不存 label）、TENANT 行是本院自有项——平台新增字典 / 项自动对租户生效、无需同步，平台项文字永远取平台定义（租户改不了）；③平台定义缓存（`PlatformConfigCache`，60 秒 TTL）在平台保存字典 / 参数后立即作废本节点缓存实现「即时生效」，多节点部署时其他节点仍靠 TTL 收敛（M1 单实例，ADR 0003 第 4 条） | 来源 E06-T10（platform-spec §10「关键字字段 · 系统参数」未定范围形状；PLT-CFG-002「继承平台字典」未定同步机制） | 暂行：按上述实现；若产品要求别的范围形状（如文本用正则、数值带步长）改 `ParamValueValidator` 与前端 `param.ts` 两处即可；字典若要求「全量快照式」继承（平台项文字允许租户覆写）则需改存储模型，属产品拍板；多节点部署时把 evict 升级为 Redis 广播（或缩短 TTL） |
| OQ-52 | 🟡 | E06-T03 落地中的三个暂行口径：①科室导入采用**同步一次性模型**（上传即逐行校验落库、返回逐行 CREATED/UPDATED/FAILED），import-export.md §2 原写的异步作业模型（job_import 预检 / 确认 / 结果文件）是为患者宽表（212+ 列、上万行）设计的，科室表 ≤1000 行不引入；②xlsx 解析用 crdp-system 内最小读写器 `SimpleXlsx`（JDK zip + StAX，支持 sharedStrings / inlineStr / 数值单元格，无日期列）——不引入 POI（ExcelGateway 注释明确宽表流式读写属 E18），E18 落地框架网关后宽表走网关、科室导入可继续用本类或届时并轨；③「从 HIS 同步科室」规格原文即「接口待确认」，医院未给接口，首期只做 Excel 导入，HIS 侧参数与调度待接口确认后另开任务 | 来源 E06-T03（platform-spec §6 PLT-ORG-003；框架 ExcelGateway 与 OutboxPublisher 原为 M0 占位） | 暂行：按上述实现；若产品要求科室导入也走异步作业（大文件、需预检确认），复用 E15+ 的作业模型替换本接口即可（响应形状已在 system.md §1 定稿）；E09 落地 outbox 分发时 sys_outbox 的 PENDING 行由分发器消费，表结构不变 |
| OQ-53 | 🟡 | E07-T03 落地中的暂行口径：PLT-PERM-008 只写「按项目成员制控制，底座提供扩展点，规则由 RCT 模块实现」，而 rct 模块首期不建（tech-plan「不做」清单）——①「项目 → 可见患者」的 SQL 分支形状（项目-受试者关联表、项目内角色 / 设盲如何参与过滤）属 rct 模块设计，本任务不预定义表名或映射；②底座已提供扩展点 `ResearchProjectScopeProvider`（crdp-framework，登录装配 `ScopeSnapshot.projectIds`）+ 空实现 `NoopResearchProjectScopeProvider`（恒空清单，RESEARCH_PROJECT 分支不放宽任何数据，只有该类型的用户为空范围）；快照带项目清单而改写分支未落地时 `RestrictedScopeRewriter` 拒绝执行（fail closed），防止半接入静默少过滤；③`RESEARCH_PROJECT` 已进角色可选范围类型（`RoleAdminService.SCOPE_TYPES`）与预览类型清单，前端 `RoleScopeType` 联合类型已扩展文案，但角色页勾选项暂不放开（随 E07-T06 配置页） | 来源 E07-T03（platform-spec §8 PLT-PERM-008；tech-plan §首期不做） | 暂行：按上述实现；rct 模块立项时提供 `@Primary` 实现替换空实现、在 `RestrictedScopeRewriter` 补项目分支（与病区 / 科室 / 本人同组 OR）、并在预览补成员项目清单；若产品要求项目数据不走患者锚点（如独立受试者表），扩展点签名随之调整（需 ADR） |
| OQ-54 | 🟡 | E08-T01 落地中的暂行口径：①audit.md 原推断表名 `aud_operation_log`（租户）/ 平台侧操作表未采用——仓库已有 `aud_log`（租户操作）与 `plt_audit_log`（平台操作 / 租户控制面）被 10+ 个 IT 断言引用，按「仓库已落地代码优先于文档旧写法」保留原名并经 V15/V25 补齐规格列；②登录 / 解锁 / 强制下线这类「账号生命周期事件」会同时出现在操作日志（管理员做了什么）与登录日志（PLT-AUD-002 要求 LOCK/UNLOCK/KICK/LOGOUT 事件）两张表，双写属有意为之；③写失败整体失败（50000）的范围取登录、平台访问与敏感数据访问（明文 / 导出 / 打印 / 下载），批量查询与普通操作只告警不回滚——比 audit.md 原文（只列明文 / 导出 / 下载）略宽，依据是 platform-spec「登录、租户视角等关键安全事件须保证不丢失」；④~~审计表数据库层只授 INSERT/SELECT、UPDATE/DELETE 失败用例留给 E08-T02~~ 已由 E08-T02 落地：只读采用**触发器**方案（迁移 V17/V26，任何账号 UPDATE / DELETE 报 45000），未走 REVOKE 收权——Flyway 以 crdp_app 运行且无 GRANT OPTION，收不了自己的权限；受影响的 20 个 IT 自清理改走 AuditTableMaintenance（连接级维护变量 @crdp_audit_maintenance，用完即清） | 来源 E08-T01（platform-spec §9；audit.md §1 原文「推断，待确认」） | 暂行：按上述实现；E08-T04 查询按 V15/V25 实际表结构开发；若产品要求统一表名或单写口径，另开迁移任务（需 ADR） |
| OQ-55 | 🟡 | E08-T02 落地中的暂行口径：①只读触发器要求迁移账号能 CREATE TRIGGER——binlog 开启时非 SUPER 账号报 MySQL 1419，本地 / CI 已在 docker-compose.yml 用 --skip-log-bin 关闭 binlog，生产 RDS 需参数组置 log_bin_trust_function_creators=1 或关 binlog（运维 E22 落实，本任务只留注释）；②断链告警（PLT-AUD-009）当前为 ERROR 日志 + aud_chain_check 记 BROKEN + 作业 FAILED，站内信通道待 NotifySender 从占位实现转正（E09）后接入；③验链对全库历史全量重算（在线保留 1 年内可承受），增量校验随 E08-T03 分区归档一并考虑 | 来源 E08-T02（platform-spec §9 PLT-AUD-008/009/011；NFR-CRY-006） | 暂行：按上述实现；生产 binlog 参数与告警通道接入列入 E22 / E09 验收 |
| OQ-56 | 🟡 | E08-T04 落地中的暂行口径：①导出文件是带 UTF-8 BOM 的 CSV（M0 无 POI，ExcelGateway 占位，宽表流式读写属 E18），「文件加水印」实现为首列水印（OQ-22 的 `姓名（账号）· 医院 · 时间` 每行重复）而非页眉水印，行数上限 50 000（超出文件尾附截断提示）；②文件体暂存 job_export.file_body（LONGBLOB，平台库 V28 / 租户库 base V19 同构），M1 单实例（ADR 0003 第 4 条）可行，E10 对象存储落地后迁桶 + 短时签名链接、接口与文件哈希口径不变；③平台端审批端点定稿为 `/api/platform/audit/export-approvals`（audit.md §3 原只列了 exports），下载直接走 `GET .../exports/{id}/download`（24 小时内有效，未做 5 分钟签名链接——import-export.md §3 的签名链接是患者导出 E13 的口径）；④平台端 view-rule 页签映射 `aud_platform_access_log.action LIKE 'VIEW_RULE%'`，视角规则变更的写入侧随 E20-T01，当前恒为空页签；⑤本地 / CI 的 MySQL 容器时钟是 UTC 而 JDBC connectionTimeZone=Asia/Shanghai，created_at（DEFAULT CURRENT_TIMESTAMP）落在库内钟面，生产 RDS 时区为北京时间时两者天然一致——E08-T04 的 IT 按库内 NOW() 构造时间窗规避，若后续要求应用层不感知库时钟，可在连接串加 forceConnectionTimeZoneToSession=true（需评估存量数据钟面迁移） | 来源 E08-T04（platform-spec §9 PLT-AUD-005~007；import-export.md §3；OQ-22） | 暂行：按上述实现；E10 / E18 / E20 / E13 落地时按①②④收敛，水印样式若要求页眉图形水印需引入 xlsx 网关（需 ADR） |

| OQ-57 | 🟡 | 敏感字段**明文查看**是否需要密码二次认证（PLT-AUTH-012）：E04-T08 任务验收写「明文查看、导出前需再次验证」，而 docs/design/components.md §3 MaskedField（UI 定稿）的确认文案明确「**不需要再次输入密码**」——明文查看的「再次验证」按设计稿是 popconfirm 二次确认 + 权限 `followup:patient:plain` + 审计（AGENTS.md 第 5 节同口径），PLT-AUTH-012 的示例（导出、电子签名）也不含明文查看 | 来源 E04-T08（tasks.csv 验收 × components.md §3 定稿冲突；明文接口 `/patients/{id}/plaintext` 随 E07-T05 落地） | 暂行：E04-T08 只落框架（`@RequireReauth` + `POST /auth/reauth` 两端 + 前端 ReauthModal）并接入现有敏感操作（审计导出提交 / 下载、重置用户 / 平台账号密码）；E07-T05 实现明文查看接口时**按 UI 定稿走 popconfirm（不加 @RequireReauth）**，若产品拍板明文查看也要密码二次认证，给该接口加注解 + MaskedField 弹 ReauthModal 并改确认文案即可（框架已就绪，无返工） |
| OQ-58 | 🟡 | E08-T05 落地中的暂行口径：①「异常登录」未在规格里量化，取登录日志 `action=LOCK`（连续失败达到阈值锁定，E04-T05）为触发——锁定事件由登录流程天然去重，一次锁定一条告警；登录失败但未达阈值的告警噪音不做；②「批量导出」无行数阈值可依（提交时行数未知、生成是异步的），按「导出即成批数据离站」取数据访问 `access_method=EXPORT` 一律告警（患者导出 E13 等后续写入同口径自动覆盖）；③规则只看 result=SUCCESS 的事件，DENIED / FAIL 不告警（审批与审计兜底）；④接收人 = 全部启用的 PLATFORM_ADMIN（站内信派发行 plt_security_alert，channel=INBOX、send_status=PENDING）——站内信收件箱与实际投递随 E09（NotifySender 仍为占位实现，消费契约同 OQ-44 的 plt_tenant_expire_notice），按租户指定平台负责人随 PLT-VIEW-006；⑤告警派发失败只记 ERROR 日志不阻断业务（事实源是审计日志，可对账）；⑥平台访问规则（action 前缀 VIEW_）已就绪但写入侧随 E20-T01，当前不会触发 | 来源 E08-T05（platform-spec §9 PLT-AUD-012 只列了四类事件，未定义阈值与接收人） | 暂行：按上述实现（规则矩阵见 audit.md §3）；E09 落地时消费 plt_security_alert（PENDING→SENT/FAILED）并把断链告警（OQ-55 ②）接到同一通道；若产品要求别的阈值（如失败 N 次未锁定也告警、导出按行数分档）或接收人扩展到租户管理员，改 SecurityAlertService 规则与接收人解析即可 |

## 二、权限码

| 编号 | 级别 | 问题 | 暂行做法 |
|---|---|---|---|
| OQ-11 | 🔴 | 种子 `tenant:dept:view` vs 规格 `tenant:org:view` | **已定论 B（2026-11-01 用户确认）**：对齐规格，`tenant:dept:view` → `tenant:org:view`（平台种子 V103 改菜单行 33 的 perm_code；零角色授权窗口下的经批准例外，E06+ 组织架构一律用 org 码）。查证过程与依据：规格原文只有权限点表两行——`tenant:org:view`「查看组织架构」、`tenant:org:edit`「维护院区与科室」；dept 码此前仅种子 V100 一处引用，接口 / 页面 / 授权均未实现。文档（system.md、pages.md）已同步 org 口径 |
| OQ-12 | 🔴 | 全院报告单菜单：种子 `followup:report:view` vs 规格只有 `followup:scan:*` | 菜单用 `followup:report:view`；上传 / 下载 / 删除用 `followup:scan:upload/download/delete` |
| OQ-13 | 🟡 | C 没有 `followup:plan:view/publish/enroll` | C 的计划与入组相关一律用 `followup:plan:edit` |
| OQ-14 | 🟡 | 修改已提交随访记录：A 用 `followup:record:edit`，C 用 `followup:record:edit-submitted`；A 是否要求填写修改原因 | 按租户权限表各用各的码；C 原因必填，A 暂定必填（待医院 A 确认） |
| OQ-15 | 🟡 | C 停止随访 / 标记失访用哪个权限 | `followup:patient:edit`（推断） |
| OQ-16 | 🟡 | A 规格没有对比规则配置码（C 有 `followup:abnormal:config`） | 给 A 也授 `followup:abnormal:config`（新增到 A 角色模板） |
| OQ-17 | 🟡 | A 没有删除患者权限码（C 有 `followup:patient:delete`）；有已提交记录的患者能否删除 | 新增同名码给 A；有已提交记录时仍允许软删除，须填原因、二次认证 |
| OQ-18 | 🟡 | 批量指定责任医生的权限 | `followup:task:assign`（推断） |
| OQ-19 | ⚪ | 患者“操作日志”页签只读用户是否可见；统计菜单权限 | 可见；统计页用 `followup:dashboard:view`（推断） |
| OQ-20 | ⚪ | 导出时 `MASKED` 字段能否导出明文 | 不能，导出脱敏值；明文导出另行申请 |

## 三、产品与界面

| 编号 | 级别 | 问题 | 暂行做法 |
|---|---|---|---|
| OQ-21 | 🔴 | 规格页签名与 UI 定稿不一致（A“概览/基线档案/年度随访/时间轴”；C“植入记录”页签） | **按 UI 定稿**：基线资料 / 随访记录 / 报告单 /（C 参数趋势）/ 沟通记录 / 操作日志；C 植入记录并入基线资料（见 `pages.md` §4） |
| OQ-22 | 🟡 | 水印格式 | 按技术方案：`姓名（账号）· 医院名称 · 预览时间（精确到分钟）` |
| OQ-23 | 🟡 | 租户主题色是否允许改主色 | 不允许：主色固定 #3A75FF，租户只换 Logo、名称与预设辅助色 |
| OQ-24 | ⚪ | 手机端首页 | 不在 5 个响应式页面内，显示 `MobileBlocker`；若医院要求再加 |
| OQ-25 | ⚪ | 若干文案为推断：C-FU-024 重复记录提示、H5 二维码过期提示、图片压缩 JPEG 质量（0.85） | 按 `pages.md` / `tokens.ts` 现值，UAT 时请医院确认 |
| OQ-26 | ⚪ | 卡片标题 CSS 类名 | 统一用 `components.md` 的 `.crdp-card-title`。`@crdp/admin-shell` 已删除（OQ-01 / ADR 0002） |
| OQ-27 | 🟡 | 医院 A 需被告知“报告单位置必填”；C 规格 Q18 延后 | 由人和医院沟通；开发按必填实现 |
| OQ-28 | ⚪ | 导入原始文件保留期限、导出文件有效期 | 导入原文件保留 30 天、导出文件 7 天（推断） |
| OQ-34 | 🟡 | 平台库敏感字段加密：ADR 0004 只定义了租户库的每租户数据密钥，但租户联系电话要求加密存储（PLT-TEN-009 关键字段），平台侧此前没有取钥处（来源 E05-T01） | 暂行：平台库也建同构 `crypto_data_key`（平台迁移 V7），`CryptoService` 在平台上下文用它加解密（purpose=`plt-tenant-phone`），主密钥与租户侧同一套（local 模式进程内，kms 模式待云账号）。待 ADR 复核是否单列“平台数据密钥”条目 |
| OQ-35 | ⚪ | 租户联系电话的明文可见范围：规格只写“电话加密存储，**列表**脱敏显示”，未写详情是否可见（来源 E05-T01） | 暂行：列表接口不返回电话字段；详情接口（`platform:tenant:view`）返回明文供信息维护编辑，修改走 `platform:tenant:edit` 且审计不落明文。若需要比照患者字段走“专用明文接口 + 二次确认”，等 E08 审计组件落地时一并定 |
| OQ-36 | 🟡 | 隔离验收是否作为开通编排的一个步骤：平台规格“开通流程”第 6 步要求“自动执行冒烟与跨租户访问用例，全部通过才允许启用”，但 E02-T04 的编排只有五步（tenant-row→resource-row→database→migrate-register→admin），全部成功即置 TRIAL，并未内嵌隔离用例（来源 E02-T09） | 暂行：隔离验收用例（种子租户 + 新开通租户 + 租户库 SQL 静态扫描）放在 CI 每次构建执行（E02-T09），相当于平台级闸门而非单租户开通内闸门。若要按规格做成编排第六步（失败停在 PROVISIONING、进度页多一步），需单开后端任务调整编排步骤与 E02-T08 进度页，由人排期 |
| OQ-37 | 🟡 | PLT-AUTH-003 写“密码 + 短信**或 OTP**”，但 tasks.csv 没有任何 OTP（TOTP 硬件令牌 / 认证器 App）任务，医院侧也未提供硬件（来源 E04-T04） | 暂行：双因素第二因素只实现短信（`methods: ["SMS"]`）；`methods[]` 字段已预留，后续若要 TOTP 再扩展通道与绑定页面，需人排期 |
| OQ-38 | ⚪ | 账号手机号（`sys_user.phone` / `plt_account.phone`）的绑定与维护入口在哪个任务：E04-T04 只能读（迁移 V6/V9 加密列 + 盲索引），用户管理页属于 E06+（来源 E04-T04） | 暂行：种子与 `DemoPhoneInitializer` 只保证演示账号有绑定手机号；正式绑定 / 换绑 / 解绑界面与接口等用户管理任务落地，期间短信登录与双因素对无手机号账号分别表现为 40101（未绑定与凭据失败同口径） |
| OQ-40 | 🟡 | E04-T06 落地中的三个暂行做法：①权限变更生效按「重算会话」实现（令牌不变、权限快照取新值），不是强制重新登录——规格写「令牌中的权限版本号失效，前端收到特定错误码后重新拉取」，其中“特定错误码”即失去权限接口返回的 40300（AGENTS 第 7 节口径）；E06 的角色 / 用户 / 数据范围用例变更授权后**必须**调用 `PermissionVersionStore.bump`，否则旧会话不会重算（现无运行时授权接口，本任务只落机制）；②平台规格写运维人员可看“在线会话”，但权限点表只定义了租户侧 `tenant:session:view/kick`，平台端在线用户管理暂不做，平台账号仅有本人会话接口；③租户安全策略页（会话超时 / 单端登录设置界面）属 E06+，本任务先落 cfg_param `security.session.idle-minutes`（只能加严）与 `security.session.single-login` 的读取（来源 E04-T06） | 暂行：按上述实现；E06 接管授权用例的 bump 调用与安全策略页，若产品要求“权限变更强制重新登录”再调整 `TokenAuthenticationFilter` 的重算分支 |
| OQ-39 | 🟡 | E04-T05 落地中的三个暂行做法：①图形验证码图片为自绘 PNG（未引入第三方 captcha 库，仓库无该依赖）；②「管理员解锁」只有医院端接口（`/system/users/{id}/unlock`，`tenant:user:reset`），用户管理页属 E06+，平台账号暂无解锁界面（靠 30 分钟自动解锁）；③平台账号重置密码（生成临时密码 + 短信下发）依赖 E06 用户管理与 E09-T03 短信通道，本任务只实现了 `must_change_password` 标记的读取与自助改密（来源 E04-T05） | 暂行：验证码图片样式（132×44、4 位去混淆字符、浅色干扰线）为工程默认，等保测评如有要求再调；解锁 / 重置界面等 E06 用户管理页接上现有接口；`tenant:user:reset` 权限码经三处落地——平台库 V10（隐藏按钮 plt_menu 311）、老租户库 base V7（角色已存在时授予）、全新 local/dev 库种子 V102（V100 建角色晚于 V7，需在种子里补授）、生产新开通库由开通编排 ensureAdmin 授予（NOT EXISTS 三处互斥），若后续要收紧授予范围由 E06 调整 |
| OQ-41 | 🟡 | PLT-AUTH-011「忘记密码」未写明端别：`pages.md` §1 线框只有医院端登录页有「忘记密码」链接，`docs/api/auth.md` 的推断接口也只列了医院端，但平台账号同样强制绑定手机号（PLT-ROLE-001），规格层面两端都可做（来源 E04-T07） | 暂行：只做医院端（`/api/hospital/auth/password/forgot` + `/password/reset`，前端 `/password/forgot` 页）；平台账号忘记密码暂由另一名平台管理员在「平台账号管理」重置（PLT-ROLE-001 支持重置密码，界面属 E06+），期间平台端登录页不显示忘记密码入口。若产品要求平台端也自助重置，再加 `/api/platform/auth/password/forgot|reset` 与平台端页面（后端用例可复用 `PasswordResetService` 的医院端套路，工作量小） |

## 四、排期（由人决定，代理不要改排期）

| 编号 | 级别 | 问题 |
|---|---|---|
| OQ-29 | 🔴 | M5 负载 104%；A 的 UAT 修复落在 W36，余量约 8.5 人天 |
| OQ-30 | 🟡 | 等保测评窗口 5 周（W39–W43）；C 上线 W46 与节假日重叠，春节未扣除 |
| OQ-31 | 🟡 | E05 平台 P1 / P2 项（PLT-TEN-014、018–021，PLT-ORG-010，PLT-PERM-012，PLT-CFG-009）排在 M4 / M5，A 上线时不提供（`tasks.md` 待确认第 7 条） |
| OQ-32 | 🟡 | 开发合计人天超技术方案 37 人天（`tasks.md` 待确认第 10 条） |
| OQ-33 | ⚪ | C-SCAN-019 图片增强（P2）不纳入首期，是唯一没有任务覆盖的需求编号（已确认，列此备查） |
