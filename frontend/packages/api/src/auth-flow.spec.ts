import { describe, expect, it } from 'vitest';

import {
  cooldownRemaining,
  isTwoFactorChallenge,
  maskPhone,
  pickTwoFactorChallenge,
} from './auth-flow';

/** E04-T04：登录结果双因素分流与短信冷却的纯逻辑（PLT-AUTH-002、003）。 */
describe('auth-flow', () => {
  describe('isTwoFactorChallenge / pickTwoFactorChallenge', () => {
    it('密码直接通过时不是双因素（token 有值、无 challengeId）', () => {
      const result = {
        expiresInSeconds: 28_800,
        token: 'opaque-token',
        tokenType: 'Bearer',
      };
      expect(isTwoFactorChallenge(result)).toBe(false);
      expect(pickTwoFactorChallenge(result)).toBeNull();
    });

    it('需要双因素时按 challengeId 分流，methods 缺省回退 SMS', () => {
      const result = {
        challengeId: 'challenge-id',
        methods: ['SMS'],
        token: null,
        tokenType: null,
      };
      expect(isTwoFactorChallenge(result)).toBe(true);
      expect(pickTwoFactorChallenge(result)).toEqual({
        challengeId: 'challenge-id',
        methods: ['SMS'],
      });

      const legacy = { challengeId: 'challenge-id', token: null } as never;
      expect(pickTwoFactorChallenge(legacy)?.methods).toEqual(['SMS']);
    });

    it('空字符串 challengeId 视为不需要双因素（后端不会下发，防御）', () => {
      expect(isTwoFactorChallenge({ challengeId: '', token: null })).toBe(
        false,
      );
    });
  });

  describe('maskPhone', () => {
    it('保留前 3 后 4，中间 4 位打码', () => {
      expect(maskPhone('13900000001')).toBe('139****0001');
    });

    it('空值与过短输入返回固定占位', () => {
      expect(maskPhone(null)).toBe('****');
      expect(maskPhone('')).toBe('****');
      expect(maskPhone('123')).toBe('****');
    });
  });

  describe('cooldownRemaining', () => {
    it('发送后 60 秒内倒计时，到点归零', () => {
      const sentAt = 1_000_000;
      expect(cooldownRemaining(sentAt, sentAt + 1000)).toBe(59);
      expect(cooldownRemaining(sentAt, sentAt + 59_900)).toBe(1);
      expect(cooldownRemaining(sentAt, sentAt + 60_000)).toBe(0);
      expect(cooldownRemaining(sentAt, sentAt + 120_000)).toBe(0);
    });

    it('未发送过（0）不在冷却中，冷却时长可配置', () => {
      expect(cooldownRemaining(0, Date.now())).toBe(0);
      const sentAt = 5000;
      expect(cooldownRemaining(sentAt, sentAt + 30_000, 120)).toBe(90);
    });
  });
});
