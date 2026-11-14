# 等保测评整改手册（后端，E22-T15）

> 任务 E22-T15（后端 / 全栈 1，5 人天，依赖 E22-T14 ✅；验收：**整改项全部关闭**）。
> 需求依据：`docs/requirements/platform-spec.md` NFR-CMP-001（等保 2.0 三级）、`docs/requirements/hospital-a-spec.md`
> A-NFR-007（沿用平台等保三级设计）；整改方案锚点 `docs/requirements/tech-plan.md` §10（病毒扫描后续方案）。
> 上游：`docs/ops/mlps/onsite-assessment.md`（E22-T14 现场测评手册）——测评问题经
> `scripts/mlps_onsite_intake.py` 入缺陷库（GitHub Issues，OQ-137①），**asset_type=backend 的问题路由到本任务**
> （frontend → E22-T16、infra → 运维会诊、management → 管理会诊）；整改窗口 W44，复测 W45（E22-T17）。
> 与 E22-T10（渗透修复）、E23-T09（UAT 修复·全栈 1）同构：现场测评在窗口 W39–W43（2027）由人执行，
> 当前缺陷库 **0 条 MLPS-ID Issue**（窗口未到）。仓库侧本 PR 交付——**整改工作流（§2–§3 / §6）+ 已知偏差
> 后端面的先行整改（D-01 本轮已落地，§4）**；窗口内真实测评问题到达后按同一流程处置，不另立口径。

## 1. 目标与验收口径

- 目的：路由到 E22-T15 的测评问题（不符合 / 部分符合）全部整改并复测通过（E22-T17），配合 E22-T16
  达成「整改项全部关闭」；自查「部分符合」项中后端面的先行整改在整改窗口前落地（本轮 D-01 已完成）。
- 验收拆解（与 tasks.csv E22-T15 行验收句「整改项全部关闭」一致）：
  - **先行整改**：自查已知偏差（`docs/ops/mlps/self-assessment.md` §4 D-01~D-08）中后端可落地的项
    在窗口前处置完毕——**D-01（上传病毒扫描，恶意代码防范部分符合）本轮已按 tech-plan §10 方案修复**
    （§4），自查 §2.4「恶意代码防范」转符合；其余偏差逐条有处置结论（§4 表：已接受 / 由人 / 条件触发）；
  - **窗口整改**：窗口内 Issue 正文标注 E22-T15 的测评问题按 §2 流程整改，复测通过后关闭，
    状态以 label `mlps` 的 Issue 列表为证（onsite-assessment.md §8 退出标准）。
- 工作清单唯一事实源：GitHub Issues（`MLPS-ID` 幂等标记 + `bug`/`mlps`/`E22`/`severity:*` 标签，
  OQ-137①）。仓库内只记录仓库侧先行整改（本手册 §4，编号沿用自查 D-01~D-08，与窗口内
  `MLPS-W{39~43}-{序}` 区分）；测评问题原文与证据不进仓库（AGENTS §5.5）。

## 2. 处置流程（Issue → 关闭）

1. **分诊**（问题入库当日）：核对测评记录表 / 报告章节引用（`evidence_ref`）→ 在 UAT 级环境复现或核对
   配置 / 代码；确认资产归属（backend / frontend / infra / management，路由错误在 Issue 评论改派）；
   复核结论与分级（不符合 / 部分符合 × 高 / 中 / 低；误报与申辩结论留 Issue 评论，onsite-assessment.md §5）。
2. **处置决策**（高风险通报后 24 小时内、其余 2 个工作日内）：按 §3 三选一定结论；接受风险须产品确认
   （评论区留痕）；需要改权限模型 / 数据模型 / 安全边界的先升级 `docs/open-questions.md`（AGENTS §10），
   不发明需求。
3. **整改**：一类问题一个分支 / PR（提交说明 `fix({scope}): E22-T15 {MLPS-ID} {标题}`），PR 关联 Issue；
   **TDD——先写失败测试锁定问题行为再修**（本轮 D-01 即此口径，§4 表列测试名）。
4. **复测**（E22-T17，W45）：由测评机构按原核查点复测（或窗口外由测试按同步骤回归），结论回写 Issue
   并同步 `self-assessment.md` §4 偏差登记（onsite-assessment.md §7 闭环）。
5. **关闭**：高风险必须复测通过才 closed；中风险在处置结论 + 整改落地（或缓解控制生效）后 closed；
   低风险排期跟踪。E22-T18 汇总闭环记录随备案材料提交。

## 3. 处置口径（三选一，与渗透 / UAT 修复同构）

| 结论 | 判据 | 记录要求 |
|---|---|---|
| **整改** | 代码 / 配置 / 迁移已改，问题不可复现，测试锁定 | PR 链接 + 测试名 + 复测结论 |
| **缓解** | 暂未根治但有等效控制（基线 / 配置 / 限流 / 流程），残余风险可接受 | 控制点位置 + 残余风险 + 跟踪项 |
| **接受风险** | 整改代价大于风险且有既有控制兜底 | 产品确认留痕 + 理由 |

