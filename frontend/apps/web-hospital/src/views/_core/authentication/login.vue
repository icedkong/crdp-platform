<script lang="ts" setup>
import type { VbenFormSchema } from '@vben/common-ui';

import { computed, onMounted, ref } from 'vue';

import { AuthenticationLogin, z } from '@vben/common-ui';

import { useAuthStore } from '#/store';

defineOptions({ name: 'Login' });

const TENANT_KEY = 'crdp.tenantCode';

const authStore = useAuthStore();
const formRef = ref<InstanceType<typeof AuthenticationLogin>>();

const formSchema = computed((): VbenFormSchema[] => {
  return [
    {
      component: 'VbenInput',
      componentProps: {
        placeholder: '租户编码，如 demoa',
      },
      fieldName: 'tenantCode',
      label: '租户编码',
      rules: z
        .string()
        .regex(/^[a-z][a-z0-9]{1,31}$/, '租户编码为小写字母开头，2–32 位'),
    },
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

function onSubmit(values: Record<string, string>) {
  if (values.tenantCode) {
    localStorage.setItem(TENANT_KEY, values.tenantCode);
  }
  return authStore.authLogin(values);
}

onMounted(() => {
  const saved = localStorage.getItem(TENANT_KEY);
  if (saved) {
    formRef.value?.getFormApi().setFieldValue('tenantCode', saved);
  }
});
</script>

<template>
  <AuthenticationLogin
    ref="formRef"
    :form-schema="formSchema"
    :loading="authStore.loginLoading"
    :show-code-login="false"
    :show-forget-password="false"
    :show-qrcode-login="false"
    :show-register="false"
    :show-third-party-login="false"
    sub-title="医院端 · 使用租户编码登录。演示环境只有虚构账号。"
    title="临床科研数据一体化平台"
    @submit="onSubmit"
  />
</template>
