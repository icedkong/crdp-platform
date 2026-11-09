import type { Pinia } from 'pinia';

import type { App } from 'vue';

import { createPinia } from 'pinia';
import SecureLS from 'secure-ls';

let pinia: Pinia;

type SecureLSStorage = {
  get(key: string): any;
  set(key: string, value: unknown): void;
};

type SecureLSCtor = new (config?: {
  encodingType?: string;
  encryptionSecret?: string;
  isCompression?: boolean;
  metaKey?: string;
}) => SecureLSStorage;

const secureLSModule = SecureLS as unknown as {
  default?: SecureLSCtor;
  SecureLS?: SecureLSCtor;
};

const SecureLSConstructor =
  secureLSModule.default ??
  secureLSModule.SecureLS ??
  (SecureLS as unknown as SecureLSCtor);

export interface InitStoreOptions {
  /**
   * @zh_CN 应用名,由于 @vben/stores 是公用的，后续可能有多个app，为了防止多个app缓存冲突，可在这里配置应用名,应用名将被用于持久化的前缀
   */
  namespace: string;
}

/**
 * @zh_CN 初始化pinia
 */
export async function initStores(app: App, options: InitStoreOptions) {
  const { createPersistedState } = await import('pinia-plugin-persistedstate');
  pinia = createPinia();
  const { namespace } = options;
  const ls = new SecureLSConstructor({
    encodingType: 'aes',
    encryptionSecret: import.meta.env.VITE_APP_STORE_SECURE_KEY,
    isCompression: true,
    metaKey: `${namespace}-secure-meta`,
  });
  pinia.use(
    createPersistedState({
      // key $appName-$store.id
      key: (storeKey) => `${namespace}-${storeKey}`,
      storage: import.meta.env.DEV
        ? localStorage
        : {
            getItem(key) {
              return ls.get(key);
            },
            setItem(key, value) {
              ls.set(key, value);
            },
          },
    }),
  );
  // E22-T11 / PT-SELF-05：access store 的会话令牌已迁往 sessionStorage
  // （ACCESS_PERSIST_OPTIONS）；这里清掉旧版本遗留在 localStorage 的
  // `${namespace}-core-access` 键，避免升级部署后 TTL 内仍有效的旧令牌
  // 留在盘上可被恢复复用（SecureLS 只加密值，键名不变，removeItem 即可）。
  localStorage.removeItem(`${namespace}-core-access`);
  app.use(pinia);
  return pinia;
}

export function resetAllStores() {
  if (!pinia) {
    console.error('Pinia is not installed');
    return;
  }
  const allStores = (pinia as any)._s;
  for (const [_key, store] of allStores) {
    store.$reset();
  }
}
