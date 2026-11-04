/**
 * 临床科研数据一体化平台 · 设计令牌（唯一来源）
 *
 * 运行时由 frontend/packages/tokens 重新导出（`@crdp/tokens`）。
 * 外壳是官方 Vben Admin 5（Ant Design Vue），见 ADR 0002、OQ-01。
 * 用 vbenPreferencesOverrides 调 defineOverridesPreferences()，并把 antdTheme 交给 ConfigProvider。
 *
 * 规则：页面与组件中不得写死颜色 / 字号 / 间距，一律引用这里的值或 AntD token（useToken）。
 * 依据：docs/design/design-system.md §1（2026-10-31 已定）。
 * 本文件不依赖 ant-design-vue，避免文档目录参与包解析。
 */

/** 颜色（已定：主色 #3A75FF；内容区底色保持外壳默认浅灰，不改） */
export const colors = {
  primary: '#3A75FF',
  primaryBg: '#E6F0FF', // 主色浅底：info 条、选中行、“报告单带入”底色、位置跳转高亮
  container: '#FFFFFF',
  tableHeader: '#FAFAFA',
  borderSecondary: '#E9E9E9', // 分隔线
  border: '#D9D9D9', // 输入框边框
  text: '#262626',
  textSecondary: '#8C8C8C',
  textPlaceholder: '#BFBFBF',
  success: '#1DC29E',
  warning: '#FA8C16',
  error: '#FF4D4F',
} as const;

/** 布局断点（已定，NFR-RESP-001）：<768 手机，768–1279 平板，≥1280 桌面 */
export const breakpoints = {
  tablet: 768,
  desktop: 1280,
} as const;

export type DeviceKind = 'mobile' | 'tablet' | 'desktop';

export function deviceOf(width: number): DeviceKind {
  if (width < breakpoints.tablet) return 'mobile';
  if (width < breakpoints.desktop) return 'tablet';
  return 'desktop';
}

/** 尺寸常量（design-system.md §6、§7、§12；业务组件直接引用） */
export const sizes = {
  pagePadding: 16,
  gutter: 16,
  cardRadius: 8,
  cardTitleBar: 3, // 卡片标题前主色竖条宽度
  /** 整页录入表单 */
  formLabelWidth: { desktop: 140, tablet: 120 }, // 手机标签在上
  detailLabelWidth: 120, // 只读详情
  fieldWidth: {
    number: 96, // 单位放框外
    date: 160,
    selectMin: 120,
    selectMax: 180,
    shortText: 320,
    // 长文本占满一行
  },
  filterInputWidth: { min: 180, max: 220 },
  formAnchorWidth: 180, // 随访表单左侧分组锚点
  attachmentNavWidth: 240, // 详情页左栏附件导航
  reportPreviewWidth: 340, // 报告单页签右侧预览
  modal: { simple: 520, multiColMin: 800, multiColMax: 960, advancedSearch: 900 },
  drawer: { min: 480, max: 720 },
  mobileInputHeight: 36,
  mobileInputFontSize: 16,
  touchTarget: 40,
  tablePageSize: 20,
} as const;

/** 时间常量（已定） */
export const timings = {
  plaintextRevealMs: 60_000, // 明文 60 秒后自动恢复脱敏
  localDraftIntervalMs: 30_000, // 本地草稿每 30 秒（另有 blur 时保存）
  serverDraftIntervalMs: 120_000, // 服务器草稿每 2 分钟，仅有改动时
  anchorHighlightMs: 2_000, // 位置跳转高亮 2 秒
  notifyPollMs: 60_000, // 站内信铃铛未读数轮询 60 秒（docs/api/notify.md §1）
  mobileUploadQrMinutes: 15, // A-ATT-015 一次性二维码
  downloadLinkMinutes: 5, // 下载签名链接有效期
} as const;

/** 图片上传前压缩（NFR-RESP-003） */
export const imageUpload = {
  maxLongEdge: 2560,
  mimeType: 'image/jpeg',
  quality: 0.85, // 推断，待确认（规格只要求转 JPEG，未规定质量）
  stripExif: true,
  convertHeic: true,
} as const;

