# 医院 A UAT 缺陷修复手册（全栈 1，E23-T09）

> 任务 E23-T09（全栈 1，5 人天，依赖 E23-T08 ✅；验收：**UAT 缺陷关闭**）。
> 需求依据：tasks.csv 本行未关联需求编号，UAT 覆盖面按 `docs/requirements/hospital-a-spec.md` 第 3–11 章（同 E23-T08 / E23-T10 口径）；修复正确性的基准 = 手册 §5 用例「预期（依据）」列引用的 A 需求编号与 `AGENTS.md` 合同。
> 上游：`docs/testing/uat-hospital-a.md`（E23-T08 UAT 手册）——医院用户在窗口 W33–W35 执行 §5 脚本（47 条），发现经 `scripts/uat_intake.py` 入缺陷库（GitHub Issues，OQ-122①），**每日 16:00 triage 定责到 E23-T09（全栈 1）/ E23-T10（全栈 2）**；复测关闭由 E19-T10（测试）执行。
> 与 E22-T10 / T11（渗透修复）、E23-T10（全栈 2）同构：窗口执行由人完成，当前缺陷库 **0 条 UAT-ID Issue**（窗口未到）。仓库侧本 PR 交付——**修复工作流（§2–§3 / §6）+ 全栈 1 分担面（§5 A 登录与账号 / B 患者档案与检索 / C 基线录入 / D 计划与入组 / E 任务与状态流转 / F 随访表单录入 / G 沟通记录与通知 / K 权限与数据范围 / L 平板与手机，另含 D-2 与 E23-T10 分担面的交叉）的 UAT 脚本自查与修复（本轮已完成）**；窗口内真实发现到达后按同一流程处置，不另立口径。

## 1. 目标与验收口径

- 目的：路由到 E23-T09 的 UAT 发现全部修复并复测通过（E19-T10），配合 E23-T10 达成手册 §7 判据 2「阻断（severity:high）与严重（severity:medium）全部关闭」，支撑 §8 签字。
- 验收拆解（与 tasks.csv E23-T09 行验收句「UAT 缺陷关闭」一致）：
  - **分担面自查**：全栈 1 分担的用例组在预演级环境逐条自跑，P0 链路不因产品缺陷阻塞（E23-T08 §10.1 已覆盖 A / B / E-1~E-3 / G-2 / K 的 P0 链路 10/10，本轮做深挖：各组 P1 / P2 及 P0 的非主链分支；发现 **3 条：严重 2 / 一般 1，均已处置**，§4）；
  - **窗口缺陷关闭**：窗口内 Issue 正文标注 E23-T09 的发现按 §2 流程修复，复测通过后由 E19-T10 关闭，状态以 label `UAT` 的 Issue 列表为证（手册 §6 闭环口径）。
- 工作清单唯一事实源：GitHub Issues（`UAT-ID` 幂等标记 + `severity:*` + `bug`/`UAT`/`E23` 标签，手册 §6）。仓库内**只记录仓库侧自查发现**（本手册 §4，编号 `UAT-SELF-*` 与窗口内 `UAT-W{周}-{序}` 区分，同渗透 PT-SELF-* 口径；全栈 2 手册已用 UAT-SELF-01~03，本手册续用 04 起）。

## 2. 处置流程（Issue → 关闭）

1. **分诊**（发现当日，16:00 triage）：核对复现步骤 → 在 UAT 级环境复现；按 module 归类（uat_intake 的 13 模块枚举）→ 定责 E23-T09 / E23-T10（只认 Issue 正文标注，不重复定责）；复核分级（误报 / 重复合并，结论留 Issue 评论）。
2. **处置决策**：阻断当日、严重 triage 后 1 个工作日内修复计划（手册 §2 时限表）；需要改权限模型 / 数据模型 / 安全边界或两份文档矛盾的，先升级 `docs/open-questions.md`（AGENTS §10），不发明需求。
3. **修复**：一类缺陷一个分支 / PR（提交说明 `fix({scope}): E23-T09 {UAT-ID/UAT-SELF-ID} {标题}`），PR 关联 Issue；**TDD——先写失败测试锁定缺陷行为再修**（本轮自查修复即此口径，§4 表列测试名）。
4. **复测**：修复版本先过 CI + 手册 §5 A 组冒烟再发 UAT（手册 §3 版本冻结口径）；由 E19-T10（或报告的医院用户）按原复现步骤复测，结论回写 Issue。
5. **关闭**：阻断 / 严重必须复测通过才 closed；一般（low）全部有处置结论（关闭或医院书面接受的延期上线处理）；建议（info）入库跟踪。

