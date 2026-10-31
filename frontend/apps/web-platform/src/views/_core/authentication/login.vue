<script lang="ts" setup>
import type { VbenFormSchema } from '@vben/common-ui';

import { computed } from 'vue';

import { AuthenticationLogin, z } from '@vben/common-ui';

import { useAuthStore } from '#/store';

defineOptions({ name: 'Login' });

const authStore = useAuthStore();

const formSchema = computed((): VbenFormSchema[] => {
  return [
    {
      component: 'VbenInput',
      componentProps: {
        placeholder: '账号',
      },
      fieldName: 'username',
      label: '账号',
      rules: z
        .string()
        .regex(/^[a-zA-Z][a-zA-Z0-9._-]{1,63}$/, '账号格式不正确'),
    },
    {
      component: 'VbenInputPassword',
      componentProps: {
        placeholder: '密码',
      },
      fieldName: 'password',
      label: '密码',
      rules: z.string().min(8, '密码至少 8 位').max(64, '密码不超过 64 位'),
    },
  ];
});
</script>

<template>
  <AuthenticationLogin
    :form-schema="formSchema"
    :loading="authStore.loginLoading"
    :show-code-login="false"
    :show-forget-password="false"
    :show-qrcode-login="false"
    :show-register="false"
    :show-third-party-login="false"
    sub-title="平台端。演示环境只有虚构账号。"
    title="临床科研数据一体化平台"
    @submit="authStore.authLogin"
  />
</template>
