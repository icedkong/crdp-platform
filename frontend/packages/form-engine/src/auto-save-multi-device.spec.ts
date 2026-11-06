// E25-T03 / NFR-SAVE-001、002、003 · 多设备同时编辑：两台设备（两个 useAutoSave 实例，
// 各自独立本机草稿存储）共用一份「服务器」替身，走完整的定时 → 冲突 → 二选一 → 离线链路。
//
// E25-T02 的 auto-save.spec 覆盖单设备分支（30s / 2min / blur / 离线 / 409 / 恢复网络）；
// 本文件补「多设备」视角：同一记录两台设备定时保存交错时后到者 409、冲突随「保留我的并
// 覆盖」传播回先保存的一侧、「载入服务器版本」重拉对齐后回到正常循环、一台离线期间另一台
// 保存多轮且离线侧恢复网络立即同步仍 409（本机草稿不丢、两台设备存储互不可见）。
import type { AutoSaveSavedView } from './auto-save';
import type { LocalDraftSnapshot } from './local-draft-store';

import { computed, effectScope, ref } from 'vue';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { useAutoSave } from './auto-save';
import { createMemoryDraftStorage } from './local-draft-store';

/**
 * E25-T01 契约的内存服务器替身（docs/api/followup.md §3）：
 *
 * - baseVersion 与当前版本一致才合并 changes、version + 1；
 * - 不一致抛 axios 形态 40900 错误，`data` 为 patient.md §4.1 冲突详情
 *   （currentVersion / updatedBy 显示名 / updatedAt / diffFields 只列 changes
 *   两边取值不同的键，label 取模板显示名）；
 * - `overwrite: true` = 「保留我的并覆盖」：正常保存并记覆盖者（RECORD_DRAFT_OVERWRITE
 *   审计替身，后端行为由 RecordDraftIT 校验）；
 * - `drop` / `restoreUser` 模拟某一台设备断网 / 恢复（该设备的请求抛网络错误——
 *   NFR-SAVE-002「请求失败或断网时只存本地」；jsdom 的 navigator.onLine 是两台设备
 *   共享的全局量，不能用来表达「只有一台设备离线」）；
 * - 版本检查与落库在首个 await 前同步完成——同一 tick 内两台设备的定时器先后触发时
 *   顺序确定（先挂载的设备先触发），与真实服务器「先到先得」一致。
 */
class FakeDraftServer {
  data: Record<string, unknown>;
  overwrites: string[] = [];
  version: number;

  /** 最近一次保存时间（冲突详情的 updatedAt 即「对方刚保存」的时间）。 */
  get lastSavedIso(): string {
    return this.savedAt;
  }

  private editor: string;
  private savedAt = new Date(2026, 10, 5, 13, 58).toISOString();
  private readonly unreachable = new Set<string>();

  constructor(
    initialData: Record<string, unknown>,
    version = 2,
    editor = '服务器',
  ) {
    this.data = { ...initialData };
    this.version = version;
    this.editor = editor;
  }

  /** 该设备所在网络断开（此后它的保存请求抛网络错误）。 */
  drop(editor: string): void {
    this.unreachable.add(editor);
  }

  /** GET /records/{id}（「载入服务器版本」时页面重拉的 data / version）。 */
  load(): { data: Record<string, unknown>; version: number } {
    return { data: { ...this.data }, version: this.version };
  }

  /** 该设备所在网络恢复（保存请求重新可达）。 */
  restoreUser(editor: string): void {
    this.unreachable.delete(editor);
  }

