import type { NotifyPrefView } from '@crdp/api';

import { describe, expect, it } from 'vitest';

import {
  hasBlockedChoice,
  optionHint,
  toPrefFormModel,
  validatePrefModel,
} from './notify-pref';

const view: NotifyPrefView = {
  options: [
    { closeable: false, eventType: 'TASK_OVERDUE', label: '已逾期' },
    { closeable: true, eventType: 'DAILY_DIGEST', label: '每日汇总' },
  ],
  overdueSmsOffAllowed: false,
  smsOff: ['TASK_OVERDUE'],
};

const allowOverdue: NotifyPrefView = {
  ...view,
  overdueSmsOffAllowed: true,
  options: [
    { closeable: true, eventType: 'TASK_OVERDUE', label: '已逾期' },
    { closeable: true, eventType: 'DAILY_DIGEST', label: '每日汇总' },
  ],
};

/** E09-T07 / A-NTF-007 · 个人通知偏好页签：表单初值过滤与提交校验。 */
describe('e09-T07 个人通知偏好', () => {
  it('表单初值滤掉不可关闭项（本院不允许时的逾期类历史残留）', () => {
    expect(toPrefFormModel(view)).toEqual([]);
    expect(toPrefFormModel(allowOverdue)).toEqual(['TASK_OVERDUE']);
  });

  it('勾选不可关闭项给出提示并禁用提交', () => {
    const message = validatePrefModel(['TASK_OVERDUE'], view);
    expect(message).toContain('不允许个人关闭逾期类短信');
    expect(hasBlockedChoice(['TASK_OVERDUE'], view)).toBe(true);
    expect(hasBlockedChoice(['DAILY_DIGEST'], view)).toBe(false);
    expect(validatePrefModel(['DAILY_DIGEST'], view)).toBeUndefined();
  });

  it('选项提示：逾期类不可关时说明原因，其余说明只影响短信', () => {
    const [overdueOption, digestOption] = view.options;
    const [overdueAllowed] = allowOverdue.options;
    expect(overdueOption && optionHint(overdueOption, false)).toContain(
      '必须触达',
    );
    expect(digestOption && optionHint(digestOption, false)).toContain(
      '站内信不受影响',
    );
    expect(overdueAllowed && optionHint(overdueAllowed, true)).toContain(
      '站内信不受影响',
    );
  });
});
