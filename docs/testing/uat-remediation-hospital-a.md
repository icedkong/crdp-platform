# 医院 A UAT 缺陷修复手册（全栈 2，E23-T10）

> 任务 E23-T10（全栈 2，6 人天，依赖 E23-T08 ✅；验收：**UAT 缺陷关闭**）。
> 需求依据：tasks.csv 本行未关联需求编号，UAT 覆盖面按 `docs/requirements/hospital-a-spec.md` 第 3–11 章（同 E23-T08 口径）；修复正确性的基准 = 手册 §5 用例「预期（依据）」列引用的 A 需求编号与 `AGENTS.md` 合同。
> 上游：`docs/testing/uat-hospital-a.md`（E23-T08 UAT 手册）——医院用户在窗口 W33–W35 执行 §5 脚本（47 条），发现经 `scripts/uat_intake.py` 入缺陷库（GitHub Issues，OQ-122①），**每日 16:00 triage 定责到 E23-T09（全栈 1）/ E23-T10（全栈 2）**；复测关闭由 E19-T10（测试）执行。
> 与 E22-T10 / T11（渗透修复）同构：窗口执行由人完成，当前缺陷库 **0 条 UAT-ID Issue**（窗口未到）。仓库侧本 PR 交付——**修复工作流（§2–§3 / §6）+ 全栈 2 分担面（§5 H 报告单与资料 / I 统计与首页 / J 历史数据导入核对 / M 审计与平台视角，另含交叉发现的 E 组一项）的 UAT 脚本自查与修复（本轮已完成）**；窗口内真实发现到达后按同一流程处置，不另立口径。

## 1. 目标与验收口径

- 目的：路由到 E23-T10 的 UAT 发现全部修复并复测通过（E19-T10），配合 E23-T09 达成手册 §7 判据 2「阻断（severity:high）与严重（severity:medium）全部关闭」，支撑 §8 签字。
- 验收拆解（与 tasks.csv E23-T10 行验收句「UAT 缺陷关闭」一致）：
  - **分担面自查**：全栈 2 分担的用例组在预演级环境逐条自跑，P0 链路不因产品缺陷阻塞（本轮发现 **2 条：阻断 1 / 严重 1，均已修复**，§4）；
  - **窗口缺陷关闭**：窗口内 Issue 正文标注 E23-T10 的发现按 §2 流程修复，复测通过后由 E19-T10 关闭，状态以 label `UAT` 的 Issue 列表为证（手册 §6 闭环口径）。
- 工作清单唯一事实源：GitHub Issues（`UAT-ID` 幂等标记 + `severity:*` + `bug`/`UAT`/`E23` 标签，手册 §6）。仓库内**只记录仓库侧自查发现**（本手册 §4，编号 `UAT-SELF-*` 与窗口内 `UAT-W{周}-{序}` 区分，同渗透 PT-SELF-* 口径）。

## 2. 处置流程（Issue → 关闭）

1. **分诊**（发现当日，16:00 triage）：核对复现步骤 → 在 UAT 级环境复现；按 module 归类（uat_intake 的 13 模块枚举）→ 定责 E23-T09 / E23-T10（本手册只认 Issue 正文标注，不重复定责）；复核分级（误报 / 重复合并，结论留 Issue 评论）。
2. **处置决策**：阻断当日、严重 triage 后 1 个工作日内修复计划（手册 §2 时限表）；需要改权限模型 / 数据模型 / 安全边界或两份文档矛盾的，先升级 `docs/open-questions.md`（AGENTS §10），不发明需求。
3. **修复**：一类缺陷一个分支 / PR（提交说明 `fix({scope}): E23-T10 {UAT-ID/UAT-SELF-ID} {标题}`），PR 关联 Issue；**TDD——先写失败测试锁定缺陷行为再修**（本轮自查修复即此口径，§4 表列测试名）。
4. **复测**：修复版本先过 CI + 手册 §5 A 组冒烟再发 UAT（手册 §3 版本冻结口径）；由 E19-T10（或报告的医院用户）按原复现步骤复测，结论回写 Issue。
5. **关闭**：阻断 / 严重必须复测通过才 closed；一般（low）全部有处置结论（关闭或医院书面接受的延期上线处理）；建议（info）入库跟踪。

## 3. 处置口径（三选一）

| 结论 | 判据 | 记录要求 |
|---|---|---|
| **修复** | 代码 / 配置 / 迁移已改，缺陷不可复现，测试锁定 | PR 链接 + 测试名 + 复测结论 |
| **缓解** | 暂未根治但有等效控制（配置 / 开关 / 流程绕行），残余风险可接受 | 控制点位置 + 残余风险 + 跟踪项（一般级 Issue 排期） |
| **延期上线（书面接受）** | 修复代价大于影响，医院方 UAT 负责人书面接受后随遗留项清单进签字单附件 | 医院方确认留痕 + 期限（手册 §7 判据 2） |

