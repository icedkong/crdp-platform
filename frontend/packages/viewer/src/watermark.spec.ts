import { describe, expect, it, vi } from 'vitest';

import {
  createWatermarkGuard,
  WATERMARK_STYLE,
  watermarkText,
} from './watermark';

const viewer = {
  account: 'doctor.demo',
  displayName: '王医生（示例）',
  tenantName: '医院 A（示例租户）',
};

/** 本地时间 2026-10-31 14:05:33 → 文本精确到分钟（components.md §12 例）。 */
const openedAt = new Date(2026, 9, 31, 14, 5, 33);

describe('e10-t04 水印文本', () => {
  it('格式：姓名（账号）· 医院名称 · 预览时间（精确到分钟）', () => {
    expect(watermarkText(viewer, openedAt)).toBe(
      '王医生（示例）（doctor.demo）· 医院 A（示例租户）· 2026-10-31 14:05',
    );
  });

  it('医院名称缺失时显示占位，不出现 undefined', () => {
    const text = watermarkText({ ...viewer, tenantName: null }, openedAt);
    expect(text).toBe(
      '王医生（示例）（doctor.demo）· 未知医院· 2026-10-31 14:05',
    );
  });

  it('分钟补零（09:05 而不是 9:5）', () => {
    expect(watermarkText(viewer, new Date(2026, 0, 2, 9, 5, 0))).toContain(
      '2026-01-02 09:05',
    );
  });
});

describe('e10-t04 水印层（不可关闭 + 防篡改）', () => {
  const tile = 'data:image/png;base64,TILE';

  function mountGuard() {
    const container = document.createElement('div');
    document.body.append(container);
    const guard = createWatermarkGuard(container, {
      makeTileUrl: () => tile,
      text: watermarkText(viewer, openedAt),
    });
    return { container, guard };
  }

  it('平铺覆盖在预览层之上：pointer-events none、背景为水印图、铺满容器', () => {
    const { container, guard } = mountGuard();
    const overlay = guard.overlay();
    expect(container.contains(overlay)).toBe(true);
    expect(overlay.dataset.watermark).toBe('1');
    expect(overlay.style.pointerEvents).toBe('none');
    expect(overlay.style.backgroundImage).toContain(tile);
    expect(overlay.style.backgroundRepeat).toBe('repeat');
    expect(overlay.style.zIndex).not.toBe('');
    expect(Number(overlay.style.zIndex)).toBeGreaterThanOrEqual(1000);
    guard.destroy();
  });

  it('删除水印节点后被立即重绘（MutationObserver，水印不可关闭）', async () => {
    const { container, guard } = mountGuard();
    const first = guard.overlay();
    first.remove();
    await new Promise((resolve) => setTimeout(resolve, 10));
    const restored = guard.overlay();
    expect(container.contains(first)).toBe(false);
    expect(container.contains(restored)).toBe(true);
    expect(restored).not.toBe(first);
    expect(restored.style.backgroundImage).toContain(tile);
    guard.destroy();
  });

  it('改水印样式（display:none）后被恢复', async () => {
    const { container, guard } = mountGuard();
    guard.overlay().style.setProperty('display', 'none');
    await new Promise((resolve) => setTimeout(resolve, 10));
    const restored = guard.overlay();
    expect(container.contains(restored)).toBe(true);
    expect(restored.style.display).not.toBe('none');
    expect(restored.style.pointerEvents).toBe('none');
    guard.destroy();
  });

  it('每 2 秒自检：异步观察者没来得及触发时同步兜底重绘', () => {
    const { container, guard } = mountGuard();
    guard.overlay().remove();
    expect(container.querySelector('[data-watermark]')).toBeNull();
    guard.selfCheck();
    expect(container.querySelector('[data-watermark]')).not.toBeNull();
    guard.destroy();
  });

  it('destroy 后停止自检与重绘（预览关闭，水印随之移除）', () => {
    vi.useFakeTimers();
    try {
      const { container, guard } = mountGuard();
      guard.destroy();
      expect(container.querySelector('[data-watermark]')).toBeNull();
      guard.overlay().remove();
      vi.advanceTimersByTime(WATERMARK_STYLE.selfCheckIntervalMs + 50);
      expect(container.querySelector('[data-watermark]')).toBeNull();
    } finally {
      vi.useRealTimers();
    }
  });
});