  /** PUT /records/{id}/draft（页面适配层把 40900 转为 DraftConflictError 的入口）。 */
  save(
    editor: string,
    body: {
      baseVersion: number;
      changes: Record<string, unknown>;
      overwrite?: boolean;
    },
  ): Promise<AutoSaveSavedView> {
    if (this.unreachable.has(editor)) throw new Error('Network Error');
    if (body.baseVersion !== this.version) {
      const diffFields = Object.entries(body.changes)
        .filter(([key, mine]) => !Object.is(mine, this.data[key] ?? null))
        .map(([key, mine]) => ({
          key,
          label: LABELS[key] ?? null,
          mine,
          server: this.data[key] ?? null,
        }));
      throw Object.assign(new Error('Request failed with status code 409'), {
        response: {
          data: {
            code: 40_900,
            data: {
              currentVersion: this.version,
              diffFields,
              updatedAt: this.savedAt,
              updatedBy: this.editor,
            },
            message: '该记录已被他人更新',
          },
        },
      });
    }
    this.data = { ...this.data, ...body.changes };
    this.version += 1;
    this.editor = editor;
    this.savedAt = new Date(2026, 10, 5, 14, this.version).toISOString();
    if (body.overwrite) this.overwrites.push(editor);
    return Promise.resolve({ savedAt: this.savedAt, version: this.version });
  }
}

/** 模板显示名替身（后端 label 取模板版本快照，IT 里为「随访日期」等）。 */
const LABELS: Record<string, string> = {
  q01_ef: 'EF 射血分数',
  q02_lvd: 'LVD 舒张末内径',
};

interface Device {
  api: ReturnType<typeof useAutoSave>;
  form: ReturnType<typeof createDeviceForm>;
  key: string;
  scope: ReturnType<typeof effectScope>;
  storage: ReturnType<typeof createMemoryDraftStorage>;
}

