// NFR-SAVE-002 · 本地草稿存储（design-system §13：键含租户、用户、记录 ID；退出登录清空）
import type { LocalDraftSnapshot } from './local-draft-store';

import { describe, expect, it } from 'vitest';

import {
  createIndexedDbDraftStorage,
  createMemoryDraftStorage,
} from './local-draft-store';

const snapshot = (
  over: Partial<LocalDraftSnapshot> = {},
): LocalDraftSnapshot => ({
  baseVersion: 3,
  data: { q01_ef: 56 },
  meta: { manual: { q02: true } },
  savedAt: '2026-11-05T14:02:00+08:00',
  ...over,
});

describe('createMemoryDraftStorage', () => {
  it('put / get / remove 按键读写快照', async () => {
    const storage = createMemoryDraftStorage();
    expect(await storage.get('tA:1:followup:701')).toBeNull();
    await storage.put('tA:1:followup:701', snapshot());
    expect(await storage.get('tA:1:followup:701')).toEqual(snapshot());
    await storage.remove('tA:1:followup:701');
    expect(await storage.get('tA:1:followup:701')).toBeNull();
  });

  it('clearAll 清空本机全部草稿（退出登录），不影响新写入', async () => {
    const storage = createMemoryDraftStorage();
    await storage.put('tA:1:followup:701', snapshot());
    await storage.put('tA:2:baseline:basic', snapshot());
    await storage.clearAll();
    expect(await storage.get('tA:1:followup:701')).toBeNull();
    expect(await storage.get('tA:2:baseline:basic')).toBeNull();
    await storage.put('tA:1:followup:701', snapshot());
    expect(await storage.get('tA:1:followup:701')).toEqual(snapshot());
  });
});

describe('createIndexedDbDraftStorage（IndexedDB 不可用时回退内存）', () => {
  it('环境没有 indexedDB 时退化为内存实现，接口行为一致', async () => {
    const original = globalThis.indexedDB;
    // @ts-expect-error 测试环境抹掉 indexedDB（私有模式 / 旧浏览器 / happy-dom 场景）
    delete globalThis.indexedDB;
    try {
      const storage = createIndexedDbDraftStorage();
      await storage.put('tA:1:followup:701', snapshot());
      expect(await storage.get('tA:1:followup:701')).toEqual(snapshot());
      await storage.clearAll();
      expect(await storage.get('tA:1:followup:701')).toBeNull();
    } finally {
      if (original) globalThis.indexedDB = original;
    }
  });
});