describe('e10-t06 水印不可关（对抗路径：换着法子关都被恢复）', () => {
  const tile = 'data:image/png;base64,TILE-E10T06';

  function mountGuard() {
    const container = document.createElement('div');
    document.body.append(container);
    const guard = createWatermarkGuard(container, {
      makeTileUrl: () => tile,
      text: '王医生（示例）（doctor.demo）· 医院 A（示例租户）· 2026-10-31 14:05',
    });
    return { container, guard };
  }

  /** 篡改后等 MutationObserver 微任务触发，取恢复出的新水印节点（断言在各用例体内，expect-expect 要求）。 */
  async function restoredAfterTamper(
    container: HTMLElement,
    guard: ReturnType<typeof createWatermarkGuard>,
  ) {
    await new Promise((resolve) => setTimeout(resolve, 10));
    const restored = container.querySelector<HTMLElement>('[data-watermark]');
    guard.destroy();
    return { guard, restored };
  }

  it('visibility:hidden 隐藏水印 → 恢复', async () => {
    const { container, guard } = mountGuard();
    const tampered = guard.overlay();
    tampered.style.setProperty('visibility', 'hidden');
    const { restored } = await restoredAfterTamper(container, guard);
    expect(restored).not.toBeNull();
    expect(restored).not.toBe(tampered);
    expect(restored?.style.backgroundImage).toContain(tile);
    expect(restored?.style.visibility).not.toBe('hidden');
  });

  it('抹掉背景水印图（backgroundImage=none）→ 恢复', async () => {
    const { container, guard } = mountGuard();
    const tampered = guard.overlay();
    tampered.style.setProperty('background-image', 'none');
    const { restored } = await restoredAfterTamper(container, guard);
    expect(restored).not.toBeNull();
    expect(restored).not.toBe(tampered);
    expect(restored?.style.backgroundImage).toContain(tile);
  });

  it('清掉 data-watermark 标记冒充普通图层 → 恢复', async () => {
    const { container, guard } = mountGuard();
    const tampered = guard.overlay();
    delete tampered.dataset.watermark;
    const { restored } = await restoredAfterTamper(container, guard);
    expect(restored).not.toBeNull();
    expect(restored).not.toBe(tampered);
    expect(restored?.dataset.watermark).toBe('1');
    expect(restored?.style.backgroundImage).toContain(tile);
  });

  it('把水印节点挪出容器（挂到 body）→ 恢复', async () => {
    const { container, guard } = mountGuard();
    const moved = guard.overlay();
    document.body.append(moved);
    const { restored } = await restoredAfterTamper(container, guard);
    expect(restored).not.toBeNull();
    expect(restored).not.toBe(moved);
    expect(container.contains(moved)).toBe(false);
    moved.remove();
  });

  it('整个容器内容被清空（innerHTML=""）→ 恢复', async () => {
    const { container, guard } = mountGuard();
    const first = guard.overlay();
    container.innerHTML = '';
    const { restored } = await restoredAfterTamper(container, guard);
    expect(restored).not.toBeNull();
    expect(restored).not.toBe(first);
    expect(restored?.style.backgroundImage).toContain(tile);
  });

  it('观察者被禁用时，自检兜底同样恢复样式篡改（visibility）', () => {
    const { container, guard } = mountGuard();
    guard.overlay().style.setProperty('visibility', 'hidden');
    guard.selfCheck();
    const overlay = guard.overlay();
    expect(container.contains(overlay)).toBe(true);
    expect(overlay.style.visibility).not.toBe('hidden');
    expect(overlay.style.backgroundImage).toContain(tile);
    guard.destroy();
  });
});