## 3. 处置口径（三选一）

| 结论 | 判据 | 记录要求 |
|---|---|---|
| **修复** | 代码 / 配置 / 迁移已改，缺陷不可复现，测试锁定 | PR 链接 + 测试名 + 复测结论 |
| **缓解** | 暂未根治但有等效控制（文档更正 / 配置 / 开关 / 流程绕行），残余风险可接受 | 控制点位置 + 残余风险 + 跟踪项（OQ / 一般级 Issue 排期） |
| **延期上线（书面接受）** | 修复代价大于影响，医院方 UAT 负责人书面接受后随遗留项清单进签字单附件 | 医院方确认留痕 + 期限（手册 §7 判据 2） |

## 4. UAT 脚本自查（本轮，窗口前完成 · 全栈 1 分担面）

自查范围 = 手册 §5 的 A（登录与账号）/ B（患者档案与检索）/ C（基线录入）/ D（计划与入组）/ E（任务与状态流转）/ F（随访表单录入）/ G（沟通记录与通知）/ K（权限与数据范围）/ L（平板与手机）九组的深挖（E23-T08 §10.1 已覆盖的 P0 主链路做回归不重复展开；H / I / J / M 归全栈 2，见其手册 §4）。方法：本机起全套环境（colima + docker compose，MySQL 13306 / Redis 16379，`local` 剖面，`t_demoa` 全新库由应用全量迁移，V123 预置账号矩阵），按 §5 步骤 API 级逐条执行（角色按「角色」列：随访员 `a.cvs1.nur01`、病区医生 `a.cvs1.doc01`、科主任 `a.cvs1.dir`、数据录入员 `a.fu.clerk01`、租户管理员 `nurse.demo`、平台管理员 `platform.admin`），全部虚构数据。

**发现 3 条（严重 2 / 一般 1），均已处置（修复 2 / 缓解 1）。**

