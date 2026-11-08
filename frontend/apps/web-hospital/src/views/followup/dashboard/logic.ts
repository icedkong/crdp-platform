import type { DashboardRoleCard } from '@crdp/api';

/**
 * E19-T06 / A-DASH-013 首页卡片配置页的纯逻辑（/system/dashboard/layout.vue）。
 * 勾选与换位的纯函数在 @crdp/api（toggleRoleCard / moveRoleCard / roleLayoutSaveOf），
 * 与 packages/api/dashboard.spec.ts 共用一套单测；这里只放页面装配。
 */

/** 已选（显示）卡片：目录顺序中 visible 的项（配置页右侧列表）。 */
export function visibleRoleCards(
  cards: DashboardRoleCard[],
): DashboardRoleCard[] {
  return cards.filter((card) => card.visible);
}
