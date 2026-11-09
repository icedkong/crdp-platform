/**
 * E22-T11 / PT-SELF-07（信息，加固）：登录回跳目标白名单。
 *
 * `?redirect=` 来自未认证用户可任意构造的 URL 查询串。守卫此前直接
 * `decodeURIComponent(redirect)` 当跳转目标——vue-router 的字符串定位本身会把
 * 绝对地址当站内路径（不出站），但协议相对形式（`//host`）会走到 history
 * pushState 抛 SecurityError（体验异常路径，也留着被框架行为变化放开的口子）。
 * 这里统一收口：只放行「单个斜杠开头」的站内路径，其余（外站绝对地址、
 * 协议相对、反斜杠变体、不以斜杠开头的相对片段）一律回落 fallback（首页）。
 */

/** 解码一次；畸形百分号编码不抛异常，按原值处理（原值仍要过白名单）。 */
function decodeOnce(value: string): string {
  try {
    return decodeURIComponent(value);
  } catch {
    return value;
  }
}

/**
 * 登录后回跳目标：candidate 合法（单斜杠开头的站内路径）则返回修剪 + 解码后的
 * 值，否则返回 fallback。
 */
export function resolveLoginRedirect(
  candidate: null | string | undefined,
  fallback: string,
): string {
  if (!candidate) return fallback;
  const target = decodeOnce(candidate.trim());
  // 不以 / 开头：外站绝对地址或相对片段，不放行
  if (!target.startsWith('/')) return fallback;
  // 协议相对（//host）与反斜杠变体（/\host、\\host）不放行
  if (target.startsWith('//') || target.startsWith('/\\')) return fallback;
  return target;
}
