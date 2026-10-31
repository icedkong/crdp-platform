<script lang="ts" setup>
import type { TwoFactorChallenge } from '@crdp/api';

import { reactive, ref } from 'vue';

import { AuthenticationLogin } from '@vben/common-ui';

import { Button, Card, Form, FormItem, Input } from 'ant-design-vue';

import { useAuthStore } from '#/store';

import { platformLoginPayload, platformLoginSchema } from './login-schema';

/**
 * E04-T03 / PLT-AUTH-005：平台端独立登录（只提交账号与密码）。
 * E04-T04 / PLT-AUTH-003：平台账号强制双因素——密码通过后切换到短信验证码第二步。
 */
defineOptions({ name: 'Login' });

const authStore = useAuthStore();
const twoFactor = ref<null | TwoFactorChallenge>(null);

function onSubmit(values: Record<string, boolean | string>) {
  // 平台端独立登录（E04-T03）：只提交账号与密码，不带任何租户上下文
  return authStore.authLogin(platformLoginPayload(values)).then((outcome) => {
    if (outcome.twoFactor) {
      twoFactor.value = outcome.twoFactor;
    }
  });
}

// ---- 双因素第二步 ----
const twoFactorFormRef = ref();
const twoFactorForm = reactive({ code: '' });
const twoFactorRules = {
  code: [
    {
      len: 6,
      message: '验证码为 6 位数字',
      pattern: /^\d{6}$/,
      trigger: 'blur',
    },
  ],
};

async function submitTwoFactor() {
  if (!twoFactor.value) {
    return;
  }
  await twoFactorFormRef.value?.validate();
  await authStore.authVerifyTwoFactor(
    twoFactor.value.challengeId,
    twoFactorForm.code,
  );
}

function backToLogin() {
  twoFactor.value = null;
  twoFactorForm.code = '';
}
</script>

<template>
  <AuthenticationLogin
    v-if="!twoFactor"
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

  <!-- 双因素第二步（PLT-AUTH-003/005）：验证码已发送至账号绑定手机号，5 分钟内有效 -->
  <Card v-else class="w-full max-w-[400px]" :bordered="false">
    <div class="mb-2 text-center">
      <div class="text-xl font-semibold">双因素认证</div>
      <div class="text-muted-foreground mt-1 text-sm">
        验证码已发送至账号绑定的手机号，5 分钟内有效
      </div>
    </div>
    <Form
      ref="twoFactorFormRef"
      :label-col="{ span: 0 }"
      :model="twoFactorForm"
      :rules="twoFactorRules"
      layout="vertical"
      @keyup.enter.prevent
    >
      <FormItem name="code">
        <Input
          v-model:value="twoFactorForm.code"
          :maxlength="6"
          placeholder="短信验证码"
        />
      </FormItem>
      <Button
        block
        :loading="authStore.twoFactorLoading"
        size="large"
        type="primary"
        @click="submitTwoFactor"
      >
        验证并登录
      </Button>
      <Button block class="mt-3" type="link" @click="backToLogin">
        返回重新登录
      </Button>
    </Form>
  </Card>
</template>
