/**
 * E24-T08 / NFR-RESP-001、002 · 真机矩阵档案单测（文档 docs/testing/device-matrix.md）。
 *
 * 锁定矩阵档案与断点令牌的一致性（每台设备的 CSS 视口宽度必须落进它声称的
 * 档位：<768 手机、768–1279 平板、≥1280 桌面），以及 applyDeviceProfile
 * 对旧内核引擎能力的模拟 / 还原（E24-T07 修复所依据的缺失 API 面）。
 */
import type { DeviceKind } from '@crdp/tokens';

import type { DeviceProfile } from './device-matrix';

import { deviceOf } from '@crdp/tokens';
import { afterEach, describe, expect, it } from 'vitest';

import { applyDeviceProfile, DEVICE_PROFILES } from './device-matrix';
import { viewportHeightUnit } from './viewport-units';

const restores: Array<() => void> = [];

afterEach(() => {
  for (const restore of restores.splice(0)) restore();
});

function apply(profile: DeviceProfile) {
  const restore = applyDeviceProfile(profile);
  restores.push(restore);
  return restore;
}

/** 档位 → CSS 视口宽度闭区间（design-system §12：手机 <768、平板 768–1279、桌面 ≥1280） */
const WIDTH_BOUNDS: Record<DeviceKind, [number, number]> = {
  desktop: [1280, Number.POSITIVE_INFINITY],
  mobile: [0, 767],
  tablet: [768, 1279],
};

describe('真机矩阵档案（tech-plan §15 兼容范围）', () => {
  it('覆盖 iOS 手机 / iOS 平板 / 安卓手机 Chrome / 安卓手机微信 / 安卓平板 / 桌面对照六档', () => {
    expect(DEVICE_PROFILES.map((p) => p.key)).toEqual([
      'iphone-safari',
      'ipad-safari',
      'android-phone-chrome',
      'android-phone-wechat',
      'android-tablet-chrome',
      'desktop-chrome',
    ]);
  });

  it.each(DEVICE_PROFILES.map((p) => [p.key, p] as const))(
    '%s：视口宽度落进声称档位（断点 768 / 1280）',
    (_key, profile) => {
      expect(deviceOf(profile.cssWidth)).toBe(profile.kind);
      // 档位边界与矩阵一致性：手机 <768、平板 768–1279、桌面 ≥1280
      const [min, max] = WIDTH_BOUNDS[profile.kind];
      expect(profile.cssWidth).toBeGreaterThanOrEqual(min);
      expect(profile.cssWidth).toBeLessThanOrEqual(max);
    },
  );

  it.each(DEVICE_PROFILES.map((p) => [p.key, p] as const))(
    '%s：手机与平板为触控（pointer: coarse）、桌面为精细指针',
    (_key, profile) => {
      expect(profile.coarsePointer).toBe(profile.kind !== 'desktop');
    },
  );

  it('微信内置浏览器档位标注为旧内核（E24-T07 修复针对的缺失 API 面）', () => {
    const wechat = DEVICE_PROFILES.find(
      (p) => p.key === 'android-phone-wechat',
    );
    expect(wechat?.engine).toEqual({
      arrayPrototypeAt: false,
      dynamicViewportUnit: false,
      objectHasOwn: false,
      structuredClone: false,
    });
  });

  it('微信档以外全部为现代内核（近两个大版本基线，四个能力齐备）', () => {
    const modern = DEVICE_PROFILES.filter(
      (p) => p.key !== 'android-phone-wechat',
    );
    expect(modern.length).toBe(DEVICE_PROFILES.length - 1);
    for (const profile of modern) {
      expect(profile.engine).toEqual({
        arrayPrototypeAt: true,
        dynamicViewportUnit: true,
        objectHasOwn: true,
        structuredClone: true,
      });
    }
  });
});

describe('applyDeviceProfile（矩阵档案 → 浏览器环境模拟）', () => {
  it.each(DEVICE_PROFILES.map((p) => [p.key, p] as const))(
    '%s：innerWidth / devicePixelRatio / pointer 查询按档案生效',
    (_key, profile) => {
      apply(profile);
      expect(window.innerWidth).toBe(profile.cssWidth);
      expect(window.innerHeight).toBe(profile.cssHeight);
      expect(window.devicePixelRatio).toBe(profile.dpr);
      expect(window.matchMedia('(pointer: coarse)').matches).toBe(
        profile.coarsePointer,
      );
      // 宽度查询按档案宽度取值（useBreakpoint 的三组监听查询走这里）
      expect(
        window.matchMedia(`(max-width: ${profile.cssWidth}px)`).matches,
      ).toBe(true);
      expect(
        window.matchMedia(`(max-width: ${profile.cssWidth - 1}px)`).matches,
      ).toBe(false);
    },
  );

  it('微信旧内核档：structuredClone / Object.hasOwn / Array.at 被摘除，dvh 不支持', () => {
    const wechat = DEVICE_PROFILES.find(
      (p) => p.key === 'android-phone-wechat',
    ) as DeviceProfile;
    apply(wechat);
    expect('structuredClone' in window).toBe(false);
    expect('hasOwn' in Object).toBe(false);
    expect('at' in Array.prototype).toBe(false);
    expect(viewportHeightUnit()).toBe('vh');
  });

  it('现代内核档：四个引擎能力齐备，视口高度单位用 dvh', () => {
    const iphone = DEVICE_PROFILES.find(
      (p) => p.key === 'iphone-safari',
    ) as DeviceProfile;
    apply(iphone);
    expect(typeof structuredClone).toBe('function');
    expect(typeof Object.hasOwn).toBe('function');
    expect(typeof [1, 2].at).toBe('function');
    expect(viewportHeightUnit()).toBe('dvh');
  });

  it('restore 还原被模拟的全部面（宿主环境不受串扰）', () => {
    const wechat = DEVICE_PROFILES.find(
      (p) => p.key === 'android-phone-wechat',
    ) as DeviceProfile;
    const beforeWidth = window.innerWidth;
    const beforeDpr = window.devicePixelRatio;
    const beforeUnit = viewportHeightUnit();

    const restore = applyDeviceProfile(wechat);
    expect(window.innerWidth).toBe(wechat.cssWidth);
    restore();

    expect(window.innerWidth).toBe(beforeWidth);
    expect(window.devicePixelRatio).toBe(beforeDpr);
    expect(typeof Object.hasOwn).toBe('function');
    expect(typeof [1, 2].at).toBe('function');
    expect(viewportHeightUnit()).toBe(beforeUnit);
  });
});
