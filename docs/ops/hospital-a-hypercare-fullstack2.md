# 医院 A 上线后运行保障手册 · 全栈 2 分担面另册（W36–W37 · E23-T14）

> 任务 E23-T14（全栈 2，3 人天，依赖 **E23-T11**（go-live runbook）✅；验收：**上线问题按时响应**）。
> 本册与全栈 1 手册 `docs/ops/hospital-a-hypercare.md`（E23-T13）**同窗同构**：值班与升级（其 §2）、问题分级与响应时限（其 §3）、处置流程与台账（其 §4–§5）、每日巡检的通用六项 HC-D1~D6（其 §6）、退出判据（其 §8）**全部共用那一册的定义，本册不另立口径**；两册由防漂移校验互锁一致（§10）。
> 本册新增的是全栈 2 分担面的三块内容：**§5 分担面明细（H / I / J / M 四组）**、**§6 分担面巡检（机器侧 HC-H1~HC-M2 + 人工清单）**、**§7 分担面已知问题 playbook**。
> 按 `AGENTS.md` 第 9 节：观察期（W36–W37，2027-07-19 ~ 2027-08-01）的真实值班、接报与生产处置由人完成；本册是他们照着走的**保障工作流**，配套交付机器侧工具 `scripts/hypercare_verify_fullstack2.py`（§6 巡检 / §5 台账判定，复用 `hypercare_verify.py` 的同一套 SLA 与台账校验，防两册漂移）。
> 需求依据：tasks.csv 本行未关联需求编号（与 E23-T08 / T09 / T10 / T13 同口径），分担面覆盖按 `docs/requirements/hospital-a-spec.md` 第 3–11 章的已交付覆盖面 + `AGENTS.md` 合同；全栈 2 在 UAT 窗口对这四组的自查与修复记录见 `docs/testing/uat-remediation-hospital-a.md`（E23-T10，观察期同类问题的先例与修复口径）。

## 1. 目标与验收口径

- 目的：与全栈 1 册相同——医院 A 上线后的两周观察期内，医院用户与监控发现的问题**在分级时限内得到响应**；本册把该目标落到全栈 2 分担面（H 报告单与资料 / I 统计与首页 / J 历史数据导入核对 / M 审计与平台视角）。
- 验收句「上线问题按时响应」的三条可判定证据（与全栈 1 册 §1 同一拆法，证据面按分担面分工）：
  1. **时限表在册**：§3 分级与响应时限表与两册工具常量一致（双册互锁，§10）；
  2. **台账可判**：共用台账经 `scripts/hypercare_verify_fullstack2.py --ledger` 判定分担面条目**全部按时响应**（默认只判 owner=全栈2 的条目；退出评审用 `--all` 判全量，结果应与全栈 1 工具 `hypercare_verify.py --ledger` 一致——同一份台账、同一套判定，两工具跑出不同结果即缺陷）；
  3. **巡检在册**：§6 分担面巡检（机器侧六项 + 人工清单）执行且留痕，异常项全部进入台账闭环。

## 2. 值班与升级（W36–W37）

**共用全栈 1 册 §2，本节只列全栈 2 侧要点**（值班窗口、接报通道、升级路径、交接口径以那一册为准）：

- 全栈 1 与全栈 2 **逐日轮值**：轮到全栈 2 的值班日，执行全栈 1 册 §6 通用巡检（HC-D1~D6）+ 本册 §6 分担面巡检（HC-H1~HC-M2）与首响应；
- 非轮值日收到分担面问题：按 §5 定责直接接手，不必等轮值（台账只认 Issue 的 owner 标注）；
- 升级路径与电话簿同全栈 1 册 §2（P0 超 15 分钟、P1 超 30 分钟未响应即电话升级）；**P0 不分面**——谁先接谁牵头，恢复后再按 §5 移交。

## 3. 问题分级与响应时限（SLA 表）

