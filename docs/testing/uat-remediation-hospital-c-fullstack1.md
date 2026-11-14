# 医院 C UAT 缺陷修复手册（全栈 1，E23-T24）

> 任务 E23-T24（全栈 1，4 人天，依赖 E23-T23 ✅；验收：**UAT 缺陷关闭**）。tasks.csv 本行未关联需求编号，UAT 覆盖面按 `docs/requirements/hospital-c-spec.md` 第 3–11 章 + §10 角色模板（同 E23-T23 / E23-T25 口径）；修复正确性的基准 = C UAT 手册 §5 用例「预期（依据）」列引用的 C 需求编号（C-ACC / C-\* / CL / Q03）与 `AGENTS.md` 合同。
> 上游：`docs/testing/uat-hospital-c.md`（E23-T23 UAT 手册）——医院用户在窗口 W44–W45 执行 §5 脚本（65 条），发现经 `scripts/uat_intake.py --hospital c` 入缺陷库（GitHub Issues，OQ-122① / OQ-153①），**每日 16:00 triage 定责到 E23-T24（全栈 1）/ E23-T25（全栈 2）**；复测关闭由 E21-T30（测试）执行。
> 与 E23-T09 / E23-T10（A 侧两册）同构：窗口执行由人完成，当前缺陷库 **0 条 UAT-ID 周次 W44 / W45 的 Issue**（窗口未到，2026-11-14 实拉核验）。仓库侧本 PR 交付——**修复工作流（§2–§3 / §6）+ 全栈 1 分担面（§5 A 登录与账号 / B 患者登记与档案 / C 植入记录与电极 / D 计划与切换 / E 任务与状态流转 / F 随访程控录入 / G 沟通记录与通知 / L 权限与数据范围八组的深挖）的 UAT 脚本自查与修复（本轮已完成）**；窗口内真实发现到达后按同一流程处置，不另立口径。

## 1. 目标与验收口径

- 目的：路由到 E23-T24 的 C 侧 UAT 发现（UAT-ID 周次 W44 / W45）全部修复并复测通过（E21-T30），配合 E23-T25 达成 C 手册 §7 判据 2「阻断（severity:high）与严重（severity:medium）全部关闭」，支撑 §8 签字与 E23-T26 上线放行。
- 验收拆解（与 tasks.csv E23-T24 行验收句「UAT 缺陷关闭」一致）：
  - **分担面自查**：全栈 1 分担的用例组在预演级环境逐条自跑，C 链路不因产品缺陷阻塞（E23-T23 §10.1 已覆盖 13 条 P0 主链路，本轮做深挖：各组 P1 / P2 及 P0 的非主链分支；发现 **2 条：严重 1 / 一般 1，均已修复**，§4）；
  - **窗口缺陷关闭**：窗口内 Issue 正文标注 E23-T24 的发现按 §2 流程修复，复测通过后由 E21-T30 经 `scripts/uat_retest.py --hospital c --apply` 回写关闭，状态以 label `UAT` 的 Issue 列表为证（C 手册 §6 / §7 闭环口径，按 UAT-ID 周次 W44 / W45 过滤，OQ-153①）。
- 工作清单唯一事实源：GitHub Issues（`UAT-ID` 幂等标记 + `severity:*` + `bug`/`UAT`/`E23` 标签，C 手册 §6）。仓库内**只记录仓库侧自查发现**（本手册 §4；编号 `UAT-SELF-*` 与窗口内 `UAT-W{周}-{序}` 区分，同渗透 PT-SELF-* 口径；**C 侧编号池自 01 起，与 A 侧 UAT-SELF-01~06 按院区隔离**——两院 Issue 空间本就按 UAT-ID 周次区分，OQ-153①；E23-T25 全栈 2 手册续用 03 起）。

## 2. 处置流程（Issue → 关闭）

