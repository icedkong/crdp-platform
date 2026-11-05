// NFR-SAVE-001/002/003 · useAutoSave（components.md §1.4：30s / 2min / blur / 离线 / 409 / 恢复网络 六分支）
import type { AutoSaveOptions, AutoSaveSavedView } from './auto-save';
import type { LocalDraftSnapshot } from './local-draft-store';

import { computed, effectScope, ref } from 'vue';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import {
  asDraftConflictDetail,
  autoSaveStatusText,
  DraftConflictError,
  hhmmOf,
  useAutoSave,
} from './auto-save';
import { createMemoryDraftStorage } from './local-draft-store';

/** 受控表单数据：page 侧 data / savedData / version 的最小替身。 */
function fixture() {
  const data = ref<Record<string, unknown>>({});
  const savedData = ref<Record<string, unknown>>({});
  const version = ref(2);
  const changes = () => {
    const delta: Record<string, unknown> = {};
    for (const key of new Set([
      ...Object.keys(savedData.value),
      ...Object.keys(data.value),
    ])) {
      if (!Object.is(savedData.value[key], data.value[key])) {
        delta[key] = data.value[key] ?? null;
      }
    }
    return delta;
  };
  const dirty = computed(() => Object.keys(changes()).length > 0);
  return { changes, data, dirty, savedData, version };
}

type SaveDraftFn = NonNullable<AutoSaveOptions['saveDraft']>;

function mountAutosave(over: Partial<AutoSaveOptions> = {}) {
  const form = fixture();
  const storage = createMemoryDraftStorage();
  const saveDraft = vi.fn(
    async (body: {
      baseVersion: number;
      changes: Record<string, unknown>;
    }): Promise<AutoSaveSavedView> => ({
      savedAt: SAVED_AT_ISO,
      version: body.baseVersion + 1,
    }),
  );
  const onSaved = vi.fn();
  const restoreServer = vi.fn(async () => {
    // 页面重拉后以服务器为准（测试里以当前 data 近似服务器内容）
    form.savedData.value = { ...form.data.value };
  });
  const scope = effectScope();
  const api = scope.run(() =>
    useAutoSave({
      enabled: ref(true),
      getBaseVersion: () => form.version.value,
      getChanges: form.changes,
      getSnapshot: (): LocalDraftSnapshot => ({
        baseVersion: form.version.value,
        data: { ...form.data.value },
        meta: { touched: true },
      }),
      isDirty: form.dirty,
      kind: 'followup',
      onSaved,
      recordKey: ref('tA:101:followup:701'),
      restoreServer,
      saveDraft: saveDraft as unknown as SaveDraftFn,
      storage,
      ...over,
    }),
  );
  if (!api) throw new Error('scope');
  return {
    api,
    form,
    onSaved,
    restoreServer,
    // over.saveDraft 覆盖默认 mock 时，返回给断言用的也是同一个
    saveDraft: (over.saveDraft ?? saveDraft) as ReturnType<typeof vi.fn>,
    scope,
    storage,
  };
}

function setOnline(online: boolean) {
  vi.spyOn(navigator, 'onLine', 'get').mockReturnValue(online);
}

// HH:mm 口径为浏览器本地时区（与站内信 noticeTimeText 一致）：所有时间常量用
// 本地时区构造（notice-bell-logic.spec 同款），CI 任意时区（如 UTC）断言等价
beforeEach(() => {
  vi.useFakeTimers();
  setOnline(true);
});

/** 本地时区 2026-11-05 14:02 的 ISO 串（HH:mm 断言值 14:02 在任意时区等价）。 */
const SAVED_AT_ISO = new Date(2026, 10, 5, 14, 2).toISOString();

/** 本地时区 2026-11-05 13:58（冲突更新时间，ConflictBanner 显示到分钟）。 */
const CONFLICT_AT_ISO = new Date(2026, 10, 5, 13, 58).toISOString();

afterEach(() => {
  vi.useRealTimers();
  vi.restoreAllMocks();
});

