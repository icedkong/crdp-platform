import type { BatchOutcomeItem } from './batch-operation';

import { describe, expect, it } from 'vitest';

import {
  BATCH_SKIP_REASON_TEXT,
  batchOutcomeText,
  countSkipByReason,
  doctorOptionLabel,
  filterDoctorsByWards,
} from './batch-operation';

/** 构造一条批量结果（E13-T07：items 与请求顺序一致，name / patientNo 为后端脱敏值）。 */
function item(outcome: string, reason: null | string = null): BatchOutcomeItem {
  return {
    name: outcome === 'SKIP' && reason === 'NOT_FOUND' ? null : '周*）',
    outcome: outcome as BatchOutcomeItem['outcome'],
    patientId: '77',
    patientNo: 'IT*******-1',
    reason,
    wardName: '虚构病区一',
  };
}

describe('e13-T07 批量操作结果（tasks.csv 验收「批量操作结果逐条可查」）', () => {
  it('跳过原因码映射为界面文案（已在组 / 已死亡 / 已移除等 A-PAT-016 口径）', () => {
    expect(BATCH_SKIP_REASON_TEXT.ALREADY_ENROLLED).toBe('已在组');
    expect(BATCH_SKIP_REASON_TEXT.DEAD).toBe('已死亡');
    expect(BATCH_SKIP_REASON_TEXT.REMOVED).toBe('已从该计划移除');
    expect(BATCH_SKIP_REASON_TEXT.DOCTOR_NOT_IN_WARD).toContain('病区');
    expect(BATCH_SKIP_REASON_TEXT.NOT_FOUND).toContain('数据范围');
  });

  it('单条结果文案：成功 / 将加入 / 跳过原因；未知码原样显示不吞错', () => {
    expect(batchOutcomeText(item('SUCCESS'))).toBe('成功');
    expect(batchOutcomeText(item('ENROLL'))).toBe('将加入');
    expect(batchOutcomeText(item('SKIP', 'ALREADY_ENROLLED'))).toBe('已在组');
    expect(batchOutcomeText(item('SKIP', 'SOME_NEW_CODE'))).toBe(
      'SOME_NEW_CODE',
    );
  });

  it('按原因码汇总计数（成功 18、跳过 2 的分组口径）', () => {
    const items = [
      ...Array.from({ length: 18 }, () => item('SUCCESS')),
      item('SKIP', 'ALREADY_ENROLLED'),
      item('SKIP', 'ALREADY_ENROLLED'),
      item('SKIP', 'DEAD'),
    ];
    const groups = countSkipByReason(items);
    expect(groups).toEqual([
      { count: 2, reason: 'ALREADY_ENROLLED', text: '已在组' },
      { count: 1, reason: 'DEAD', text: '已死亡' },
    ]);
  });

  it('医生候选展示名与病区预筛（A-PAT-017「只能选该病区的医生」）', () => {
    const doctor = {
      displayName: '演示医生（示例）',
      id: '5',
      username: 'doctor.demo',
      wardId: '3',
      wardName: '虚构病区一',
    };
    expect(doctorOptionLabel(doctor)).toBe('演示医生（示例） · 虚构病区一');

    const doctors = [
      doctor,
      { ...doctor, id: '6', wardId: '4', displayName: '他科医生（示例）' },
    ];
    expect(filterDoctorsByWards(doctors, [3])).toEqual([doctor]);
    expect(filterDoctorsByWards(doctors, ['3', '4'])).toHaveLength(2);
  });
});
