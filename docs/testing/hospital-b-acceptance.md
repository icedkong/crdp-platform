# 医院 B 验收手册：补录任务与节点随访率（W38 · E23-T17）

> 任务 E23-T17（测试，3 人天，依赖 **E23-T16** ✅；验收：**验收用例通过**）。关联需求 **A-DASH-012**（按节点统计的随访率，平台共用统计）；验收面按 A 规格 §14「术后第 1 / 3 / 5 天任务」表（Q43 / Q44 补录规则）与 §14「统计」行（补录按 Q38 口径照常计入）+ A-DASH-013（B 初始化后在首页卡片配置中开启）划定。
> 按 `AGENTS.md` 第 9 节：生产侧验收（B 租户激活后的真实操作与医院确认）由**测试 + 实施在场执行**（§4），仓库交付——用例集（§3）+ 机器侧自动化 `BAcceptanceBackfillNodeRateIT`（门控 `CRDP_IT_MYSQL_PORT`，CI / 本机同口径，§7）+ 防漂移校验 `scripts/check_hospital_b_acceptance.py`（CI 恒跑）。
> 排期：S19 · W38（2027-08-02 当周）；E23-T19（B 上线）以本手册全部用例通过为前置。

## 1. 目标与验收口径

- 「验收用例通过」= §3 用例集**全部通过**：机器可判定子集在 CI 每次构建执行（§7），生产侧动作按 §4 由人执行留痕（§5）；
- 前置：E23-T15 两级核对通过（B 租户配置与 A 一致）、E23-T16 §3 激活完成（A 预置停用、B 预置「心脏术后随访（医院 B）」已发布）；
- 任一用例不通过 → 按 `docs/ops/hospital-b-plan-preset.md` §4 回滚与中止口径处置（B 尚无正式数据，成本可控），修复后全组复测；
- 与 A 侧既有覆盖分工：补录任务的**创建形态**（三种登记形态、窗口与缓存）已由 `BPresetPlanVerifyIT`（E23-T16）锁定，本手册不重复；本手册的机器面在激活态之上走**行为闭环**（逾期豁免 → 直接录入 → 完成 → 计入统计）与**节点随访率卡片**。

## 2. 执行面分工

| 执行面 | 内容 | 载体 |
|---|---|---|
| 机器 CI | B-1~B-5 的机器可判定部分（demob 作 B 化身：E23-T15 runbook §3 演示化身口径，全虚构数据，与生产同链路） | `BAcceptanceBackfillNodeRateIT`，门控 `CRDP_IT_MYSQL_PORT` |
| 生产人工 | B-0 全部 + B-1~B-5 的生产侧动作（真实入口、B 管理员 / 医生账号操作、医院方确认） | 本手册 §4 流程 + §5 执行记录 |

## 3. 验收用例集（共 10 条）

> 级别全部为**阻断**（任一不通过 = 验收不通过）；「依据」列括号内为口径来源（需求编号 / 确认问题 / 仓库口径）。