1. **分诊**（发现当日，16:00 triage）：核对复现步骤 → 在 UAT 级环境复现；按 module 归类（uat_intake 的模块枚举）→ 定责 E23-T24 / E23-T25（只认 Issue 正文标注，不重复定责）；复核分级（误报 / 重复合并，结论留 Issue 评论）。
2. **处置决策**：阻断当日、严重 triage 后 1 个工作日内修复计划（C 手册 §2 时限表）；需要改权限模型 / 数据模型 / 安全边界或两份文档矛盾的，先升级 `docs/open-questions.md`（AGENTS §10），不发明需求。
3. **修复**：一类缺陷一个分支 / PR（提交说明 `fix({scope}): E23-T24 {UAT-ID/UAT-SELF-ID} {标题}`），PR 关联 Issue；**TDD——先写失败测试锁定缺陷行为再修**（本轮自查修复即此口径，§4 表列测试名）。
4. **复测**：修复版本先过 CI（含 W43 起 `CFullChainAcceptanceIT`）+ C 手册 §5 A 组冒烟再发 UAT（C 手册 §3 版本冻结口径）；由 E21-T30（或报告的医院用户）按原复现步骤复测，结论经 `scripts/uat_retest.py --hospital c --apply` 回写 Issue。
5. **关闭**：阻断 / 严重必须复测通过才 closed；一般（low）全部有处置结论（关闭或医院书面接受的延期上线）；建议（info）入库跟踪（C 手册 §7 判据 2；打回路由回本任务，`--round 2` 起新轮次）。

## 3. 处置口径（三选一）

| 结论 | 判据 | 记录要求 |
|---|---|---|
| **修复** | 代码 / 配置 / 迁移已改，缺陷不可复现，测试锁定 | PR 链接 + 测试名 + 复测结论 |
| **缓解** | 暂未根治但有等效控制（文档更正 / 配置 / 开关 / 流程绕行），残余风险可接受 | 控制点位置 + 残余风险 + 跟踪项（OQ / 一般级 Issue 排期） |
| **延期上线（书面接受）** | 修复代价大于影响，医院方 UAT 负责人书面接受后随遗留项清单进签字单附件 | 医院方确认留痕 + 期限（C 手册 §7 判据 4） |

## 4. UAT 脚本自查（本轮，窗口前完成 · 全栈 1 分担面）

自查范围 = C 手册 §5 的 A（登录与账号）/ B（患者登记与档案）/ C（植入记录与电极）/ D（计划与切换）/ E（任务与状态流转）/ F（随访程控录入）/ G（沟通记录与通知）/ L（权限与数据范围）八组深挖（E23-T23 §10.1 已覆盖的 P0 主链路做回归不重复展开；H / I / J / K / M 归全栈 2，见 A 侧两册分工同构）。方法：本机起全套环境（colima + docker compose，MySQL 13306 / Redis 16379，`local` 剖面，`t_demoa` 按手册 §3 激活医院 C 形态——**八项开关全开**（含本轮 UAT-SELF-02 补全的 `record_edit_submitted` / `record_sequence_no`）、V94 类型 ENABLED、V91 两套计划 PUBLISHED），按 §5 步骤 API 级逐条执行（角色按「角色」列：医生 `c.card1.doc01`、随访员 `c.card1.fu01`、护士 `c.card1.nur01`、科主任 `c.cardio.dir`、越范围对照 `c.card2.doc02`、租户管理员），全部虚构数据（自查留痕见 C 手册 §10.2）。

**发现 2 条（严重 1 / 一般 1），均已修复。**

