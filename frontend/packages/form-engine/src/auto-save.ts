/**
 * E25-T02 / NFR-SAVE-001、002、003 · useAutoSave（components.md §1.4，design-system §13）。
 *
 * 随访表单与基线分节编辑共用的自动保存组合函数：
 *
 * - 服务器草稿：每 `timings.serverDraftIntervalMs`（2 分钟）检查一次，**只有有改动时**才
 *   请求（带 baseVersion，changes 只带变更字段）；保存成功显示「已自动保存 HH:mm」；
 * - 本地草稿：每 `timings.localDraftIntervalMs`（30 秒）+ 每次字段 blur 写 IndexedDB
 *   （键 `${tenantId}:${userId}:${kind}:${id}`）——断网不丢数据；
 * - 离线（`navigator.onLine === false` 或请求网络错误）：只存本地、状态显示离线、可继续
 *   录入；`online` 事件后立即同步一次；
 * - 版本冲突（409 / 40900）：进入 conflict、**暂停自动保存**，冲突详情给 ConflictBanner
 *   （保留我的并覆盖 / 载入服务器版本；不做字段级合并）；
 * - 已提交 / 只读（`enabled = false`）不自动保存；自动保存不逐次写审计（覆盖他人版本由
 *   后端写，NFR-SAVE-003）。
 *
 * components.md §1.4 接口之上的页面适配扩展（均已注明）：`isDirty`（状态条实时显示
 * 「有改动待同步」）、`getBaseVersion` / `getSnapshot` / `restoreServer` / `onSaved` /
 * `onConflict` / `onOffline` / `storage` / `intervals`，以及 `saveNow` 返回 boolean
 * （「暂存并离开」需要知道是否成功）、`noteBlur` / `restoreLocalDraft`（页面接
 * FormRenderer 的 fieldBlur 与「发现未提交的草稿，是否恢复」）。
 */
import type { ComputedRef, Ref } from 'vue';

import type {
  LocalDraftSnapshot,
  LocalDraftStorage,
} from './local-draft-store';

import { onScopeDispose, ref, watch } from 'vue';

import { timings } from '@crdp/tokens';

import { defaultDraftStorage } from './local-draft-store';

/** 40900 冲突详情（docs/api/followup.md §3：E25-T01 契约，patient.md §4.1 形态）。 */
export interface DraftConflictDetail {
  currentVersion: number;
  diffFields: DraftDiffField[];
  updatedAt: string;
  updatedBy: null | string;
}

/** 两边不同字段：mine = 请求值、server = 服务器当前值，label 取模板显示名。 */
export interface DraftDiffField {
  key: string;
  label: null | string;
  mine: unknown;
  server: unknown;
}

/** saveDraft 在 40900 时抛出（页面适配层用 asDraftConflictError 转换后端响应）。 */
export class DraftConflictError extends Error {
  readonly detail: DraftConflictDetail;

  constructor(detail: DraftConflictDetail) {
    super('draft version conflict');
    this.name = 'DraftConflictError';
    this.detail = detail;
  }
}

/**
 * 从请求错误里识别 40900 冲突详情（`{code: 40900, data: {...}}`；axios 错误在
 * `error.response.data`，requestClient 包装错误在 `error.data`）。其他错误返回 null。
 */
export function asDraftConflictDetail(
  error: unknown,
): DraftConflictDetail | null {
  if (error instanceof DraftConflictError) return error.detail;
  const source = error as
    | null
    | undefined
    | {
        data?: { code?: unknown; data?: unknown };
        response?: { data?: { code?: unknown; data?: unknown } };
      };
  for (const body of [source?.response?.data, source?.data]) {
    if (body?.code === 40_900) {
      return (body.data as DraftConflictDetail | undefined) ?? null;
    }
  }
  return null;
}

/** 自动保存状态（components.md §1.4）。 */
export type AutoSaveStatus =
  | 'conflict'
  | 'dirty'
  | 'idle'
  | 'offline'
  | 'saved'
  | 'saving';

