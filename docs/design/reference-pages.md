# 样板页（医院端）

样板页是给后续编码代理抄的前端模板，只在 `frontend/apps/web-hospital`。数据全部虚构（测试患者01、`138****0001`、`DEMO-A-0001`）。不要换成真实患者，不要把明文写进 localStorage、URL 或日志。

视觉数值以 `tokens.ts` 为准，经 `@crdp/tokens` 引用。外壳是官方 Vben Admin 5（Ant Design Vue），见 ADR 0002。侧栏、顶栏、标签栏不要改成另一套样式。

## 文件

| 用途 | 路径 |
|---|---|
| 患者列表 | `apps/web-hospital/src/views/followup/patient/index.vue` |
| 患者详情（基线 / 随访 / 其他页签） | `apps/web-hospital/src/views/followup/patient/detail.vue`（E13-T05 起概览 / 时间轴 / 联系方式与变更记录已接真实接口；基线分节、报告单、沟通记录页签仍随 E13-T02 / E16 / E15 开放） |
| 虚构数据 | `apps/web-hospital/src/reference/fixtures.ts` |
| 附件分组 | `apps/web-hospital/src/reference/group.ts` |
| 断点 | `apps/web-hospital/src/reference/use-breakpoint.ts` |
| 权限 | `apps/web-hospital/src/reference/use-permission.ts` |
| 明文 60 秒 | `apps/web-hospital/src/reference/plaintext.ts` |
| 业务组件 | `apps/web-hospital/src/components/followup/` |

组件文件：`patient-header.vue`、`attachment-nav.vue`、`attachment-nav-list.vue`、`category-bar.vue`、`inline-attachment-strip.vue`、`location-picker.vue`、`followup-timeline.vue`、`status-tag.vue`、`masked-field.vue`、`watermark-preview.vue`、`upload-report-dialog.vue`、`readonly-categories.vue`。

`StatusTag`、`MaskedField` 目前只被医院端样板使用。平台端要复用时，再抽到独立 package，不要放回已删除的 `@crdp/admin-shell`。

## 怎么抄

1. 新页面放在 `src/views/` 下，路径与后端菜单的 `component` 一致（例如 `followup/patient/index` → `src/views/followup/patient/index.vue`）。隐藏路由如果种子菜单还没有，先在 `src/api/core/menu.ts` 里追加一条 `hideInMenu`，并记一笔，等 Flyway 补菜单后删掉前端硬编码。
2. 布局用现成组件组合，不要在页面里重写附件树或脱敏计时。
3. 医院 A / C 的差别只来自患者（或以后的 `me.features`）上的功能开关，例如 `features.implant`。不要写 `if (tenant === 'A')`。
4. 列表不提供逐行明文。明文只在 `PatientHeader` 的 `MaskedField`，确认文案照抄 `components.md`，60 秒后清除。
5. 上传弹窗用 `LocationPicker`，至少选 1 个位置。样板的「确定」不调用上传接口。
6. 按钮显隐走 `usePermission().hasPermission(code)`。附件导航的上传入口由样板页传 `can-upload`（演示账号种子没有 `followup:scan:upload`）。`MaskedField` 不提供任何绕过权限的演示通道（M3 审查 A1 已删除 `referenceMode`）：明文按钮一律按 `followup:patient:plain` 显示，没有权限就不渲染——演示账号想看交互就给种子角色补权限码（走迁移），不要在组件里开后门。
7. 断点只用 `useBreakpoint()`：`≥1280` 左栏附件导航，`768–1279` 为「附件（n）」打开左侧抽屉且分类条横向滚动，`<768` 为底部抽屉且分类条改为下拉。

## 和规格的已知缺口

- `FormRenderer` 编辑引擎已随 E11-T07 落地（`packages/form-engine`：分类布局、多选互斥、带入上次值、条件显示与校验显示）；派生重算（E11-T05）、已填 / 应填与分类锚点（E11-T11：`progress` 事件 + `anchors()` 暴露）已落地；对比标红（E17）还没做。样板页暂未接入，仍用 `readonly-categories.vue` 按虚构分类连续只读展示，锚点 id 为 `crdp-anchor-<key>`。
- 高级检索弹窗只展示规格里的逻辑表达式预览，没有条件树提交。
- 报告单、参数趋势页签是占位说明。水印预览会画水印并监听节点被删，但没有 pdf.js。
- 功能开关接口见 OQ-03，样板先用 fixture 上的 `features`。
