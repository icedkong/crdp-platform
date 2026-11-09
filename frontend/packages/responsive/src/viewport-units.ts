/**
 * E24-T07 真机兼容（iOS Safari）：100vh 按「大视口」取值（地址栏 / 工具栏收起
 * 后的高度），iPhone 上 Safari 工具栏可见时 100vh 比可视区域高一截——固定高度 /
 * maxHeight 用 100vh 的弹窗、抽屉底部内容会被工具栏盖住。dvh（dynamic viewport
 * height）跟随可视区域：iOS Safari 15.4+、Chromium 108+ 支持；旧内核（微信 XWeb
 * 等）不认，回退 100vh 与原行为一致、不劣化。
 */

/**
 * 当前内核可用的视口高度单位：支持 dvh 返回 'dvh'，否则 'vh'。
 * SSR 安全（无 window 时回退 'vh'）。
 */
export function viewportHeightUnit(): 'dvh' | 'vh' {
  if (typeof window === 'undefined') return 'vh';
  const css = (
    window as { CSS?: { supports?: (...args: string[]) => boolean } }
  ).CSS;
  if (typeof css?.supports !== 'function') return 'vh';
  return css.supports('max-height', '100dvh') ? 'dvh' : 'vh';
}