describe('服务器草稿：每 2 分钟，有改动才保存（NFR-SAVE-001）', () => {
  it('无改动时到点不请求', async () => {
    const { api, saveDraft } = mountAutosave();
    await vi.advanceTimersByTimeAsync(6 * 60_000);
    expect(saveDraft).not.toHaveBeenCalled();
    expect(api.status.value).toBe('idle');
  });

  it('有改动时到点保存一次：带 baseVersion 与变更字段，状态 saving→saved，lastSavedAt 为 HH:mm', async () => {
    const { api, form, onSaved, saveDraft } = mountAutosave();
    form.data.value = { q01_ef: 45 };
    await vi.advanceTimersByTimeAsync(120_000);
    expect(saveDraft).toHaveBeenCalledTimes(1);
    expect(saveDraft).toHaveBeenCalledWith({
      baseVersion: 2,
      changes: { q01_ef: 45 },
    });
    expect(api.status.value).toBe('saved');
    expect(api.lastSavedAt.value).toBe('14:02');
    expect(onSaved).toHaveBeenCalledWith(
      { savedAt: SAVED_AT_ISO, version: 3 },
      'auto',
    );
    // 保存成功后页面以服务器版本为新基准 → 不再 dirty，下一个周期不再请求
    form.savedData.value = { q01_ef: 45 };
    form.version.value = 3;
    await vi.advanceTimersByTimeAsync(120_000);
    expect(saveDraft).toHaveBeenCalledTimes(1);
  });

  it('保存期间（saving）不重入', async () => {
    let release: () => void = () => {};
    const gate = new Promise<void>((resolve) => {
      release = resolve;
    });
    const saveDraft = vi.fn(async (body: { baseVersion: number }) => {
      await gate;
      return {
        savedAt: '2026-11-05T14:02:00+08:00',
        version: body.baseVersion + 1,
      };
    });
    const { api, form } = mountAutosave({
      saveDraft: saveDraft as unknown as SaveDraftFn,
    });
    form.data.value = { q01_ef: 45 };
    await vi.advanceTimersByTimeAsync(120_000);
    expect(api.status.value).toBe('saving');
    await vi.advanceTimersByTimeAsync(120_000);
    expect(saveDraft).toHaveBeenCalledTimes(1);
    release();
    await vi.advanceTimersByTimeAsync(0);
    expect(api.status.value).toBe('saved');
  });
});

describe('本地草稿：每 30 秒 + blur 写 IndexedDB（NFR-SAVE-002）', () => {
  it('30 秒到点把全量快照写入本机（断网不丢数据）', async () => {
    const { api, form, storage } = mountAutosave();
    form.data.value = { q01_ef: 45, q02_lvd: 58 };
    await vi.advanceTimersByTimeAsync(30_000);
    const draft = await storage.get('tA:101:followup:701');
    expect(draft).not.toBeNull();
    expect(draft?.data).toEqual({ q01_ef: 45, q02_lvd: 58 });
    expect(draft?.baseVersion).toBe(2);
    expect(typeof draft?.savedAt).toBe('string');
    expect(api.status.value).not.toBe('offline');
  });

  it('无改动时 30 秒到点不写本机', async () => {
    const { storage } = mountAutosave();
    await vi.advanceTimersByTimeAsync(30_000);
    expect(await storage.get('tA:101:followup:701')).toBeNull();
  });

  it('字段 blur 立即写一次（不等 30 秒）', async () => {
    const { api, form, storage } = mountAutosave();
    form.data.value = { q01_ef: 45 };
    api.noteBlur();
    await vi.advanceTimersByTimeAsync(0);
    expect(await storage.get('tA:101:followup:701')).not.toBeNull();
  });

  it('hasLocalDraft / restoreLocalDraft：重开时发现并恢复草稿', async () => {
    const { api, form } = mountAutosave();
    expect(await api.hasLocalDraft()).toBe(false);
    form.data.value = { q01_ef: 45 };
    api.noteBlur();
    await vi.advanceTimersByTimeAsync(0);
    expect(await api.hasLocalDraft()).toBe(true);
    const draft = await api.restoreLocalDraft();
    expect(draft?.data).toEqual({ q01_ef: 45 });
  });

  it('服务器保存成功后清除本机草稿（服务器版本为准，重开不再误提示）', async () => {
    const { api, form, storage } = mountAutosave();
    form.data.value = { q01_ef: 45 };
    api.noteBlur();
    await vi.advanceTimersByTimeAsync(0);
    await vi.advanceTimersByTimeAsync(120_000);
    expect(await storage.get('tA:101:followup:701')).toBeNull();
  });
});