/** 冲突信息（ConflictBanner 展示；fields 为显示名，缺省回退字段稳定键）。 */
export interface AutoSaveConflict {
  fields: string[];
  /** 服务器当前版本：「保留我的并覆盖」以它作 baseVersion 重发。 */
  serverVersion: number;
  updatedAt: string;
  updatedBy: string;
}

/** 保存成功结果（E25-T01：`{ version, savedAt }`）。 */
export interface AutoSaveSavedView {
  savedAt: string;
  version: number;
}

/** 保存触发来源（页面按来源决定提示文案：auto 不弹 toast，状态条自会更新）。 */
export type AutoSaveTrigger = 'auto' | 'keepMine' | 'manual' | 'reconnect';

export interface AutoSaveOptions {
  /** 已提交 / 只读 → false（conflict 由组合函数内部接管，同样暂停）。 */
  enabled: ComputedRef<boolean> | Ref<boolean>;
  /** 每次保存前的乐观锁基准（页面 version ref）。 */
  getBaseVersion: () => number;
  /** 与上次服务器版本相比的变更字段（E25-T01：changes 只带变更字段）。 */
  getChanges: () => Record<string, unknown>;
  /** 本地草稿全量快照（恢复界面所需）。 */
  getSnapshot: () => LocalDraftSnapshot;
  /** 定时间隔（缺省 tokens.timings：本地 30 秒、服务器 2 分钟）。 */
  intervals?: { localDraftIntervalMs?: number; serverDraftIntervalMs?: number };

  // ------------------------------------------------------------ 页面适配扩展

  /** 实时 dirty（状态条立即显示「有改动待同步」）；缺省在定时器触发时求值。 */
  isDirty?: ComputedRef<boolean> | Ref<boolean>;
  kind: 'baseline' | 'followup';
  /** 进入冲突（页面可上报告警日志）。 */
  onConflict?: (conflict: AutoSaveConflict) => void;
  /** 请求失败进入离线。 */
  onOffline?: (error: unknown) => void;
  /** 保存成功（页面更新 version / 基准快照；manual / keepMine 弹结果提示）。 */
  onSaved?: (
    result: AutoSaveSavedView,
    trigger: AutoSaveTrigger,
  ) => Promise<void> | void;
  /** IndexedDB 键：`${tenantId}:${userId}:${kind}:${id}`。 */
  recordKey: ComputedRef<string> | Ref<string>;
  /** 「载入服务器版本」后页面重拉服务器数据与版本。 */
  restoreServer?: () => Promise<void>;
  /** 保存服务器草稿；40900 抛 DraftConflictError（asDraftConflictError 转换）。 */
  saveDraft: (body: {
    baseVersion: number;
    changes: Record<string, unknown>;
    overwrite?: boolean;
  }) => Promise<AutoSaveSavedView>;
  /** 缺省 IndexedDB（不可用回退内存）。 */
  storage?: LocalDraftStorage;
}

/** HH:mm（本地时区口径，与站内信 noticeTimeText 一致）；非法值空串。 */
export function hhmmOf(iso: null | string): string {
  if (!iso) return '';
  const time = new Date(iso);
  if (Number.isNaN(time.getTime())) return '';
  const pad = (value: number) => String(value).padStart(2, '0');
  return `${pad(time.getHours())}:${pad(time.getMinutes())}`;
}

/**
 * 状态条文案（design-system §13「状态提示」照抄）：
 * 「已自动保存 HH:mm」/「有改动待同步（每 2 分钟）」/「离线 · 改动存在本机草稿」/
 * 「版本冲突 · 自动保存已暂停」；idle 不显示；saving 为瞬时过渡态「正在保存…」。
 */
export function autoSaveStatusText(
  status: AutoSaveStatus,
  lastSavedAt: null | string,
): string {
  switch (status) {
    case 'conflict': {
      return '版本冲突 · 自动保存已暂停';
    }
    case 'dirty': {
      return '有改动待同步（每 2 分钟）';
    }
    case 'offline': {
      return '离线 · 改动存在本机草稿';
    }
    case 'saved': {
      return lastSavedAt ? `已自动保存 ${lastSavedAt}` : '已自动保存';
    }
    case 'saving': {
      return '正在保存…';
    }
    default: {
      return '';
    }
  }
}

