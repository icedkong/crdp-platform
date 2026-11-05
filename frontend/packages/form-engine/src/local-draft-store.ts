/**
 * NFR-SAVE-002 · 本地草稿存储（design-system §13「本地草稿」、components.md §1.4）。
 *
 * - IndexedDB（库名 crdp-drafts、对象仓库 drafts，键 = `${tenantId}:${userId}:${kind}:${id}`）；
 * - 环境没有 IndexedDB（私有模式 / 旧浏览器 / 测试环境）时静默回退内存实现，
 *   断网暂存尽力而为、绝不因存储失败打断录入；
 * - **只存表单草稿**：明文查看结果不写本地（AGENTS.md 第 5 节规则 3）；
 * - 退出登录清空本机全部草稿（`clearAllLocalDrafts`，由 auth store 调用）。
 */

/** 一份本机草稿快照：恢复界面所需的全量内容。 */
export interface LocalDraftSnapshot {
  /** 写入时的服务器版本（恢复后作为乐观锁基准的参考）。 */
  baseVersion: number;
  data: Record<string, unknown>;
  meta?: Record<string, unknown>;
  /** 写入时间（ISO-8601 带时区）。 */
  savedAt: string;
}

/** 存储适配（useAutoSave 注入；测试用内存实现）。 */
export interface LocalDraftStorage {
  clearAll(): Promise<void>;
  get(key: string): Promise<LocalDraftSnapshot | null>;
  put(key: string, snapshot: LocalDraftSnapshot): Promise<void>;
  remove(key: string): Promise<void>;
}

/** 内存实现（测试 / IndexedDB 不可用时的回退）。 */
export function createMemoryDraftStorage(): LocalDraftStorage {
  const map = new Map<string, LocalDraftSnapshot>();
  return {
    async clearAll() {
      map.clear();
    },
    async get(key) {
      return map.get(key) ?? null;
    },
    async put(key, snapshot) {
      map.set(key, snapshot);
    },
    async remove(key) {
      map.delete(key);
    },
  };
}

const DB_NAME = 'crdp-drafts';
const DB_VERSION = 1;
const STORE = 'drafts';

function openDatabase(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    const request = indexedDB.open(DB_NAME, DB_VERSION);
    request.onupgradeneeded = () => {
      const db = request.result;
      if (!db.objectStoreNames.contains(STORE)) {
        db.createObjectStore(STORE); // 键由 put(value, key) 提供
      }
    };
    request.onsuccess = () => resolve(request.result);
    request.addEventListener('error', () => reject(request.error));
  });
}

function transactionDone(tx: IDBTransaction): Promise<void> {
  return new Promise((resolve, reject) => {
    tx.oncomplete = () => resolve();
    tx.addEventListener('error', () => reject(tx.error));
    tx.addEventListener('abort', () => reject(tx.error));
  });
}

/**
 * IndexedDB 实现：懒建库，任何一步失败（打开 / 事务）都回退内存实现——
 * 本地草稿是「断网不丢数据」的兜底，不能反过来阻断录入。
 */
export function createIndexedDbDraftStorage(): LocalDraftStorage {
  const memory = createMemoryDraftStorage();
  let dbPromise: null | Promise<IDBDatabase | null> = null;

  function db(): Promise<IDBDatabase | null> {
    dbPromise ??= (async () => {
      try {
        return await openDatabase();
      } catch {
        return null;
      }
    })();
    return dbPromise;
  }

  async function run(
    action: (store: IDBObjectStore) => IDBRequest,
  ): Promise<null | unknown> {
    const conn = await db();
    if (!conn) return null;
    try {
      const tx = conn.transaction(STORE, 'readwrite');
      const request = action(tx.objectStore(STORE));
      const done = transactionDone(tx);
      const result = await new Promise<unknown>((resolve, reject) => {
        request.onsuccess = () => resolve(request.result);
        request.addEventListener('error', () => reject(request.error));
      });
      await done;
      return result;
    } catch {
      return null;
    }
  }

  return {
    async clearAll() {
      const conn = await db();
      if (!conn) {
        await memory.clearAll();
        return;
      }
      await run((store) => store.clear());
    },
    async get(key) {
      const conn = await db();
      if (!conn) return memory.get(key);
      const value = await run((store) => store.get(key));
      return (value as LocalDraftSnapshot | undefined) ?? null;
    },
    async put(key, snapshot) {
      const conn = await db();
      if (!conn) {
        await memory.put(key, snapshot);
        return;
      }
      await run((store) => store.put(snapshot, key));
    },
    async remove(key) {
      const conn = await db();
      if (!conn) {
        await memory.remove(key);
        return;
      }
      await run((store) => store.delete(key));
    },
  };
}

/** 默认存储实例（页面不注入时使用）。 */
let defaultStorage: LocalDraftStorage | null = null;

export function defaultDraftStorage(): LocalDraftStorage {
  defaultStorage ??= createIndexedDbDraftStorage();
  return defaultStorage;
}

/** 退出登录时清空本机全部草稿（auth store 调用；失败静默）。 */
export async function clearAllLocalDrafts(): Promise<void> {
  try {
    await defaultDraftStorage().clearAll();
  } catch {
    // 本机清理失败不阻断登出
  }
}