/** 受控表单替身（与 auto-save.spec 的 fixture 同构，初始值取自「打开页面时」的服务器数据）。 */
function createDeviceForm(server: FakeDraftServer) {
  const initial = server.load();
  const data = ref<Record<string, unknown>>({ ...initial.data });
  const savedData = ref<Record<string, unknown>>({ ...initial.data });
  const version = ref(initial.version);
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

/**
 * 挂载一台设备：独立 effectScope（独立定时器 / online 事件监听）、独立本机草稿存储
 * （两台设备各自的 IndexedDB），onSaved / restoreServer 照抄 record/form.vue 的页面适配
 * （保存成功以服务器版本为新基准；「载入服务器版本」重拉 data / version）。
 */
function mountDevice(
  server: FakeDraftServer,
  options: { key: string; user: string },
): Device {
  const form = createDeviceForm(server);
  const storage = createMemoryDraftStorage();
  const scope = effectScope();
  const api = scope.run(() =>
    useAutoSave({
      enabled: ref(true),
      getBaseVersion: () => form.version.value,
      getChanges: form.changes,
      getSnapshot: (): LocalDraftSnapshot => ({
        baseVersion: form.version.value,
        data: { ...form.data.value },
        meta: {},
      }),
      isDirty: form.dirty,
      kind: 'followup',
      onSaved(result) {
        form.version.value = result.version;
        form.savedData.value = { ...form.data.value };
      },
      recordKey: ref(options.key),
      async restoreServer() {
        const remote = server.load();
        form.data.value = remote.data;
        form.savedData.value = { ...remote.data };
        form.version.value = remote.version;
      },
      saveDraft: (body) => server.save(options.user, body),
      storage,
    }),
  );
  if (!api) throw new Error('scope');
  return { api, form, key: options.key, scope, storage };
}

/** 王医生的电脑与李医生的平板：同一记录、同一服务器、各自本机草稿。 */
function mountTwoDevices() {
  const server = new FakeDraftServer({ q01_ef: 1, q02_lvd: 2 }, 2);
  const doctor = mountDevice(server, {
    key: 'demoa:101:followup:701',
    user: '王医生（示例）',
  });
  const nurse = mountDevice(server, {
    key: 'demoa:202:followup:701',
    user: '李医生（示例）',
  });
  return { doctor, nurse, server };
}

beforeEach(() => {
  vi.useFakeTimers();
});

afterEach(() => {
  vi.useRealTimers();
  vi.restoreAllMocks();
});

describe('多设备定时保存交错：先到者成功、后到者 409 并暂停（E25-T03）', () => {
  it('同一 tick 两台设备到点：先触发者保存成功，后到者持旧 baseVersion → 409 冲突', async () => {
    const { doctor, nurse, server } = mountTwoDevices();
    doctor.form.data.value = { q01_ef: 45, q02_lvd: 2 }; // 王医生改 EF
    nurse.form.data.value = { q01_ef: 1, q02_lvd: 58 }; // 李医生改 LVD

    await vi.advanceTimersByTimeAsync(120_000);
    // 王医生先挂载先触发：保存成功，服务器合并其改动
    expect(doctor.api.status.value).toBe('saved');
    expect(server.version).toBe(3);
    expect(server.data).toEqual({ q01_ef: 45, q02_lvd: 2 });
    // 李医生仍持 v2 → 40900：冲突详情指向王医生（更新人 / 服务器版本 / 差异字段显示名）
    expect(nurse.api.status.value).toBe('conflict');
    expect(nurse.api.conflict.value).toEqual({
      fields: ['LVD 舒张末内径'],
      serverVersion: 3,
      updatedAt: server.lastSavedIso,
      updatedBy: '王医生（示例）',
    });
  });

  it('后到者冲突暂停期间服务器版本不再前进；先到者继续定时保存不受影响', async () => {
    const { doctor, nurse, server } = mountTwoDevices();
    doctor.form.data.value = { q01_ef: 45, q02_lvd: 2 };
    nurse.form.data.value = { q01_ef: 1, q02_lvd: 58 };
    await vi.advanceTimersByTimeAsync(120_000);
    expect(nurse.api.status.value).toBe('conflict');

    // 李医生冲突暂停：接下来两个周期到点都不再请求（服务器停在 v3）
    await vi.advanceTimersByTimeAsync(240_000);
    expect(server.version).toBe(3);
    expect(nurse.api.status.value).toBe('conflict');

    // 王医生继续编辑：下一周期照常保存（v3 → v4），不受李医生冲突影响
    doctor.form.data.value = { q01_ef: 50, q02_lvd: 2 };
    await vi.advanceTimersByTimeAsync(120_000);
    expect(doctor.api.status.value).toBe('saved');
    expect(server.version).toBe(4);
    expect(server.data.q01_ef).toBe(50);
  });
});

describe('多设备冲突二选一（E25-T03）', () => {
  /** 推进到「李医生 409 冲突中、服务器 v3 为王医生版本」的公共起点。 */
  async function nurseConflict() {
    const devices = mountTwoDevices();
    devices.doctor.form.data.value = { q01_ef: 45, q02_lvd: 2 };
    devices.nurse.form.data.value = { q01_ef: 1, q02_lvd: 58 };
    await vi.advanceTimersByTimeAsync(120_000);
    expect(devices.nurse.api.status.value).toBe('conflict');
    return devices;
  }

  it('保留我的并覆盖：李医生以 serverVersion 重发（overwrite），冲突随覆盖传播回王医生一侧', async () => {
    const { doctor, nurse, server } = await nurseConflict();

    await nurse.api.keepMine();
    expect(server.version).toBe(4);
    expect(server.data).toEqual({ q01_ef: 45, q02_lvd: 58 });
    expect(server.overwrites).toEqual(['李医生（示例）']);
    expect(nurse.api.status.value).toBe('saved');
    expect(nurse.api.conflict.value).toBeNull();

    // 王医生仍持 v3：下一次到点保存 → 409，更新人指向覆盖者李医生
    doctor.form.data.value = { q01_ef: 50, q02_lvd: 2 };
    await vi.advanceTimersByTimeAsync(120_000);
    expect(doctor.api.status.value).toBe('conflict');
    expect(doctor.api.conflict.value).toEqual({
      fields: ['EF 射血分数'],
      serverVersion: 4,
      updatedAt: server.lastSavedIso,
      updatedBy: '李医生（示例）',
    });
  });

  it('载入服务器版本：王医生重拉对齐（data / version 以服务器为准）后回到正常定时保存', async () => {
    const { doctor, nurse, server } = await nurseConflict();
    // 李医生先覆盖（v4），王医生随后到点保存也 409
    await nurse.api.keepMine();
    doctor.form.data.value = { q01_ef: 50, q02_lvd: 2 };
    await vi.advanceTimersByTimeAsync(120_000);
    expect(doctor.api.status.value).toBe('conflict');

    await doctor.api.loadServer();
    expect(doctor.form.data.value).toEqual({ q01_ef: 45, q02_lvd: 58 });
    expect(doctor.form.version.value).toBe(4);
    expect(doctor.api.conflict.value).toBeNull();
    expect(doctor.api.status.value).toBe('idle');
    // 王医生被覆盖的改动另存本机冲突副本（不丢，可对照）
    expect(
      await doctor.storage.get('demoa:101:followup:701:conflict-copy'),
    ).not.toBeNull();

    // 重拉对齐后继续编辑：下一周期保存成功，回到正常循环
    doctor.form.data.value = { q01_ef: 60, q02_lvd: 58 };
    await vi.advanceTimersByTimeAsync(120_000);
    expect(doctor.api.status.value).toBe('saved');
    expect(server.version).toBe(5);
    expect(server.data).toEqual({ q01_ef: 60, q02_lvd: 58 });
  });
});

describe('一台断网、另一台在线：恢复网络立即同步仍 409，本机草稿不丢（E25-T03）', () => {
  it('王医生断网期间李医生保存两轮；王医生恢复网络 → 立即同步 → 409 冲突', async () => {
    const { doctor, nurse, server } = mountTwoDevices();
    server.drop('王医生（示例）'); // 只有王医生这台设备断网
    doctor.form.data.value = { q01_ef: 45, q02_lvd: 2 };
    await vi.advanceTimersByTimeAsync(120_000);
    // 请求失败 → 只存本地：状态离线、本机草稿照写
    expect(doctor.api.status.value).toBe('offline');
    const draft = await doctor.storage.get('demoa:101:followup:701');
    expect(draft?.data).toEqual({ q01_ef: 45, q02_lvd: 2 });
    expect(draft?.baseVersion).toBe(2);

    // 同一时刻李医生在线：两轮定时保存（v2 → v3 → v4）
    nurse.form.data.value = { q01_ef: 1, q02_lvd: 58 };
    await vi.advanceTimersByTimeAsync(120_000);
    nurse.form.data.value = { q01_ef: 2, q02_lvd: 58 };
    await vi.advanceTimersByTimeAsync(120_000);
    expect(nurse.api.status.value).toBe('saved');
    expect(server.version).toBe(4);
    expect(server.data).toEqual({ q01_ef: 2, q02_lvd: 58 });

    // 王医生网络恢复：立即同步（不等 2 分钟）→ baseVersion 2 已过期 → 409 冲突
    server.restoreUser('王医生（示例）');
    window.dispatchEvent(new window.Event('online'));
    await vi.advanceTimersByTimeAsync(0);
    expect(doctor.api.status.value).toBe('conflict');
    expect(doctor.api.conflict.value?.serverVersion).toBe(4);
    expect(doctor.api.conflict.value?.updatedBy).toBe('李医生（示例）');
    // 冲突期间数据也不丢：本机草稿仍在
    expect(await doctor.storage.get('demoa:101:followup:701')).not.toBeNull();
  });

  it('两台设备本机草稿互不可见；在线一侧保存成功只清自己的草稿', async () => {
    const { doctor, nurse, server } = mountTwoDevices();
    // 王医生断网留草稿（本机 30 秒 / blur 兜底照写）
    server.drop('王医生（示例）');
    doctor.form.data.value = { q01_ef: 45, q02_lvd: 2 };
    doctor.api.noteBlur();
    await vi.advanceTimersByTimeAsync(0);
    // 李医生在线保存成功（自己的草稿写过后被清除）
    nurse.form.data.value = { q01_ef: 1, q02_lvd: 58 };
    nurse.api.noteBlur();
    await vi.advanceTimersByTimeAsync(0);
    await expect(nurse.api.saveNow()).resolves.toBe(true);
    expect(server.version).toBe(3);

    // 各自本机（独立存储）：李医生看不到王医生的草稿，自己的已随服务器保存清除
    expect(await nurse.storage.get('demoa:101:followup:701')).toBeNull();
    expect(await nurse.storage.get('demoa:202:followup:701')).toBeNull();
    // 王医生的草稿仍在本机（离线一侧不受另一台设备保存影响）
    expect(await doctor.storage.get('demoa:101:followup:701')).not.toBeNull();
  });
});
