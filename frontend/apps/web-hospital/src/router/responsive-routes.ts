/**
 * E24 / NFR-RESP-002：已做手机（<768）响应式的路由（按路由名登记）。
 * 名单外的路由在手机上由响应式守卫（E24-T01）整页替换为 MobileBlocker
 * 「请在电脑或平板上操作」。名单即 design-system §12「只有 5 个页面做响应式」
 * 的落点，锁定用例见 views/followup/device-matrix.spec.ts（E24-T08 真机矩阵）。
 */
export const responsiveRouteNames: ReadonlySet<string> = new Set([
  // E24-T09 走查补登记：基线分节编辑（plt_menu 227）pages.md §0.1 标响应式
  // 「是」（布局同随访整页表单 §5），页面自身已具备手机形态（page-mobile、
  // CategoryBar select、手机底部栏）；此前漏登记，手机被守卫拦截无法编辑基线
  'FollowupBaselineEdit',
  // E24-T03：患者列表（plt_menu 21，手机卡片 + 筛选 / 高级检索底部抽屉、
  // 平板筛选右侧抽屉）；E24-T04：患者详情（plt_menu 215，折叠头部 / 操作
  // 底部抽屉 / 页签横向滚动 / 沟通记录与报告单表格转卡片）
  'FollowupPatient',
  'FollowupPatientDetail',
  // E24-T02：随访录入整页表单（plt_menu 223，平板 / 手机单列 + 底部固定操作栏）
  'FollowupRecordForm',
  // E24-T08：全院报告单检索（pages.md §6.4 属「报告单上传与查看」响应式范围，
  // E16-T05 已实现手机卡片 + 平板精简列；此前漏登记，手机被守卫拦截进不了本页）
  'FollowupReport',
  // E24-T05：我的任务（plt_menu 22，手机任务卡片 + 状态分段 + 筛选底部抽屉、
  // 平板主要列 + 点行进表单；手机从任务进表单连续录入）
  'FollowupTask',
]);