与全栈 1 册 §3 **同一张表**（数字共用 `hypercare_verify.py` 与 `hypercare_verify_fullstack2.py` 的同一组常量，双册防漂移校验互锁，§10）。分级梯子与渗透（OQ-112）、UAT（OQ-122②）共用同一套 GitHub label；「响应」= **受理登记 + 首个实质动作**（分诊结论 / 缓解措施 / 修复 PR 任一，`response_action` 留痕），不是「已读」。

| 级别 | label | 定义 | 响应时限（机器判定） | 处置时限（人工闭环） |
|---|---|---|---|---|
| P0 阻断 | `severity:high` | 医院端或平台端全站不可用；数据损坏 / 丢失风险；安全事件（越权访问迹象、批量泄露）——含 H 面附件 / 资料批量不可访问、M 面审计断写 | **15 分钟**（7×24） | 4 小时内恢复服务或按 go-live §7 中止回切 |
| P1 严重 | `severity:medium` | 核心链路不可用且无绕行——含 I 面首页 / 统计全空、J 面导入确认门控不可用、M 面视角会话失控 | **30 分钟**（7×24） | 1 个工作日内修复或稳定绕行 |
| P2 一般 | `severity:low` | 有绕行的功能缺陷（如 H 面单个预览失败可改走详情附件、映射写错可人工修正基线）；性能明显劣化；非核心功能错误 | **120 分钟**（工作时间） | 3 个工作日内 |
| P3 建议 | `severity:info` | 文案、易用性、优化建议 | **480 分钟**（1 个工作日） | 排期处理（进常态需求池） |

- **机器判定口径（从严）**：两册工具都按自然时钟分钟数判定（P3 的「1 个工作日」以 8 小时 = 480 分钟从严），不做工作时间换算；夜间 / 假期接报的 P2/P3 如按工作时间口径顺延，triage 在 Issue 评论留痕说明，机器口径不改。

## 4. 处置流程（接报 → 关闭）

与全栈 1 册 §4 同一流程（受理登记 15 分钟内建 Issue、16:00 triage 分级复核、定责、三选一处置、验证、关闭），全栈 2 侧差异只有两处：

1. **Issue 标注**：label `HYPERCARE` + `E23` + 分级 label（缺陷类再加 `bug`），正文首行幂等编号 `HYPC-W{周}-{序}`（**编号段与全栈 1 册共用，不分区段**，OQ-122① 梯子）；owner 标注 `全栈2`（或按 §5 定责到具体面）；
2. **修复提交格式**：`fix({scope}): E23-T14 {HYPC-编号} {标题}`，PR 关联 Issue；**TDD——先写失败测试锁定行为再修**（AGENTS §8）。处置三选一（**修复 / 缓解 / 延期**）与生产动作走 runbook 的口径同全栈 1 册 §4，涉及导入回滚一律走 `docs/ops/hospital-a-import-rollback.md` 决策树，不在 Issue 里口头约定。

## 5. 问题台账与分担面

### 5.1 台账（与全栈 1 册共用一份）

- 工作清单唯一事实源 = GitHub Issues（label `HYPERCARE`）；机器判定用 CSV 导出（**共用模板** `docs/ops/hypercare-findings-template.csv`，13 列，表头与两册工具的 `LEDGER_COLUMNS` 逐字一致）：`finding_id`（HYPC- 编号）、`reported_at`（YYYY-MM-DD HH:MM）、`channel`（值班电话 / 医院服务群 / 巡检 / 监控告警 / 邮件）、`severity`（P0~P3）、`module`（模块标识，词表同 `uat_intake.py`：分担面常用 report / stats / import，M 面落 other）、`summary`（脱敏摘要，**疑似真实手机号 / 身份证号一律拦截**）、`triaged_at`、`owner`（全栈1 / 全栈2 / 运维 / 产品 / 测试 / 医院）、`responded_at`、`response_action`、`resolved_at`、`status`（OPEN / RESPONDED / RESOLVED）、`issue_url`；
- 判定（观察期每日 18:00 与退出评审各一次）：