## 4. 已知偏差后端面先行整改（本轮，整改窗口前完成）

自查基准（`self-assessment.md` §4，D-01~D-08）中后端可落地的项逐一处置：**D-01 整改落地（本 PR），
D-02~D-08 已有结论（已接受 / 由人 / 条件触发），后端无悬空项。**

| 编号 | 偏差（自查 §4 原口径） | 处置结论 | 证据 / 测试（本 PR） |
|---|---|---|---|
| D-01 | 上传病毒扫描首期不做（§2.4 恶意代码防范部分符合；整改窗口 W44 按 tech-plan §10 补齐） | **整改**：按 tech-plan §10 定稿方案落地——自建 ClamAV（clamd INSTREAM）**同步扫描**：上传链路在真实类型校验后、写任何对象前扫描（`FileUploadService`），检出 40001 + `FILE_VIRUS_REJECTED` 审计（等保证据），扫描不可用 **50300 不放行**（fail closed，「稍后重试」）；禁用态（本机 / CI / 未部署 clamd）不扫描、台账 UNSCANNED。**存量补扫**：`sys_file` 加扫描列（followup V139），`VirusScanRetroService`（每日 03:20，按租户扇出 job_tenant_run）按已记录 SHA-256 读回核对完整性后补扫——干净 CLEAN、检出 INFECTED + `FILE_VIRUS_FOUND` 审计（**不自动删除**，处置见表后）、对象与台账哈希不一致 HASH_MISMATCH + `FILE_VIRUS_HASH_MISMATCH` 审计（完整性事件）；单趟单租户上限 `retro-batch-limit`（默认 200）逐晚推进。**不用云沙箱**（文件不出平台，tech-plan §10 方案对比）；HSS 病毒查杀为主机层补充（云上由运维配置，§5） | `ClamdVirusScannerTest`（4：INSTREAM 分块编码与多块重组 / 干净 / 检出名称解析 / 连接拒绝与 ERROR 应答 50300 fail closed）、`FileUploadServiceTest`（+4：检出 40001 + 审计 + 零对象写入 / 干净 CLEAN·禁用 UNSCANNED / 不可用 50300 fail closed / 扫描顺序在类型后配额前）、`VirusScanRetroServiceTest`（7：禁用 no-op / CLEAN / INFECTED 不删对象 / HASH_MISMATCH / 不可用中止批次幂等 / 对象缺失与前缀不符跳过 / 批量上限）、`SysFileVirusScanSqlTest`（3：只新增 / 存量默认 UNSCANNED / 状态机登记）、`ProdProfileHardeningTest`（+1：prod 字面量 `${CRDP_VIRUS_SCAN_MODE:off}` 等三键锁定） |
| D-02 | 跨区域备份异步、区域级 RPO 不保证（已确认接受，不购 DRS） | **接受风险**：区域内 RPO ≤ 5 分钟达标；如测评要求区域级保障，DRS 方案已备（tech-plan §14），届时另行立项（云上资源，运维） | 决策记录（self-assessment §4）；条件触发不预算实现 |
| D-03 | 审计归档无异地 WORM 副本（已确认接受） | **接受风险**：在线 1 年审计随 RDS 跨区域备份；如需整改：归档作业双写备用区域 WORM 桶 + 历史补传（tech-plan §14，届时另行立项） | 同上 |
| D-04 | 归档桶未开桶级默认加密（OQ-123 ③） | **条件触发**：测评或监管要求则加桶级 SSE-KMS（IaC 一处 + 基线一字段，运维执行） | `iac/obs-archive/worm-baseline.json` 结构已留扩展位；触发后在 OQ-123 登记 |
| D-05 | 报告单水印仅页面层（OQ-127 定稿口径） | **条件触发**：如测评要求水印随文件走，预览 / 下载代理烧录水印（另立任务，OQ-127 暂行③） | 防线在位：预览短链 5 分钟 + 签发即审计 + 下载单独权限（self-assessment §4） |
| D-06 | 图形验证码为自绘 PNG（OQ-39 暂行） | **条件触发**：测评有要求再调整样式（OQ-39） | OQ-39 |
| D-07 | 反代 / WAF 覆盖 `X-Forwarded-For` 上线检查（OQ-42 定论） | **由人**：上线检查单执行留痕（M-16 云上核验一并留证，运维） | `docs/ops/cce-prod.md` §6；`crdp.web.trust-xff` 默认 false（只信 RemoteAddr）已落地 |
| D-08 | 测评窗口 6 周压缩为 5 周（OQ-30） | **已确认**：M-03 合同确认；C 上线排期已考虑 | OQ-30 |

**检出存量感染文件的处置口径（补扫 INFECTED 行）**：不自动删除（临床附件处置须评估对患者随访的影响）——
台账 `virus_scan_status=INFECTED` 即待处置清单 + `FILE_VIRUS_FOUND` 审计告警；由安全会诊（开发 + 产品 +
医院信息科）决定删除或隔离（对象移入隔离前缀），处置结论回写本表与对应审计记录。该口径与「测评新发现
INFECTED」一致（§2 流程第 2 步走产品确认）。

