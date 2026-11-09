/**
 * E22-T11 / PT-SELF-06（低危，修复）：外部链接打开规则。
 *
 * 平台端站内信等入口打开后端下发的链接时：
 * 1. 必须 `noopener,noreferrer`——不带 noopener 的 `window.open(link, '_blank')`
 *    会让新页拿到 window.opener，可把原标签页改向钓鱼页（反向标签钓鱼）；
 * 2. 只放行 http(s) 绝对地址（大小写不敏感）——window.open 的第一个参数可被
 *    构造成 `javascript:` / `data:` 伪协议，相对路径应走 router.push 站内跳转。
 * open 参数可注入（单测），默认 window.open。
 */

/** 外部 http(s) 绝对地址判定（大小写不敏感；其余一律不是「外链」）。 */
export function isExternalHttpLink(link: null | string | undefined): boolean {
  if (!link) return false;
  return /^https?:\/\//iu.test(link.trim());
}

/**
 * 打开外部链接：http(s) 以 `_blank` + `noopener,noreferrer` 打开并返回 true；
 * 伪协议、协议相对、相对路径不打开并返回 false（调用方对站内路径走 router）。
 */
export function openExternalLink(
  link: string,
  open: (url: string, target: string, features: string) => unknown = (
    url,
    target,
    features,
  ) => window.open(url, target, features),
): boolean {
  if (!isExternalHttpLink(link)) return false;
  open(link.trim(), '_blank', 'noopener,noreferrer');
  return true;
}
