import type {
  ContactLogRow,
  ReportCatalogField,
  ReportLocationInput,
} from '@crdp/api';

// E16-T02 / A-ATT-001、A-ATT-002；C-SCAN-010：添加报告单对话框纯逻辑
// （公共槽位标签推导、指标行构建与 ↑/↓、校验、载荷、位置相等与预选初始化）
import { describe, expect, it } from 'vitest';

import {
  buildIndicatorRows,
  CALL_TYPE_CODE,
  callContactOptions,
  hasReportFormError,
  indicatorPayload,
  initialLocations,
  locationDiff,
  locationRequired,
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

  it('e16-T14 locationDiff：新增进 add、消失进 remove（四元组匹配，版本不参与）；无变化两空', () => {
    const baseline: ReportLocationInput = {
      anchorKey: 'case',
      anchorTemplateVersion: 3,
      anchorType: 'category',
      linkType: 'baseline',
    };
    const staleVersion: ReportLocationInput = {
      anchorKey: 'case',
      anchorTemplateVersion: 9,
      anchorType: 'category',
      linkType: 'baseline',
    };
    const whole: ReportLocationInput = {
      anchorTemplateVersion: 2,
      anchorType: null,
      linkRecordId: '88',
      linkType: 'followup_record',
    };
    // 基线保留（版本变化不算差异）+ 加整条记录 → 只有 add
    expect(locationDiff([baseline], [staleVersion, whole])).toEqual({
      add: [whole],
      remove: [],
    });
    // 移除基线、保留整条 → 只有 remove
    expect(locationDiff([baseline, whole], [whole])).toEqual({
      add: [],
      remove: [baseline],
    });
    // 同时增删 / 无变化
    expect(locationDiff([baseline], [whole])).toEqual({
      add: [whole],
      remove: [baseline],
    });
    expect(locationDiff([baseline], [{ ...baseline }])).toEqual({
      add: [],
      remove: [],
    });
    // 指定位置（无初始位置）：全部进 add
    expect(locationDiff([], [whole])).toEqual({ add: [whole], remove: [] });
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

// ---------------------------------------------------------------- E16-T08 / A-ATT-017 通话记录关联

function contactLog(overrides: Partial<ContactLogRow> = {}): ContactLogRow {
  return {
    audios: [
      {
        fileId: '6001',
        format: 'mp3',
        inlinePlayable: true,
        mimeType: 'audio/mpeg',
        originalName: '通话录音（示例）.mp3',
        sizeBytes: 2048,
      },
    ],
    contactTime: '2026-11-05T09:00:00',
    createdAt: '2026-11-05T09:00:00',
    creatorName: '王医生（示例）',
    id: '5001',
    method: 'PHONE',
    note: null,
    patientId: '301',
    result: 'REACHED',
    version: 0,
    ...overrides,
  };
}

describe('callContactOptions（关联沟通记录下拉，A-ATT-017）', () => {
  it('只列带录音的沟通记录，标签含时间 / 方式 / 录音数', () => {
    const options = callContactOptions([
      contactLog(),
      contactLog({ id: '5002', audios: [] }),
      contactLog({
        audios: [
          {
            fileId: '6002',
            format: 'mp3',
            inlinePlayable: true,
            originalName: 'a.mp3（示例）',
            sizeBytes: 1,
          },
          {
            fileId: '6003',
            format: 'wav',
            inlinePlayable: true,
            originalName: 'b.wav（示例）',
            sizeBytes: 1,
          },
        ],
        contactTime: '2026-11-06T16:30:00',
        id: '5003',
        method: 'WECHAT',
      }),
    ]);

    expect(options).toHaveLength(2);
    expect(options[0]).toEqual({
      audioCount: 1,
      label: '2026-11-05 09:00 · 电话（录音 1 个）',
      value: '5001',
    });
    expect(options[1]?.value).toBe('5003');
    expect(options[1]?.label).toContain('微信');
    expect(options[1]?.label).toContain('录音 2 个');
  });
});

describe('locationRequired（通话记录关联时位置可空，A-ATT-017）', () => {
  it('普通类型与未关联的通话记录仍必填（A-ATT-002）', () => {
    expect(locationRequired('ECHO', null)).toBe(true);
    expect(locationRequired(CALL_TYPE_CODE, null)).toBe(true);
    expect(locationRequired(null, '5001')).toBe(true);
  });

  it('关联沟通记录的通话记录可空（后端按任务对应随访记录推导 / 未指定位置）', () => {
    expect(locationRequired(CALL_TYPE_CODE, '5001')).toBe(false);
  });
});