## 5. 待人工 / 外部事项清单（启用与核验，运维 / 产品）

- [ ] **clamd 副本部署（启用扫描的前置）**：CCE 上独立部署 ClamAV（tech-plan §10 资源口径：约 1 vCPU、
      3–4 GiB 内存〔签名加载约 1.2 GiB，`ConcurrentDatabaseReload no` 避免重载期翻倍〕，freshclam
      签名每日更新需出网或自建签名镜像）；建议 K8s Deployment + Service（集群内 Service 名填
      `CRDP_CLAMD_HOST`），不与业务容器混部。由持账号运维执行（AGENTS §9：云上资源不落仓库，部署清单
      存运维 wiki，随 M-16 留痕）；
- [ ] **启用时点**：clamd 部署并核验（上传一个 EICAR 测试文件被 40001 拒绝、正常文件通过）后，UAT 与
      生产置 `CRDP_VIRUS_SCAN_MODE=clamd`（经 `crdp-server-env` Secret 注入，同 PT-SELF-02 口径）；
      启用后存量由补扫作业逐晚自动补扫（无需人工触发）；
- [ ] **HSS 病毒查杀任务（主机层补充）**：HSS 企业版建周期查杀任务扫描节点容器目录（云上由运维配置，
      tech-plan §10；不作为上传前置——文件要先落盘再异步扫，且不扫 OBS 对象）；
- [ ] **签名更新监控**：freshclam 更新失败告警接入 LTS / CES（运维，E22-T03 通道）；签名过期 > 48 小时
      视为扫描降级，人工核验；
- [ ] 测评窗口内 backend 类真实问题（`MLPS-W{39~43}-{序}`）按 §2 处置留痕回填 §8。

## 6. 复测与闭环判据

- 「整改项全部关闭」以缺陷库 Issue 状态为证（onsite-assessment.md §8）：label `mlps` 且正文标注 E22-T15
  的 Issue 全部 closed（高风险复测通过；中风险处置结论落地；低风险排期跟踪不计入关闭面）；
- 本手册 §4 先行整改不进缺陷库（仓库内留痕即处置证据，同 E22-T10 §6 / E23-T09 §6 口径）；若测评复现
  同类问题，按其 `MLPS-ID` 走 Issue 流程，本表作交叉引用（修复不重复做，复测以 Issue 为准）；
- 整改与复测结论回写 `self-assessment.md` §4（偏差登记更新），归档为测评周期证据链，随 E22-T18 备案
  材料提交。

## 7. 与其他任务的衔接

| 任务 | 关系 |
|---|---|
| E22-T13 / E22-T14 | 上游：自查记录（偏差登记 D-01~D-08）与现场测评问题清单（MLPS-ID 入库）即本手册输入 |
| E22-T16 | 并行：frontend 类测评问题整改（同窗口、同验收句式） |
| E22-T17 | 下游：整改复测（W45）；复测期新发现不混入首轮清单（OQ-137③） |
| E22-T18 | 下游：测评报告与备案——本手册 §4 处置表 + Issue 闭环记录随备案材料提交 |
| E22-T10 / E22-T11 | 同构先例：渗透修复手册与流程（PT-SELF-*）——本手册沿用其处置口径 |
| E23-T26 | 下游：C 上线切换（W46）在测评通过（E22-T17）后 |
| E10-T01 / E16-T10 | 扫描接入的上传链路：电脑端上传与 H5 手机上传共用 `FileUploadService`（一处接入两端生效） |

## 8. 验证留痕

| 项 | 结果 | 日期 / 依据 |
|---|---|---|
| 先行整改范围 | §4 表 D-01~D-08 后端面 8 条，整改 1 / 接受 2 / 条件触发 3 / 由人 1 / 已确认 1 | 本 PR（分支 feat/e22-t15-mlps-remediation-backend） |
| D-01 修复测试 | `ClamdVirusScannerTest` 4/4、`FileUploadServiceTest` 19/19（含病毒 4 例）、`VirusScanRetroServiceTest` 7/7、`SysFileVirusScanSqlTest` 3/3、`ProdProfileHardeningTest` 6/6（含病毒扫描接线 1 例） | 本 PR CI |
| 本机 MySQL 集成测试（门控 CRDP_IT_MYSQL_PORT=13306） | `FileServiceAcceptanceIT` 5/5、`MobileUploadIT` 4/4、`M1FoundationRegressionIT` + `TenantIsolationAcceptanceIT` 6/6——应用全上下文启动（禁用态扫描器装配）+ followup V139 在真库执行 | 2026-11-14 本机（colima MySQL） |
| 防漂移校验 | `scripts/check_mlps_remediation.py`（接入 ci.yml 恒跑） | 本 PR CI |
| 窗口内 MLPS 问题整改 | 待窗口（W44）回填（Issue 区间 + 复测结论） | — |
