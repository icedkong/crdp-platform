import type { TenantViewShellSession } from '@crdp/api';

import { computed, ref } from 'vue';
import { useRouter } from 'vue-router';

import { resetAllStores, useAccessStore } from '@vben/stores';

import { TENANT_VIEW_EXIT_PATH } from '@crdp/api';
import { defineStore } from 'pinia';

import {
  enterTenantViewByTicket,
  exitTenantViewSession,
  getTenantViewSession,
} from '#/api';

/**
 * 医院端租户视角会话（E20-T04 / PLT-VIEW-002、003）：
 * 平台端以 60 秒一次性凭据打开 /view/entry，本 store 负责换令牌装壳、
 * 横幅倒计时（剩余时长）、到时 / 失效后的落地与一键退出。
 *
 * 剩余时长口径与 E20-T01 平台端弹窗一致：remainingSeconds 是令牌当前存活窗口
 * （空闲 15 分钟滑动续期、单次最长 2 小时）；本地每秒递减，到 0 不直接退出——
 * 先回服务端刷新实际状态（期间有请求会续期），只有会话真死了才落地退出页。
 */
export const useTenantViewStore = defineStore('tenant-view', () => {
  const router = useRouter();
  const accessStore = useAccessStore();

  const session = ref<null | TenantViewShellSession>(null);
  const remainingSeconds = ref(0);
  let timer: null | ReturnType<typeof setInterval> = null;
  let refreshing = false;

  const active = computed(() => session.value !== null);

  function stopTimer() {
    if (timer) {
      clearInterval(timer);
      timer = null;
    }
  }

  function adopt(next: TenantViewShellSession) {
    session.value = next;
    remainingSeconds.value = Math.max(0, next.remainingSeconds);
    stopTimer();
    timer = setInterval(() => {
      if (remainingSeconds.value > 0) {
        remainingSeconds.value -= 1;
      }
      if (remainingSeconds.value <= 0) {
        // 倒计时归零不等于会话已死（期间有请求会滑动续期）：刷新实际状态
        void refreshAfterCountdown();
      }
    }, 1000);
  }

  async function refreshAfterCountdown() {
    if (!session.value || refreshing) return;
    refreshing = true;
    try {
      const next = await getTenantViewSession();
      adopt(next);
    } catch {
      // 会话已失效（空闲超时 / 到时 / 权限变更）：落地退出页
      await landExit('EXPIRED');
    } finally {
      refreshing = false;
    }
  }

  /**
   * 凭据换取并装壳（/view/entry 页调用）：令牌放 accessStore（只在内存 / 会话存储，
   * 与登录令牌同一位），横幅会话信息由换取响应带回，无需再查一次。
   */
  async function exchangeAndAdopt(
    ticket: string,
  ): Promise<TenantViewShellSession> {
    const result = await enterTenantViewByTicket(ticket);
    accessStore.setAccessToken(result.token);
    adopt(result.session);
    return result.session;
  }

  /** 页面刷新 / 重新打开时按令牌找回会话（守卫取到 me=TENANT_VIEW 后调用）。 */
  async function rehydrate() {
    if (session.value) return;
    try {
      adopt(await getTenantViewSession());
    } catch {
      session.value = null;
      stopTimer();
    }
  }

  /** 清理本端状态（令牌失效 40100 / 退出）：不动服务端。 */
  function clearLocal() {
    session.value = null;
    remainingSeconds.value = 0;
    stopTimer();
  }

  /** 令牌已被服务端判定失效（请求 40100）：不调退出接口，直接落地。 */
  async function landExit(reason: string) {
    clearLocal();
    accessStore.setAccessToken(null);
    resetAllStores();
    accessStore.setLoginExpired(false);
    await router.replace({
      path: TENANT_VIEW_EXIT_PATH,
      query: { reason },
    });
  }

  /** 一键退出（PLT-VIEW-003 横幅按钮）：退自己的会话（失败也落地——会话多半已结束）。 */
  async function exit(reason: string = 'LOGOUT') {
    try {
      await exitTenantViewSession();
    } catch {
      // 会话已结束（到时 / 权限变更）——落地即可
    }
    await landExit(reason);
  }

  function $reset() {
    clearLocal();
    refreshing = false;
  }

  return {
    $reset,
    active,
    adopt,
    clearLocal,
    exchangeAndAdopt,
    exit,
    landExit,
    rehydrate,
    remainingSeconds,
    session,
  };
});