/** Ant Design Vue 4 主题（a-config-provider :theme）。结构对齐 ThemeConfig。 */
export const antdTheme = {
  token: {
    colorPrimary: colors.primary,
    colorPrimaryBg: colors.primaryBg,
    colorInfo: colors.primary,
    colorSuccess: colors.success,
    colorWarning: colors.warning,
    colorError: colors.error,
    colorText: colors.text,
    colorTextSecondary: colors.textSecondary,
    colorTextPlaceholder: colors.textPlaceholder,
    colorBorder: colors.border,
    colorBorderSecondary: colors.borderSecondary,
    colorBgContainer: colors.container,
    // colorBgLayout 不覆盖：沿用外壳默认浅灰（已定，不用竞品偏蓝底色）
    fontSize: 14,
    fontSizeSM: 12,
    fontSizeLG: 16,
    fontSizeHeading4: 18, // 页面标题 18/600
    fontSizeHeading5: 16,
    lineHeight: 1.5715,
    borderRadius: 6,
    borderRadiusSM: 4,
    borderRadiusLG: 8,
    controlHeight: 32,
    wireframe: false,
  },
  // 注意：ant-design-vue 4.2 的组件级 token 很少（多数组件只接受 AliasToken 覆盖），
  // 下面只用 4.2 确实支持的键（已用 tsc 校验）；其余样式（表头无竖线、卡片标题竖条等）用全局 CSS，
  // 见文件末尾 globalCss。
  components: {
    Card: {
      borderRadiusLG: 8,
      fontSizeLG: 16, // 卡片标题 16/600
      paddingLG: 16,
    },
    Table: {
      // 表头底色取 colorFillAlter；选中行取 controlItemBgActive
      colorFillAlter: colors.tableHeader,
      controlItemBgActive: colors.primaryBg,
      controlItemBgActiveHover: colors.primaryBg,
      colorBorderSecondary: colors.borderSecondary,
    },
    Button: {
      borderRadius: 6,
      controlHeight: 32,
    },
    Modal: {
      fontSizeLG: 16, // 弹窗标题 16/600
      borderRadiusLG: 8,
    },
    Tabs: {
      colorPrimary: colors.primary, // line 样式选中下划线
    },
    Tag: {
      borderRadiusSM: 4,
    },
    Menu: {
      // 菜单保持外壳默认，只让选中色随主色（已确认接受）
      colorItemTextSelected: colors.primary,
    },
    Alert: {
      colorInfoBg: colors.primaryBg,
    },
  },
};

/**
 * Vben Admin 5 preferences 覆盖（仅在切换到上游 Vben 外壳后使用）。
 * 对应 apps/<app>/src/preferences.ts:
 *   export const overridesPreferences = defineOverridesPreferences(vbenPreferencesOverrides);
 */
export const vbenPreferencesOverrides = {
  app: {
    name: '临床科研数据一体化平台',
    locale: 'zh-CN',
    accessMode: 'backend', // 菜单与权限码由后端下发（PLT-PERM-005/006）
    enableRefreshToken: false, // 推断，待确认
    defaultHomePath: '/dashboard',
  },
  theme: {
    colorPrimary: colors.primary,
    colorSuccess: colors.success,
    colorWarning: colors.warning,
    colorDestructive: colors.error,
    mode: 'light',
    radius: '0.5', // 约 8px 卡片圆角
    builtinType: 'custom',
  },
  tabbar: {
    enable: true, // 保留多标签页；手机（isMobile）时不显示
    persist: true,
    showIcon: true,
  },
  sidebar: {
    collapsed: false, // 平板宽度下自动收成图标栏
  },
  widget: {
    themeToggle: false, // 首期只做亮色
    languageToggle: false,
  },
  copyright: { enable: false },
} as const;

/** 状态 → 颜色（StatusTag / a-badge 共用；完整枚举见 components.md） */
export type StatusTone = 'processing' | 'success' | 'pending' | 'warning' | 'error' | 'default';

