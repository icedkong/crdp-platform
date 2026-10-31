import type { TenantStatusImpact } from '@crdp/api';

import { describe, expect, it } from 'vitest';

import {
  actionConfirmText,
  actionVerb,
  canDisable,
  canEnable,
  canRenew,
  EMPTY_EXPIRY_FORM,
  expiryFormText,
  impactRows,
  parseRemindInput,
  renewalConfirmText,
  toCreateExpiry,
  toExpireRequest,
  toExpiryFormModel,
  validateExpiryForm,
  validateReason,
} from './lifecycle';

const fixedDetail = {
  expireAt: '2027-01-31',
  expireType: 'FIXED' as const,
  graceDays: 20,
  graceReadOnly: true,
  remindDays: [30, 7],
};

function futureDate(daysAhead: number): string {
  const date = new Date();
  date.setDate(date.getDate() + daysAhead);
  const month = String(date.getMonth() + 1).padStart(2, '0');
  const day = String(date.getDate()).padStart(2, '0');
  return `${date.getFullYear()}-${month}-${day}`;
}

describe('e05-T02 状态操作可用性（按规格状态图）', () => {
  it('手动停用仅启用态；启用是转正或恢复', () => {
    expect(canDisable('ENABLED')).toBe(true);
    expect(canDisable('TRIAL')).toBe(false);
    expect(canDisable('GRACE')).toBe(false);
    expect(canDisable('PROVISIONING')).toBe(false);
    expect(canEnable('TRIAL')).toBe(true);
    expect(canEnable('DISABLED')).toBe(true);
    expect(canEnable('GRACE')).toBe(false);
    expect(canEnable('ENABLED')).toBe(false);
  });

  it('续期对试用/启用/宽限/停用开放', () => {
    expect(canRenew('GRACE')).toBe(true);
    expect(canRenew('DISABLED')).toBe(true);
    expect(canRenew('ENABLED')).toBe(true);
    expect(canRenew('TRIAL')).toBe(true);
    expect(canRenew('PROVISIONING')).toBe(false);
    expect(canRenew('CANCELLED')).toBe(false);
  });

  it('动词与确认文案随状态区分', () => {
    expect(actionVerb('ENABLE', 'TRIAL')).toBe('转正');
    expect(actionVerb('ENABLE', 'DISABLED')).toBe('恢复启用');
    expect(actionVerb('DISABLE', 'ENABLED')).toBe('停用');
    expect(actionConfirmText('DISABLE', 'ENABLED')).toContain('不可登录');
    expect(actionConfirmText('ENABLE', 'TRIAL')).toContain('正式服务');
    expect(renewalConfirmText('GRACE')).toContain('立即恢复启用');
    expect(renewalConfirmText('ENABLED')).not.toContain('立即恢复启用');
  });

  it('影响面：在线用户与进行中任务，未上线显示「—」', () => {
    const impact = {
      code: 'demoa',
      name: '虚构示例医院甲',
      onlineUserCount: 3,
      pendingTaskCount: null,
      status: 'ENABLED' as const,
      tenantId: 1,
    } satisfies TenantStatusImpact;
    expect(impactRows(impact)).toEqual({ online: '3 人', pending: '—' });
    expect(impactRows(null)).toEqual({ online: '—', pending: '—' });
    expect(impactRows({ ...impact, pendingTaskCount: 5 })).toEqual({
      online: '3 人',
      pending: '5 条',
    });
  });

  it('变更原因必填且不超 200 字', () => {
    expect(validateReason('  正式签约  ')).toBeUndefined();
    expect(validateReason('')).toBe('必填');
    expect(validateReason('   ')).toBe('必填');
    expect(validateReason('长'.repeat(201))).toBe('最多 200 个字符');
  });
});

