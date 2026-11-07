/**
 * E13-T11 / A-PAT-021、C-PAT-018 · 分类条 / 分组条的定位与联动逻辑
 * （components.md §6 CategoryBar、§5 AttachmentNav.locate 第 1 条）：
 *
 * - 点击 chip / 位置标签 → 滚到 `#crdp-anchor-<key>` 并加 `.crdp-anchor-flash`
 *   高亮 `timings.anchorHighlightMs`（2 秒，tokens globalCss 已注入该类）；
 * - 基线分类的位置写入页面地址 `#cat=<key>`（可分享、可后退）；
 * - scroll-spy：IntersectionObserver 观察各分类锚点，顶部带内最先可见的一个
 *   为 active（`rootMargin` 只留顶部 25% 判定带，等价「顶部最近的一个」）。
 *
 * 抽成纯逻辑 + 组合函数，供患者详情基线页签（E13-T11）与随访记录展开分组条共用。
 */
import type { Ref, WatchSource } from 'vue';

import { toValue, watch } from 'vue';

import { timings } from '@crdp/tokens';

/** 分类 / 分节锚点 DOM id（与 FormRenderer / 基线分节包裹 id 同一约定）。 */
export function anchorDomId(key: string): string {
  return `crdp-anchor-${key}`;
}

/** 页面地址 hash：`#cat=<key>`（A-PAT-021「位置写入页面地址便于分享」）。 */
export function categoryHash(key: string): string {
  return `#cat=${encodeURIComponent(key)}`;
}

/**
 * 从 hash 中解析 `cat` 键（`#cat=<key>`，或 E16-T14 的 `#rec=<id>&cat=<key>`
 * 拼接形态）；无 cat / 空值返回 null。
 */
export function parseCategoryHash(hash: string): null | string {
  const match = /(?:^#|&)cat=([^&]*)/.exec(hash);
  const key = match?.[1] ? decodeURIComponent(match[1]) : null;
  return key && key.length > 0 ? key : null;
}

/**
 * 页面地址 hash（E16-T14 随访位置）：`#rec=<recordId>&cat=<key>`（分组）或
 * `#rec=<recordId>`（整条记录）；与基线 `#cat=<key>` 同为「可分享、可后退」口径。
 */
export function recordHash(recordId: string, anchorKey?: string): string {
  const rec = `rec=${encodeURIComponent(recordId)}`;
  return anchorKey ? `#${rec}&cat=${encodeURIComponent(anchorKey)}` : `#${rec}`;
}

/** 从 hash 中解析 `rec` 与 `cat`（E16-T14 随访位置回放）；缺项为 null。 */
export function parseRecordHash(hash: string): {
  anchorKey: null | string;
  recordId: null | string;
} {
  const rec = /(?:^#|&)rec=([^&]*)/.exec(hash);
  const recordId = rec?.[1] ? decodeURIComponent(rec[1]) : null;
  return {
    anchorKey: parseCategoryHash(hash),
    recordId: recordId && recordId.length > 0 ? recordId : null,
  };
}

/**
 * 给锚点元素加 2 秒高亮（`.crdp-anchor-flash`，timings.anchorHighlightMs）；
 * 返回清理函数（提前卸载时移除类并撤销定时器）。元素为空时是 no-op。
 */
export function flashAnchor(
  el: Element | null | undefined,
  view: null | undefined | Window,
): () => void {
  if (!el) return () => {};
  el.classList.add('crdp-anchor-flash');
  const timer = view?.setTimeout(
    () => el.classList.remove('crdp-anchor-flash'),
    timings.anchorHighlightMs,
  );
  return () => {
    if (timer !== undefined) view?.clearTimeout(timer);
    el.classList.remove('crdp-anchor-flash');
  };
}

/** 滚动到分类锚点（smooth、顶部对齐）并高亮 2 秒；锚点不存在时不抛错。 */
export function jumpToAnchor(key: string, doc: Document): () => void {
  const el = doc.querySelector(`#${anchorDomId(key)}`);
  if (!el) return () => {};
  el.scrollIntoView({ behavior: 'smooth', block: 'start' });
  return flashAnchor(el, doc.defaultView);
}

/** scroll-spy 判定：按声明顺序取第一个可见分类（顶部带内最先交到的）；全不可见为 null。 */
export function pickActiveKey(
  items: Array<{ key: string; visible: boolean }>,
): null | string {
  for (const item of items) {
    if (item.visible) return item.key;
  }
  return null;
}

/** IntersectionObserver 顶部判定带：只留视口顶部 25%（其余 -75% 收掉）。 */
const SPY_ROOT_MARGIN = '0px 0px -75% 0px';

/**
 * scroll-spy 组合函数：观察 `keys` 对应的锚点元素（`crdp-anchor-<key>`），
 * 顶部带内最先可见的键经 `onActive` 上抛（CategoryBar 的 update:activeKey 用）。
 *
 * - keys 是响应式的：分节懒加载完成后自动补观察、旧键取消观察；
 * - 环境 / 测试无 IntersectionObserver（happy-dom 部分版本）时静默降级为不联动；
 * - 用完调 `stop()`（组件卸载钩子里调用）。
 */
export function useAnchorSpy(options: {
  /** 默认 true；为 false 时完全不创建 observer（CategoryBar 的 spy 开关用）。 */
  enabled?: WatchSource<boolean>;
  getDocument?: () => Document | null;
  keys: Ref<string[]> | WatchSource<string[]>;
  onActive: (key: string) => void;
}): { refresh: () => void; stop: () => void } {
  const { keys, onActive } = options;
  const doc = () => options.getDocument?.() ?? document;
  const globalWithObserver = globalThis as unknown as {
    IntersectionObserver?: typeof IntersectionObserver;
  };
  const ObserverCtor = globalWithObserver.IntersectionObserver;

  let observer: IntersectionObserver | null = null;
  const visible = new Map<string, boolean>();

  function computeActive() {
    const items = [...visible].map(([itemKey, isVisible]) => ({
      key: itemKey,
      visible: isVisible,
    }));
    const key = pickActiveKey(items);
    if (key) onActive(key);
  }

  function refresh() {
    if (!ObserverCtor) return;
    if (options.enabled && !toValue(options.enabled)) {
      observer?.disconnect();
      observer = null;
      visible.clear();
      return;
    }
    if (!observer) {
      observer = new ObserverCtor(
        (entries) => {
          for (const entry of entries) {
            const key = entry.target.id.startsWith('crdp-anchor-')
              ? entry.target.id.slice('crdp-anchor-'.length)
              : entry.target.id;
            visible.set(key, entry.isIntersecting);
          }
          computeActive();
        },
        { rootMargin: SPY_ROOT_MARGIN },
      );
    }
    const current = new Set<string>();
    const keyList = toValue(keys);
    for (const key of keyList) {
      if (current.has(key)) continue;
      current.add(key);
      const el = doc().querySelector(`#${anchorDomId(key)}`);
      if (el) observer.observe(el);
    }
    for (const key of visible.keys()) {
      if (current.has(key)) continue;
      visible.delete(key);
      const el = doc().querySelector(`#${anchorDomId(key)}`);
      if (el) observer.unobserve(el);
    }
  }

  const stopWatch = watch(
    [keys, () => toValue(options.enabled ?? true)] as WatchSource[],
    () => {
      refresh();
    },
    { immediate: true },
  );

  function stop() {
    stopWatch();
    observer?.disconnect();
    observer = null;
    visible.clear();
  }

  return { refresh, stop };
}
