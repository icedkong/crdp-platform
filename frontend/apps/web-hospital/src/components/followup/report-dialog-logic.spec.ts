import type { ReportCatalogField, ReportLocationInput } from '@crdp/api';

// E16-T02 / A-ATT-001、A-ATT-002；C-SCAN-010：添加报告单对话框纯逻辑
// （公共槽位标签推导、指标行构建与 ↑/↓、校验、载荷、位置相等与预选初始化）
import { describe, expect, it } from 'vitest';

import {
  buildIndicatorRows,
  hasReportFormError,
  indicatorPayload,
  initialLocations,
  reportConclusionLabel,
  reportDateLabel,
  reportFindingsLabel,
  reportNoteLabel,
  sameReportLocation,
  validateReportForm,
  withIndicatorFlag,
} from './report-dialog-logic';

const echoFields: ReportCatalogField[] = [
  {
    code: 'report_date',
    key: 'f001',
    label: '报告时间',
    required: true,
    sortNo: 10,
    type: 'date',
    unit: null,
  },
  {
    code: 'findings',
    key: 'f002',
    label: '超声所见',
    required: false,
    sortNo: 20,
    type: 'textarea',
    unit: null,
  },
  {
    code: 'conclusion',
    key: 'f003',
    label: '超声结论',
    required: false,
    sortNo: 30,
    type: 'textarea',
    unit: null,
  },
];

const operationFields: ReportCatalogField[] = [
  {
    code: 'surgery_date',
    key: 'f001',
    label: '手术日期',
    required: true,
    sortNo: 10,
    type: 'date',
    unit: null,
  },
];

const labFields: ReportCatalogField[] = [
  {
    code: 'report_date',
    key: 'f001',
    label: '报告时间',
    required: true,
    sortNo: 10,
    type: 'date',
    unit: null,
  },
];

describe('e16-t02 添加报告单 · 公共槽位标签（按类型显示）', () => {
  it('日期标签取类型日期字段（手术日期 / 报告时间）', () => {
    expect(reportDateLabel(echoFields)).toBe('报告时间');
    expect(reportDateLabel(operationFields)).toBe('手术日期');
    expect(reportDateLabel([])).toBe('报告时间');
  });

  it('所见 / 结论槽位按类型出现或隐藏', () => {
    expect(reportFindingsLabel(echoFields)).toBe('超声所见');
    expect(reportConclusionLabel(echoFields)).toBe('超声结论');
    expect(reportFindingsLabel(labFields)).toBeNull();
    expect(reportConclusionLabel(labFields)).toBeNull();
  });

  it('备注标签缺省「备注」，通话说明用类型字段标签', () => {
    expect(reportNoteLabel([])).toBe('备注');
    expect(
      reportNoteLabel([
        {
          code: 'call_note',
          key: 'f002',
          label: '通话说明',
          required: false,
          sortNo: 20,
          type: 'textarea',
          unit: null,
        },
      ]),
    ).toBe('通话说明');
  });
});

describe('e16-t02 添加报告单 · 指标表（A-ATT-012）', () => {
  const name = {
    id: '12',
    indicatorTplId: '55',
    items: [
      {
        itemCode: 'WBC',
        itemName: '白细胞（示例）',
        refRange: '3.5–9.5',
        refRangeFemale: null,
        refRangeMale: null,
        sortNo: 10,
        unit: '10^9/L',
      },
      {
        itemCode: 'HGB',
        itemName: '血红蛋白（示例）',
        refRange: null,
        refRangeFemale: '115–150',
        refRangeMale: '130–175',
        sortNo: 20,
        unit: 'g/L',
      },
    ],
    name: '血常规（示例）',
    typeCode: 'LAB',
  };

  it('选名称自动带出项目行；参考范围按性别选定', () => {
    expect(buildIndicatorRows(null, 'F')).toEqual([]);
    const female = buildIndicatorRows(name, 'F');
    expect(female).toHaveLength(2);
    expect(female[0]).toMatchObject({ itemCode: 'WBC', refRange: '3.5–9.5' });
    expect(female[1]).toMatchObject({ itemCode: 'HGB', refRange: '115–150' });
    expect(buildIndicatorRows(name, 'M')[1]).toMatchObject({
      refRange: '130–175',
    });
    // 未提供性别用通用列，通用缺失时回退男性列
    expect(buildIndicatorRows(name, undefined)[1]).toMatchObject({
      refRange: '130–175',
    });
  });

  it('填结果重算 ↑ / ↓，未填不标', () => {
    const row = buildIndicatorRows(name, 'F').at(0);
    expect(withIndicatorFlag({ ...row, result: '3.2' }).flag).toBe('↓');
    expect(withIndicatorFlag({ ...row, result: '10.1' }).flag).toBe('↑');
    expect(withIndicatorFlag({ ...row, result: '6.0' }).flag).toBeNull();
    expect(withIndicatorFlag(row).flag).toBeNull();
  });

  it('载荷只带已填结果的项目', () => {
    const rows = buildIndicatorRows(name, 'F');
    const wbc = rows.find((item) => item.itemCode === 'WBC');
    const hgb = rows.find((item) => item.itemCode === 'HGB');
    if (wbc) wbc.result = '6.0';
    if (hgb) hgb.result = '  ';
    expect(indicatorPayload(rows)).toEqual([
      { itemCode: 'WBC', refRange: '3.5–9.5', result: '6.0' },
    ]);
  });
});

describe('e16-t02 添加报告单 · 校验与位置（A-ATT-002 / C-SCAN-010）', () => {
  it('类型 / 名称（按类型必填）/ 报告时间逐项给出错误', () => {
    expect(
      hasReportFormError(
        validateReportForm({
          nameId: null,
          nameRequired: true,
          reportDate: null,
          typeCode: null,
        }),
      ),
    ).toBe(true);
    const ok = validateReportForm({
      nameId: '12',
      nameRequired: true,
      reportDate: '2026-10-28',
      typeCode: 'LAB',
    });
    expect(hasReportFormError(ok)).toBe(false);
    expect(
      validateReportForm({
        nameId: null,
        nameRequired: false,
        reportDate: '2026-10-28',
        typeCode: 'OTHER',
      }).nameId,
    ).toBeNull();
  });

  it('位置四元组相等（整条 vs 分组是不同位置）', () => {
    const whole: ReportLocationInput = {
      anchorTemplateVersion: 2,
      anchorType: null,
      linkRecordId: '88',
      linkType: 'followup_record',
    };
    const section: ReportLocationInput = {
      anchorKey: 'echo',
      anchorTemplateVersion: 2,
      anchorType: 'section',
      linkRecordId: '88',
      linkType: 'followup_record',
    };
    expect(sameReportLocation(whole, { ...whole })).toBe(true);
    expect(sameReportLocation(whole, section)).toBe(false);
    expect(
      sameReportLocation(section, { ...section, anchorKey: 'overview' }),
    ).toBe(false);
  });

  it('打开对话框按进入位置预选（可再加）', () => {
    const preset: ReportLocationInput[] = [
      {
        anchorKey: 'case',
        anchorTemplateVersion: 3,
        anchorType: 'category',
        linkType: 'baseline',
      },
    ];
    expect(initialLocations(preset)).toEqual(preset);
    expect(initialLocations(preset)).not.toBe(
      preset,
      '预选是副本，避免误改调用方',
    );
    expect(initialLocations(undefined)).toEqual([]);
  });
});
