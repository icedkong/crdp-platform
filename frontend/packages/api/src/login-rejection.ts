/**
 * E04-T05 / PLT-AUTH-007：登录失败的结构化提示（40101 / 40102 / 42900 的 data）。
 *
 * 后端在 40101 的 data 里带 { captchaRequired, remainingAttempts }（连续失败 3 次后要求图形验证码），
 * 42900 锁定时带 { lockedSeconds }（剩余锁定秒数）。前端据此显示验证码输入与剩余次数 / 锁定文案，
 * 不外露失败原因（后端统一口径，PLT-AUTH-004、007）。
 */

/** 登录失败时后端附带的结构化提示。 */
export interface LoginRejectionInfo {
  /** 连续失败达到阈值（默认 3 次）后为 true：登录必须携带图形验证码。 */
  captchaRequired: boolean;
  /** 锁定剩余秒数（42900 时存在）。 */
  lockedSeconds?: number;
  /** 距锁定还剩几次机会（锁定后为 0）。 */
  remainingAttempts?: number;
}

interface ShapedError {
  data?: unknown;
  response?: { data?: unknown };
}

/** 从 requestClient 抛出的错误里取统一响应体 { code, message, data }。 */
export function errorBody(error: null | ShapedError | undefined): {
  code?: number;
  data?: unknown;
  message?: string;
} {
  const body = (error?.data ?? error?.response?.data ?? {}) as {
    code?: number;
    data?: unknown;
    message?: string;
  };
  return body ?? {};
}

/**
 * 解析登录失败的提示信息。不匹配（成功响应、无 data 的错误）返回 null。
 * 兼容 mock 与真实请求层：错误对象可能把响应体放在 data 或 response.data。
 */
export function loginRejectionInfo(
  error: null | ShapedError | undefined,
): LoginRejectionInfo | null {
  const body = errorBody(error);
  const code = Number(body.code);
  const data = (body.data ?? {}) as Record<string, unknown>;
  if (code === 40_101) {
    return {
      captchaRequired: data.captchaRequired === true,
      remainingAttempts:
        typeof data.remainingAttempts === 'number'
          ? data.remainingAttempts
          : undefined,
    };
  }
  if (code === 42_900) {
    return {
      captchaRequired: true,
      remainingAttempts: 0,
      lockedSeconds:
        typeof data.lockedSeconds === 'number' ? data.lockedSeconds : undefined,
    };
  }
  return null;
}

/** 锁定剩余时间文案（向上取整到分钟）；未锁定返回 null。 */
export function lockedMessage(lockedSeconds?: number): null | string {
  if (!lockedSeconds || lockedSeconds <= 0) {
    return null;
  }
  const minutes = Math.max(1, Math.ceil(lockedSeconds / 60));
  return `账号已锁定，请 ${minutes} 分钟后再试或联系管理员解锁`;
}

/** 剩余次数提示（锁定或未开始计数返回 null）。 */
export function remainingAttemptsMessage(
  remainingAttempts?: number,
): null | string {
  if (
    remainingAttempts === undefined ||
    remainingAttempts <= 0 ||
    remainingAttempts >= 5
  ) {
    return null;
  }
  return `还可尝试 ${remainingAttempts} 次`;
}