| 编号 | 级别 | module | 发现（复现位置） | 处置结论 | 证据 / 测试 |
|---|---|---|---|---|---|
| UAT-SELF-04 | 严重 | notice（通知配置） | 全新租户库**没有任何角色**持有「通知设置」菜单（plt_menu 36，`followup:notice:config`）——followup V22 把它授予 TENANT_ADMIN，但全新库的种子 V100（创建该角色）按版本号排序在 V22 之后执行，授予命中 0 行（与 base V7 / V10 的授予在新库落空、由种子 V102 / V105 兜底是同一类问题）；V123 的 A 预置角色按 §10 口径也不持有。实测：租户管理员 `nurse.demo`、随访员 `a.cvs1.nur01` `GET /api/hospital/notify/rules` / `settings` / `send-logs/search` 全部 40300 → **G-3（P1，A-NTF-001）逐事件规则配置页对所有角色不可用**（绕行：租户管理员经角色管理页手工授菜单，菜单在授权范围模板内） | **修复**：租户库种子新迁移 `V134__grant_notice_menu.sql`（幂等 NOT EXISTS、只新增不改旧、限定 built_in=1，V105 同口径）授予 TENANT_ADMIN ← 36；A 规格 §10 租户管理员「主要权限」列明确含通知设置 | `NoticeMenuGrantSqlTest`（4 例：幂等只增 / 恰为 TENANT_ADMIN←36 / built_in 限定 / 根因注释）+ `NoticeMenuGrantVerifyIT`（2 例：双租户授予在位；规则清单 200 且 10 项、窗口预告提前 7 天 / 即将逾期 2 天 / 已逾期站内 + 短信可抄送病区负责人）；修复后本机实测 `nurse.demo` 读规则 200 / 10 项 |
| UAT-SELF-05 | 严重 | patient / plan（登记与入组） | 出院状态 = 3（死亡，A 规格字段 191）的患者登记后 `followup_status` 仍为 ACTIVE（依赖 pt_patient 列默认值，登记不写该列）——违反 A 规格 §10 状态规则表「已死亡：随访记录是否死亡 = 是，**或出院状态 = 死亡**」；后果：死亡患者可被**批量入组**（实测 batch-preview / batch 均 ENROLL / SUCCESS 入组并建随访任务），D-2（P1）预期「已入组 / 死亡患者被跳过并说明」失败，且状态展示错误（死亡患者显示随访中）。对照：自动入组（Q35）与随访记录「是否死亡 = 是」两条路径已正确判死，仅登记入口漏 | **修复**：登记事务内 `dischargeStatus = 3` → 插入行 `followup_status = 'DEAD'`（PatientInsert 增列、INSERT 显式写入）+ 状态时间线初始行走 `recordRegistrationDead`（DEAD / REGISTRATION / 原因「出院状态为死亡」）；批量入组既有 followupStatus 守卫随之自然跳过 | `PatientRegistrationServiceTest`（+2 例：死亡登记 DEAD + 状态初始行；存活 / 病重仍 ACTIVE 不回退）+ `PatientRegistrationIT`（+1 例端到端：followup_status=DEAD、pt_status_history 初始行 DEAD、无自动入组、批量预检 SKIP/DEAD）；修复后本机实测死亡患者 `SKIP/DEAD`、存活患者入组不受影响 |
| UAT-SELF-06 | 一般 | patient（接口文档台账） | `docs/api/patient.md` 的 `PUT /api/hospital/followup/patients/{id}` 行声称「接口本体随本任务实现」，实际仓库无此 Controller、plt_api_resource 未登记（实测 40307「接口未登记，默认拒绝」），`StartDateRecalculationService.recalculateOnBaseDateChange`（E14-T07）也无生产调用方——文档与代码漂移（AGENTS §8.7）。UI 无基础信息编辑面（pages.md §4.2 概览只读 + §4.3 基线编辑），B-5（P1）「编辑任一字段并填修改原因 → 查看字段历史」经基线编辑（PUT baseline sections 写 fe_field_history，含默认原因）与联系方式版本（POST contact-info 版本化留痕）两口径可执行，**不阻塞用例** | **缓解**：patient.md 该行更正为「未实现 + 设计保留（字段历史 / 日期变更同事务重算）」；是否实现待产品定夺（可编辑字段面、姓名 / 病案号改动的加密列与盲索引重写契约）——登记 **OQ-124** 跟踪，不擅自发明契约（AGENTS §10） | 文档 diff（patient.md §2 该行）+ `docs/open-questions.md` OQ-124（含「不做 / 做」两分支与实现要点）；B-5 实测通过：f013 字段历史行含 oldValue 172 / newValue 174 / 王医生（示例）/ 时间 / reason「基线分节保存」 |

**口径说明（脚本与规格的差异，交产品回填，不立缺陷）**：

- **C-3 执行角色**：UAT 脚本 C-3 角色列写「科主任」，A-PAT-006 / V122 / V123 的口径是**病区医生**确认无误后锁定（A_WARD_DOCTOR 持按钮 226 `followup:patient:lock`，科主任不持——§10 科主任「主要权限」列无锁定）。本轮按规格以病区医生执行：锁定 → 暂存 / 保存一律 40901、空原因解锁 40001、解锁留痕，全部通过；**建议手册 §5 C-3 角色列勘误为「病区医生」**（§5 待人工）。
- **D-3 的「不可修改」验证口径**：科主任无 `followup:plan:edit`（V117 口径：计划配置〔24/219/220〕由租户管理员维护；§10 科主任只有「发布随访计划」）——科主任侧入口不可见 + 后端 40300 兜底（更严格地满足「不可修改」）；「只能复制新版本」的 40901 / 复制链按租户管理员验证（实测 40901「已发布的计划不能修改，请复制为新版本（A-FU-019）」+ copy 出 v2 UNPUBLISHED）。
- **E-4 逾期标记**：逾期置位在每日 00:10 调度链（`FollowupTaskCreationService` → `refreshOverdue`，补录任务豁免，`FollowupTaskSchedulerIT` 锁定）；本轮 API 级验证「已过窗口补录任务可执行」（最近一个已过窗口的 BACKFILL 任务 start → submit → COMPLETED / OVERDUE 完成），早于下一节点开启日的年度按 Q09 走历史补录（taskless 记录实测可建可提交）。
- **G-4 每日汇总**：SMS 通道 local 为模拟（手册 §3 口径）；发送台账 `send-logs/search` 需 `followup:notice:config`（UAT-SELF-04 修复后租户管理员可查，汇总作业口径 E09-T05 已由 NotifyAcceptanceIT 锁定）。

