import type { ConditionGroup } from './advanced-search';
import type { PatientFilterState } from './patient-search';

/**
 * E13-T09 / A-PAT-009、A-PAT-012 纯逻辑用例：导出请求体构建（当前筛选 + 高级检索、
 * 检索词不进导出条件）、值模式 / 理由校验、删除原因校验（必填 + 上限）、状态文案。
 */
import { describe, expect, it } from 'vitest';

import {
  buildPatientExportPayload,
  PATIENT_DELETE_REASON_MAX,
  validatePatientDeleteReason,
  validatePatientExportForm,
} from './patient-export';
import { emptyPatientFilters } from './patient-search';

const advanced: ConditionGroup = {
  items: [{ field: 'gender', op: 'eq', value: 'M' }],
  logic: 'and',
};

describe('buildPatientExportPayload（A-PAT-009 按当前筛选导出）', () => {
  it('空筛选与无条件树时 filters 不发送（导出全量范围内患者）', () => {
    const payload = buildPatientExportPayload(
      emptyPatientFilters(),
      null,
      'LABEL',
      '',
    );
    expect(payload.scope).toBe('PATIENT');
    expect(payload.valueMode).toBe('LABEL');
    expect(payload.condition).toEqual({});
    expect(payload.reason).toBeUndefined();
  });

  it('携带当前筛选与高级检索条件树，检索词不进导出条件', () => {
    const filters: PatientFilterState = {
      ...emptyPatientFilters(),
      keyword: '张小明（示例）',
      status: 'ACTIVE',
      wardId: 3,
      dischargeRange: ['2026-01-01', '2026-06-30'],
    };
    const payload = buildPatientExportPayload(
      filters,
      advanced,
      'CODE',
      '  年度报表  ',
    );
    expect(payload.valueMode).toBe('CODE');
    expect(payload.reason).toBe('年度报表');
    expect(payload.condition.filters).toEqual({
      dischargeFrom: '2026-01-01',
      dischargeTo: '2026-06-30',
      status: ['ACTIVE'],
      wardIds: [3],
    });
    expect(payload.condition.condition).toEqual(advanced);
    // 检索词可能是患者姓名，不落库（与保存的筛选同口径）
    expect(JSON.stringify(payload)).not.toContain('张小明');
  });
});

describe('导出与删除校验', () => {
  it('申请理由上限 200 字，可空', () => {
    expect(validatePatientExportForm('')).toBeNull();
    expect(validatePatientExportForm('合理理由')).toBeNull();
    expect(validatePatientExportForm('长'.repeat(201))).toContain('200');
  });

  it('删除原因必填且上限 255（A-PAT-012）', () => {
    expect(validatePatientDeleteReason('  ')).toContain('请填写');
    expect(validatePatientDeleteReason('录入错误（示例）')).toBeNull();
    expect(
      validatePatientDeleteReason('长'.repeat(PATIENT_DELETE_REASON_MAX + 1)),
    ).toContain('255');
  });
});
