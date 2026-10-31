<script lang="ts" setup>
import type { CaptchaImage, TwoFactorChallenge } from '@crdp/api';

import { reactive, ref } from 'vue';

import {
  lockedMessage,
  loginRejectionInfo,
  remainingAttemptsMessage,
} from '@crdp/api';
import {
  Alert,
  Button,
  Card,
  Form,
  FormItem,
  Input,
  InputPassword,
} from 'ant-design-vue';

import { getCaptchaApi } from '#/api';
import { useAuthStore } from '#/store';

import { loginRules } from './login-rules';

/**
 * E04-T03 / PLT-AUTH-005：平台端独立登录（只提交账号与密码，不带租户上下文）。
 * E04-T04 / PLT-AUTH-003：平台账号强制双因素——密码通过后切换到短信验证码第二步。
 * E04-T05 / PLT-AUTH-007：连续失败 3 次后出现图形验证码，失败次数 / 锁定时间按后端 data 提示。
 */
defineOptions({ name: 'Login' });

const authStore = useAuthStore();
const twoFactor = ref<null | TwoFactorChallenge>(null);

const formRef = ref();
const form = reactive({ password: '', username: '' });
const rules = loginRules;

// ---- E04-T05 / PLT-AUTH-001、007：图形验证码（连续失败 3 次后出现）与锁定提示 ----
const captcha = ref<CaptchaImage | null>(null);
const captchaCode = ref('');
const loginHint = ref('');

async function loadCaptcha() {
  captcha.value = await getCaptchaApi();
  captchaCode.value = '';
}

/** 登录被拒后按响应 data 更新验证码可见性与提示文案（失败原因本身不展示，防探测）。 */
function handleLoginRejection(error: unknown) {
  const info = loginRejectionInfo(error as never);
  if (!info) {
    return;
  }
  if (info.captchaRequired && !captcha.value) {
    loadCaptcha();
  }
  const messages = [
    lockedMessage(info.lockedSeconds),
    remainingAttemptsMessage(info.remainingAttempts),
    info.captchaRequired && !info.lockedSeconds
      ? '请输入图形验证码后重试'
      : null,
  ].filter(Boolean);
  loginHint.value = messages.join('，');
}

async function submitPassword() {
  await formRef.value?.validate();
  loginHint.value = '';
  try {
    const outcome = await authStore.authLogin({
      captchaCode: captchaCode.value || undefined,
      captchaId: captcha.value?.captchaId,
      password: form.password,
      username: form.username,
    });
    if (outcome.twoFactor) {
      twoFactor.value = outcome.twoFactor;
    }
  } catch (error) {
    handleLoginRejection(error);
    // 验证码一次一密：无论成败都换一张（不存在则忽略）
    if (captcha.value) {
      loadCaptcha();
    }
    throw error;
  }
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
  <Card v-if="!twoFactor" class="w-full max-w-[400px]" :bordered="false">
    <div class="mb-2 text-center">
      <div class="text-xl font-semibold">临床科研数据一体化平台</div>
      <div class="text-muted-foreground mt-1 text-sm">
        平台端登录。演示环境只有虚构账号。
      </div>
    </div>
    <Form
      ref="formRef"
      :label-col="{ span: 0 }"
      :model="form"
      :rules="rules"
      layout="vertical"
      @keyup.enter.prevent
    >
      <FormItem name="username">
        <Input v-model:value="form.username" placeholder="账号" />
      </FormItem>
      <FormItem name="password">
        <InputPassword v-model:value="form.password" placeholder="密码" />
      </FormItem>
      <!-- E04-T05 / PLT-AUTH-007：连续失败 3 次后出现图形验证码 -->
      <FormItem v-if="captcha">
        <div class="flex items-center gap-2">
          <Input
            v-model:value="captchaCode"
            :maxlength="4"
            placeholder="图形验证码"
            style="flex: 1"
          />
          <img
            alt="图形验证码"
            :src="captcha?.image"
            class="h-9 cursor-pointer rounded border border-gray-200"
            title="看不清？点击换一张"
            @click="loadCaptcha"
          />
        </div>
      </FormItem>
      <Alert
        v-if="loginHint"
        :message="loginHint"
        class="mb-3"
        show-icon
        type="warning"
      />
      <Button
        block
        :loading="authStore.loginLoading"
        size="large"
        type="primary"
        @click="submitPassword"
      >
        登录
      </Button>
    </Form>
  </Card>

  <!-- 双因素第二步（PLT-AUTH-003/005）：验证码已发送至账号绑定手机号 -->
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
