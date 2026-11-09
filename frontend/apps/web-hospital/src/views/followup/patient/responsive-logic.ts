/**
 * E24-T03 / NFR-RESP-002 · 患者列表响应式纯逻辑（pages.md §3）：
 * 平板筛选收进右侧抽屉、手机筛选进底部抽屉；检索词输入框常驻顶栏。
 */
import type { PatientFilterState } from '@crdp/api';

/**
 * 筛选抽屉触发按钮上的生效条件数（病区 / 随访状态 / 两个日期区间）。
 * 检索词不计——顶栏常驻输入框自身可见；口径与 buildPatientSearchPayload
 * 「空筛选不进 filters、日期未选全不发送」一致，按已应用（appliedFilters）
 * 计数，不跟抽屉草稿实时联动。
 */
export function drawerFilterCount(state: PatientFilterState): number {
  let count = 0;
  if (state.wardId !== null && state.wardId !== undefined) count += 1;
  if (state.status) count += 1;
  if (state.dischargeRange?.[0] && state.dischargeRange?.[1]) count += 1;
  if (state.nextVisitRange?.[0] && state.nextVisitRange?.[1]) count += 1;
  return count;
}
