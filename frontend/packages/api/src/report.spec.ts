// E16-T02 / A-ATT-002、A-ATT-012：报告单运行时纯函数（位置标签文案、↑/↓ 容错解析、
// 参考范围按性别选定）——与服务端 ReportService.indicatorFlag 同口径
import { describe, expect, it } from 'vitest';

import {
  digitizeChangeError,
  digitizeChangeRequiresReason,
  foldReportLocations,
  reportInboxArchiveError,
  reportInboxSizeText,
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

  // E16-T12 补：退回过的位置（adjusted，E16-T15 起由退回作业置位）折叠后悬停全文仍带「原位置已调整」
  it('退回标记进入折叠文案：悬停全文带「（原位置已调整）」、折叠计数不变', () => {
    const folded = foldReportLocations([
      tag('a'),
      tag('b'),
      { ...tag('c'), adjusted: true },
    ]);
    expect(folded.extraCount).toBe(1);
    expect(folded.all).toBe('基线 · a、基线 · b、基线 · c（原位置已调整）');
    expect(folded.visible).toEqual(['基线 · a', '基线 · b']);
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

// ---------------------------------------------------------------- E21-T15 手工修改（C-SCAN-011）

describe('e21-T15 digitizeChangeError（改为「无需录入」须填原因，前端先拦 / 后端同口径兜底）', () => {
  it('目标状态必选且与当前不同', () => {
    expect(digitizeChangeError('PENDING', null, '')).toBe('请选择电子化状态');
    expect(digitizeChangeError('PENDING', 'PENDING', '')).toBe(
      '电子化状态未变化',
    );
  });

  it('改为「无需录入」必填原因；其余状态选填', () => {
    expect(digitizeChangeError('PENDING', 'NO_ENTRY', '   ')).toBe(
      '改为「无需录入」须填写原因',
    );
    expect(
      digitizeChangeError('PENDING', 'NO_ENTRY', '检验类资料（示例）'),
    ).toBeNull();
    expect(digitizeChangeError('PENDING', 'DONE', '')).toBeNull();
  });

  it('当前为 null（未设置）也可手工设定；原因 ≤ 255 字', () => {
    expect(digitizeChangeError(null, 'PENDING', '')).toBeNull();
    expect(digitizeChangeError('PENDING', 'DONE', '长'.repeat(256))).toBe(
      '原因不能超过 255 字',
    );
    expect(digitizeChangeError('PENDING', 'DONE', '长'.repeat(255))).toBeNull();
  });

  it('digitizeChangeRequiresReason：仅无需录入须填原因', () => {
    expect(digitizeChangeRequiresReason('NO_ENTRY')).toBe(true);
    expect(digitizeChangeRequiresReason('PENDING')).toBe(false);
    expect(digitizeChangeRequiresReason('DONE')).toBe(false);
    expect(digitizeChangeRequiresReason(null)).toBe(false);
  });
});

// E21-T16 / C-SCAN-009：待归档区归档表单校验与大小文案（前端先提示，后端同口径 40001 兜底）
describe('e21-t16 reportInboxArchiveError（归档表单校验）', () => {
  const base = {
    nameId: '5',
    nameRequired: true,
    patientId: '101',
    reportDate: '2026-11-14',
    typeCode: 'LAB',
  };

  it('齐全（位置 ≥ 1）通过', () => {
    expect(reportInboxArchiveError(base, 1)).toBeNull();
    expect(reportInboxArchiveError(base, 3)).toBeNull();
  });

  it('患者 / 类型 / 名称（按类型必填）/ 报告时间缺失逐项提示', () => {
    expect(reportInboxArchiveError({ ...base, patientId: null }, 1)).toBe(
      '请选择患者',
    );
    expect(reportInboxArchiveError({ ...base, typeCode: null }, 1)).toBe(
      '请选择资料类型',
    );
    expect(reportInboxArchiveError({ ...base, nameId: null }, 1)).toBe(
      '请选择名称',
    );
    expect(
      reportInboxArchiveError(
        { ...base, nameId: null, nameRequired: false },
        1,
      ),
    ).toBeNull();
    expect(reportInboxArchiveError({ ...base, reportDate: null }, 1)).toBe(
      '请选择报告时间',
    );
  });

  it('位置为空提示「至少选择 1 个位置」（C-SCAN-009 至少一个）', () => {
    expect(reportInboxArchiveError(base, 0)).toBe('请至少选择 1 个位置');
  });
});

describe('e21-t16 reportInboxSizeText（文件大小文案）', () => {
  it('kB 向上取整（最小 1 KB）、MB 保留一位小数', () => {
    expect(reportInboxSizeText(70)).toBe('1 KB');
    expect(reportInboxSizeText(2048)).toBe('2 KB');
    expect(reportInboxSizeText(5 * 1024 * 1024)).toBe('5.0 MB');
    expect(reportInboxSizeText(1536 * 1024)).toBe('1.5 MB');
  });
});
