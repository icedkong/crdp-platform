/**
 * E16-T01 / A-ATT-003、A-ATT-011、A-ATT-012：报告单设置类型与文案（report-config.ts）单测。
 *
 * 锁定：状态 / 电子化状态文案、按性别区分的参考范围显示（A 规格检验指标模板示例写法）、
 * 类型编码字符集校验（与后端 40001 同口径）、名称必填文案（C-SCAN-003）。
 */

import { describe, expect, it } from 'vitest';

import {
  reportConfigStatusText,
  reportDigitizeStatusText,
  reportNameRequiredText,
  reportRefRangeText,
  validateReportTypeCode,
} from './report-config';

describe('report-config 状态与文案', () => {
  it('状态与电子化状态文案', () => {
    expect(reportConfigStatusText('ENABLED')).toBe('启用');
    expect(reportConfigStatusText('DISABLED')).toBe('停用');
    expect(reportDigitizeStatusText(null)).toBe('不启用');
    expect(reportDigitizeStatusText('PENDING')).toBe('待录入');
    expect(reportDigitizeStatusText('NO_ENTRY')).toBe('无需录入');
  });

  it('名称必填文案（C-SCAN-003：检查、检验类必填，其他类型选填）', () => {
    expect(reportNameRequiredText(true)).toBe('必填');
    expect(reportNameRequiredText(false)).toBe('选填');
  });
});

describe('report-config 参考范围显示', () => {
  it('通用参考范围直接显示', () => {
    expect(
      reportRefRangeText({
        itemCode: 'WBC',
        itemName: '白细胞',
        refRange: '3.5–9.5',
        refRangeFemale: null,
        refRangeMale: null,
        sortNo: 10,
        unit: '10^9/L',
      }),
    ).toBe('3.5–9.5');
  });

  it('按性别区分时显示「男 a / 女 b」（A 规格血红蛋白示例）', () => {
    expect(
      reportRefRangeText({
        itemCode: 'HGB',
        itemName: '血红蛋白',
        refRange: null,
        refRangeFemale: '115–150',
        refRangeMale: '130–175',
        sortNo: 20,
        unit: 'g/L',
      }),
    ).toBe('男 130–175 / 女 115–150');
  });

  it('无任何范围显示占位符', () => {
    expect(
      reportRefRangeText({
        itemCode: 'INR',
        itemName: '国际标准化比值',
        refRange: null,
        refRangeFemale: null,
        refRangeMale: null,
        sortNo: 30,
        unit: '—',
      }),
    ).toBe('—');
  });
});

describe('report-config 类型编码校验（与后端同口径）', () => {
  it('合法编码通过', () => {
    expect(validateReportTypeCode('ECHO')).toBeNull();
    expect(validateReportTypeCode('chest-imaging_2')).toBeNull();
  });

  it('空、超长、非 ASCII 拒绝', () => {
    expect(validateReportTypeCode('  ')).toBe('请填写类型编码');
    expect(validateReportTypeCode('x'.repeat(33))).toBe(
      '类型编码最多 32 个字符',
    );
    expect(validateReportTypeCode('心脏超声')).toBe(
      '类型编码只支持字母、数字、短横线与下划线',
    );
    expect(validateReportTypeCode('ECHO 01')).toBe(
      '类型编码只支持字母、数字、短横线与下划线',
    );
  });
});