通过面（本轮自查未再发现产品缺陷，留痕供回归比对）：A-2 改密后旧口令 40101 / 新口令可登录 / 历史口令（最近 5 次）42200 拦截；A-3 不存在账号与错误口令文案一致（「租户、账号或密码不正确」）；B-2 登记成功 + 重复病案号 40902；B-4 keyword 按病案号 / 姓名盲索引精确命中、高级检索条件树（病区 + 出院日期 BETWEEN）命中且越范围行不可见；C-1 身高 172 / 体重 70 → BMI 派生 23.7；C-2 旧 baseVersion 40900 附冲突详情（currentVersion / updatedBy / diffFields 逐字段）；B-5 见 UAT-SELF-06 行；D-1 预置计划节点「电话随访」+1 年 / 提前 7 天 / 窗口 ±7 天 / 表单 A_ANNUAL_FU / 责任医生、长期不设结束年度；D-2 预检显示将加入 / 将跳过人数与逐条原因（REMOVED / ALREADY_ENROLLED / DEAD）；E-5 批量指定责任医生与单任务改派（E23-T10 V133 修复回归，不再 40300）；F-1 模板按分类分组下发（categories / fields / required / optionSet）、必填未填提交 42200 且 fieldErrors 定位到字段与分类；F-2 暂存 → 刷新恢复（GET 草稿值一致）→ 提交成功；F-3 `previous` 解析上次记录（visitDate / values）+ compareRules 17 条随记录下发（数值触发与 highlight 重算由 CompareHighlightIT / CompareRulesIT / CompareSourceIT 锁定）；G-1「已联系 / 电话」留痕（未联系到链 T08 §10.1 已演）；K-2 跨病区直链 40400、本病区对照 200；K-3 平台放开 211（scope PUT，自动收回联动）→ 租户授 A_DIRECTOR → 明文逐字段（`expiresInSeconds: 60`，OQ-57 popconfirm 口径不加 reauth）+ 每次调用写 VIEW_PLAIN 数据访问审计（实测 3 次调用 3 行、描述只含字段中文名不含明文）；K-4 敏感权限（211 明文 / 212 导出 / 217 删除 / 232 下载）在全新库零预授（本轮 DB 全量比对：无角色持有的非敏感菜单仅 36 一项，即 UAT-SELF-04；其余零授予项恰为敏感按钮）；L-1 五个响应式页面（患者列表 / 详情 / 随访表单 / 我的任务 / 报告单）isMobile 分支与手机样式在位、L-2 未适配页（随访计划等）MobileBlocker 阻断文案在位（系统走查归 E24-T09，`docs/testing/responsive-walkthrough.md` §1）。

## 5. 待人工 / 外部事项清单（窗口 W33–W35 内执行）

