// E16-T02 / A-ATT-002、A-ATT-012：报告单运行时纯函数（位置标签文案、↑/↓ 容错解析、
// 参考范围按性别选定）——与服务端 ReportService.indicatorFlag 同口径
import { describe, expect, it } from 'vitest';

import {
  reportIndicatorFlag,
  reportLocationText,
  reportRefRangeFor,
} from './report';

describe('e16-t02 reportLocationText（位置标签）', () => {
  it('基线 · 分类；随访记录 · 分组；整条记录只显示记录名', () => {
    expect(
      reportLocationText({
        anchorKey: 'case',
        anchorLabel: '病例基本信息',
        anchorType: 'category',
        linkType: 'baseline',
        templateVersion: 3,
      }),
    ).toBe('基线 · 病例基本信息');
    expect(
      reportLocationText({
        anchorKey: 'echo',
        anchorLabel: '超声复查',
        anchorType: 'section',
        linkRecordId: '88',
        linkType: 'followup_record',
        recordLabel: '第3年随访',
        templateVersion: 2,
      }),
    ).toBe('第3年随访 · 超声复查');
    expect(
      reportLocationText({
        anchorType: null,
        linkRecordId: '88',
        linkType: 'followup_record',
        recordLabel: '计划外随访',
        templateVersion: 2,
      }),
    ).toBe('计划外随访');
  });

  it('退回过的位置追加「原位置已调整」（E16-T15 起维护）', () => {
    expect(
      reportLocationText({
        adjusted: true,
        anchorLabel: '病例基本信息',
        anchorType: 'category',
        linkType: 'baseline',
        templateVersion: 3,
      }),
    ).toBe('基线 · 病例基本信息（原位置已调整）');
  });
});

describe('e16-t02 reportIndicatorFlag（↑ / ↓，A-ATT-012）', () => {
  it('区间 / 上限 / 下限三型与不可解析范围', () => {
    expect(reportIndicatorFlag('3.5–9.5', '3.2')).toBe('↓');
    expect(reportIndicatorFlag('3.5-9.5', '10.1')).toBe('↑');
    expect(reportIndicatorFlag('3.5~9.5', '5.0')).toBeNull();
    expect(reportIndicatorFlag('≤ 3.4', '3.5')).toBe('↑');
    expect(reportIndicatorFlag('＜125', '100')).toBeNull();
    expect(reportIndicatorFlag('≥ 60', '59')).toBe('↓');
    expect(reportIndicatorFlag('＞ 5.0', '4.9')).toBe('↓');
    expect(reportIndicatorFlag('阴性', '阳性')).toBeNull();
    expect(reportIndicatorFlag('3.5–9.5', '见备注')).toBeNull();
    expect(reportIndicatorFlag(null, '5.0')).toBeNull();
  });
});

describe('e16-t02 reportRefRangeFor（参考范围按性别选定）', () => {
  const item = {
    refRange: null,
    refRangeFemale: '115–150',
    refRangeMale: '130–175',
  };

  it('按性别取列；未知性别通用列缺失时回退男性列', () => {
    expect(reportRefRangeFor(item, 'F')).toBe('115–150');
    expect(reportRefRangeFor(item, 'M')).toBe('130–175');
    expect(reportRefRangeFor(item, undefined)).toBe('130–175');
    // 性别列存在时优先于通用列（可按性别区分即以此为准，A-ATT-012）
    expect(reportRefRangeFor({ ...item, refRange: '3.5–9.5' }, 'F')).toBe(
      '115–150',
    );
  });
});
