# ADR 0002：医院端和平台端改用官方 Vben Admin 5（仅 Ant Design Vue）

日期：2026-10-31

状态：已接受

取代 ADR 0001 里「前端为什么没有整仓复制 Vben」这一节。ADR 0001 的后端、多租户和安全决定仍然有效。

## 背景

M0 用自研 `@crdp/admin-shell` 模仿 Vben 5 的布局和后端权限模式。技术方案写的是 Vben Admin 5，Ant Design Vue 版。OQ-01 当时的暂行做法是继续用自研壳。

产品负责人已决定：前端必须使用官方 Vben Admin 5（`vbenjs/vue-vben-admin`，Ant Design Vue，编写时的最新稳定标签 **v5.7.0**）。

## 决定

1. `frontend/` 放入 Vben 5.7.0 的 monorepo 结构：`packages/@core`、`packages/effects`、`internal`、`scripts`，以及基于 `apps/web-antd` 的两个应用 `apps/web-hospital`、`apps/web-platform`。手机上传页 `apps/mobile-upload` 仍是占位。
2. 只保留 Ant Design Vue 这一条 UI 路径。不引入、不保留 Element Plus（`web-ele`）、Naive UI（`web-naive`）、TDesign（`web-tdesign`）、antdv-next（`web-antdv-next`），以及它们的样式入口和 catalog 依赖。上游的 playground、文档站、backend-mock 也不放入本仓库。`@vben/vite-config` 里的 Nitro mock 插件仍在，但两个应用把 `VITE_NITRO_MOCK` 设为 false，开发时代理到本仓库后端 `127.0.0.1:18080`。
3. 删除 `@crdp/admin-shell`。登录、动态菜单、令牌和 `{code, message, data, traceId}` 改接到 Vben 的请求客户端和 `accessMode: 'backend'`。
4. 侧边栏、顶栏、多标签页保持 Vben 默认样式。主色 `#3A75FF` 以及成功、警告、错误色通过 `defineOverridesPreferences`（`docs/design/tokens.ts` 的 `vbenPreferencesOverrides`）和 Ant Design Vue `theme`（`antdTheme`）落地。内容区底色不改成竞品蓝。
5. 样板页在 `apps/web-hospital`，说明见 `docs/design/reference-pages.md`。数据全部虚构。

## 和文档旧写法的差异

- 登录路由用 `/login`（`pages.md`），不是上游默认的 `/auth/login`。`LOGIN_PATH` 已改。
- 登录后首页是 `/dashboard`，与现有菜单和 `pages.md` 一致。`tokens.ts` 里曾经写的 `/workbench` 已改为 `/dashboard`。
- 平板宽度下侧栏是否收成图标，跟随 Vben 自己的断点（小于 768 进抽屉）。页面内容（附件导航、分类条）仍按 768 / 1280 适配。
- 功能开关还没有从 `me` 下发（OQ-03）。样板页用虚构患者上的 feature 字段驱动医院 A / C 差异，不写 `if (tenant === 'A')`。
- 演示种子没有把 `followup:patient:plain`、`followup:scan:upload` 授给 `nurse.demo`。样板页曾用 `referenceMode` 把这些控件展示出来；**2026-11-10 更新（M3 审查 A1）**：该后门已删除，`MaskedField` 一律按 `hasPermission` 显隐，想演示明文交互就给种子角色补权限码（走迁移）。

## 后果

- 前端安装、格式化和静态检查改用 Vben 的工具链：pnpm catalog、oxfmt、oxlint、ESLint、Stylelint。CI 仍在 `frontend/` 执行 `pnpm install --frozen-lockfile`、`format:check`、`lint`、`test`、`build`。
- 以后升级 Vben，只同步 Ant Design Vue 应用及其依赖的 workspace 包，不要把其他 UI 库应用加回来。
