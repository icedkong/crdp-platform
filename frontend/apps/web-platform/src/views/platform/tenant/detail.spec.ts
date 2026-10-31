import type { TenantDetail } from '@crdp/api';

import { describe, expect, it } from 'vitest';

import {
  expireText,
  orDash,
  statusText,
  toFormModel,
  toUpdateRequest,
  validateTenantForm,
} from './detail';

const detail = {
  code: 'demoa',
  contactEmail: null,
  contactName: '李经理（示例）',
  contactPhone: '13900000002',
  contractNo: null,
  createdAt: '2026-11-01T09:00:00+08:00',
  creditCode: null,
  expireAt: null,
  expireType: 'LONG_TERM',
  id: 1,
  name: '虚构示例医院甲',
  orgType: null,
  region: '示例市示例区',
  remark: null,
  shortName: '示例甲院',
  status: 'ENABLED',
  updatedAt: '2026-11-01T10:00:00+08:00',
} satisfies TenantDetail;

describe('e05-T01 租户信息维护逻辑', () => {
  it('详情转表单模型：null 落成空串', () => {
    const form = toFormModel(detail);
    expect(form.name).toBe('虚构示例医院甲');
    expect(form.contactEmail).toBe('');
    expect(form.contactPhone).toBe('13900000002');
  });

  it('表单转提交载荷：空白传 null、名称去首尾空格', () => {
    const payload = toUpdateRequest({
      ...toFormModel(detail),
      creditCode: '  DEMOUSCC0000001X ',
      region: '   ',
    });
    expect(payload.name).toBe('虚构示例医院甲');
    expect(payload.creditCode).toBe('DEMOUSCC0000001X');
    expect(payload.region).toBe(null);
    expect(payload.contactName).toBe('李经理（示例）');
  });

  it('校验：名称必填、长度限制、邮箱格式', () => {
    expect(validateTenantForm(toFormModel(detail))).toEqual({});
    expect(validateTenantForm({ ...toFormModel(detail), name: ' ' }).name).toBe(
      '必填',
    );
    expect(
      validateTenantForm({ ...toFormModel(detail), name: '长'.repeat(129) })
        .name,
    ).toBe('最多 128 个字符');
    expect(
      validateTenantForm({
        ...toFormModel(detail),
        contactEmail: 'not-an-email',
      }).contactEmail,
    ).toBe('邮箱格式不正确');
  });

  it('展示：状态中文、到期「长期」、空值「—」', () => {
    expect(statusText('ENABLED')).toBe('启用');
    expect(expireText(detail)).toBe('长期');
    expect(
      expireText({ ...detail, expireAt: '2027-01-31', expireType: 'FIXED' }),
    ).toBe('2027-01-31');
    expect(orDash(null)).toBe('—');
    expect(orDash('示例甲院')).toBe('示例甲院');
  });
});