/** useAutoSave 返回（components.md §1.4 AutoSaveState + 页面适配扩展）。 */
export interface AutoSaveApi {
  conflict: Ref<AutoSaveConflict | null>;
  /** 丢弃本地草稿（提交成功后调用，避免重开误提示恢复）。 */
  discardLocalDraft(): Promise<void>;
  /** 打开时提示「发现未提交的草稿，是否恢复」。 */
  hasLocalDraft(): Promise<boolean>;
  /** 「保留我的并覆盖」：以 serverVersion 作 baseVersion 重发并带 overwrite。 */
  keepMine(): Promise<void>;
  /** 最近一次服务器草稿保存时间（HH:mm；null = 尚未保存过）。 */
  lastSavedAt: Ref<null | string>;
  /** 「载入服务器版本」：本地改动另存一份副本（`:conflict-copy` 键）后重拉服务器。 */
  loadServer(): Promise<void>;
  /** 字段 blur（FormRenderer fieldBlur）：有改动立即写一次本地草稿。 */
  noteBlur(): void;
  /** 取回本地草稿（恢复时应用到表单）；无草稿返回 null。 */
  restoreLocalDraft(): Promise<LocalDraftSnapshot | null>;
  /** 「暂存」按钮 / Ctrl/⌘+S；true = 服务器已有最新改动（含无改动的成功空操作）。 */
  saveNow(): Promise<boolean>;
  status: Ref<AutoSaveStatus>;
}

/** 409 冲突详情 → 冲突条数据（显示名优先，缺省回退字段稳定键）。 */
function toConflict(detail: DraftConflictDetail): AutoSaveConflict {
  return {
    updatedBy: detail.updatedBy ?? '',
    updatedAt: detail.updatedAt,
    fields: detail.diffFields.map((field) => field.label ?? field.key),
    serverVersion: detail.currentVersion,
  };
}

