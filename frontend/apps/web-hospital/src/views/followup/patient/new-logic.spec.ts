import { describe, expect, it } from 'vitest';

import { noticeFromSubmitError, patientDetailPath } from './new-logic';

describe('e13-T01 提交错误映射（重复病案号提示并阻止）', () => {
  it('40902 且带范围内摘要：转阻止提示并保留跳转数据', () => {
    const notice = noticeFromSubmitError({
      code: 40_902,
      message: '该病案号已在本院登记，可打开已有患者档案继续随访',
      data: {
        exists: true,
        patient: { id: '11', nameMasked: '李*）', wardName: '示例二病区' },
      },
    });
    expect(notice?.kind).toBe('blocked');
    expect(notice?.patient?.id).toBe('11');
  });

  it('40902 范围外（data 无摘要）：只报阻止文案，不泄露信息', () => {
    const notice = noticeFromSubmitError({
      code: 40_902,
      message: '该病案号已在本院登记，请核对后重试',
      data: { exists: true },
    });
    expect(notice?.kind).toBe('blocked');
    expect(notice?.patient ?? null).toBeNull();
  });

  it('40902 且无 data：兜底文案', () => {
    const notice = noticeFromSubmitError({ code: 40_902, message: '' });
    expect(notice?.kind).toBe('blocked');
    expect(notice?.text).toContain('已在本院登记');
  });

  it('非 40902：不产生查重提示（页面走通用错误提示）', () => {
    expect(
      noticeFromSubmitError({ code: 40_001, message: '请填写病案号' }),
    ).toBeNull();
    expect(noticeFromSubmitError(null)).toBeNull();
  });

  it('患者详情路由约定', () => {
    expect(patientDetailPath(11)).toBe('/followup/patient/11');
    expect(patientDetailPath('990001')).toBe('/followup/patient/990001');
  });
});