| 编号 | 级别 | 执行面 | 角色 | 步骤 | 预期（依据） |
|---|---|---|---|---|---|
| B-0 | 阻断 | 生产人工（机器侧断言化身态） | B 租户管理员 | 医院端登录后核对功能开关下发：`GET /api/hospital/auth/features` 或等价页面入口；再核对 B 库 `cfg_feature` 行 `node_follow_rate = 1` | `node_follow_rate = true`（§6 开关行载体：B 经 A 配置模板开通，`cfg_feature` 在 A-FLD-014 复制清单内、E23-T15 §5.1 已逐表核对；演示 / IT 环境为种子 V100 置开）。若为 false = 模板源缺行，按 §6 兜底口径处置后再验 |
| B-1 | 阻断 | 机器 CI + 生产人工 | B 租户管理员 | 激活态复核（E23-T16 §3 已做）：计划列表 A 预置「冠心病术后年度随访」停用、B 预置「心脏术后随访（医院 B）」已发布；首页统计 → 节点随访率卡片打开（不选计划） | 卡片缺省计划 = B 预置（id 最小的已发布版本，A-DASH-012「默认本租户的预置计划」、OQ-109③）；计划下拉候选只有 B 预置（A 预置停用退出候选）；机器侧另断言开关关闭 → 40301（AGENTS §5.7，回归 `DashboardRateCardsIT`） |
| B-2 | 阻断 | 机器 CI + 生产人工 | 病区医生 | 登记虚构示例患者「孙示例（示例）」：手术日期 8 天前、出院今天（§14 虚构示例 03-01 / 03-08 形态）；再看患者详情随访页签 | 只入组 B 预置一条、起算日期 = **手术日期**（B 与 A 的核心差异）；术后第 1 / 3 / 5 天窗口均已结束 → 补建三条「补录」任务（创建方式标补录，Q44），未来节点不预生成（创建形态行级参数由 `BPresetPlanVerifyIT` 锁定，不重复执行） |
| B-3 | 阻断 | 机器 CI + 生产人工 | 随访员 / 病区医生 | 等 1 天（或以窗口已结束的补录任务直接执行）：逾期刷新后查看补录任务状态与通知 | 补录任务**不进入逾期**：仍待随访、不产生逾期站内信 / 短信（A 规格 §14「不进入逾期、不发逾期类通知」；机器侧调每日逾期刷新断言豁免与无 `TASK_OVERDUE` 事件） |
| B-4 | 阻断 | 机器 CI + 生产人工 | 随访员 | 打开 B-2 的术后第 1 天补录任务 → 开始录入完整随访记录表单（分类 9 全部字段，Q43）→ 填实际随访日期（落原窗口内）提交 | 可直接录入（不受直接录入期拦截）；提交后任务「已完成（补录）」——`create_type = BACKFILL` 身份保留、完成方式按提交日判为逾期完成（fu_task 层），前端展示由 `createType = BACKFILL` 优先派生「已完成（补录）」（`packages/api/src/task.ts`，单测已锁）；沟通记录照常可填、可只记录补录信息来源（§14） |
| B-5 | 阻断 | 机器 CI + 生产人工 | B 租户管理员 / 科主任 | 首页 → 节点随访率卡片核对各节点行（B-2~B-4 造的数据）：术后第 1 / 3 / 5 天、术后 3 个月、第 1 年 | 各节点行数字与按 Q38 手工计算的总表**逐格一致**：应随访含未完成补录（计入分母）、已随访含补录完成（甲补第 1 天、丙补第 1 年计入）、窗口内完成按**实际随访日期**判定（与提交日 / 完成方式无关，Q38）；行标签：固定节点 = 节点名（术后第 1 / 3 / 5 天、术后 3 个月）、年度循环节点 = 第 N 年（OQ-109③）；年度节点行与每年随访率卡同一筛选下数字一致（A-DASH-012「与每年随访率的关系」） |
| B-6 | 阻断 | 机器 CI + 生产人工 | B 租户管理员 | 首页卡片配置 → 角色布局：给本院角色（逐个）勾选「节点随访率」保存；换持该角色的账号看首页 | 未配置角色按目录默认**不显示**节点随访率（`default_visible = 0`，A 默认不显示的同一保证，followup V77）；逐角色开启后首页即出现（配置即时生效，A-DASH-013）；写 `DASHBOARD_ROLE_LAYOUT` 审计 |
| B-7 | 阻断 | 机器 CI（回归引用） | —— | 开关关闭面回归（不破坏生产开关）：关 `node_follow_rate` 后卡片接口与首页入口 | 后端 40301、前端隐藏入口（`GET /auth/features` 判定，AGENTS §5.7；机器侧已由 `BAcceptanceBackfillNodeRateIT` B-1 与 `DashboardRateCardsIT` / `DashboardLayoutIT` 锁定，生产**不执行**关开关动作） |
| B-8 | 阻断 | 机器 CI（引用） | —— | 补录创建不写 `TaskOpened`、行级参数（偏移 / 提醒 / 窗口 / 表单）与 Q42 表逐项一致 | `BPresetPlanVerifyIT`（E23-T16）已锁定；本手册机器面在 B-2 / B-3 断言回归（事件计数 = 0） |
| B-9 | 阻断 | 生产人工 | B 租户管理员 | 系统管理 → 审计中心核对当日：登记建档、任务完成、卡片布局保存 | 各事件在册含操作人 / 时间 / 结果（PLT-AUD；机器面已断言 `TASK_COMPLETE` / `RECORD_SUBMIT` / `DASHBOARD_ROLE_LAYOUT` 落库） |

## 4. 生产验收执行流程（激活完成后，半天内）

> 数据红线：只用带「（示例）」标记的虚构患者（B-2 一名 + 视需要再加），验收后删除；不碰真实患者明文（AGENTS §5.5）。

| # | 动作 | 用例 | 预算 |
|---|---|---|---|
| 1 | B 管理员登录医院端，核对 `node_follow_rate` 下发与 B 库开关行（§6） | B-0 | 10 分钟 |
| 2 | 复核激活态与卡片缺省计划 | B-1 | 10 分钟 |
| 3 | 登记虚构示例患者（手术 8 天前出院），核补录创建 | B-2 | 20 分钟 |
| 4 | 执行一次每日作业（或等次日 00:10 后）核对补录未逾期、无逾期通知 | B-3 | 15 分钟 |
| 5 | 补录任务录入并提交，核「已完成（补录）」 | B-4 | 20 分钟 |
| 6 | 首页节点随访率逐格核对（§3 B-5 手工总表法） | B-5 | 40 分钟 |
| 7 | 角色布局逐角色开启，换账号核对首页显示 | B-6 | 20 分钟 |
| 8 | 审计核对、示例患者删除、执行记录归档（§5） | B-9 | 15 分钟 |

- 失败处置：按 `docs/ops/hospital-b-plan-preset.md` §4（停用 B 预置 / 复制新版本重发布；B 尚无正式数据时可整租户重来）；缺陷入台账并回归受影响用例组；
- 医院 B 确认点：节点行数字与医院自己台账的口径核对（Q38 已由医院 A 确认，B 沿用）——由实施带院方接口人签字（同 UAT 口径）。

