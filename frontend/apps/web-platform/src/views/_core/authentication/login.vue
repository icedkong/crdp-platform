<script lang="ts" setup>
import { AuthenticationLogin } from '@vben/common-ui';

import { useAuthStore } from '#/store';

import { platformLoginPayload, platformLoginSchema } from './login-schema';

defineOptions({ name: 'Login' });

const authStore = useAuthStore();

function onSubmit(values: Record<string, boolean | string>) {
  // 平台端独立登录（E04-T03）：只提交账号与密码，不带任何租户上下文
  return authStore.authLogin(platformLoginPayload(values));
}
</script>

<template>
  <AuthenticationLogin
    :form-schema="platformLoginSchema()"
    :loading="authStore.loginLoading"
    :show-code-login="false"
    :show-forget-password="false"
    :show-qrcode-login="false"
    :show-register="false"
    :show-third-party-login="false"
    sub-title="平台端。演示环境只有虚构账号。"
    title="临床科研数据一体化平台"
    @submit="onSubmit"
  />
</template>
