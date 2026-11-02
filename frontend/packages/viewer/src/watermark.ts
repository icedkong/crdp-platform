/**
 * E10-T04 / A-ATT-005、tech-plan §10「预览」：可见水印（不可关闭）。
 *
 * 水印文本「姓名（账号）· 医院名称 · 预览时间（精确到分钟）」（components.md §12）， 由 canvas 生成平铺背景图覆盖在预览层之上：
 * `pointer-events: none`（盖住内容但可操作）、旋转 -20°、透明度约 0.15。 接受截屏风险，水印 + 审计作为威慑与追溯手段（tech-plan §10 已确认口径）。
 *
 * 防篡改：水印节点不进组件 vdom，由本模块直接管理 DOM；MutationObserver 观察 容器 childList 与水印节点 style/class，被删或改时**立即重绘**；另有每
 * 2 秒一次的自检兜底（观察者被禁用 / 未触发时同步恢复）。 没有关闭水印的任何入口——组件也不暴露关闭 prop（验收：水印不可关闭）。
 */

import type { WatermarkViewer } from './typings';

import { colors } from '@crdp/tokens';

export type { WatermarkViewer };

/** 平铺参数（components.md §12「推断，待确认」值：-20°、透明度约 0.15）。 */
export const WATERMARK_STYLE = {
  color: colors.text,
  font: '14px sans-serif',
  opacity: 0.15,
  rotateDeg: -20,
  /** 自检间隔（components.md §12：每 2 秒自检一次）。 */
  selfCheckIntervalMs: 2000,
  tileHeight: 180,
  tileWidth: 320,
  zIndex: 4096,
} as const;

/** 水印文本：姓名（账号）· 医院名称 · 预览时间（精确到分钟）。 */
export function watermarkText(
  viewer: WatermarkViewer,
  at: Date = new Date(),
): string {
  const pad = (value: number) => String(value).padStart(2, '0');
  const minute = `${at.getFullYear()}-${pad(at.getMonth() + 1)}-${pad(at.getDate())} ${pad(
    at.getHours(),
  )}:${pad(at.getMinutes())}`;
  const tenant = viewer.tenantName?.trim() || '未知医院';
  return `${viewer.displayName}（${viewer.account}）· ${tenant}· ${minute}`;
}

/** 画一张水印平铺 tile（dataURL）。canvas 2d 不可用（如测试环境）返回 null，调用方降级为文本层。 */
export function renderWatermarkTile(text: string): null | string {
  if (typeof document === 'undefined') {
    return null;
  }
  const canvas = document.createElement('canvas');
  canvas.width = WATERMARK_STYLE.tileWidth;
  canvas.height = WATERMARK_STYLE.tileHeight;
  const context = canvas.getContext('2d');
  if (!context) {
    return null;
  }
  context.clearRect(0, 0, canvas.width, canvas.height);
  context.font = WATERMARK_STYLE.font;
  context.fillStyle = WATERMARK_STYLE.color;
  context.globalAlpha = WATERMARK_STYLE.opacity;
  context.translate(canvas.width / 2, canvas.height / 2);
  context.rotate((WATERMARK_STYLE.rotateDeg * Math.PI) / 180);
  context.textAlign = 'center';
  context.fillText(text, 0, 0);
  return canvas.toDataURL('image/png');
}

export interface WatermarkGuardOptions {
  /** 自检间隔毫秒（默认 2 秒；测试可调大禁用）。 */
  intervalMs?: number;
  /** 平铺图工厂（默认 {@link renderWatermarkTile}；测试注入替身）。 */
  makeTileUrl?: (text: string) => null | string;
  text: string;
}

/** 水印防篡改守卫：redraw / selfCheck / destroy。 */
export interface WatermarkGuard {
  /** 移除水印并停止守护（预览关闭时调用）。 */
  destroy(): void;
  /** 当前水印节点（只读检视用）。 */
  overlay(): HTMLDivElement;
  /** 强制重绘（外部不应调用——水印不提供任何关闭或换肤入口，仅防篡改内部使用）。 */
  redraw(): void;
  /** 同步自检：水印被删或被改立即恢复（定时器与测试直用）。 */
  selfCheck(): void;
}

