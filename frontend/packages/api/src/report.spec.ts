// E16-T02 / A-ATT-002、A-ATT-012：报告单运行时纯函数（位置标签文案、↑/↓ 容错解析、
// 参考范围按性别选定）——与服务端 ReportService.indicatorFlag 同口径
import { describe, expect, it } from 'vitest';

import {
  foldReportLocations,
  reportIndicatorFlag,
  reportLocationText,
  reportRefRangeFor,
  reportRowDigitizeStatusText,
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

// E16-T05 / A-ATT-016、C-SCAN-014：全院报告单列表（位置列 +N 折叠、行电子化状态文案）
describe('e16-t05 foldReportLocations（位置列超过 2 个折叠为 +N）', () => {
  const tag = (label: string) => ({
    anchorKey: label,
    anchorLabel: label,
    anchorType: 'category' as const,
    linkType: 'baseline' as const,
    templateVersion: 3,
  });

  it('1–2 个全部显示；3 个起折叠 +N 并给悬停全文', () => {
    expect(foldReportLocations([tag('a')])).toEqual({
      all: '基线 · a',
      extraCount: 0,
      visible: ['基线 · a'],
    });
    expect(foldReportLocations([tag('a'), tag('b')]).extraCount).toBe(0);
    const folded = foldReportLocations([
      tag('a'),
      tag('b'),
      tag('c'),
      tag('d'),
    ]);
    expect(folded.visible).toEqual(['基线 · a', '基线 · b']);
    expect(folded.extraCount).toBe(2);
    expect(folded.all).toBe('基线 · a、基线 · b、基线 · c、基线 · d');
  });

  it('空清单 extraCount=0（由调用方显示「未指定位置」）', () => {
    expect(foldReportLocations([])).toEqual({
      all: '',
      extraCount: 0,
      visible: [],
    });
  });
});

describe('e16-t05 reportRowDigitizeStatusText（行电子化状态，C-SCAN-011）', () => {
  it('三态文案；A 租户 null 行不显示', () => {
    expect(reportRowDigitizeStatusText('PENDING')).toBe('待录入');
    expect(reportRowDigitizeStatusText('DONE')).toBe('已电子化');
    expect(reportRowDigitizeStatusText('NO_ENTRY')).toBe('无需录入');
    expect(reportRowDigitizeStatusText(null)).toBeNull();
  });
});