describe('e05-T02 到期设置表单（PLT-TEN-011）', () => {
  it('详情转表单模型：空值落空串、数组落逗号串', () => {
    const form = toExpiryFormModel(fixedDetail);
    expect(form).toEqual({
      expireAt: '2027-01-31',
      expireType: 'FIXED',
      graceDays: '20',
      graceReadOnly: true,
      remindDays: '30,7',
    });
    expect(
      toExpiryFormModel({
        expireAt: null,
        expireType: 'LONG_TERM',
        graceDays: null,
        graceReadOnly: false,
        remindDays: null,
      }).remindDays,
    ).toBe('');
  });

  it('提醒节点输入去重降序，兼容中文逗号与空格', () => {
    expect(parseRemindInput('7, 30，7 1')).toEqual([30, 7, 1]);
    expect(parseRemindInput('')).toEqual([]);
  });

  it('校验：FIXED 必填未来到期日，节点与宽限期有范围；LONG_TERM 其余项不校验', () => {
    expect(
      validateExpiryForm({
        ...toExpiryFormModel(fixedDetail),
        expireAt: futureDate(1),
      }),
    ).toEqual({});
    expect(
      validateExpiryForm({ ...toExpiryFormModel(fixedDetail), expireAt: '' })
        .expireAt,
    ).toBe('有到期日时必填');
    expect(
      validateExpiryForm({
        ...toExpiryFormModel(fixedDetail),
        expireAt: '2027/01/31',
      }).expireAt,
    ).toBe('日期格式应为 YYYY-MM-DD');
    expect(
      validateExpiryForm({
        ...toExpiryFormModel(fixedDetail),
        expireAt: futureDate(-1),
      }).expireAt,
    ).toBe('必须晚于今天');
    expect(
      validateExpiryForm({
        ...toExpiryFormModel(fixedDetail),
        remindDays: '400',
      }).remindDays,
    ).toBe('须为 1 至 365 的天数');
    expect(
      validateExpiryForm({
        ...toExpiryFormModel(fixedDetail),
        graceDays: '0.5',
      }).graceDays,
    ).toBe('须为 1 至 365 的整数');
    expect(
      validateExpiryForm({
        expireAt: '',
        expireType: 'LONG_TERM',
        graceDays: 'abc',
        graceReadOnly: false,
        remindDays: 'xxx',
      }),
    ).toEqual({});
  });

  it('表单转续期提交：未填项不传、原因去空格；长期有效只传类型', () => {
    expect(
      toExpireRequest(toExpiryFormModel(fixedDetail), '  续费一年  '),
    ).toEqual({
      expireAt: '2027-01-31',
      expireType: 'FIXED',
      graceDays: 20,
      graceReadOnly: true,
      reason: '续费一年',
      remindDays: [30, 7],
    });
    expect(
      toExpireRequest(
        {
          expireAt: '',
          expireType: 'LONG_TERM',
          graceDays: '',
          graceReadOnly: false,
          remindDays: '',
        },
        '转长期',
      ),
    ).toEqual({ expireType: 'LONG_TERM', reason: '转长期' });
  });

  it('到期设置摘要：长期有效一句话；FIXED 逐项拼接', () => {
    expect(expiryFormText(EMPTY_EXPIRY_FORM)).toBe('长期有效');
    expect(
      expiryFormText({
        expireAt: '2027-01-31',
        expireType: 'FIXED',
        graceDays: '20',
        graceReadOnly: true,
        remindDays: '30,7',
      }),
    ).toBe('有到期日 · 2027-01-31 / 提醒 30、7 天前 / 宽限 20 天 / 宽限期只读');
    expect(
      expiryFormText({
        expireAt: '',
        expireType: 'FIXED',
        graceDays: '',
        graceReadOnly: false,
        remindDays: '',
      }),
    ).toBe('有到期日 · 未填');
  });

  it('向导提交段：长期有效只传类型；FIXED 未填项由后端取默认', () => {
    expect(
      toCreateExpiry({
        expireAt: '',
        expireType: 'LONG_TERM',
        graceDays: '',
        graceReadOnly: false,
        remindDays: '',
      }),
    ).toEqual({ expireType: 'LONG_TERM' });
    expect(
      toCreateExpiry({
        expireAt: futureDate(60),
        expireType: 'FIXED',
        graceDays: '',
        graceReadOnly: false,
        remindDays: '',
      }),
    ).toEqual({
      expireAt: futureDate(60),
      expireType: 'FIXED',
      graceReadOnly: false,
    });
    expect(
      toCreateExpiry({
        expireAt: futureDate(60),
        expireType: 'FIXED',
        graceDays: '20',
        graceReadOnly: true,
        remindDays: '7,30',
      }),
    ).toEqual({
      expireAt: futureDate(60),
      expireType: 'FIXED',
      graceDays: 20,
      graceReadOnly: true,
      remindDays: [30, 7],
    });
  });
});
