// E24-T07 真机兼容（NFR-RESP-001/002/003 设备矩阵：iOS Safari、安卓 Chrome、
// 微信内置浏览器）：微信 XWeb 与主流厂商浏览器内核可能落后于开发机的桌面 Chrome，
// structuredClone（Chromium 98+）、Object.hasOwn（Chromium 93+）、
// Array.prototype.at（Chromium 92+）在旧内核上不存在——真机上点开页面就是
// TypeError，属阻断问题。本文件把「运行时 API 下限」钉成回归用例：临时删掉这些
// API 模拟旧内核，业务函数必须照常工作。
import { describe, expect, it } from 'vitest';

import { deepCloneJson } from './compat';
import { patientStatusOfQuery } from './patient-search';

/** 形状对齐高级检索条件树（advanced-search 的 ConditionGroup 精简版）。 */
interface ConditionNode {
  field?: string;
  items?: ConditionNode[];
  logic?: string;
  value?: string[];
}

describe('deepCloneJson（structuredClone 的纯 JSON 替身，微信内置浏览器可用）', () => {
  it('深层条件树完整拷贝：内容相等且与源对象不共享任何引用', () => {
    const source: ConditionNode = {
      logic: 'AND',
      items: [
        { field: 'patientName' },
        {
          logic: 'OR',
          items: [{ field: 'status', value: ['ACTIVE', 'LOST'] }],
        },
      ],
    };
    const cloned = deepCloneJson(source);
    expect(cloned).toEqual(source);
    // 改克隆不回写源（续编草稿不污染父状态，原 structuredClone 语义）
    cloned.items?.[1]?.items?.[0]?.value?.push('DEAD');
    expect(source.items?.[1]?.items?.[0]?.value).toEqual(['ACTIVE', 'LOST']);
  });

  it('null / 基本类型原样返回（筛选条件的 value 允许标量）', () => {
    expect(deepCloneJson(null)).toBeNull();
    expect(deepCloneJson('ACTIVE')).toBe('ACTIVE');
    expect(deepCloneJson(3)).toBe(3);
    expect(deepCloneJson(undefined)).toBeUndefined();
  });

  it('嵌套数组与对象混排与源逐层不等同（真深拷贝）', () => {
    const source: ConditionNode = {
      items: [{ field: 'a' }, { items: [{ field: 'b' }] }],
    };
    const cloned = deepCloneJson(source);
    expect(cloned).not.toBe(source);
    expect(cloned.items).not.toBe(source.items);
    expect(cloned.items?.[1]?.items).not.toBe(source.items?.[1]?.items);
  });
});

describe('真机内核下限回归（Object.hasOwn 缺失时不得崩溃）', () => {
  it('患者列表状态映射：Object.hasOwn 不存在时仍正常（微信 XWeb 内核）', () => {
    const hasOwn = Object.hasOwn;
    // oxlint-disable-next-line no-extend-native -- 测试临时摘除 API 模拟旧内核，finally 恢复
    Object.defineProperty(Object, 'hasOwn', {
      configurable: true,
      value: undefined,
    });
    try {
      expect(patientStatusOfQuery('active')).toBe('ACTIVE');
      expect(patientStatusOfQuery(['LOST'])).toBe('LOST');
      expect(patientStatusOfQuery('unknown')).toBeUndefined();
    } finally {
      Object.defineProperty(Object, 'hasOwn', {
        configurable: true,
        value: hasOwn,
      });
    }
  });
});
