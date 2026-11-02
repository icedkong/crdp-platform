<script lang="ts" setup>
import { computed } from 'vue';
import { useRouter } from 'vue-router';

import { AuthenticationLoginExpiredModal } from '@vben/common-ui';
import { BasicLayout, LockScreen, UserDropdown } from '@vben/layouts';
import { preferences } from '@vben/preferences';
import { useAccessStore, useUserStore } from '@vben/stores';

import NoticeBell from '#/components/notify/notice-bell.vue';
import { useAuthStore } from '#/store';
import LoginForm from '#/views/_core/authentication/login.vue';

const userStore = useUserStore();
const authStore = useAuthStore();
const accessStore = useAccessStore();

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
  <BasicLayout @clear-preferences-and-logout="handleLogout">
    <!-- E09-T02 / A-NTF-002、C-NTF-002：顶栏站内信铃铛（未读角标 60 秒轮询，点击消息跳到对应任务） -->
    <template #notification>
      <NoticeBell />
    </template>
    <template #user-dropdown>
      <UserDropdown
        :avatar
        :description="description"
        :menus="userMenus"
        :tag-text="userStore.userInfo?.tenantCode ? '医院' : '平台'"
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
</template>