## 5. 执行记录（归档，不进仓库）

机器侧输出与生产执行表归档运维 wiki（与 `hospital-a-go-live.md` §5.4 同口径）；只登记用例编号、数据概况与结论，**不登记患者数据**。

## 6. 开关行载体与兜底口径（OQ-128 ④ 定夺）

- **载体定夺**：`node_follow_rate` 生产开关行不由迁移补行、定为**配置模板承载**——租户库种子与主迁移共用同一 Flyway 历史（`TenantSchemaMigrator`，V100 种子高于全部主迁移），任何 `V8x` 主迁移插入该行都会在全新演示 / CI 库与种子 V100 的裸 `INSERT` 撞 `uk_cfg_feature` 唯一键；也不写租户分支（AGENTS §5.7）。
- **生效链**：`cfg_feature` 在 A-FLD-014 配置复制清单内（`ConfigImportSpecs`，E12-T05 落地；E23-T15 §5.1 机器核对 23 张等价表已含）——B 经 A 配置模板开通即得 `node_follow_rate = 1`；A 自身默认不显示节点随访率由 `dash_card.default_visible = 0` + 角色布局双重保证（followup V77），全链路无「if 租户」。
- **生产核对（B-0）**：B 管理员 `GET /api/hospital/auth/features` 应见 `node_follow_rate = true`；为 false = 模板源（医院 A 生产库）缺行——兜底口径：由运维在 B 库补 `cfg_feature` 行（`INSERT INTO cfg_feature (feature_code, enabled) VALUES ('node_follow_rate', 1)`，运维 wiki 留痕）后复验；根治载体（平台端 `GET/PUT /api/platform/tenants/{id}/features` 按租户维护接口，`docs/api/platform.md` §62「随后续任务」）仍随后续任务接入，本定夺已回写 `docs/open-questions.md` OQ-128 ④。

## 7. 机器侧自动化对照（CI / 本机门控）

| 用例 | 承载（`BAcceptanceBackfillNodeRateIT`，demob 化身，自建自清 `IT-E23T17-` 前缀） |
|---|---|
| B-1 | `b1ActivationMakesBPresetDefaultPlanAndFeatureOn`：激活（停 A 预置 + 发布 B 预置）→ `/auth/features` 下发开关 → 卡片缺省 `planId` = B 预置、候选仅 1 条 → 开关关闭 40301、恢复可用 |
| B-2 / B-8 | `b2BackfillTasksCreatedAndExemptFromOverdue`：三名虚构患者（手术 8 天 / 14 个月 / 14 个月前）出院登记 → 补录任务计数（3 / 5 / 5，全部 BACKFILL） |
| B-3 | 同上：`refreshOverdue` 刷新后补录任务仍待随访、`TASK_OVERDUE` 与 `TaskOpened` 事件计数 = 0 |
| B-4 | `b3BackfillDirectEntryCompletesAsBackfillKind`：甲补术后第 1 天、丙补第 1 年（f214 实际随访日期落原窗口内）→ `COMPLETED` + `complete_kind = OVERDUE`（按提交日）+ `create_type = BACKFILL` 身份保留 |
| B-5 | `b4NodeRateCountsBackfillAsDueAndFollowed`：事实表刷新 → node_rate 五行与手工总表逐格一致（due / followed / inWindow）+ yearly_rate 第 1 年与年度行一致 + 行标签 |
| B-6 | `b5RoleLayoutEnablesNodeRateCard`：默认布局无 node_rate → `PUT /dashboard/role-layouts` 开启 → `GET /dashboard/layout` 即出现 |
| 修复 | 本验收发现并修复 FIXED 固定次数排完后 `occurrenceScheduleOf` 凭空算出「下一次」、把补录提交误拦在直接录入期外的缺陷（B-4 机器面当时红）；修复见 `TaskCreationPlanner.occurrenceScheduleOf`（FIXED 超次数 → null）+ `TaskCreationPlannerTest.occurrenceScheduleReturnsNullBeyondFixedRepeatCount` |

## 8. 由人执行的事项（按 `AGENTS.md` 第 9 节）

- 生产验收动线（§4 全部）——测试 + 实施在场，医院 B 接口人参与 B-5 口径确认；
- 开关缺行的兜底补行与留痕（§6，运维）；
- 执行记录归档（§5，运维）。

## 9. 防漂移

`scripts/check_hospital_b_acceptance.py`（CI 恒跑）校验：本手册 ↔ `BAcceptanceBackfillNodeRateIT` 用例锚点（B-x）↔ `docs/ops/hospital-b-plan-preset.md` / `hospital-b-onboarding.md` 互引 ↔ `docs/README.md` 索引 ↔ `docs/tasks/tasks.csv` 行 ↔ `.github/workflows/ci.yml` 接线；接线见 ci.yml「医院 B 验收校验（E23-T17）」步骤。