describe('离线：只存本机、可继续录入，恢复网络立即同步（NFR-SAVE-002）', () => {
  it('navigator.onLine=false：状态离线，到点不请求服务器，本地照写', async () => {
    const { api, form, saveDraft, storage } = mountAutosave();
    setOnline(false);
    window.dispatchEvent(new window.Event('offline'));
    form.data.value = { q01_ef: 45 };
    await vi.advanceTimersByTimeAsync(120_000);
    expect(saveDraft).not.toHaveBeenCalled();
    expect(api.status.value).toBe('offline');
    expect(await storage.get('tA:101:followup:701')).not.toBeNull();
  });

  it('请求网络错误 → 离线状态且本地已留草稿', async () => {
    const saveDraft = vi.fn(async () => {
      throw new Error('Network Error');
    });
    const { api, form, storage } = mountAutosave({
      saveDraft: saveDraft as unknown as SaveDraftFn,
    });
    form.data.value = { q01_ef: 45 };
    await vi.advanceTimersByTimeAsync(120_000);
    expect(api.status.value).toBe('offline');
    expect(await storage.get('tA:101:followup:701')).not.toBeNull();
  });

  it('online 事件后立即同步一次（不等 2 分钟）', async () => {
    const { api, form, saveDraft } = mountAutosave();
    setOnline(false);
    window.dispatchEvent(new window.Event('offline'));
    form.data.value = { q01_ef: 45 };
    setOnline(true);
    window.dispatchEvent(new window.Event('online'));
    await vi.advanceTimersByTimeAsync(0);
    expect(saveDraft).toHaveBeenCalledTimes(1);
    expect(api.status.value).toBe('saved');
    // 同步成功后回到正常周期：无新改动不再重复请求
    form.savedData.value = { q01_ef: 45 };
    await vi.advanceTimersByTimeAsync(120_000);
    expect(saveDraft).toHaveBeenCalledTimes(1);
  });
});

describe('版本冲突：409 暂停自动保存并提示（NFR-SAVE-003）', () => {
  function conflictError() {
    return new DraftConflictError({
      currentVersion: 9,
      diffFields: [
        { key: 'q01_ef', label: 'EF 射血分数', mine: 45, server: 52 },
        { key: 'q03_la', label: null, mine: 40, server: 44 },
      ],
      updatedAt: CONFLICT_AT_ISO,
      updatedBy: '王医生（示例）',
    });
  }

  function mountConflict() {
    const saveDraft = vi.fn(async () => {
      throw conflictError();
    });
    return {
      ...mountAutosave({ saveDraft: saveDraft as unknown as SaveDraftFn }),
      conflictError,
    };
  }

  it('409 → conflict 状态：更新人 / 更新时间 / 字段（显示名优先）、serverVersion', async () => {
    const { api, form } = mountConflict();
    form.data.value = { q01_ef: 45 };
    await vi.advanceTimersByTimeAsync(120_000);
    expect(api.status.value).toBe('conflict');
    expect(api.conflict.value).toEqual({
      fields: ['EF 射血分数', 'q03_la'],
      serverVersion: 9,
      updatedAt: CONFLICT_AT_ISO,
      updatedBy: '王医生（示例）',
    });
  });

  it('冲突期间暂停：定时器到点不再请求，暂存也不发', async () => {
    const { api, form, saveDraft } = mountConflict();
    form.data.value = { q01_ef: 45 };
    await vi.advanceTimersByTimeAsync(120_000);
    expect(saveDraft).toHaveBeenCalledTimes(1);
    await vi.advanceTimersByTimeAsync(300_000);
    await api.saveNow();
    expect(saveDraft).toHaveBeenCalledTimes(1);
    expect(api.status.value).toBe('conflict');
  });

  it('冲突时数据也不丢：本机保留草稿', async () => {
    const { form, storage } = mountConflict();
    form.data.value = { q01_ef: 45 };
    await vi.advanceTimersByTimeAsync(120_000);
    expect(await storage.get('tA:101:followup:701')).not.toBeNull();
  });

  it('保留我的并覆盖：以 serverVersion 为 baseVersion 重发并带 overwrite=true，冲突解除', async () => {
    let fail = true;
    const saveDraft = vi.fn(
      async (_body: { baseVersion: number; overwrite?: boolean }) => {
        if (fail) throw conflictError();
        return {
          savedAt: new Date(2026, 10, 5, 14, 10).toISOString(),
          version: 10,
        };
      },
    );
    const { api, form } = mountAutosave({
      saveDraft: saveDraft as unknown as SaveDraftFn,
    });
    form.data.value = { q01_ef: 45 };
    await vi.advanceTimersByTimeAsync(120_000);
    fail = false;
    await api.keepMine();
    expect(saveDraft).toHaveBeenLastCalledWith({
      baseVersion: 9,
      changes: { q01_ef: 45 },
      overwrite: true,
    });
    expect(api.conflict.value).toBeNull();
    expect(api.status.value).toBe('saved');
    expect(api.lastSavedAt.value).toBe('14:10');
    // 冲突解除后自动保存恢复
    form.savedData.value = { q01_ef: 45 };
    form.version.value = 10;
    form.data.value = { q01_ef: 47 };
    await vi.advanceTimersByTimeAsync(120_000);
    expect(saveDraft).toHaveBeenCalledTimes(3);
  });

  it('载入服务器版本：本地改动另存一份副本，重拉服务器后冲突解除', async () => {
    const { api, form, restoreServer, storage } = mountConflict();
    form.data.value = { q01_ef: 45 };
    await vi.advanceTimersByTimeAsync(120_000);
    await api.loadServer();
    expect(restoreServer).toHaveBeenCalledTimes(1);
    expect(api.conflict.value).toBeNull();
    expect(api.status.value).toBe('idle');
    const copy = await storage.get('tA:101:followup:701:conflict-copy');
    expect(copy?.data).toEqual({ q01_ef: 45 });
    // 正式草稿键清空，重开不再提示恢复旧改动
    expect(await storage.get('tA:101:followup:701')).toBeNull();
  });
});