## 4. UAT 脚本自查（本轮，窗口前完成 · 全栈 2 分担面）

自查范围 = 手册 §5 的 H（报告单与资料）/ I（统计与首页）/ J（历史数据导入核对）/ M（审计与平台视角）四组 + 交叉发现的 E-5；E23-T08 §10.1 已覆盖 A/B/E-1~E-3/G-2/K 的 P0 链路（10/10）。方法：本机起全套环境（colima + docker compose，MySQL 13306 / Redis 16379，`local` 剖面，`t_demoa` 全新库由应用全量迁移，V123 预置账号矩阵），按 §5 步骤 API 级逐条执行（角色按「角色」列：随访员 `a.cvs1.nur01`、病区医生 `a.cvs1.doc01`、科主任 `a.cvs1.dir`、数据录入员 `a.fu.clerk01`、租户管理员 `nurse.demo`、平台管理员 `platform.admin`），全部虚构数据。

**发现 3 条（阻断 1 / 严重 1 / 一般 1），均已修复；另 E-5 交叉项与 UAT-SELF-01 同根因一并修复。**

| 编号 | 级别 | module | 发现（复现位置） | 处置结论 | 证据 / 测试 |
|---|---|---|---|---|---|
| UAT-SELF-01 | 阻断 | report / import / scope | V123 预置角色权限面缺口：随访员 / 病区医生未授「上传文件」（`followup:scan:upload`，plt_menu 231）——H-1 / H-2（P0）上传报告单直接 40300；数据录入员未授「导入向导」（`followup:patient:import`，plt_menu 353）——J-1（P0）下载模板 / 上传即 40300；科主任未授「批量指定责任医生」（`followup:task:assign`，plt_menu 224）——E-5 批量指定 / 改派 40300（交叉发现，E 组归全栈 1，同根因一并修复）。A 规格 §10 角色模板「主要权限」列三处明确：病区医生「报告单添加与查看」、随访员「报告单添加与查看」、数据录入员「导入」 | **修复**：租户库种子新迁移 `V133__grant_a_report_import_assign.sql`（幂等 NOT EXISTS，只新增不改旧；敏感权限 211/212/217 仍不预授，K-4 口径不破坏） | `APresetUatGrantSqlTest`（4 例：幂等只增 / 补授面恰为四对 / 并集满足 §10 矩阵且无敏感项 / 成对书写）；实测 `t_demoa` 迁移后四授予在位，H-1 上传、J-1 模板下载、E-5 改派均不再 40300 |
| UAT-SELF-02 | 严重 | other（环境基线） | 本地 / CI 的 docker compose MySQL 未设时区（容器系统时区 UTC），与生产 RDS 参数基线 `time_zone = UTC+08:00`（`iac/rds-prod/params-baseline.json`）漂移 8 小时；审计等表 `created_at` 依赖数据库默认 `CURRENT_TIMESTAMP(3)`（`JdbcAuditLogger` 的 INSERT 不带该列），落库为 UTC 墙钟——违反「数据库 DATETIME 一律北京时间」合同（`AuditQuerySupport.ZONE`），M-1 / M-2「当日」窗口检索恒空（`parseTime` 把 ISO-8601 带时区入参折算成北京墙钟再比较，命中不了行），UAT 预演与回归结论失真 | **修复**：`docker-compose.yml` 的 mysql 增 `--default-time-zone=+08:00`（与生产 RDS 基线对齐；CI 起的是同一份 compose，本地 / CI / 生产三侧一致） | `UatEnvTimezoneBaselineIT`（2 例：`@@global.time_zone = +08:00`、`NOW()` 与北京墙钟差 ≤ 10 分钟）；实测修复前漂移 7h59m59s / `SYSTEM`，修复后当日窗口检索命中（审计行 `2026-11-10T10:15+08:00`） |
| UAT-SELF-03 | 一般 | other（审计与视角台账） | 视角会话访问计数竞态：`TenantViewGuardFilter.allow()` 先 `copyBodyToResponse()`（缓存体已刷回客户端，HTTP 调用方可提前返回）再写 VIEW_QUERY 审计与 `plt_view_session` 的 `page_count` / `query_count`——快客户端可在 UPDATE 提交前读库，审计中心「视角会话」页签的计数摘要偶发少计（本 PR CI 首跑在慢机稳定复现 `PlatformTenantViewExecutionMySqlIT` 两例：page_count 断言失败 + 泄漏会话级联 40901） | **修复**：记录挪到响应体释放之前（条数从 wrapper 缓存读，无需先回写）——审计与计数先落库、客户端后拿到响应，顺序确定 | `PlatformTenantViewExecutionMySqlIT`（既有 3 例，修复后 CI 复跑通过；本地刻意用快机全量 1219 例不回归） |

