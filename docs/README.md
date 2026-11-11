# docs 索引 — 临床科研数据一体化平台（crdp）

> 先读仓库根目录 `AGENTS.md`（规则、命令、完成定义），再按下面的顺序查阅。本目录内容于 2026-10-31 由需求页面整理转换，是开发的唯一需求依据；仓库里旧的 docx 导出版本已过时，不要参考。

## 目录

| 路径 | 内容 | 什么时候读 |
|---|---|---|
| `requirements/tech-plan.md` | 整体技术方案 V0.2：架构、多租户、数据模型、安全合规、自动保存、响应式、非功能（NFR-*） | 动架构、数据模型、安全相关代码前 |
| `requirements/platform-spec.md` | 平台底座需求规格 V0.3（PLT-TEN / MOD / AUTH / ORG / ROLE / PERM / SCOPE / CFG / VIEW / AUD） | 平台端、认证、权限、审计 |
| `requirements/hospital-a-spec.md` | 医院 A 需求规格 V0.3（A-*）；第 14 章为医院 B 配置差异；附录 A 字段字典 266 个字段 | A / B 业务 |
| `requirements/hospital-c-spec.md` | 医院 C 需求规格 V0.1（C-*）；附录 A 字段字典 97 个字段 | C 业务（起搏器、程控、纸质资料） |
| `tasks/tasks.md` | 里程碑、执行说明、各里程碑任务表 | 选任务 |
| `tasks/tasks.csv` | 277 个任务：编号、标题、类型、人天、依赖、关联需求、冲刺 / 周、验收 | 选任务、查依赖 |
| `design/design-system.md` | 设计规范：令牌、布局、表格、表单、弹窗、脱敏、水印、响应式、自动保存、文案 | 任何前端任务 |
| `design/tokens.ts` | 设计令牌（AntD 主题 + 自定义），前端直接引用 | 前端主题、样式 |
| `design/components.md` | 业务组件规格（props / 事件 / 状态 / 权限） | 写组件 |
| `design/pages.md` | 逐页规格：路由、权限、需求、接口、线框、响应式 | 写页面 |
| `design/reference-pages.md` | 医院端样板页：哪些文件是模板、怎么抄 | 写医院端页面时先看 |
| `api/README.md` | 接口通用约定：前缀、鉴权、响应包、错误码、分页、数据范围、脱敏、审计 | 任何接口 |
| `api/auth.md` · `platform.md` · `system.md` · `form.md` · `patient.md` · `implant.md` · `followup.md` · `report.md` · `notify.md` · `stats.md` · `import-export.md` · `audit.md` | 各模块接口契约提纲 | 对应模块 |
| `testing/device-matrix.md` | 平板与手机真机矩阵（六档）与 5 个关键页面测试用例、CI 自动化覆盖图、真机执行记录表（档案唯一来源 `frontend/packages/responsive/src/device-matrix.ts`；物理真机执行由人按记录表完成，E24-T08） | 响应式改动前后、真机验收（E24） |
| `testing/responsive-walkthrough.md` | 平板与手机交互走查脚本与执行记录（手机 390 / 平板 820 双档逐页）、走查问题清单与关闭状态（W-0 表单白屏 / W-1 抽屉触控尺寸 / W-2 基线编辑登记 / W-3 状态点；E24-T09） | 响应式回归、产品验收复走（E24） |
| `testing/uat-hospital-a.md` | 医院 A UAT 组织、脚本与问题跟踪手册：组织与日程（W33–W35）、UAT 脚本 47 条（P0/P1/P2）、发现入缺陷库（模板 `testing/uat-findings-template.csv` + `scripts/uat_intake.py`）、签字判据与签字单（现场执行与签字由人完成，E23-T08） | A 租户 UAT、上线放行（E23；C 的 UAT E23-T23 复用框架） |
| `testing/uat-remediation-hospital-a.md` | 医院 A UAT 缺陷修复手册（全栈 2）：处置流程（Issue→关闭）、三选一处置口径、分担面（H/I/J/M + E-5 交叉）UAT 脚本自查与修复（阻断 1 / 严重 1，已修复：V133 预置角色补授、compose MySQL 时区对齐生产 RDS 基线）、人工事项清单与复测闭环判据（窗口内真实发现到达后按同一流程处置） | UAT 缺陷关闭（E23-T10；E23-T09 分担面另册） |
| `testing/uat-remediation-hospital-a-fullstack1.md` | 医院 A UAT 缺陷修复手册（全栈 1）：处置流程（Issue→关闭）、三选一处置口径、分担面（A/B/C/D/E/F/G/K/L 深挖）UAT 脚本自查与修复（严重 2 已修复：V134 通知设置菜单补授、登记即死亡不入组；一般 1 缓解：patient.md PUT 行更正 + OQ-124）、人工事项清单与复测闭环判据（窗口内真实发现到达后按同一流程处置） | UAT 缺陷关闭（E23-T09；E23-T10 分担面另册） |
| `testing/uat-retest-hospital-a.md` | 医院 A UAT 支持与缺陷复测手册（测试角色）：支持侧职责（triage / 版本准入冒烟 / 执行记录表收集）、复测流程（准入→按原步骤复测→结论回写→关闭/打回）、结论三档与关闭动作（通过 completed / 不通过打回 / 遗留 not_planned 仅低级别）、复测工具 `scripts/uat_retest.py`（结论回写 Issue + `--status` 判据看板；模板 `testing/uat-retest-template.csv`）、复测面预演（UAT-SELF-01~06 回归复测 9/9）、闭环判据（阻断/严重清零） | UAT 缺陷复测关闭（E19-T10；C 侧 E21-T30 复用框架） |
| `testing/hospital-a-golive-smoke.md` | 医院 A 上线核对与冒烟测试手册：冒烟用例集 13 条（S-0~S-12，runbook §6 切换动作 + UAT P0 主干；执行面 = 机器 CI / 生产人工）、生产执行流程与数据红线（只用「示例」虚构患者）、失败处置（runbook §7 / hypercare SLA）、机器侧自动化 `GoliveSmokeIT` 与防漂移校验 `scripts/check_golive_smoke.py`（切换后立即执行，E23-T12；B/C 上线复用口径） | A 上线冒烟、切换后放行（E23；B/C 上线 E23-T19 / E23-T26 复用） |
| `testing/hospital-b-acceptance.md` | 医院 B 验收手册（补录任务与节点随访率）：用例集 10 条（B-0~B-9，§14 Q43/Q44 补录行为闭环 + A-DASH-012 口径 + A-DASH-013 首页开启；执行面 = 机器 CI / 生产人工）、生产执行流程与数据红线、开关行载体定夺（配置模板承载，OQ-128 ④ 关闭；迁移补行与种子 V100 撞唯一键不可行）、机器侧自动化 `BAcceptanceBackfillNodeRateIT`（demob 化身，门控恒跑；验收发现并修复 FIXED 超次直接录入期误拦缺陷）与防漂移校验 `scripts/check_hospital_b_acceptance.py` | B 验收通过、E23-T19 上线前置（E23-T17） |
| `ops/rds-prod.md` | RDS 8.0 生产实例运维手册：参数基线、主备切换验证、跨区域备份开启（IaC 在 `iac/rds-prod/`；真实云操作由运维执行） | 数据库运维、备份恢复演练（E22） |
| `ops/cce-prod.md` | CCE 生产环境三级基线运维手册：集群 / ELB / WAF / 通配符证书 / HSS 对照清单与验证（IaC 在 `iac/cce-prod/`；真实云操作由运维执行） | 生产入口、主机防护、渗透测试对接（E22） |
| `ops/audit-worm-archive.md` | 审计归档 OBS WORM 桶 runbook：桶级 WORM（合规 15 年）开通顺序、生产验证清单（删除 / 覆盖尝试必须失败）、归档演练与留痕模板（IaC 在 `iac/obs-archive/`；真实云操作由运维执行，CI 含本地不可删改演练） | 审计长期留存、防篡改验证（E22-T08 / PLT-AUD-010） |
| `ops/tenant-restore.md` | 单院恢复 runbook：单租户库恢复到指定时间点（脚本 `scripts/restore_tenant.sh`；PITR 临时实例等真实云操作由运维执行，CI 含本地演练） | 误操作 / 逻辑损坏的单院回滚（E22-T06） |
| `ops/cross-region-drill.md` | 跨区域恢复演练 runbook：区域不可用 → 上海一从跨区域备份 IaC 重建恢复，实测区域内 / 跨区域 RPO 与 RTO、验证清单与报告存档（脚本 `scripts/cross_region_drill.sh`；PITR 与 IaC 重建等真实云操作由运维执行，CI 含本地演练） | 半年恢复演练（E22-T07；E22-T12 核对的输入） |
| `ops/drill-records/` | 恢复演练报告存档：本地 / CI 演练报告全文与生产演练登记表（区域内 RPO ≤ 5 分钟、RTO ≤ 4 小时的达标判据）+ E22-T12 核对登记表 | 演练报告存档（E22-T07 验收）；E22-T12 核对的输入 |
| `ops/drill-recovery-checklist.md` | 恢复演练核对清单（E22-T12）：runbook §4 九项逐项映射机器核对（`scripts/drill_recovery_check.py`：存档核对 / 化身按清单核对 / 自检）与生产由人核对方法，核对记录登记到 drill-records 核对登记表 | 按清单核对恢复数据（E22-T12 验收） |
| `ops/pentest.md` | 渗透测试对接手册：范围与授权、靶机环境、日程（W32–W35）、报告分级、发现入缺陷库（模板 `ops/pentest-findings-template.csv` + `scripts/pentest_intake.py`；选商 / 签约 / 测试 / 报告出具由人执行） | 上线前渗透测试（E22-T09；E22-T10/T11 修复的输入） |
| `ops/pentest-remediation.md` | 渗透修复手册（后端）：处置流程（Issue→关闭）、三选一处置口径、上线前后端安全自查发现与处置（高危 0 / 中危 2，已修复）、人工事项清单与复测闭环判据（第三方发现到达后按同一流程处置） | 渗透高危清零与中危处置（E22-T10） |
| `ops/pentest-remediation-frontend.md` | 渗透修复手册（前端）：处置流程（Issue→关闭）、三选一处置口径、上线前前端安全自查发现与处置（高危 0 / 中危 1，已修复：令牌 sessionStorage 化、外链 noopener、登录回跳白名单、依赖扫描缓解）、人工事项清单与复测闭环判据（第三方发现到达后按同一流程处置） | 前端相关问题复测通过（E22-T11） |
| `ops/hospital-a-sample-intake.md` | 医院 A 历史数据样本接收与脱敏对接手册：导出口径、保密红线、脱敏工具 `scripts/deidentify_sample.py`、「校验通过」判据与警告处置、迭代循环（样本交接 / 医院修正 / 复核结论由人执行） | 租户上线样本对接（E23-T05；E23-T06/T07/T11 演练与正式导入的输入） |
| `ops/hospital-a-import-rehearsal.md` | 医院 A 导入演练 · 数据核对手册：约 5000 名患者确认提交后「汇总数核对四路一致（源表台账 = 作业统计 = 库内 = 列表 API，含任务数 = 0）+ 抽样核对（随机 20 + 边界 + 脏值行逐字段）」的口径、差异处置与人工事项；自动化对照 `ImportRehearsalVerifyIT`（CI 恒跑，现场执行由人完成） | 导入演练与数据放行（E23-T06；UAT J-2、E23-T07 / T11 的前置） |
| `ops/hospital-a-import-rollback.md` | 医院 A 导入演练 · 回滚方案与演练记录：发现不一致后走哪条路（取消 / 40901 闸 / 按批回滚 / 整作业回滚 / 重试 / 单院恢复边界）、「等于没导过」核对清单、回滚耗时取证 SQL、演练环步骤与耗时记录模板（含本机实测参考）；自动化对照 `ImportRehearsalOpsIT`（CI 恒跑） | 导入演练技术执行与清场重来（E23-T07；E23-T11 正式导入的回滚预案） |
| `ops/hospital-a-go-live.md` | 医院 A 正式数据导入与上线切换 runbook：放行闸门（演练放行 / UAT 签字 / 渗透高危清零 / 恢复演练达标 / 生产基线 / 租户就绪 / 数据交接七道）、W36 窗口与耗时预算（按 E23-T07 实测推算）、生产导入执行、汇总数核对（工具 `scripts/golive_import_verify.py`：只读护栏 + 台账对账 + `--drill` 差异注入自验）与抽样核对（人工）、上线切换动作（DNS → WAF CNAME、租户转正 PLT-TEN-010）、中止与回滚、上线后衔接；真实生产执行由持账号运维 / 实施在场完成 | A 上线（E23-T11；E23-T12/T13/T14 与 B / C 上线切换的输入） |
| `ops/hospital-a-hypercare.md` | 医院 A 上线后运行保障手册（全栈 1，W36–W37 观察期）：值班与升级口径、问题分级与响应时限表（P0 15 分钟 / P1 30 分钟 / P2 2 小时 / P3 1 个工作日，机器判定自然时钟从严）、处置流程（GitHub Issues 台账 HYPC- 编号 + 三选一处置 + 超时升级）、分担面（全栈 1 A/B/C/D/E/F/G/K/L，E23-T14 同构另册）、每日巡检（机器侧六项 HC-D1~D6 只读 SQL + 人工清单；工具 `scripts/hypercare_verify.py`：`--ledger` 时限判定 + `--host` 生产巡检 + `--drill` 隔离库异常注入自验）、已知问题 playbook、观察期退出判据与 B/C 上线复用衔接 | 上线问题按时响应（E23-T13；E23-T14 分担面另册、E23-T19/T26 复用口径） |
| `ops/hospital-b-onboarding.md` | 医院 B 用 A 配置模板初始化 runbook（W37）：放行闸门（A 上线观察期 / A 配置冻结 / 模板路径 IT / 生产基线 / B 租户信息五道）、开通执行（导出 A 配置模板 → 向导带 templateId → config-template 步骤 → 转正，复用 E12-T05 路径）、两级一致性核对（机器侧 `scripts/check_tenant_b_onboarding.py`：全注册表 23 张等价表 + 角色包含 + 只复制红线，`--drill` 克隆演练差异注入自验 + 生产只读核对；人工每域抽查）、第 14 章 B 差异处置与承接任务（计划 / 首页卡片 / 病区术者 / 用户 → E23-T16~T18）、中止回滚（E05-T08 注销，A 不受影响）；向导路径演练 IT `TenantBOnboardingMySqlIT`（CI 门控恒跑） | B 租户开通、配置与 A 一致（E23-T15；E23-T16~T19 / T28 的输入） |
| `ops/hospital-b-plan-preset.md` | 医院 B 预置计划配置与激活 runbook（W37）：第 14 章 Q42 节点 / 提醒 / 窗口表的载体与核对（followup V80 未发布草稿随包，行级 `BPresetPlanSqlTest` CI 恒跑）、B 侧激活手册（停用 A 预置 + 发布 B 预置 + 虚构患者抽查；激活须在 config-template 之后）、回滚（停用终态与复制新版本）、首页节点随访率卡片衔接（dash_role_layout 与 node_follow_rate 开关，随 E23-T17）；激活路径演练 IT `BPresetPlanVerifyIT`（demob 化身，门控恒跑） | B 预置计划按 Q42 配出（E23-T16；E23-T17 验收的输入） |
| `ops/hospital-b-user-ward-role.md` | 医院 B 用户、病区与角色配置 runbook（W38）：第 14 章「用户、角色取值」的载体与核对（tenant/seed V136 与 A 预置 V123 同构并存、命名空间隔离，行级 `BPresetWardUserSqlTest` CI 恒跑；生产不执行种子，按交接单录入）、生产配置手册（病区字典 → 从模板复制角色配病区范围 → 用户录入 / 批量导入 → 按病区分配 → 默认随访负责人 → 抽查）、回滚与人员 / 病区处置、敏感项按需分配口径；落库与行为演练 IT `BPresetWardUserVerifyIT`（demob 化身，门控恒跑：本病区可见 / 他病区 40400 / 跨病区并集 / 权限预览） | B 用户按病区分配（E23-T18；E23-T17 验收 / E23-T19 上线的输入） |
| `ops/hospital-a-hypercare-fullstack2.md` | 医院 A 上线后运行保障手册 · 全栈 2 分担面另册（W36–W37 观察期，与全栈 1 册同窗同构：值班 / SLA / 台账 HYPC- 编号段 / 退出判据共用不另立）：分担面明细（H 报告单与资料 / I 统计与首页 / J 历史数据导入核对 / M 审计与平台视角）、分担面巡检（机器侧六项 HC-H1~HC-M2 只读 SQL：附件断链 / 上传滞留 / 统计停更 / 导入批次卡死 / 审计断写 / 视角会话超时，含平台库项 + 人工清单）、台账判定（`scripts/hypercare_verify_fullstack2.py`：`--ledger` 默认只判 owner=全栈2、`--all` 全量与全栈 1 工具互证 + `--host` 生产巡检 + `--drill` 双隔离库异常注入自验；复用 `hypercare_verify.py` 同一套 SLA / 台账校验，双册互锁防漂移）、分担面 playbook、退出判据补充分担面证据 | 上线问题按时响应（E23-T14；E23-T19/T26 复用口径） |
| `open-questions.md` | 待确认问题 OQ-01…OQ-131（顺延）与暂行做法 | 遇到不确定时先查 |
| `adr/` | 仓库已有架构决定（如 M0 骨架） | 改架构前 |
| `reviews/m3-code-review.md` | M0–M3 代码审查报告（2026-11-10，对照 tasks.csv 与验收标准）。B1 的演示绕过见 PR #205；B1 与 B2 暂缓 | 看完成度、上线缺口和已知缺陷 |
| `reviews/hospital-c-acceptance-review.md` | 医院 C 需求澄清与验收用例评审（产品，W38 · E21-T31）：澄清记录 12 条（CL-01…12，两项新增暂行口径回写 OQ-131）、验收用例集 45 条（C-ACC-01…45，算例取自规格各章验收标准与 tasks.csv 验收列）、计划切换十行子表、任务覆盖核对 37 行与 UAT 复核清单；机器面防漂移 `scripts/check_hospital_c_acceptance_review.py` | C 侧测试任务（E21-T25…T29）与 UAT（E23-T23）的用例口径基准 |

