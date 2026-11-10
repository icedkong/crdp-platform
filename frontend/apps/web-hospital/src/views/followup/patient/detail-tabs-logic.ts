/**
 * G3（M3 审查）/ pages.md §4.1 患者详情页签目录纯逻辑：
 * 基线资料 / 随访记录 / 报告单 / 沟通记录 / 操作日志（顺序即页签顺序）。
 * 报告单页签随租户功能开关 `report` 显隐（「功能开关关闭的页签直接隐藏」），
 * 操作日志页签总是显示（只读角色也可看）；基线 / 随访 / 沟通总是显示。
 * 测试见 detail-tabs-logic.spec.ts。
 */
export type PatientDetailTabKey =
  | 'baseline'
  | 'contacts'
  | 'followup'
  | 'logs'
  | 'reports';

export interface PatientDetailTabDef {
  key: PatientDetailTabKey;
  label: string;
}

/** 页签定义（pages.md §4.1；报告单 label 与既有线框一致）。 */
export const PATIENT_DETAIL_TABS: PatientDetailTabDef[] = [
  { key: 'baseline', label: '基线资料' },
  { key: 'followup', label: '随访记录' },
  { key: 'reports', label: '报告单' },
  { key: 'contacts', label: '沟通记录' },
  { key: 'logs', label: '操作日志' },
];

/** 可见页签清单：report 开关关闭时报告单页签整页隐藏（不置灰，AGENTS §5.7）。 */
export function visiblePatientDetailTabs(
  reportEnabled: boolean,
): PatientDetailTabDef[] {
  return PATIENT_DETAIL_TABS.filter(
    (tab) => tab.key !== 'reports' || reportEnabled,
  );
}

/**
 * ?tab= 直链初始页签（E16-T05 全院报告单列表跳转）：未知值回落基线资料；
 * 开关关闭时的 reports 也回落基线资料（页签不可见）。
 */
export function initialPatientDetailTab(
  queryTab: null | string | undefined,
  reportEnabled: boolean,
): PatientDetailTabKey {
  const known = PATIENT_DETAIL_TABS.some((tab) => tab.key === queryTab);
  if (!known) return 'baseline';
  if (queryTab === 'reports' && !reportEnabled) return 'baseline';
  return queryTab as PatientDetailTabKey;
}
