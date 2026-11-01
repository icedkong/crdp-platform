import type { ReauthResult } from './reauth';

/**
 * E04-T08 / PLT-AUTH-012：敏感操作二次认证共享纯逻辑单测。
 * 内存令牌保存（窗口/过期/作废）、40304 识别（多种错误形态）、
 * runWithReauth 编排（无令牌先验证、窗口内复用、失效重验一次、取消中止）。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import {
  isReauthCancelled,
  isReauthRequired,
  REAUTH_HEADER,
  ReauthCancelledError,
  reauthHeaders,
  ReauthTokenKeeper,
  runWithReauth,
} from './reauth';

function makeResult(token = 'reauth-token-1', ttl = 300): ReauthResult {
  return { expiresInSeconds: ttl, reauthToken: token };
}

describe('reauthTokenKeeper', () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('无令牌时 valid 返回 null', () => {
    expect(new ReauthTokenKeeper().valid()).toBeNull();
  });

  it('窗口内返回令牌，过期后返回 null', () => {
    const keeper = new ReauthTokenKeeper();
    keeper.store('token-a', 300);
    expect(keeper.valid()).toBe('token-a');

    vi.advanceTimersByTime(298 * 1000);
    expect(keeper.valid()).toBe('token-a');

    // 留 1 秒安全余量：到期前 1 秒即视为不可用
    vi.advanceTimersByTime(2 * 1000);
    expect(keeper.valid()).toBeNull();
  });

  it('clear 作废令牌', () => {
    const keeper = new ReauthTokenKeeper();
    keeper.store('token-a', 300);
    keeper.clear();
    expect(keeper.valid()).toBeNull();
  });
});

describe('isReauthRequired', () => {
  it('识别 axios 错误形态 { response: { data: { code } } }', () => {
    expect(isReauthRequired({ response: { data: { code: 40_304 } } })).toBe(
      true,
    );
  });

  it('识别拆包后的 { data: { code } } 与裸 { code }', () => {
    expect(isReauthRequired({ data: { code: 40_304 } })).toBe(true);
    expect(isReauthRequired({ code: 40_304 })).toBe(true);
  });

  it('其他错误码与空值不是 40304', () => {
    expect(isReauthRequired({ response: { data: { code: 40_300 } } })).toBe(
      false,
    );
    expect(isReauthRequired({ response: { data: {} } })).toBe(false);
    expect(isReauthRequired(null)).toBe(false);
    expect(isReauthRequired('40304')).toBe(false);
  });
});

describe('reauthHeaders', () => {
  it('有令牌带 X-Reauth-Token，无令牌空对象', () => {
    expect(reauthHeaders('t1')).toEqual({ [REAUTH_HEADER]: 't1' });
    expect(reauthHeaders(null)).toEqual({});
  });
});

describe('runWithReauth', () => {
  function deps(options?: {
    current?: null | string;
    prompt?: null | ReauthResult;
  }) {
    // 注意 prompt 允许显式传 null（用户取消），不能用 ?? 兜底
    const promptResult =
      options?.prompt === undefined
        ? makeResult('fresh-token')
        : options.prompt;
    const promptVerified = vi.fn().mockResolvedValue(promptResult);
    const keeper = new ReauthTokenKeeper();
    if (options?.current) keeper.store(options.current, 300);
    return {
      action: vi.fn(
        async (headers: Record<string, string>) =>
          `done:${headers[REAUTH_HEADER] ?? 'none'}`,
      ),
      deps: {
        currentToken: () => keeper.valid(),
        onInvalidated: () => keeper.clear(),
        onIssued: (r: ReauthResult) =>
          keeper.store(r.reauthToken, r.expiresInSeconds),
        promptVerified,
      },
      keeper,
      promptVerified,
    };
  }

  it('窗口内已有令牌：直接带头发起，不弹密码框', async () => {
    const ctx = deps({ current: 'cached-token' });
    const result = await runWithReauth(ctx.action, ctx.deps);

    expect(result).toBe('done:cached-token');
    expect(ctx.promptVerified).not.toHaveBeenCalled();
  });

  it('没有令牌：弹密码框 → 验证 → 签发后带新令牌发起并保存', async () => {
    const ctx = deps();
    const result = await runWithReauth(ctx.action, ctx.deps);

    expect(result).toBe('done:fresh-token');
    expect(ctx.promptVerified).toHaveBeenCalledTimes(1);
    expect(ctx.keeper.valid()).toBe('fresh-token');
  });

  it('用户取消：抛 ReauthCancelledError，不发起敏感请求', async () => {
    const ctx = deps({ prompt: null });

    await expect(runWithReauth(ctx.action, ctx.deps)).rejects.toBeInstanceOf(
      ReauthCancelledError,
    );
    expect(ctx.action).not.toHaveBeenCalled();
    expect(isReauthCancelled(new ReauthCancelledError())).toBe(true);
  });

  it('窗口内令牌被服务端拒绝（40304）：作废后重验一次再发起', async () => {
    const ctx = deps({ current: 'stale-token' });
    ctx.action
      .mockRejectedValueOnce(
        Object.assign(new Error('敏感操作需二次认证'), {
          response: { data: { code: 40_304 } },
        }),
      )
      .mockResolvedValueOnce('done:fresh-token');

    const result = await runWithReauth(ctx.action, ctx.deps);

    expect(result).toBe('done:fresh-token');
    expect(ctx.action).toHaveBeenCalledTimes(2);
    expect(ctx.promptVerified).toHaveBeenCalledTimes(1);
    expect(ctx.keeper.valid()).toBe('fresh-token');
  });

  it('重验时用户取消：抛出原始 40304 错误', async () => {
    const ctx = deps({ current: 'stale-token', prompt: null });
    ctx.action.mockRejectedValueOnce(
      Object.assign(new Error('敏感操作需二次认证'), {
        response: { data: { code: 40_304 } },
      }),
    );

    await expect(runWithReauth(ctx.action, ctx.deps)).rejects.toThrow(
      '敏感操作需二次认证',
    );
  });

  it('非 40304 的业务错误原样抛出，不重验', async () => {
    const ctx = deps({ current: 'cached-token' });
    const business = Object.assign(new Error('状态不允许'), {
      response: { data: { code: 40_901 } },
    });
    ctx.action.mockRejectedValueOnce(business);

    await expect(runWithReauth(ctx.action, ctx.deps)).rejects.toBe(business);
    expect(ctx.promptVerified).not.toHaveBeenCalled();
  });

  it('重验后仍 40304 不再无限循环（第二次失败原样抛出）', async () => {
    const ctx = deps({ current: 'stale-token' });
    ctx.action.mockRejectedValue(
      Object.assign(new Error('敏感操作需二次认证'), {
        response: { data: { code: 40_304 } },
      }),
    );

    await expect(runWithReauth(ctx.action, ctx.deps)).rejects.toThrow(
      '敏感操作需二次认证',
    );
    expect(ctx.action).toHaveBeenCalledTimes(2);
    expect(ctx.promptVerified).toHaveBeenCalledTimes(1);
  });
});
