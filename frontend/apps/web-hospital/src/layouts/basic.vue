<script lang="ts" setup>
import { computed, watch } from 'vue';
import { useRouter } from 'vue-router';

import { AuthenticationLoginExpiredModal } from '@vben/common-ui';
import { useWatermark } from '@vben/hooks';
import { BasicLayout, LockScreen, UserDropdown } from '@vben/layouts';
import { preferences } from '@vben/preferences';
import { useAccessStore, useUserStore } from '@vben/stores';

import { tenantViewWatermarkText } from '@crdp/api';
import { useResponsiveShell } from '@crdp/responsive';

import NoticeBell from '#/components/notify/notice-bell.vue';
import TenantViewBanner from '#/components/view/tenant-view-banner.vue';
import { useAuthStore, useTenantViewStore } from '#/store';
import LoginForm from '#/views/_core/authentication/login.vue';

// E24-T01 / NFR-RESP-001 三档外壳布局：平板菜单收成图标栏、手机菜单进抽屉并
// 关闭多标签页（design-system §12）。
useResponsiveShell();

const userStore = useUserStore();
const authStore = useAuthStore();
const accessStore = useAccessStore();
const tenantViewStore = useTenantViewStore();
const { destroyWatermark, updateWatermark } = useWatermark();

// E20-T04 / PLT-VIEW-002：me 为 TENANT_VIEW 时进入视角模式——按令牌找回
// 横幅会话信息（会话号 / 租户 / 剩余时长，PLT-VIEW-003），刷新后横幅仍在。
watch(
  () => userStore.userInfo?.sessionType,
  (sessionType) => {
    if (sessionType === 'TENANT_VIEW') {
      void tenantViewStore.rehydrate();
    }
  },
  { immediate: true },
);

// E20-T04 / PLT-VIEW-003：全页水印含平台账号与会话号（视角期间常驻，退出即撤）。
watch(
  () => tenantViewStore.session,
  async (session) => {
    if (session) {
      await updateWatermark({ content: tenantViewWatermarkText(session) });
    } else {
      destroyWatermark();
    }
  },
);

const avatar = computed(() => {
  return userStore.userInfo?.avatar ?? preferences.app.defaultAvatar;
});

const description = computed(() => {
  const info = userStore.userInfo;
  if (!info) {
    return '';
  }
  const tenant = info.tenantName as null | string | undefined;
  return tenant ? `${tenant} · ${info.username}` : info.username;
});

async function handleLogout() {
  // E20-T04 / PLT-VIEW-003：视角模式下的「退出登录」即一键退出租户视角
  // （视角令牌不是医院账号会话，走视角收尾而不是医院登出）。
  if (tenantViewStore.active) {
    await tenantViewStore.exit('LOGOUT');
    return;
  }
  await authStore.logout(false);
}

const router = useRouter();

/**
 * E06-T11 / PLT-ORG-011：顶栏用户菜单提供「个人中心」入口（/profile，登录即可，
 * 不占侧栏菜单——后端菜单按权限下发，个人中心对全员开放）。
 */
const userMenus = [
  {
    handler: () => {
      router.push('/profile');
    },
    icon: 'ant-design:user-outlined',
    text: '个人中心',
  },
];
</script>

<template>
  <!-- E20-T04 / PLT-VIEW-003：视角模式在主壳上方常驻视角横幅（橙色、不可关闭、一键退出） -->
  <div class="hospital-shell">
    <TenantViewBanner
      v-if="tenantViewStore.active && tenantViewStore.session"
      :remaining-seconds="tenantViewStore.remainingSeconds"
      :session="tenantViewStore.session"
      @exit="tenantViewStore.exit('LOGOUT')"
    />
    <BasicLayout
      class="hospital-shell__layout"
      @clear-preferences-and-logout="handleLogout"
    >
      <!-- E09-T02 / A-NTF-002、C-NTF-002：顶栏站内信铃铛（未读角标 60 秒轮询，点击消息跳到对应任务）；
           视角模式不轮询业务接口（视角令牌 fail closed，随 E20-T03 放开） -->
      <template v-if="!tenantViewStore.active" #notification>
        <NoticeBell />
      </template>
      <template #user-dropdown>
        <UserDropdown
          :avatar
          :description="description"
          :menus="userMenus"
          :tag-text="
            tenantViewStore.active
              ? '视角'
              : userStore.userInfo?.tenantCode
                ? '医院'
                : '平台'
          "
          :text="userStore.userInfo?.realName"
          @clear-preferences-and-logout="handleLogout"
          @logout="handleLogout"
        />
      </template>
      <template #extra>
        <AuthenticationLoginExpiredModal
          v-model:open="accessStore.loginExpired"
          :avatar
        >
          <LoginForm />
        </AuthenticationLoginExpiredModal>
      </template>
      <template #lock-screen>
        <LockScreen :avatar @to-login="handleLogout" />
      </template>
    </BasicLayout>
  </div>
</template>

<style scoped>
.hospital-shell {
  display: flex;
  flex-direction: column;
  height: 100vh;
}

.hospital-shell__layout {
  flex: 1;
  min-height: 0;
}
</style>
