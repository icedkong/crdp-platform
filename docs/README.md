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
| `ops/rds-prod.md` | RDS 8.0 生产实例运维手册：参数基线、主备切换验证、跨区域备份开启（IaC 在 `iac/rds-prod/`；真实云操作由运维执行） | 数据库运维、备份恢复演练（E22） |
| `ops/cce-prod.md` | CCE 生产环境三级基线运维手册：集群 / ELB / WAF / 通配符证书 / HSS 对照清单与验证（IaC 在 `iac/cce-prod/`；真实云操作由运维执行） | 生产入口、主机防护、渗透测试对接（E22） |
| `ops/tenant-restore.md` | 单院恢复 runbook：单租户库恢复到指定时间点（脚本 `scripts/restore_tenant.sh`；PITR 临时实例等真实云操作由运维执行，CI 含本地演练） | 误操作 / 逻辑损坏的单院回滚（E22-T06） |
| `open-questions.md` | 待确认问题 OQ-01…OQ-33 与暂行做法 | 遇到不确定时先查 |
| `adr/` | 仓库已有架构决定（如 M0 骨架） | 改架构前 |

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