| 编号 | 级别 | module | 发现（复现位置） | 处置结论 | 证据 / 测试 |
|---|---|---|---|---|---|
| UAT-SELF-01 | 严重 | implant（植入记录编辑） | 植入记录编辑**没有「修改原因」入口**：`PUT /api/hospital/followup/implants/{id}` 请求体无 reason 字段（`ImplantService.UpdateRequest(int, ImplantPayload)`），落库的 fe_field_history 行（record_type=PT_IMPLANT）**reason 恒为 NULL**——违反 C-PAT-011（P0：「每个字段的修改记录旧值、新值、修改人、时间、**原因**」）；UAT 脚本 B-5（P1）「患者登记信息编辑任一字段填修改原因 → 查看字段历史；**植入记录编辑同样核对**」的植入侧预期「旧值 / 新值 / 修改人 / 时间 / 原因」缺最后一项（实测：本机编辑型号后历史行 f106 带 old/new/operator/时间、reason NULL）；前端 implant-form-modal.vue 编辑态亦无原因输入。对照：修改已提交随访记录的原因必填（OQ-14「A / C 都必填」+ 40001）是同口径先例 | **修复**：请求体增 `reason`（必填 40001、≤255 字，与修改已提交记录同口径）——`ImplantService.update` 校验后以「修改植入记录：{原因}」写入 fe_field_history 逐行 reason、IMPLANT_UPDATE 审计附原因；前端编辑弹窗增「修改原因（必填）」文本域（show-count、≤255）+ `implantEditReasonError` 校验；implant.md §1 PUT 契约、components.md ReasonModal 行同步 | `ImplantServiceTest`（+2：缺 / 空白原因 40001 不落库；logChanges 收到「修改植入记录：…」）+ 扩展既有字段历史用例断言 reason；`ImplantRecordIT`（+1 `editRecordsReasonInFieldHistory`：无原因 40001 → 带原因 200 → fe_field_history f106 行 reason = 修改植入记录：型号抄录更正（示例）、全部行带原因）；前端 `implant.spec.ts`（+2：payload 携带 trim 后原因；原因必填 / ≤255 校验）；修复后本机实测 40001 / 历史行带原因 |
| UAT-SELF-02 | 一般 | testing（UAT 就绪配置口径） | C UAT 手册 §3 激活链④的开关清单只列六项（implant / scan_progress / eri_cycle / param_trend / remote_monitor_record / report_inbox）+「等」，**漏 `record_edit_submitted` 与 `record_sequence_no`**——按清单激活的 UAT 环境会让 F-6（P0，C-ACC-22 / CL-12「修改已提交记录与删除记录两权限独立」）测到 A 口径（开关无行 = 关闭 = record:edit 即可修改已提交，229 闸门不生效，OQ-136①「未开启则 C 侧退回 A 口径，属配置缺失」）、F-7 / I 组（C-ACC-25 / C-FU-018 随访序号补录重排）序号恒空（OQ-143②「医院 C 随租户开通配置开启，未开启属配置缺失」）→ 窗口内产出**假缺陷**（本机实测：两开关关闭时 sequence_no 全 NULL、无重排历史） | **修复**：手册 §3 ④ 改为**八项清单**（补 `record_edit_submitted`〔OQ-136①〕与 `record_sequence_no`〔OQ-143②〕，各项注明漏开后果）；E23-T26 上线切换核对与运维 runbook 引用同源口径不变 | 文档 diff（uat-hospital-c.md §3 激活链行）；本机对照实测——开关开启后 F-7 全链通过（补录 11-03/05/08 → 序号 1/2/3 → 插入 11-04 → 1/2(new)/3/4 重排），关闭时序号 NULL；check 脚本锁定 §3 行含八项开关名（`check_uat_remediation_c_fullstack1.py`） |

**口径说明（脚本与规格的差异，交产品回填，不立缺陷）**：