/**
 * 在容器上挂一层不可关闭的水印。容器需自带定位（组件样式 position: relative）。
 * 返回守卫；卸载预览时必须 destroy，防止定时器与观察器泄漏。
 */
export function createWatermarkGuard(
  container: HTMLElement,
  options: WatermarkGuardOptions,
): WatermarkGuard {
  const makeTile = options.makeTileUrl ?? renderWatermarkTile;
  let overlay = buildOverlay(options.text, makeTile);
  container.append(overlay);
  let stopped = false;

  const observer = new MutationObserver(() => {
    if (stopped) {
      return;
    }
    // 容器 childList（水印被删）或水印节点 attributes（style/class 被改）→ 重绘
    if (!container.contains(overlay) || isTampered(overlay)) {
      redraw();
    }
  });
  observe();

  const timer = setInterval(
    () => selfCheck(),
    options.intervalMs ?? WATERMARK_STYLE.selfCheckIntervalMs,
  );

  function buildOverlay(
    text: string,
    tile: (text: string) => null | string,
  ): HTMLDivElement {
    const node = document.createElement('div');
    node.dataset.watermark = '1';
    node.setAttribute('aria-hidden', 'true');
    node.style.position = 'absolute';
    node.style.inset = '0';
    node.style.zIndex = String(WATERMARK_STYLE.zIndex);
    node.style.pointerEvents = 'none';
    node.style.overflow = 'hidden';
    const tileUrl = tile(text);
    if (tileUrl) {
      node.style.backgroundImage = `url(${tileUrl})`;
      node.style.backgroundRepeat = 'repeat';
      node.style.backgroundSize = `${WATERMARK_STYLE.tileWidth}px ${WATERMARK_STYLE.tileHeight}px`;
    } else {
      // canvas 不可用的降级（生产浏览器不会走到）：仍保留可见文本水印
      node.style.display = 'flex';
      node.style.alignItems = 'center';
      node.style.justifyContent = 'center';
      const label = document.createElement('span');
      label.textContent = text;
      label.style.transform = `rotate(${WATERMARK_STYLE.rotateDeg}deg)`;
      label.style.opacity = String(WATERMARK_STYLE.opacity * 4);
      node.append(label);
    }
    return node;
  }

  /** 节点被改的判定：关键样式偏离重绘基准（display/visibility 隐藏、pointer-events 恢复、背景被抹）。 */
  function isTampered(node: HTMLDivElement): boolean {
    const style = node.style;
    if (
      style.display === 'none' ||
      style.visibility === 'hidden' ||
      (style.pointerEvents !== '' && style.pointerEvents !== 'none') ||
      node.dataset.watermark !== '1'
    ) {
      return true;
    }
    if (
      style.backgroundImage !== '' &&
      node.querySelector('span') === null &&
      !style.backgroundImage.startsWith('url(')
    ) {
      return true;
    }
    return false;
  }

  function observe() {
    observer.observe(container, { childList: true });
    observer.observe(overlay, {
      // data-watermark 也要盯：isTampered 把标记被清视为篡改（E10-T06 对抗用例）
      attributeFilter: ['class', 'style', 'data-watermark'],
      attributes: true,
    });
  }

  function redraw(): void {
    if (stopped) {
      return;
    }
    observer.disconnect();
    overlay.remove();
    overlay = buildOverlay(options.text, makeTile);
    container.append(overlay);
    observe();
  }

  function selfCheck(): void {
    if (stopped) {
      return;
    }
    if (!container.contains(overlay) || isTampered(overlay)) {
      redraw();
    }
  }

  return {
    overlay: () => overlay,
    redraw,
    selfCheck,
    destroy() {
      stopped = true;
      observer.disconnect();
      clearInterval(timer);
      overlay.remove();
    },
  };
}
