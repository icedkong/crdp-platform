/**
 * E04-T08 / PLT-AUTH-012：敏感操作二次认证（明文查看、导出、下载、重置密码等）。
 *
 * 后端契约（docs/api/auth.md §1）：`POST /auth/reauth` 密码验证通过后返回
 * `{ reauthToken, expiresInSeconds }`（默认 5 分钟、绑定当前会话）；敏感接口通过
 * 请求头 `X-Reauth-Token` 携带，缺失 / 过期 / 换会话一律 40304。
 *
 * 前端约束（AGENTS.md 第 5 节）：reauthToken 只保存在内存（含到期时间戳），
 * 不写 localStorage / sessionStorage / IndexedDB / URL / 日志；窗口内复用，
 * 过期或收到 40304 后作废重新验证。
 */

/** 敏感接口返回「需要二次认证」的错误码（docs/api/README.md）。 */
export const REAUTH_REQUIRED_CODE = 40_304;

/** 敏感接口携带二次认证令牌的请求头。 */
export const REAUTH_HEADER = 'X-Reauth-Token';

/** `POST /auth/reauth` 响应。 */
export interface ReauthResult {
  expiresInSeconds: number;
  reauthToken: string;
}

/**
 * reauthToken 的内存保存（60 秒明文显示计时同思路，这里窗口默认 5 分钟）。
 * 每个应用一个实例；过期即失效，不提供持久化。
 */
export class ReauthTokenKeeper {
  private expiresAtMs = 0;
  private token: null | string = null;

  /** 作废（收到 40304 时调用，下次敏感操作重新验证）。 */
  clear(): void {
    this.token = null;
    this.expiresAtMs = 0;
  }

  /** 收下新签发的令牌（含服务端给的窗口，秒）。 */
  store(reauthToken: string, expiresInSeconds: number): void {
    this.token = reauthToken;
    this.expiresAtMs = Date.now() + expiresInSeconds * 1000;
  }

  /** 仍有效的令牌；过期或没有返回 null（留 1 秒安全余量，避免拿刚过期的令牌发请求）。 */
  valid(): null | string {
    if (this.token === null) return null;
    if (Date.now() >= this.expiresAtMs - 1000) {
      this.token = null;
      this.expiresAtMs = 0;
      return null;
    }
    return this.token;
  }
}

/** 用户取消二次认证（关掉密码框）：页面静默中止，不当错误弹toast。 */
export class ReauthCancelledError extends Error {
  constructor() {
    super('已取消身份验证');
    this.name = 'ReauthCancelledError';
  }
}

export function isReauthCancelled(error: unknown): boolean {
  return error instanceof ReauthCancelledError;
}

/**
 * 判断错误是否「需要二次认证」（40304）。兼容统一响应信封被 axios 拆包后的
 * 几种形态：`{ response: { data: { code } } }`、`{ data: { code } }`、`{ code }`。
 */
export function isReauthRequired(error: unknown): boolean {
  if (typeof error !== 'object' || error === null) return false;
  const candidate = error as {
    code?: unknown;
    data?: { code?: unknown };
    response?: { data?: { code?: unknown } };
  };
  const code =
    candidate.response?.data?.code ?? candidate.data?.code ?? candidate.code;
  return code === REAUTH_REQUIRED_CODE;
}

/** 敏感请求要带的附加请求头。 */
export function reauthHeaders(
  reauthToken: null | string,
): Record<string, string> {
  return reauthToken ? { [REAUTH_HEADER]: reauthToken } : {};
}

/** runWithReauth 的依赖注入：密码框与验证口由各应用接线（requestClient + ReauthModal）。 */
export interface ReauthFlowDeps {
  /** 内存里仍有效的令牌（无则 null）。 */
  currentToken(): null | string;
  /** 令牌中途失效（40304）时作废。 */
  onInvalidated(): void;
  /** 签发成功后收下（内存保存）。 */
  onIssued(result: ReauthResult): void;
  /**
   * 弹密码框并完成验证（POST /auth/reauth）：成功返回结果、用户取消返回 null。
   * 验证失败（42200 等）由实现自行处理（如保持弹窗让用户重试），不向外抛。
   */
  promptVerified(): Promise<null | ReauthResult>;
}

/**
 * 执行一个敏感操作：窗口内有令牌直接带头发起；没有先弹密码框验证。
 * 首个令牌中途失效（服务端 40304）时作废并重新验证一次（防死循环只重试一次）。
 * 用户取消 → 抛 ReauthCancelledError（页面静默中止）。
 */
export async function runWithReauth<T>(
  action: (headers: Record<string, string>) => Promise<T>,
  deps: ReauthFlowDeps,
): Promise<T> {
  let token = deps.currentToken();
  if (!token) {
    const result = await deps.promptVerified();
    if (result === null) {
      throw new ReauthCancelledError();
    }
    deps.onIssued(result);
    token = result.reauthToken;
  }
  try {
    return await action(reauthHeaders(token));
  } catch (error) {
    if (!isReauthRequired(error)) {
      throw error;
    }
    // 窗口内令牌被服务端拒绝（如恰好过期）：作废后重新验证一次
    deps.onInvalidated();
    const refreshed = await deps.promptVerified();
    if (refreshed === null) {
      throw error;
    }
    deps.onIssued(refreshed);
    return action(reauthHeaders(refreshed.reauthToken));
  }
}
