/**
 * E13-T11 / A-PAT-021、C-PAT-018 · 分类条 / 分组条定位逻辑单测（components.md §6）：
 * 点击 chip → 滚到 `#crdp-anchor-<key>` 并加 `.crdp-anchor-flash` 2 秒
 * （timings.anchorHighlightMs）；位置写入页面地址 `#cat=<key>`（可分享、可后退）；
 * scroll-spy（IntersectionObserver 观察各分类锚点，顶部最近的一个为 active）。
 */
import { flushPromises } from '@vue/test-utils';
import { ref } from 'vue';

import { afterEach, describe, expect, it, vi } from 'vitest';

import {
  anchorDomId,
  categoryHash,
  flashAnchor,
  jumpToAnchor,
  parseCategoryHash,
  parseRecordHash,
  pickActiveKey,
  recordHash,
  useAnchorSpy,
} from './anchor-nav';

afterEach(() => {
  document.body.innerHTML = '';
  vi.useRealTimers();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe('anchorDomId / categoryHash（components.md §6：#crdp-anchor-<key>、#cat=<key>）', () => {
  it('锚点 DOM id 与页面地址 hash 互为可逆', () => {
    expect(anchorDomId('basic_info')).toBe('crdp-anchor-basic_info');
    expect(categoryHash('basic_info')).toBe('#cat=basic_info');
    expect(parseCategoryHash(categoryHash('basic_info'))).toBe('basic_info');
  });

  it('parseCategoryHash：空 / 无 cat / 空 cat 值返回 null；含 & 拼接形态也能取出', () => {
    expect(parseCategoryHash('')).toBeNull();
    expect(parseCategoryHash('#')).toBeNull();
    expect(parseCategoryHash('#rec=701&sec=echo')).toBeNull();
    expect(parseCategoryHash('#cat=')).toBeNull();
    // 兼容 E16-T14 的多段 hash（#rec=<id>&cat=<key>）
    expect(parseCategoryHash('#rec=701&cat=echo')).toBe('echo');
  });

  it('parseCategoryHash：URL 编码的键可解码（锚点键理论上为 ASCII，防御即可）', () => {
    expect(parseCategoryHash(`#cat=${encodeURIComponent('a b')}`)).toBe('a b');
  });

  it('recordHash / parseRecordHash（E16-T14 随访位置：#rec=<id>&cat=<key>，可分享 / 后退）', () => {
    expect(recordHash('701', 'echo')).toBe('#rec=701&cat=echo');
    expect(recordHash('701')).toBe('#rec=701');
    expect(parseRecordHash('#rec=701&cat=echo')).toEqual({
      anchorKey: 'echo',
      recordId: '701',
    });
    expect(parseRecordHash('#rec=701')).toEqual({
      anchorKey: null,
      recordId: '701',
    });
    // 无 rec / 空 rec / 纯基线 hash 返回 null recordId（基线定位不走这里）
    expect(parseRecordHash('#cat=basic_info')).toEqual({
      anchorKey: 'basic_info',
      recordId: null,
    });
    expect(parseRecordHash('')).toEqual({ anchorKey: null, recordId: null });
    expect(parseRecordHash('#rec=&cat=echo')).toEqual({
      anchorKey: 'echo',
      recordId: null,
    });
    expect(parseRecordHash(recordHash('701', 'a b'))).toEqual({
      anchorKey: 'a b',
      recordId: '701',
    });
  });
});

describe('jumpToAnchor（点击滚动到该分类并高亮 2 秒）', () => {
  it('scrollIntoView smooth + 加 .crdp-anchor-flash，2 秒后移除', () => {
    vi.useFakeTimers();
    const section = document.createElement('section');
    section.id = anchorDomId('basic_info');
    document.body.append(section);
    const scrollIntoView = vi.fn();
    section.scrollIntoView = scrollIntoView;

    const clear = jumpToAnchor('basic_info', document);

    expect(scrollIntoView).toHaveBeenCalledWith({
      behavior: 'smooth',
      block: 'start',
    });
    expect(section.classList.contains('crdp-anchor-flash')).toBe(true);

    vi.advanceTimersByTime(2000);
    expect(section.classList.contains('crdp-anchor-flash')).toBe(false);
    expect(clear).toBeInstanceOf(Function);
  });

  it('锚点不存在时不抛错（分节未渲染 / 模板缺分类的防御）', () => {
    expect(() => jumpToAnchor('missing', document)).not.toThrow();
  });

  it('flashAnchor：返回的清理函数可提前撤销高亮（组件卸载时用）', () => {
    vi.useFakeTimers();
    const section = document.createElement('section');
    document.body.append(section);

    const clear = flashAnchor(section, window);
    expect(section.classList.contains('crdp-anchor-flash')).toBe(true);
    clear();
    expect(section.classList.contains('crdp-anchor-flash')).toBe(false);
  });
});

describe('pickActiveKey（scroll-spy：顶部最近的一个为 active）', () => {
  it('按 items 顺序取第一个可见分类（顶部带内最先交到的）', () => {
    const items = [
      { key: 'basic', visible: false },
      { key: 'diag', visible: true },
      { key: 'echo', visible: true },
    ];
    expect(pickActiveKey(items)).toBe('diag');
  });

  it('全部不可见时返回 null（保持当前 active 不变）', () => {
    expect(
      pickActiveKey([
        { key: 'basic', visible: false },
        { key: 'diag', visible: false },
      ]),
    ).toBeNull();
  });
});

/** 假 IntersectionObserver：记录实例与观察目标，由测试手动触发回调。 */
class FakeIntersectionObserver {
  static instances: FakeIntersectionObserver[] = [];
  callback: IntersectionObserverCallback;
  entries = new Map<Element, IntersectionObserverEntry>();

  constructor(
    callback: IntersectionObserverCallback,
    _options?: IntersectionObserverInit,
  ) {
    this.callback = callback;
    FakeIntersectionObserver.instances.push(this);
  }

  disconnect() {
    this.entries.clear();
  }

  observe(target: Element) {
    this.entries.set(target, {
      isIntersecting: false,
      target,
    } as IntersectionObserverEntry);
  }

  /** 模拟某目标进入 / 离开顶部带。 */
  setVisible(target: Element, visible: boolean) {
    const entry = this.entries.get(target);
    if (!entry) return;
    this.callback(
      [{ ...entry, isIntersecting: visible }] as IntersectionObserverEntry[],
      this as unknown as IntersectionObserver,
    );
  }

  unobserve(target: Element) {
    this.entries.delete(target);
  }
}

describe('useAnchorSpy（IntersectionObserver 观察锚点 → 更新 active）', () => {
  function mountSections(keys: string[]) {
    for (const key of keys) {
      const el = document.createElement('section');
      el.id = anchorDomId(key);
      document.body.append(el);
    }
  }

  it('keys 变化时重新观察；顶部带内最先可见的键回调给页面', async () => {
    vi.stubGlobal('IntersectionObserver', FakeIntersectionObserver);
    mountSections(['basic', 'diag', 'echo']);
    const keys = ref(['basic', 'diag', 'echo']);
    const active = ref('');
    const spy = useAnchorSpy({
      keys,
      onActive: (key) => {
        active.value = key;
      },
    });

    const observer = FakeIntersectionObserver.instances.at(-1);
    expect(observer).toBeDefined();
    expect(observer?.entries.size).toBe(3);

    // 滚动到中部：diag 在顶部带内最先可见（basic 已滚出，echo 也在带内但更靠下）
    const diagEl = document.querySelector(`#${anchorDomId('diag')}`);
    const echoEl = document.querySelector(`#${anchorDomId('echo')}`);
    observer?.setVisible(diagEl as Element, true);
    observer?.setVisible(echoEl as Element, true);
    expect(active.value).toBe('diag');

    // diag 滚出后 echo 成为顶部带内最先可见的 → active 跟随
    observer?.setVisible(diagEl as Element, false);
    expect(active.value).toBe('echo');

    // 全部滚出：active 保持最后一个（不回跳）
    observer?.setVisible(echoEl as Element, false);
    expect(active.value).toBe('echo');

    spy.stop();
  });

  it('keys 更新（分节懒加载完）后观察新锚点，旧锚点取消', async () => {
    vi.stubGlobal('IntersectionObserver', FakeIntersectionObserver);
    mountSections(['basic', 'diag']);
    const keys = ref(['basic']);
    const spy = useAnchorSpy({ keys, onActive: () => {} });
    let observer = FakeIntersectionObserver.instances.at(-1);
    expect(observer?.entries.size).toBe(1);

    keys.value = ['basic', 'diag'];
    await flushPromises();
    observer = FakeIntersectionObserver.instances.at(-1);
    expect(observer?.entries.size).toBe(2);

    spy.stop();
    expect(observer?.entries.size).toBe(0);
  });
});