```bash
# 默认只判全栈 2 分担面（owner=全栈2）
python3 scripts/hypercare_verify_fullstack2.py --ledger <台账.csv> [--asof "YYYY-MM-DD HH:MM"]
# 退出评审：判全量（结果应与全栈 1 工具 --ledger 一致）
python3 scripts/hypercare_verify_fullstack2.py --ledger <台账.csv> --all
```

  全部 `[PASS]` 才算「按时响应」达成；`[FAIL]`（超时响应 / 超时未响应）逐条按 §2 升级、§4 处置。

### 5.2 分担面明细（H/I/J/M 四组）

分界沿用 UAT 手册（`docs/testing/uat-hospital-a.md` §5）的字母分界，与全栈 1 册 §5.2 互补、不重叠（A/B/C/D/E/F/G/K/L + 平台端租户面归全栈 1）：

| 组 | 面 | 覆盖内容（页面 / 接口 / 表） |
|---|---|---|
| **H** | 报告单与资料 | 报告单归档（按患者时间树，A-ATT-010）、上传（jpg / pdf 双格式 + 数量上限）、心脏超声映射预览 / 确认写入基线（A-ATT-013，时相拦截）、预览短链与水印（H-4 口径）、全院检索按范围 + 脱敏（H-5 口径）；表 `rpt_report` / `rpt_report_file` / `rpt_report_link` / `rpt_type` / `rpt_name` / `rpt_mapping(+item)` / `rpt_indicator_*` / `sys_file` |
| **I** | 统计与首页 | 首页卡片目录与显示 / 隐藏（`dash_card` / `dash_user_pref`，user-pref 全量覆盖）、各卡接口、节点随访率（`st_node_occurrence`，每日 00:10 `st-node-refresh` 整表重建，Q38 口径：暂停计入应随访、逾期 / 补录 / 历史导入都算已随访） |
| **J** | 历史数据导入核对 | 导入向导（模板下载 / 上传 / 预检 / 批注校验报告 / 确认提交）、行级问题处置（ERROR 阻断 / WARN 人工复核）、按批提交与回滚（每批约 200 名患者一个事务）、导入后核对（患者数 / 记录数汇总比对，工具 `golive_import_verify.py`）；表 `imp_job` / `imp_row` / `imp_issue` / `imp_batch(+write)` |
| **M** | 审计与平台视角 | 审计中心检索与抽验（`aud_log` / `aud_login_log` / `aud_data_access_log`，被拒请求 ACCESS_DENIED 留痕）、数据访问审计（明文查看 60 秒口径的审计面）、审计哈希链与月度分区 WORM 归档（`aud_chain_head` / `aud_archive`，`docs/ops/audit-worm-archive.md`）、导出审批（`job_export(+approval)`）、平台视角进入 / 退出与双侧审计（`plt_view_session`，PLT-VIEW-004：2 小时上限、空闲 / 权限变更即结束、会话摘要计数） |

- 定责只认 Issue 的 owner 标注（不重复定责）；跨面问题定主责 + 协办；**P0 不分面**。

## 6. 分担面巡检（轮值人执行，与全栈 1 册 §6 巡检同轮次：09:00 与 17:00 各一轮）

### 6.1 机器侧（只读 SQL，`scripts/hypercare_verify_fullstack2.py` 巡检口径）

生产执行（持账号运维或值班人经跳板机，**只读账号，需租户库与平台库两库授权**；口令只经环境变量 `CRDP_HYPERCARE_MYSQL_PASSWORD` 进 mysql 客户端进程环境，不进 argv、不落盘）：

```bash
export CRDP_HYPERCARE_MYSQL_PASSWORD='<只读账号口令>'
python3 scripts/hypercare_verify_fullstack2.py --host <生产 RDS 地址> --port 3306 \
  --db t_<租户编码> --user <只读账号> [--platform-db crdp_platform]
```