export const statusTone: Record<string, StatusTone> = {
  随访中: 'processing',
  进行中: 'processing',
  已完成: 'success',
  已发布: 'success',
  已联系: 'success',
  已电子化: 'success',
  正常: 'success',
  已启动: 'success', // 计划患者列表（A-FU-022）：已启动 / 已暂停 / 已移除
  待随访: 'pending', // 白底主色描边
  窗口外: 'warning',
  失访: 'warning',
  失访候选: 'warning',
  未接通: 'warning',
  未联系到: 'warning',
  未发布: 'warning',
  待录入: 'warning',
  未指定位置: 'warning',
  逾期: 'error',
  已暂停: 'error',
  暂停: 'error',
  标红: 'error',
  补录: 'default',
  计划中: 'default',
  停止: 'default',
  已停止: 'default',
  死亡: 'default',
  已禁用: 'default',
  已停用: 'default',
  已移除: 'default', // 计划患者列表（A-FU-022）
  无需录入: 'default',
  草稿: 'default',
  // 租户（PLT-TEN-010 七态；开通中是向导阶段的初始态，components.md tenant 行的补充）
  试用: 'processing',
  启用: 'success',
  离职: 'default', // 用户管理（PLT-ORG-006 终态）
  宽限期: 'warning',
  停用: 'error',
  注销中: 'warning',
  已注销: 'default',
  开通中: 'processing',
  // 模块目录（PLT-MOD-001 上架状态：上架 / 下架，下架后不能再被新开通）
  上架: 'success',
  下架: 'error',
  // 租户模块开通状态（PLT-MOD-004、005；未开通用中性 default）
  已开通: 'success',
  开通中: 'processing',
  开通失败: 'error',
  维护中: 'warning',
  已关闭: 'default',
  未开通: 'default',
};

/**
 * 无法用 token 表达的全局样式。应用启动时注入（见 bootstrap）。
 */
export const globalCss = `
/* 表头无竖线 */
.ant-table-thead > tr > th::before { display: none !important; }
/* 卡片标题前 3px 主色竖条 */
.crdp-card .ant-card-head-title { font-weight: 600; }
.crdp-card .ant-card-head-title::before {
  content: ''; display: inline-block; width: ${sizes.cardTitleBar}px; height: 14px;
  margin-right: 8px; vertical-align: -2px; border-radius: 2px; background: ${colors.primary};
}
/* 页面标题 18/600 */
.crdp-page-title { font-size: 18px; font-weight: 600; line-height: 1.5715; color: ${colors.text}; }
/* 位置跳转高亮 2 秒：主色浅底 + 左侧竖条 */
.crdp-anchor-flash { background: ${colors.primaryBg}; box-shadow: inset 3px 0 0 ${colors.primary}; transition: background .3s; }
/* 标红字段：红底 + ▲（与必填错误的红边框区分） */
.crdp-field-flag .ant-input-number, .crdp-field-flag .ant-input, .crdp-field-flag .ant-select-selector { background: #FFF1F0 !important; }
/* 报告单带入字段 */
.crdp-field-from-report .ant-input-number, .crdp-field-from-report .ant-input, .crdp-field-from-report .ant-select-selector { background: ${colors.primaryBg} !important; }
/* 手机输入框 36px / 16px，避免 iOS 聚焦放大 */
@media (max-width: ${breakpoints.tablet - 1}px) {
  .crdp-form .ant-input, .crdp-form .ant-input-number-input, .crdp-form .ant-select-selector, .crdp-form .ant-picker-input > input { font-size: ${sizes.mobileInputFontSize}px !important; }
  .crdp-form .ant-input, .crdp-form .ant-input-number, .crdp-form .ant-select-selector, .crdp-form .ant-picker { min-height: ${sizes.mobileInputHeight}px; width: 100% !important; }
  .crdp-touch { min-width: ${sizes.touchTarget}px; min-height: ${sizes.touchTarget}px; }
}
`;
