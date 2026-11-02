import { describe, expect, it } from 'vitest';

import {
  dndRangeText,
  toSettingsUpdate,
  validateSettingsModel,
} from './send-settings';

/** E09-T07 / A-NTF-005、A-NTF-007 · 发送设置页签：校验与请求体（与后端同口径）。 */
describe('e09-T07 发送设置', () => {
  it('默认值合法：21:00–08:00、上限 3', () => {
    expect(
      validateSettingsModel({
        dndEnd: '08:00',
        dndStart: '21:00',
        overdueSmsOffAllowed: false,
        smsDailyLimit: 3,
      }),
    ).toEqual([]);
  });

  it('跨零点时段合法、起止相同非法', () => {
    expect(
      validateSettingsModel({
        dndEnd: '07:30',
        dndStart: '22:30',
        overdueSmsOffAllowed: true,
        smsDailyLimit: 5,
      }),
    ).toEqual([]);
    expect(
      validateSettingsModel({
        dndEnd: '21:00',
        dndStart: '21:00',
        overdueSmsOffAllowed: false,
        smsDailyLimit: 3,
      }),
    ).toContain('免打扰起止时间不能相同');
  });

  it('时间口径与上限范围（0 关掉全部短信、21 超上限）都报错', () => {
    const base = {
      dndEnd: '08:00',
      overdueSmsOffAllowed: false,
      smsDailyLimit: 3,
    };
    expect(
      validateSettingsModel({ ...base, dndStart: '25:00' }).join(','),
    ).toContain('HH:mm');
    const limitErrors = (limit: null | number) =>
      validateSettingsModel({
        dndEnd: '08:00',
        dndStart: '21:00',
        overdueSmsOffAllowed: false,
        smsDailyLimit: limit,
      });
    expect(limitErrors(0).join(',')).toContain('每日短信上限');
    expect(limitErrors(21).join(',')).toContain('每日短信上限');
    expect(limitErrors(null).join(',')).toContain('每日短信上限');
  });

  it('表单 → PUT 请求体字段一一对应', () => {
    expect(
      toSettingsUpdate({
        dndEnd: '07:30',
        dndStart: '22:30',
        overdueSmsOffAllowed: true,
        smsDailyLimit: 5,
      }),
    ).toEqual({
      dndEnd: '07:30',
      dndStart: '22:30',
      overdueSmsOffAllowed: true,
      smsDailyLimit: 5,
    });
    expect(dndRangeText('21:00', '08:00')).toBe('21:00 至次日 08:00');
  });
});