通过面（本轮自查未再发现产品缺陷，留痕供回归比对）：H-1 上传 jpg / pdf 双格式与归档展示、H-2 心脏超声映射预览 / 确认写入基线（含「报告时间晚于手术日期」时相拦截正确）、H-4 预览短链、H-5 全院检索按范围 + 脱敏；I-1 卡片目录与显示 / 隐藏（user-pref 全量覆盖生效）、I-2 / I-3 各卡接口可用（`st_node_occurrence` 随每日 00:10 作业刷新，空表属新库预期，Q38 口径已有 `E19-T08` 用例锁定）；J 组导入链路由 `ImportRehearsalVerifyIT`（E23-T06，CI 恒跑）端到端覆盖；M-1 审计中心抽验（含被拒请求 ACCESS_DENIED 留痕）、M-2 平台视角进入 / 退出与双侧审计（VIEW_ENTER / VIEW_EXIT 落 `aud_platform_access_log`）。

## 5. 待人工 / 外部事项清单（窗口 W33–W35 内执行）

- [ ] 运维在 UAT 级环境按生产 RDS 参数基线核验 MySQL `time_zone`（UAT-SELF-02 的环境侧一致性；应用侧基线已由 IT 锁定）；
- [ ] 医院用户窗口执行 §5 发现的 `UAT-W{周}-{序}` 按 §2 分诊定责，标注 E23-T10 的进本手册流程；
- [ ] E23-T09（全栈 1）分担面（A / B / C / D / E / F / G / K / L 组深挖）自查与修复；
- [ ] E19-T10 复测关闭：复测通过结论回写 Issue 后关闭；遗留项进签字单附件（手册 §7 / §8）。

## 6. 复测与闭环判据

- 「UAT 缺陷关闭」以缺陷库 Issue 状态为证（手册 §7 判据 2 的数据来源）：label `UAT` 的 Issue 中 severity:high / medium 全部 closed，low 全部有处置结论，info 全部入库；
- 本手册 §4 自查发现不进缺陷库（仓库内留痕即处置证据，同 E22-T10 §6 口径）；若窗口内发现复现同类问题，按其 `UAT-ID` 走 Issue 流程，自查记录作交叉引用（修复不重复做，复测以 Issue 为准）；
- 每个修复版本发 UAT 前过 CI + 手册 §5 A 组冒烟（版本冻结口径，手册 §3）。

## 7. 与其他任务的衔接

| 任务 | 关系 |
|---|---|
| E23-T08 | 上游：UAT 手册与入缺陷库机制（uat_intake / 模板 / OQ-122）；本手册消费其 Issue 工作清单，§4 自查即其 §10.1 预演的延续（分担面扩展） |
| E23-T09 | 并行：全栈 1 分担面修复（同窗口、同验收句式）；UAT-SELF-01 的 E-5 交叉项同根因已在本任务修复，E23-T09 回归时纳入 |
| E19-T10 | 下游：复测与关闭（§2 步骤 4–5 的执行方；复测流程与结论回写工具见 `docs/testing/uat-retest-hospital-a.md`，其 §6 预演已对本手册 UAT-SELF-01~03 回归复测） |
| E23-T11 | 上线闸门：A 上线（W36）以 §6 判据达成为前置 |
| E22-T10 / E22-T11 | 同构：处置流程 / 三选一口径 / 自查编号（PT-SELF-* ↔ UAT-SELF-*）共用同一套缺陷库梯子（OQ-112 / OQ-122） |
| E22-T04（rds-prod） | UAT-SELF-02 的对齐基线来源（`params-baseline.json` 的 `time_zone = UTC+08:00`） |

## 8. 验证留痕

| 项 | 结果 | 日期 / 依据 |
|---|---|---|
| 自查范围与发现 | §4 表 2 条（阻断 1 / 严重 1），H / I / J / M 四组 + E-5 交叉项 | 本 PR（分支 feat/e23-t10-uat-remediation-fullstack2） |
| 自查修复测试 | `APresetUatGrantSqlTest` 4/4（先红：V133 缺失时失败）、`UatEnvTimezoneBaselineIT` 2/2（先红：`SYSTEM` + 漂移 7h59m59s） | 本 PR CI |
| 端到端复测 | V133 迁移后四授予在位、H-1 / J-1 / E-5 不再 40300；时区对齐后 M-1 当日窗口命中 | 本 PR（本机实测，§4 证据列） |
| 防漂移校验 | `scripts/check_uat_remediation.py`（接入 ci.yml 恒跑） | 本 PR CI |
| 窗口内 UAT 发现处置 | 待窗口内回填（Issue 编号区间 + 复测关闭日期） | — |