| 检查项 | 口径（阈值） | 异常去向 |
|---|---|---|
| HC-H1 报告单附件断链 | `rpt_report_file` 挂接的 `sys_file` 缺失或状态非 ATTACHED > 0 个 | 预览将 404；查 sys_file 状态与对象键（OBS 桶 `iac/obs-archive`），按 §7 H 面行处置 |
| HC-H2 上传文件滞留 | `sys_file` PENDING 超 24 小时 > 0 个 | 上传→保存链路中断（H-1/H-2 口径）；联系上传人确认是否重传，链路缺陷进台账 |
| HC-I1 统计事实表停更 | `st_node_occurrence` 行数 > 0 且 MAX(refreshed_date) 落后当日超 1 天 | 查 `st-node-refresh` 作业（HC-D2 同源）；首页 / 统计卡片数字过期，医院侧先公告口径 |
| HC-J1 导入批次卡死 | `imp_batch` PENDING/RUNNING 超 24 小时未推进 > 0 个 | 按 `imp_batch.fail_reason` 排查，配合 HC-D5 与 `docs/ops/hospital-a-import-rollback.md` §1 决策树 |
| HC-M1 审计断写 | 近 24h `aud_log` 与 `aud_login_log` **双零** | 审计管道停摆或全站无活动（观察期两者都异常）→ P1 研判：先核时区基线（UAT-SELF-02 口径）与写入路径，再查是否全站无登录 |
| HC-M2 视角会话超时未收口（平台库） | `plt_view_session` ACTIVE 过 ends_at + 1 小时宽限 > 0 个 | 超时清扫未跑（PLT-VIEW-004 上限 2 小时）；会话级联与双侧审计按 M-2 口径复核 |

- 任何一项 FAIL：当场建 Issue 进台账（§4），按 §3 分级（多为 P1/P2；HC-M1 双零先按 P1 研判）；
- 与全栈 1 册 HC-D1~D6 的关系：**互补不重复**——D5 已覆盖 `imp_job` 卡死 / 部分失败，本册 HC-J1 只看批次级卡死；D1 已覆盖登录失败激增，本册 HC-M1 看审计写入停摆（成功 + 失败都算活动）；
- 本地 / CI 自验：`--drill` 在克隆真实 Flyway schema 的两个隔离库（租户侧 `t_hypcd2`、平台侧 `crdp_hypcd2`，LIKE 建表、结束整库删除，不碰共享演示数据）上跑「空库基线六项全 PASS → 注入六类异常逐项检出 → 清场归零」（§10）。

### 6.2 人工清单（每轮，全栈 2 分担面）

- [ ] 审计中心抽验一轮（M-1 口径）：今日巡检自身登录与操作有留痕、被拒请求可见 ACCESS_DENIED；
- [ ] 平台端视角会话列表无超时未结束会话（M-2；HC-M2 异常时逐会话核对结束方式与双侧审计）；
- [ ] 医院端首页卡片渲染正常、统计页数字与上一轮巡检记录比对无突变（I 面；新库 / 新入组初期空卡片属预期，Q38 口径）；
- [ ] 任选一名患者打开报告单归档树 + 任一报告单预览带水印（`姓名（账号）· 医院名称 · 预览时间`，H 面）；
- [ ] 导入向导最近作业状态一览无卡死（J 面，配合 HC-D5 / HC-J1）；
- [ ] 巡检结果记值班日志（生产数据不进仓库、不进 CI、不进日志，AGENTS §5.5）。

## 7. 分担面已知问题 playbook（症状 → 处置）

