import { ref } from 'vue';

import { validateTenantViewProxyReason } from '@crdp/api';
import { defineStore } from 'pinia';

/**
 * 代为操作原因（E20-T06 / PLT-VIEW-013「二次确认并填写原因」）：视角模式下写请求统一经
 * `requestReason()` 收集原因并随 `X-View-Reason` 头提交（encodeURIComponent 编码），
 * 由请求拦截器注入——各页面无需逐个改造。
 *
 * 「本次会话沿用」勾选后缓存原因：显式保存 / 提交仍会弹出（预填上次原因，二次确认），
 * 表单自动保存等后台写直接复用缓存，不打断录入。
 */
export const useProxyReasonStore = defineStore(
  'tenant-view-proxy-reason',
  () => {
    const open = ref(false);
    const draft = ref('');
    const errors = ref<string[]>([]);
    const remember = ref(true);
    const accepted = ref('');
    let resolver: ((reason: null | string) => void) | null = null;

    /** 收集本次写操作的原因：已勾选沿用且有缓存 → 直接复用；否则弹二次确认（预填上次原因）。取消返回 null。 */
    async function requestReason(): Promise<null | string> {
      if (!open.value && remember.value && accepted.value) {
        return accepted.value;
      }
      if (open.value && resolver) {
        // 已有弹窗等待中：合并为一次确认（并发写共用同一原因）
        return new Promise((resolve) => {
          const previous = resolver;
          resolver = (reason) => {
            previous?.(reason);
            resolve(reason);
          };
        });
      }
      draft.value = accepted.value;
      errors.value = [];
      open.value = true;
      return new Promise((resolve) => {
        resolver = resolve;
      });
    }

    function confirm() {
      errors.value = validateTenantViewProxyReason(draft.value);
      if (errors.value.length > 0) return;
      accepted.value = draft.value.trim();
      open.value = false;
      resolver?.(accepted.value);
      resolver = null;
    }

    function cancel() {
      open.value = false;
      resolver?.(null);
      resolver = null;
    }

    function $reset() {
      open.value = false;
      draft.value = '';
      errors.value = [];
      accepted.value = '';
      remember.value = true;
      resolver = null;
    }

    return {
      $reset,
      accepted,
      cancel,
      confirm,
      draft,
      errors,
      open,
      remember,
      requestReason,
    };
  },
);