- [ ] UAT 手册 §5 C-3 角色列勘误：「科主任」→「病区医生」（A-PAT-006 口径；产品，随 §10.1 同一批手册修订）；
- [ ] OQ-124 拍板：患者基础信息编辑接口 `PUT /patients/{id}` 做或不做（做则需定字段面与姓名 / 病案号改动的加密 / 盲索引重写契约；产品）；
- [ ] 运维在 UAT 级环境按生产口径重新执行全量迁移（含 V133 / V134 种子）核验授予在位（与全栈 2 手册 §5 的 UAT-SELF-02 环境核验同一张清单）；
- [ ] 医院用户窗口执行 §5 发现的 `UAT-W{周}-{序}` 按 §2 分诊定责，标注 E23-T09 的进本手册流程；
- [ ] E19-T10 复测关闭：复测通过结论回写 Issue 后关闭；遗留项进签字单附件（手册 §7 / §8）。

## 6. 复测与闭环判据

- 「UAT 缺陷关闭」以缺陷库 Issue 状态为证（手册 §7 判据 2 的数据来源）：label `UAT` 的 Issue 中 severity:high / medium 全部 closed，low 全部有处置结论，info 全部入库；
- 本手册 §4 自查发现不进缺陷库（仓库内留痕即处置证据，同 E22-T10 §6 / E23-T10 §6 口径）；若窗口内发现复现同类问题，按其 `UAT-ID` 走 Issue 流程，自查记录作交叉引用（修复不重复做，复测以 Issue 为准）；
- 每个修复版本发 UAT 前过 CI + 手册 §5 A 组冒烟（版本冻结口径，手册 §3）。

## 7. 与其他任务的衔接

| 任务 | 关系 |
|---|---|
| E23-T08 | 上游：UAT 手册与入缺陷库机制（uat_intake / 模板 / OQ-122）；本手册 §4 自查即其 §10.1 预演的深挖延续（全栈 1 分担面） |
| E23-T10 | 并行：全栈 2 分担面修复（H / I / J / M + E-5 交叉项）；其 UAT-SELF-01 的 E-5 修复在本轮回归通过；两手册共用 UAT-SELF 编号段（01~03 归全栈 2、04 起归全栈 1）与同一处置流程 |
| E19-T10 | 下游：复测与关闭（§2 步骤 4–5 的执行方；复测流程与结论回写工具见 `docs/testing/uat-retest-hospital-a.md`，其 §6 预演已对本手册 UAT-SELF-04~06 回归复测） |
| E23-T11 | 上线闸门：A 上线（W36）以 §6 判据达成为前置 |
| E14-T05 / E14-T07 | UAT-SELF-05 与自动入组（Q35）死亡口径对齐；UAT-SELF-06 涉及的 `StartDateRecalculationService` 待调用方（OQ-124） |
| E22-T10 / E22-T11 | 同构：处置流程 / 三选一口径 / 自查编号（PT-SELF-* ↔ UAT-SELF-*）共用同一套缺陷库梯子（OQ-112 / OQ-122） |

## 8. 验证留痕

| 项 | 结果 | 日期 / 依据 |
|---|---|---|
| 自查范围与发现 | §4 表 3 条（严重 2 / 一般 1），A / B / C / D / E / F / G / K / L 九组深挖 | 本 PR（分支 feat/e23-t09-uat-remediation-fullstack1） |
| 自查修复测试 | `NoticeMenuGrantSqlTest` 4/4（先红：V134 缺失时失败）、`NoticeMenuGrantVerifyIT` 2/2、`PatientRegistrationServiceTest` +2（先红：无 recordRegistrationDead 时编译失败）、`PatientRegistrationIT` +1（先红：修复前 followup_status 实测 ACTIVE 断言失败） | 本 PR CI（MySQL 门控 CRDP_IT_MYSQL_PORT） |
| 端到端复测 | V134 迁移后租户管理员读规则 200 / 10 项（窗口预告 7 天、即将逾期 2 天、已逾期站内 + 短信 + 抄送病区负责人）；死亡患者登记 `followup_status=DEAD` + 状态历史初始行 + 批量预检 `SKIP/DEAD`，存活患者入组不受影响 | 本 PR（本机实测，§4 证据列） |
| 防漂移校验 | `scripts/check_uat_remediation_fullstack1.py`（接入 ci.yml 恒跑） | 本 PR CI |
| 窗口内 UAT 发现处置 | 待窗口内回填（Issue 编号区间 + 复测关闭日期） | — |