- **E-3 执行角色**：UAT 脚本 E-3（P0）角色列写「科主任」，C 规格 §10 科主任「主要权限」= 全部查看、首页、转科室病区、发布随访计划——**无改派**；V137 的 C_DOCTOR 持按钮 224 `followup:task:assign`（科主任不持）。实测科主任 batch-assign 40300 属**正确权限行为**，医生执行通过。**已随本 PR 勘误 C 手册 §5 E-3 角色列为「医生」**（A 侧 E23-T09 的 C-3 勘误同款）；若产品要求科主任也可改派，走角色管理页加授 224（授权范围模板内，无需改码）。
- **本地持久库注记（复测侧环境核验）**：`PlatformTenantScopeMySqlIT`（平台收回 22/223 用例）跑后**不还原租户库 sys_role_menu 的 22 / 223 授予**（其 @AfterAll 只还原平台侧；重跑时 Order(2) 自身会补齐——IT 注释已言明，CI 全新库不受影响）。本机预演 / 复测前若任务列表 40300，按该 IT Order(2) 的预置 INSERT 补回即可（本手册自查即遇此况，补回后全过）；UAT 级环境全新开通无此问题。

通过面（本轮自查未再发现产品缺陷，留痕供回归比对，详见 C 手册 §10.2 修复后复测列）：B-2 登记成功 + 随访编号 `105-2026-NNN` 自动生成 + 重复病历号 40902 + 同名同生日 possibleMatches 提示可继续；B-3 概览（器械 / 病区 / 状态 / 随访编号）；B-6 转病区后任务随患者迁移（旧范围 40400、新范围 200）；C-1 升级 UPGRADE CRT-D + 三腔电极（LV 部位 CS）+ RV 电极填 RA 部位 40001（腔室过滤）；C-2 更换后旧器械 REPLACED、结束日期 = 新植入日期（两条历史器械链 186/187/188 核对）；C-3 补录 2026-10-05 自动关联当时在用 DDD（implantId=186）；C-5 植入与程控共用字典（pacemaker_brand 九项、pacing_mode 十一项、电极部位按腔室 ra/rv/lv 分字典）；D-1 登记即自动入组 C_PACER_FU_PLAN（起算 = 植入日期、术后 72 小时窗口 = 植入日至 +7 天，与 Q03 节点表一致）；D-3 提交 f323=1 → 入组 mode=ERI、eri_since=随访日期、doctor_next_date（f333=2027-01-15）登记、last_visit_date 推进；E-1 WARD / ALL 页签与任务行列（器械类型 / timepoint / nextPlanDate）；E-2 start 建草稿 → 详情 context（nodeName / 计划日 / 窗口 / device chambers=2）→ 提交 COMPLETED / ON_TIME；F-2 check-duplicate 命中已有记录（duplicate=true 带 recordId）；F-3 提交含标红参数（f313 阈值升高 + ERI）后站内信**零新增**（不产生通知，C-ACC-19 / OQ-130③）；F-5 已终止患者守卫在 RecordBackfillService 133/161 行（40901 代码路径核对）；F-7 见 UAT-SELF-02 证据列；G-2 站内信脱敏（E23-T23 §10.1 已演，本轮回归零异常）；G-3 租户管理员读通知设置 200（V134 后 C 侧同样适用——通知菜单授予走租户库种子，与院区无关）；L-4 越范围 summary 404 / 40400；L-5 明文逐字段无权限 40300（专用通道 POST /plaintext）。

## 5. 待人工 / 外部事项清单（窗口 W44–W45 内执行）

- [ ] 医院用户窗口执行 §5 发现的 `UAT-W4{4|5}-{序}` 经 uat_intake --hospital c 入库、分诊标注 E23-T24 的进本手册 §2 流程修复；
- [ ] 运维按 C 手册 §3（**八项开关**，UAT-SELF-02 后）核对 UAT 级环境激活链，尤其 `record_edit_submitted` / `record_sequence_no` 两项易漏；
- [ ] E-3 角色勘误若医院坚持科主任执行改派：产品决定是否在角色模板加授 224（走配置，不改码）；
- [ ] E21-T30 复测关闭：`uat_retest.py --hospital c --apply` 回写结论并按结论关闭 / 打回（打回路由回本任务）；遗留项进签字单附件（C 手册 §7 / §8）。

## 6. 复测与闭环判据

