/**
 * E04-T04 / PLT-AUTH-002、003：登录结果的双因素分流与短信登录的前端纯逻辑。
 * 供医院端与平台端共用（两端登录接口同构）。
 */

import type { LoginResult } from './index';

/** 双因素第二步需要的最小信息。 */
export interface TwoFactorChallenge {
  challengeId: string;
  methods: string[];
}

/**
 * 后端在需要双因素时 login 返回 { challengeId, methods[] }（token 为 null）。
 * 判断只依赖 challengeId，token 为空不参与（保持向后兼容）。
 */
export function isTwoFactorChallenge(result: LoginResult): boolean {
  return (
    typeof result.challengeId === 'string' && result.challengeId.length > 0
  );
}

/** 从登录结果里取双因素 challenge；不需要双因素时返回 null。 */
export function pickTwoFactorChallenge(
  result: LoginResult,
): null | TwoFactorChallenge {
  if (!isTwoFactorChallenge(result)) {
    return null;
  }
  return {
    challengeId: result.challengeId as string,
    methods: result.methods ?? ['SMS'],
  };
}

/** 审计与界面通用的手机号脱敏：139****0001（与后端 AuthService.maskedPhone 同规则）。 */
export function maskPhone(phone: null | string): string {
  if (!phone || phone.length < 7) {
    return '****';
  }
  return `${phone.slice(0, 3)}****${phone.slice(-4)}`;
}

/**
 * 短信重发冷却剩余秒数。sentAt 为上次发送成功的 epoch 毫秒；now 便于测试注入。
 * 返回值向下取整且不为负；未发送过（sentAt 为 0）返回 0。
 */
export function cooldownRemaining(
  sentAt: number,
  now: number,
  cooldownSeconds = 60,
): number {
  if (!sentAt || cooldownSeconds <= 0) {
    return 0;
  }
  return Math.max(0, Math.ceil((sentAt + cooldownSeconds * 1000 - now) / 1000));
}
