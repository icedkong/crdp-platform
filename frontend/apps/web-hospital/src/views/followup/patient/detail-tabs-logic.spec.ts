import { describe, expect, it } from 'vitest';

import {
  initialPatientDetailTab,
  PATIENT_DETAIL_TABS,
  visiblePatientDetailTabs,
} from './detail-tabs-logic';

/**
 * G3（M3 审查）/ pages.md §4.1：操作日志页签总是显示；报告单页签随功能开关
 * report 显隐、参数趋势页签随 param_trend 显隐（E21-T13 / C-FU-012，关闭直接
 * 隐藏）；?tab= 直链未知值 / 关闭中的页签回落基线资料。
 */
describe('patient detail tabs (G3)', () => {
  it('总是显示基线 / 随访 / 沟通 / 操作日志，报告单与参数趋势跟随开关', () => {
    expect(visiblePatientDetailTabs(true, true).map((tab) => tab.key)).toEqual([
      'baseline',
      'followup',
      'reports',
      'trend',
      'contacts',
      'logs',
    ]);
    expect(
      visiblePatientDetailTabs(false, false).map((tab) => tab.key),
    ).toEqual(['baseline', 'followup', 'contacts', 'logs']);
    // 报告单开、趋势关（医院 A / B 的形态）
    expect(visiblePatientDetailTabs(true, false).map((tab) => tab.key)).toEqual(
      ['baseline', 'followup', 'reports', 'contacts', 'logs'],
    );
  });

  it('页签都带中文 label（pages.md §4.1 页签名）', () => {
    const labels = Object.fromEntries(
      PATIENT_DETAIL_TABS.map((tab) => [tab.key, tab.label]),
    );
    expect(labels).toEqual({
      baseline: '基线资料',
      contacts: '沟通记录',
      followup: '随访记录',
      logs: '操作日志',
      reports: '报告单',
      trend: '参数趋势',
    });
  });

  it('?tab= 直链：合法值原样生效，未知值回落基线资料', () => {
    expect(initialPatientDetailTab('reports', true, true)).toBe('reports');
    expect(initialPatientDetailTab('trend', false, true)).toBe('trend');
    expect(initialPatientDetailTab('logs', false, false)).toBe('logs');
    expect(initialPatientDetailTab('followup', false, false)).toBe('followup');
    expect(initialPatientDetailTab(undefined, true, true)).toBe('baseline');
    expect(initialPatientDetailTab('unknown', true, true)).toBe('baseline');
  });

  it('开关关闭时 ?tab=reports / ?tab=trend 回落基线资料（页签不可见不落空页）', () => {
    expect(initialPatientDetailTab('reports', false, true)).toBe('baseline');
    expect(initialPatientDetailTab('trend', true, false)).toBe('baseline');
  });
});