- 「UAT 缺陷关闭」以缺陷库 Issue 状态为证（C 手册 §7 判据 2 的数据来源）：label `UAT` 的 Issue 中 **C 侧（UAT-ID 周次 W44 / W45）** severity:high / medium 全部 closed，low 全部有处置结论，info 全部入库；
- 本手册 §4 自查发现不进缺陷库（仓库内留痕即处置证据，同 A 侧两册口径）；若窗口内发现复现同类问题，按其 `UAT-ID` 走 Issue 流程，自查记录作交叉引用（修复不重复做，复测以 Issue 为准）；
- 每个修复版本发 UAT 前过 CI（含 `CFullChainAcceptanceIT`）+ C 手册 §5 A 组冒烟（版本冻结口径，C 手册 §3）；阻断 / 严重清零硬期限 = W45 内 2027-09-23 前，不延入 W46 国庆周（C 手册 §1）。

## 7. 与其他任务的衔接

| 任务 | 关系 |
|---|---|
| E23-T23 | 上游：C UAT 手册与入缺陷库机制（uat_intake --hospital c / OQ-153）；本手册 §4 自查即其 §10.1 预演的深挖延续（全栈 1 分担面），自查留痕回填其 §10.2 |
| E23-T25 | 并行：全栈 2 分担面修复（H / I / J / K / M）；两手册共用 UAT-SELF 编号池（01~02 归全栈 1，03 起归全栈 2——C 侧池，与 A 侧按院区隔离）与同一处置流程 |
| E21-T30 | 下游：复测与关闭（§2 步骤 4–5 的执行方；复测流程与工具见 `docs/testing/uat-retest-hospital-c.md`；本地持久库 22/223 注记见其 §6 预演参考） |
| E23-T26 | 上线闸门：C 上线切换（W46，测评通过后）以 C 手册 §7 判据达成（含本任务修复关闭）为硬前置；§3 八项开关清单与切换核对同源 |
| E21-T01 / E21-T11 / E23-T20 | UAT-SELF-01 涉及的植入编辑链路（ImplantService / C_IMPLANT 模板 / fe_field_history）的上游交付方；OQ-129② 契约「编辑写字段级历史」补齐原因维度 |
| E23-T09 / E23-T10 | 同构：A 侧两册的处置流程 / 三选一口径 / 自查编号（UAT-SELF-*）与工具链共用（OQ-112 / OQ-122 / OQ-153） |

## 8. 验证留痕

| 项 | 结果 | 日期 / 依据 |
|---|---|---|
| 自查范围与发现 | §4 表 2 条（严重 1 / 一般 1），A / B / C / D / E / F / G / L 八组深挖（API 级，本机全套环境 + C 形态激活） | 本 PR（分支 feat/e23-t24-uat-remediation-c-fullstack1） |
| 自查修复测试 | `ImplantServiceTest` +2（先红：UpdateRequest 无 reason 时编译失败）+ 字段历史用例断言 reason、`ImplantRecordIT` +1（先红：修复前无原因 40001 用例失败于 reason=NULL 断言）、前端 `implant.spec.ts` +2（先红：buildImplantUpdatePayload 双参调用失败） | 本 PR CI（IT 门控 CRDP_IT_MYSQL_PORT） |
| 端到端复测 | 植入编辑：无原因 40001「修改植入记录必须填写修改原因（C-PAT-011）」；带原因 200 → fe_field_history 全部行 reason =「修改植入记录：型号抄录更正（示例）」、f106 行 old/new 在位；F-7 序号重排链（开关开启后）1/2/3 → 插入 → 1/2/3/4 | 本 PR（本机实测，§4 证据列） |
| 防漂移校验 | `scripts/check_uat_remediation_c_fullstack1.py`（接入 ci.yml 恒跑：手册结构 / UAT-SELF-01 修复代码与测试在位 / UAT-SELF-02 手册 §3 八项开关 / 索引同步） | 本 PR CI |
| 窗口内 C UAT 缺陷处置 | 待窗口内回填（Issue 编号区间 + 修复版本 + 复测关闭日期 + `--status --hospital c` 看板结论） | — |
