/**
 * E24-T08 / NFR-RESP-001、002 · 真机矩阵档案（唯一来源，与文档
 * docs/testing/device-matrix.md 一节一档对齐）。
 *
 * tech-plan §15 兼容范围：iOS Safari（近两个大版本）、安卓 Chrome 与主流
 * 厂商浏览器、微信内置浏览器、iPad 与主流安卓平板。这里按「设备类 × 代表
 * CSS 视口」落成档案：档位判定只看 CSS 视口宽度（与 useBreakpoint / tokens
 * breakpoints 同口径），DPR、指针类型与引擎能力供测试模拟与文档核对。
 *
 * 真机上的系统 / 浏览器大版本随时间漂移，执行时按文档「版本校准」一节取
 * 执行当期的近两个大版本；本档案锁定的是设备类的视口与指针口径（相对稳定）
 * 与旧内核能力面（E24-T07 修复基线：Chromium <98 无 structuredClone、<93 无
 * Object.hasOwn、<92 无 Array.prototype.at、<108 无 dvh）。
 */
import type { DeviceKind } from '@crdp/tokens';

/** 引擎能力面（E24-T07 兼容修复针对的四个 API；旧内核 = 微信 XWeb 基线）。 */
export interface DeviceEngineCapabilities {
  /** Array.prototype.at（Chromium 92+） */
  arrayPrototypeAt: boolean;
  /** CSS dvh 单位（Safari 15.4+ / Chromium 108+） */
  dynamicViewportUnit: boolean;
  /** Object.hasOwn（Chromium 93+） */
  objectHasOwn: boolean;
  /** window.structuredClone（Chromium 98+） */
  structuredClone: boolean;
}

/** 真机矩阵里的一档设备（设备类 × 代表视口）。 */
export interface DeviceProfile {
  /** pointer: coarse（触控）还是 fine（鼠标 / 触控板） */
  coarsePointer: boolean;
  /** 代表 CSS 视口高（px，竖屏） */
  cssHeight: number;
  /** 代表 CSS 视口宽（px，竖屏） */
  cssWidth: number;
  /** 设备像素比 */
  dpr: number;
  /** 引擎能力面 */
  engine: DeviceEngineCapabilities;
  /** 平台分组（iOS / 安卓 / 微信 / 桌面对照） */
  group: 'android' | 'desktop' | 'ios' | 'wechat';
  /** 档案键（文档矩阵行的锚点，稳定不改名） */
  key: string;
  /** 该设备 CSS 视口宽度落入的档位（deviceOf(cssWidth) 必须等于它） */
  kind: DeviceKind;
  /** 简体中文标签（与文档矩阵行同名） */
  label: string;
}

const MODERN_ENGINE: DeviceEngineCapabilities = {
  arrayPrototypeAt: true,
  dynamicViewportUnit: true,
  objectHasOwn: true,
  structuredClone: true,
};

const LEGACY_ENGINE: DeviceEngineCapabilities = {
  arrayPrototypeAt: false,
  dynamicViewportUnit: false,
  objectHasOwn: false,
  structuredClone: false,
};

/**
 * 真机矩阵（六档）：手机三档（iOS Safari / 安卓 Chrome / 微信内置浏览器）、
 * 平板两档（iPad Safari / 安卓平板 Chrome）、桌面对照一档。
 */
export const DEVICE_PROFILES: readonly DeviceProfile[] = [
  {
    coarsePointer: true,
    cssHeight: 844,
    cssWidth: 390,
    dpr: 3,
    engine: MODERN_ENGINE,
    group: 'ios',
    key: 'iphone-safari',
    kind: 'mobile',
    label: 'iPhone · Safari（近两个大版本 iOS）',
  },
  {
    coarsePointer: true,
    cssHeight: 1180,
    cssWidth: 820,
    dpr: 2,
    engine: MODERN_ENGINE,
    group: 'ios',
    key: 'ipad-safari',
    kind: 'tablet',
    label: 'iPad · Safari（竖屏）',
  },
  {
    coarsePointer: true,
    cssHeight: 915,
    cssWidth: 412,
    dpr: 2.625,
    engine: MODERN_ENGINE,
    group: 'android',
    key: 'android-phone-chrome',
    kind: 'mobile',
    label: '安卓手机 · Chrome / 主流厂商浏览器',
  },
  {
    coarsePointer: true,
    cssHeight: 915,
    cssWidth: 412,
    dpr: 3,
    engine: LEGACY_ENGINE,
    group: 'wechat',
    key: 'android-phone-wechat',
    kind: 'mobile',
    label: '安卓手机 · 微信内置浏览器（旧内核）',
  },
  {
    coarsePointer: true,
    cssHeight: 1280,
    cssWidth: 800,
    dpr: 2,
    engine: MODERN_ENGINE,
    group: 'android',
    key: 'android-tablet-chrome',
    kind: 'tablet',
    label: '安卓平板 · Chrome（竖屏）',
  },
  {
    coarsePointer: false,
    cssHeight: 1080,
    cssWidth: 1920,
    dpr: 1,
    engine: MODERN_ENGINE,
    group: 'desktop',
    key: 'desktop-chrome',
    kind: 'desktop',
    label: '桌面对照 · Chrome / Edge（1920×1080）',
  },
];

type MutableWindow = typeof window & {
  CSS?: { supports?: (...args: unknown[]) => boolean };
};