| 症状 | 先看哪份文档 / 检查项 |
|---|---|
| 报告单预览 404 / 附件打不开 | HC-H1 / HC-H2 线索；查 `sys_file` 状态与对象键、OBS 桶（`iac/obs-archive`）；入口链路侧见 `docs/ops/cce-prod.md` §7 |
| 心脏超声映射未写入基线 / 写错时相 | `rpt_mapping` 配置与指标模板（A-ATT-013，E16 口径）；映射停用开关（A-ATT-018）；「报告时间晚于手术日期」时相拦截为预期行为 |
| 统计卡片空 / 数字不刷新 | HC-I1 + HC-D2（`st-node-refresh` 每日 00:10 整表重建）；口径 Q38；空表属新库预期（E19-T08 用例已锁定，勿当缺陷） |
| 导入批次卡住 / 患者数核对不一致 | HC-J1 + HC-D5；`docs/ops/hospital-a-import-rollback.md` §1 回滚决策树；核对跑 `golive_import_verify.py`（只读）；切换后数据不回滚（`docs/ops/hospital-a-go-live.md` §7） |
| 审计查不到 / 疑似断写 | HC-M1；先核 MySQL 时区基线（UAT-SELF-02：`@@global.time_zone` 应为 +08:00，「当日」窗口检索才命中）；归档与 WORM 口径见 `docs/ops/audit-worm-archive.md` |
| 视角会话超时未结束 / 计数异常 | HC-M2；PLT-VIEW-004（2 小时上限、空闲 / 权限变更即结束）；计数竞态的修复口径见 `docs/testing/uat-remediation-hospital-a.md` §4 UAT-SELF-03 |
| 安全事件（越权 / 异常登录激增） | HC-D1（全栈 1 册）线索 + `docs/ops/pentest-remediation.md` 通道；涉患者数据按 A-NFR 安全口径即报产品与医院 |

## 8. 观察期退出判据与衔接

退出判据与全栈 1 册 §8 **同一套**（2027-08-01 退出评审，产品 + 开发 + 运维 + 医院方），本册补充分担面证据：

1. 台账内 P0 / P1 全部 RESOLVED（复测通过），P2 全部 RESOLVED 或有医院书面接受的延期结论；
2. 连续 3 天巡检无异常项 = 全栈 1 册 HC-D1~D6 全 PASS **+ 本册 HC-H1~HC-M2 全 PASS** + 两侧人工清单全绿；
3. `--ledger --all` 判定全部按时响应（与全栈 1 工具 `--ledger` 同一结果，退出评审当场双跑互证）；
4. 医院方确认日常使用正常（服务群无未决反馈）。

未满足 → 观察期顺延一周并通报风险。衔接：E23-T15（B 租户初始化）起本册分担面口径随 B / C 上线（E23-T19 / E23-T26）复用；E23-T28（B 回归）以观察期内的 A 生产基线为对照。

## 9. 由人执行的事项（按 `AGENTS.md` 第 9 节）

- 轮值表执行、接报与值班日志留痕（与全栈 1 册 §9 同一清单，不分册重复）；
- 生产只读账号申请（**租户库 + 平台库两库授权**）与 §6.1 分担面巡检执行（持华为云账号运维）；
- HC-M1 双零、HC-M2 泄漏等异常的现场研判与升级拨打（运维负责人 / 产品 / 项目负责人）；
- 医院方沟通与延期书面接受（实施 / 产品）；
- 观察期退出评审与台账判定留痕归档运维 wiki（真实患者数据**不进仓库**）。

## 10. 自动化对照（CI 恒跑）

`scripts/hypercare_verify_fullstack2.py` 在 CI 与本机按三种模式运行（`.github/workflows/ci.yml`）：

```bash
python3 scripts/hypercare_verify_fullstack2.py --selftest   # 纯逻辑自检：与全栈 1 工具常量一致 / 分担面判定边界 / 台账校验 / 只读护栏
python3 scripts/hypercare_verify_fullstack2.py              # 本册防漂移校验（时限表双册互锁 / 台账列 / 巡检项 / 互引 / 接线）
python3 scripts/hypercare_verify_fullstack2.py --drill      # compose MySQL：两个隔离库基线全 PASS + 六类异常逐项检出 + 清场归零
```

- 防漂移校验锁定：验收原句与依赖、时限表数字与共用常量逐字一致且**全栈 1 册同数字（双册互锁）**、共用台账 13 列与模板表头一致、分担面巡检项 HC-H1~HC-M2 与阈值在册、互引文档存在（含全栈 1 册反向互引本册）、ci.yml 接线、`docs/README.md` 索引；
- `--ledger`（现场判定）与 `--host`（生产巡检）不在 CI 运行，由值班人 / 持账号运维按 §5 / §6 执行。
