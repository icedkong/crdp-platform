# 待确认问题（open-questions）

> 规则：本文件列出交接时**没有定论**的事项。编码代理遇到这里的问题时，**按“暂行做法”实现并在代码 / PR 中注明 OQ 编号**；暂行做法也没有的，停下来问人，不要自己发明需求。新发现的问题追加到末尾（编号顺延），写明来源与影响。
> 优先级：🔴 阻塞（不确认就可能返工）、🟡 重要、⚪ 一般。“推断，待确认”字样在 `docs/api/`、`docs/design/` 中随处出现，凡未单列的，均按该文档的建议实现。

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
| OQ-09 | 🟡 | 平台账号数据范围交集 | 仓库 DataScopeGuard 是 M0 占位 | 按 PLT-SCOPE 实现；平台租户视角会话类型（`TENANT_VIEW`）为推断 |
| OQ-10 | ⚪ | 组织架构事件（病区停用、人员调离）是否自动改派 | 规格未写 | 不自动改派，只提示管理员（推断） |

## 二、权限码

| 编号 | 级别 | 问题 | 暂行做法 |
|---|---|---|---|
| OQ-11 | 🔴 | 种子 `tenant:dept:view` vs 规格 `tenant:org:view` | 保留种子码（权限码创建后不改，见仓库 README），文档里两者视为同一个；如要改，新增码 + 迁移角色授权 |
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

## 四、排期（由人决定，代理不要改排期）

| 编号 | 级别 | 问题 |
|---|---|---|
| OQ-29 | 🔴 | M5 负载 104%；A 的 UAT 修复落在 W36，余量约 8.5 人天 |
| OQ-30 | 🟡 | 等保测评窗口 5 周（W39–W43）；C 上线 W46 与节假日重叠，春节未扣除 |
| OQ-31 | 🟡 | E05 平台 P1 / P2 项（PLT-TEN-014、018–021，PLT-ORG-010，PLT-PERM-012，PLT-CFG-009）排在 M4 / M5，A 上线时不提供（`tasks.md` 待确认第 7 条） |
| OQ-32 | 🟡 | 开发合计人天超技术方案 37 人天（`tasks.md` 待确认第 10 条） |
| OQ-33 | ⚪ | C-SCAN-019 图片增强（P2）不纳入首期，是唯一没有任务覆盖的需求编号（已确认，列此备查） |