export function useAutoSave(options: AutoSaveOptions): AutoSaveApi {
  const {
    intervals: {
      localDraftIntervalMs = timings.localDraftIntervalMs,
      serverDraftIntervalMs = timings.serverDraftIntervalMs,
    } = {},
    storage = defaultDraftStorage(),
  } = options;

  const status = ref<AutoSaveStatus>('idle');
  const lastSavedAt = ref<null | string>(null);
  const conflict = ref<AutoSaveConflict | null>(null);
  let inFlight = false;

  const dirtyNow = (): boolean =>
    options.isDirty
      ? options.isDirty.value
      : Object.keys(options.getChanges()).length > 0;

  const isOnline = (): boolean =>
    typeof navigator === 'undefined' ? true : navigator.onLine !== false;

  /** 冲突中 / 已提交只读：定时器到点直接跳过（「暂停自动保存」）。 */
  const active = (): boolean =>
    options.enabled.value && status.value !== 'conflict';

  function normalizeAfterDirtyChange(): void {
    if (status.value === 'saving' || status.value === 'conflict') return;
    if (status.value === 'offline') return; // 离线状态优先展示，联网后再归一
    if (dirtyNow()) {
      if (status.value !== 'dirty') status.value = 'dirty';
    } else if (status.value === 'dirty') {
      status.value = lastSavedAt.value ? 'saved' : 'idle';
    }
  }

  async function writeLocalDraft(key = options.recordKey.value): Promise<void> {
    try {
      const snapshot = options.getSnapshot();
      snapshot.savedAt = new Date().toISOString();
      await storage.put(key, snapshot);
    } catch {
      // 本地草稿尽力而为：存储失败不阻断录入
    }
  }

  async function doSave(
    trigger: AutoSaveTrigger,
    baseVersionOverride?: number,
  ): Promise<boolean> {
    if (!options.enabled.value) return false;
    if (status.value === 'conflict' && trigger !== 'keepMine') return false;
    if (inFlight) return false;
    const changes = options.getChanges();
    if (Object.keys(changes).length === 0) {
      // 有改动才保存（NFR-SAVE-001）：无改动直接视为成功并归一状态
      normalizeAfterDirtyChange();
      return true;
    }
    if (!isOnline()) {
      status.value = 'offline';
      await writeLocalDraft();
      return false;
    }
    inFlight = true;
    status.value = 'saving';
    try {
      const result = await options.saveDraft({
        baseVersion: baseVersionOverride ?? options.getBaseVersion(),
        changes,
        ...(trigger === 'keepMine' ? { overwrite: true } : {}),
      });
      lastSavedAt.value = hhmmOf(result.savedAt);
      status.value = 'saved';
      conflict.value = null;
      // 服务器已有最新改动：清掉本机草稿，重开时不再误提示「发现未提交的草稿」
      try {
        await storage.remove(options.recordKey.value);
      } catch {
        // 清理失败不影响保存结果
      }
      await options.onSaved?.(result, trigger);
      return true;
    } catch (error) {
      const detail =
        error instanceof DraftConflictError
          ? error.detail
          : asDraftConflictDetail(error);
      if (detail) {
        conflict.value = toConflict(detail);
        status.value = 'conflict'; // 暂停自动保存（active() 恒 false，直到用户处理）
        await writeLocalDraft(); // 冲突期间数据也不丢
        options.onConflict?.(conflict.value);
      } else {
        status.value = 'offline';
        await writeLocalDraft();
        options.onOffline?.(error);
      }
      return false;
    } finally {
      inFlight = false;
    }
  }

  // ------------------------------------------------------------ 本地 / 服务器定时器

  const localTimer = setInterval(() => {
    if (!active() || !dirtyNow()) return;
    void writeLocalDraft();
  }, localDraftIntervalMs);

  const serverTimer = setInterval(() => {
    if (!active()) return;
    void doSave('auto');
  }, serverDraftIntervalMs);

  // ------------------------------------------------------------ 离线 / 恢复网络

  function handleOffline(): void {
    if (status.value !== 'conflict') status.value = 'offline';
  }

  function handleOnline(): void {
    if (status.value !== 'offline') return;
    if (dirtyNow()) {
      status.value = 'dirty';
    } else if (lastSavedAt.value) {
      status.value = 'saved';
    } else {
      status.value = 'idle';
    }
    if (dirtyNow() && options.enabled.value) {
      void doSave('reconnect'); // 恢复网络后立即同步一次（NFR-SAVE-002）
    }
  }

  if (typeof window !== 'undefined') {
    window.addEventListener('online', handleOnline);
    window.addEventListener('offline', handleOffline);
  }

  // ------------------------------------------------------------ 响应式联动

  if (options.isDirty) {
    watch(options.isDirty, normalizeAfterDirtyChange);
  }
  watch(options.enabled, (enabled) => {
    if (!enabled) {
      conflict.value = null;
      status.value = 'idle';
    }
  });

  onScopeDispose(() => {
    clearInterval(localTimer);
    clearInterval(serverTimer);
    if (typeof window !== 'undefined') {
      window.removeEventListener('online', handleOnline);
      window.removeEventListener('offline', handleOffline);
    }
  });

  return {
    conflict,
    async discardLocalDraft() {
      try {
        await storage.remove(options.recordKey.value);
      } catch {
        // 清理失败不影响流程
      }
    },
    async hasLocalDraft() {
      return (await storage.get(options.recordKey.value)) !== null;
    },
    lastSavedAt,
    async keepMine() {
      if (!conflict.value || inFlight) return;
      await doSave('keepMine', conflict.value.serverVersion);
    },
    async loadServer() {
      if (!conflict.value) return;
      // 自己的改动另存本机一份副本（可对照）
      await writeLocalDraft(`${options.recordKey.value}:conflict-copy`);
      // 先清正式草稿键再让页面重拉（页面打开时的「发现未提交的草稿」提示不再误触发）
      try {
        await storage.remove(options.recordKey.value);
      } catch {
        // 清理失败不影响流程
      }
      await options.restoreServer?.();
      conflict.value = null;
      status.value = dirtyNow() ? 'dirty' : 'idle';
    },
    noteBlur() {
      if (!active() || !dirtyNow()) return;
      void writeLocalDraft();
    },
    async restoreLocalDraft() {
      return storage.get(options.recordKey.value);
    },
    saveNow() {
      return doSave('manual');
    },
    status,
  };
}