/**
 * 把矩阵档案应用到当前浏览器环境（happy-dom / jsdom 测试用；真机上是环境
 * 本身）：视口尺寸、DPR、pointer 查询与旧内核能力面（摘除 / 接回
 * structuredClone、Object.hasOwn、Array.prototype.at，dvh 支持探测）。
 *
 * 返回还原函数：必须成对调用，保证宿主环境与后续用例不受串扰。
 */
export function applyDeviceProfile(profile: DeviceProfile): () => void {
  const target = window as MutableWindow;
  const innerWidth = target.innerWidth;
  const innerHeight = target.innerHeight;
  const dpr = target.devicePixelRatio;
  const realMatchMedia = target.matchMedia.bind(target);
  // happy-dom 的 window.CSS 是每次访问返回新对象的 getter（真机浏览器是稳定的
  // 单例），所以不能只改属性——须换成稳定的桩对象，还原时恢复原描述符。
  const cssOwnDescriptor = Object.getOwnPropertyDescriptor(target, 'CSS');
  const cssFirst = target.CSS;
  const cssProto = cssFirst ? Object.getPrototypeOf(cssFirst) : null;
  const realSupports = cssFirst?.supports?.bind(cssFirst);
  const hasStructuredClone = 'structuredClone' in target;
  const structuredCloneFn = target.structuredClone;
  const hasHasOwn = 'hasOwn' in Object;
  const hasOwnFn = Object.hasOwn;
  const hasAt = 'at' in Array.prototype;
  const atFn = Array.prototype.at;

  target.innerWidth = profile.cssWidth;
  target.innerHeight = profile.cssHeight;
  target.devicePixelRatio = profile.dpr;

  // pointer / 宽度查询按档案取值，其余查询回落宿主实现
  target.matchMedia = ((query: string) => {
    const coarse = query.includes('pointer: coarse');
    if (coarse) return stubQuery(query, profile.coarsePointer);
    if (query.includes('pointer: fine')) {
      return stubQuery(query, !profile.coarsePointer);
    }
    const max = /max-width:\s*(\d+)px/.exec(query);
    if (max) return stubQuery(query, profile.cssWidth <= Number(max[1]));
    const min = /min-width:\s*(\d+)px/.exec(query);
    if (min) return stubQuery(query, profile.cssWidth >= Number(min[1]));
    return realMatchMedia(query);
  }) as typeof window.matchMedia;

  if (profile.engine.structuredClone) {
    if (!hasStructuredClone && typeof structuredClone !== 'undefined') {
      // 宿主缺 structuredClone 时补挂全局实现（现代档模拟需要它存在）
      (target as { structuredClone?: unknown }).structuredClone =
        structuredClone;
    }
  } else if (hasStructuredClone) {
    delete (target as { structuredClone?: unknown }).structuredClone;
  }
  setFlag(Object, 'hasOwn', profile.engine.objectHasOwn, hasHasOwn, hasOwnFn);
  setFlag(Array.prototype, 'at', profile.engine.arrayPrototypeAt, hasAt, atFn);

  if (cssFirst && cssProto) {
    const cssStub = Object.create(cssProto) as MutableWindow['CSS'];
    cssStub.supports = ((...args: unknown[]) => {
      if (
        args.length === 2 &&
        args[1] === '100dvh' &&
        typeof args[0] === 'string' &&
        args[0].includes('height')
      ) {
        return profile.engine.dynamicViewportUnit;
      }
      return realSupports ? realSupports(...args) : false;
    }) as typeof target.CSS.supports;
    Object.defineProperty(target, 'CSS', {
      configurable: true,
      get: () => cssStub,
    });
  }

  return () => {
    target.innerWidth = innerWidth;
    target.innerHeight = innerHeight;
    target.devicePixelRatio = dpr;
    target.matchMedia = realMatchMedia;
    if (cssOwnDescriptor) {
      Object.defineProperty(target, 'CSS', cssOwnDescriptor);
    } else if (cssFirst && cssProto) {
      delete (target as { CSS?: unknown }).CSS;
    }
    if (hasStructuredClone) {
      (target as { structuredClone?: unknown }).structuredClone =
        structuredCloneFn;
    } else if ('structuredClone' in target) {
      delete (target as { structuredClone?: unknown }).structuredClone;
    }
    restoreFlag(Object, 'hasOwn', hasHasOwn, hasOwnFn);
    restoreFlag(Array.prototype, 'at', hasAt, atFn);
  };
}

function stubQuery(media: string, matches: boolean): MediaQueryList {
  return {
    addEventListener: () => {},
    addListener: () => {},
    media,
    matches,
    removeEventListener: () => {},
    removeListener: () => {},
  } as unknown as MediaQueryList;
}

function setFlag(
  owner: object,
  name: string,
  enabled: boolean,
  originallyPresent: boolean,
  originalValue: unknown,
) {
  if (enabled) {
    if (!originallyPresent && originalValue !== undefined) {
      Object.defineProperty(owner, name, {
        configurable: true,
        enumerable: false,
        value: originalValue,
        writable: true,
      });
    }
  } else if (name in owner) {
    Reflect.deleteProperty(owner, name);
  }
}

function restoreFlag(
  owner: object,
  name: string,
  originallyPresent: boolean,
  originalValue: unknown,
) {
  if (originallyPresent) {
    Object.defineProperty(owner, name, {
      configurable: true,
      enumerable: false,
      value: originalValue,
      writable: true,
    });
  } else if (name in owner) {
    Reflect.deleteProperty(owner, name);
  }
}