describe('enabled=false（已提交 / 只读）：不自动保存', () => {
  it('定时器到点不请求、不写本机', async () => {
    const { api, form, saveDraft, storage } = mountAutosave({
      enabled: ref(false),
    });
    form.data.value = { q01_ef: 45 };
    api.noteBlur();
    await vi.advanceTimersByTimeAsync(300_000);
    expect(saveDraft).not.toHaveBeenCalled();
    expect(await storage.get('tA:101:followup:701')).toBeNull();
  });
});

describe('saveNow（暂存按钮 / Ctrl+⌘+S）', () => {
  it('无改动 → 成功返回且不请求', async () => {
    const { api, saveDraft } = mountAutosave();
    await expect(api.saveNow()).resolves.toBe(true);
    expect(saveDraft).not.toHaveBeenCalled();
  });

  it('有改动 → 立即保存', async () => {
    const { api, form, saveDraft } = mountAutosave();
    form.data.value = { q01_ef: 45 };
    await expect(api.saveNow()).resolves.toBe(true);
    expect(saveDraft).toHaveBeenCalledTimes(1);
  });

  it('失败 → false（页面据此阻止「暂存并离开」）', async () => {
    const saveDraft = vi.fn(async () => {
      throw new Error('Network Error');
    });
    const { api, form } = mountAutosave({
      saveDraft: saveDraft as unknown as SaveDraftFn,
    });
    form.data.value = { q01_ef: 45 };
    await expect(api.saveNow()).resolves.toBe(false);
  });
});

describe('状态文案与时间口径（design-system §13 照抄）', () => {
  it('四态文案 + HH:mm', () => {
    expect(autoSaveStatusText('saved', '14:02')).toBe('已自动保存 14:02');
    expect(autoSaveStatusText('dirty', null)).toBe('有改动待同步（每 2 分钟）');
    expect(autoSaveStatusText('offline', null)).toBe('离线 · 改动存在本机草稿');
    expect(autoSaveStatusText('conflict', null)).toBe(
      '版本冲突 · 自动保存已暂停',
    );
    expect(autoSaveStatusText('idle', null)).toBe('');
  });

  it('hhmmOf：ISO 带时区按本地时区取 HH:mm（本地构造，任意时区等价），非法值空串', () => {
    expect(hhmmOf(new Date(2026, 10, 5, 14, 2).toISOString())).toBe('14:02');
    expect(hhmmOf('bad')).toBe('');
    expect(hhmmOf(null)).toBe('');
  });
});

describe('asDraftConflictDetail：识别 40900 响应体', () => {
  it('从 axios 错误里取出冲突详情（response.data 或 data）', () => {
    const body = {
      code: 40_900,
      data: {
        currentVersion: 9,
        diffFields: [
          { key: 'q01_ef', label: 'EF 射血分数', mine: 45, server: 52 },
        ],
        updatedAt: '2026-11-05T13:58:00+08:00',
        updatedBy: '王医生（示例）',
      },
      message: '版本冲突',
    };
    expect(asDraftConflictDetail({ response: { data: body } })).toEqual(
      body.data,
    );
    expect(asDraftConflictDetail({ data: body })).toEqual(body.data);
  });

  it('其他错误原样返回 null', () => {
    expect(asDraftConflictDetail(new Error('x'))).toBeNull();
    expect(
      asDraftConflictDetail({ response: { data: { code: 40_901 } } }),
    ).toBeNull();
  });
});

describe('卸载清理', () => {
  it('scope 停止后定时器不再触发', async () => {
    const { form, saveDraft, scope } = mountAutosave();
    scope.stop();
    form.data.value = { q01_ef: 45 };
    await vi.advanceTimersByTimeAsync(300_000);
    expect(saveDraft).not.toHaveBeenCalled();
  });
});