## 编号规则

- 需求：`PLT-xxx-nnn`（平台，126 条）、`A-xxx-nnn`（医院 A，131 条）、`C-xxx-nnn`（医院 C，109 条）、`NFR-xxx-nnn`（非功能，技术方案 31 条，另有设计补充的 `NFR-SAVE-*`、`NFR-RESP-*`）。每条需求在规格的功能表里有优先级（P0 / P1 / P2）或“首期不做”。
- 任务：`E{史诗}-T{序号}`，如 `E15-T01`。里程碑 M0…M6。
- 待确认问题：`OQ-nn`。
- 需求覆盖：366 条业务需求中 348 条有任务覆盖，17 条明确延后（首期不做），1 条（C-SCAN-019 图片增强，P2）已确认不做、无任务。

## 标记约定

- **推断，待确认**：规格没有写死、整理时给出的建议实现。可以按建议做；要改先在 `open-questions.md` 记一笔。
- **规格已定**：来自规格原文，不要改。
- **示例**：文档中的人名、编号都是虚构示例（如“张医生（示例）”），不对应任何真实人员。

## 没有转换进来的内容

- 交互式线框中的点击切换、悬停等动态状态：已用文字和字符线框写进 `design/pages.md`，以文字为准。
- 医院提供的字段 Excel：只通过规格附录 A 的字段字典表使用；原文件含真实数据，**不进仓库**。
- 竞品截图：含个人信息，未复制、未描述；只吸收了布局结论（见 `design/design-system.md`）。
