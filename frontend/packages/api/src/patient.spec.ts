/**
 * E07-T05 / A-PAT-010、C-PAT-012：明文查看请求组装纯逻辑单测（接口契约 patient.md §5）。
 * 组件键别名（mrn / idNo）归一到目录码；目录外字段与非法场景不产出请求体（不发起调用）。
 */
import { describe, expect, it } from 'vitest';

import {
  buildPlaintextRevealRequest,
  normalizePatientPlainField,
  normalizePatientPlainScene,
  PATIENT_PLAIN_FIELDS,
} from './patient';

describe('normalizePatientPlainField', () => {
  it('目录码原样通过', () => {
    for (const { field } of PATIENT_PLAIN_FIELDS) {
      expect(normalizePatientPlainField(field)).toBe(field);
    }
  });

  it('组件键别名归一到接口目录码', () => {
    expect(normalizePatientPlainField('mrn')).toBe('medicalRecordNo');
    expect(normalizePatientPlainField('idNo')).toBe('idCard');
  });

  it('目录外字段返回 null（不发起明文请求）', () => {
    expect(normalizePatientPlainField('nickname')).toBeNull();
    expect(normalizePatientPlainField('')).toBeNull();
  });
});

describe('normalizePatientPlainScene', () => {
  it('空值缺省 detail', () => {
    expect(normalizePatientPlainScene(null)).toBe('detail');
    expect(normalizePatientPlainScene(undefined)).toBe('detail');
    expect(normalizePatientPlainScene('')).toBe('detail');
  });

  it('非法场景返回 null', () => {
    expect(normalizePatientPlainScene('export')).toBeNull();
  });
});

describe('buildPlaintextRevealRequest', () => {
  it('单字段请求体（组件键 mrn → 病案号）', () => {
    expect(buildPlaintextRevealRequest('mrn', 'form')).toEqual({
      fields: ['medicalRecordNo'],
      scene: 'form',
    });
  });

  it('目录外字段或非法场景不产出请求体', () => {
    expect(buildPlaintextRevealRequest('wardName')).toBeNull();
    expect(buildPlaintextRevealRequest('name', 'export')).toBeNull();
  });
});
