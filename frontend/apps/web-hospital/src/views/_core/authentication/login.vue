<script lang="ts" setup>
import type { TwoFactorChallenge } from '@crdp/api';

import { computed, onMounted, onUnmounted, reactive, ref } from 'vue';

import { cooldownRemaining } from '@crdp/api';
import {
  Button,
  Card,
  Checkbox,
  Form,
  FormItem,
  Input,
  InputPassword,
  TabPane,
  Tabs,
} from 'ant-design-vue';

import { sendLoginSmsApi } from '#/api';
import { useAuthStore } from '#/store';

import {
  canRequestSmsCode,
  PHONE_PATTERN,
  SMS_CODE_PATTERN,
  SMS_COOLDOWN_SECONDS,
  TENANT_CODE_PATTERN,
} from './sms-login-form';
import {
  applyTenantCodeMemory,
  recalledTenantCode,
} from './tenant-code-memory';

/**
 * E04-T04 / PLT-AUTH-002、003：登录页提供「账号密码 | 短信验证码」两种方式（docs/design/pages.md §1）。
 * 密码第一步若命中双因素，切换到第二步输入短信验证码（challengeId 5 分钟有效）。
 */
defineOptions({ name: 'Login' });

const authStore = useAuthStore();

const activeTab = ref<'password' | 'sms'>('password');
const twoFactor = ref<null | TwoFactorChallenge>(null);

// ---- 账号密码表单 ----
const passwordFormRef = ref();
const passwordForm = reactive({
  password: '',
  rememberTenant: true,
  tenantCode: '',
  username: '',
});
const tenantCodeRule = [
  {
    message: '租户编码为小写字母开头，2–32 位',
    pattern: TENANT_CODE_PATTERN,
    trigger: 'blur',
  },
];
const smsCodeRule = [
  { message: '验证码为 6 位数字', pattern: SMS_CODE_PATTERN, trigger: 'blur' },
];

const passwordRules = {
  password: [{ message: '密码至少 8 位', min: 8, trigger: 'blur' }],
  tenantCode: tenantCodeRule,
  username: [
    {
      message: '账号格式不正确',
      pattern: /^[a-zA-Z][a-zA-Z0-9._-]{1,63}$/,
      trigger: 'blur',
    },
  ],
};

async function submitPassword() {
  await passwordFormRef.value?.validate();
  applyTenantCodeMemory(
    localStorage,
    passwordForm.tenantCode,
    passwordForm.rememberTenant !== false,
  );
  const outcome = await authStore.authLogin({
    password: passwordForm.password,
    tenantCode: passwordForm.tenantCode,
    username: passwordForm.username,
  });
  if (outcome.twoFactor) {
    twoFactor.value = outcome.twoFactor;
  }
}

// ---- 短信验证码表单 ----
const smsFormRef = ref();
const smsForm = reactive({ code: '', phone: '', tenantCode: '' });
const smsRules = {
  code: smsCodeRule,
  phone: [
    { message: '手机号格式不正确', pattern: PHONE_PATTERN, trigger: 'blur' },
  ],
  tenantCode: tenantCodeRule,
};

/** 发送冷却时长见 sms-login-form（与后端 crdp.security.sms-resend-cooldown-seconds 一致）。 */
const smsSentAt = ref(0);
const smsCountdown = ref(0);
const sending = ref(false);
let countdownTimer: null | ReturnType<typeof setInterval> = null;

const canSendSms = computed(() =>
  canRequestSmsCode({
    countdown: smsCountdown.value,
    phone: smsForm.phone,
    sending: sending.value,
    tenantCode: smsForm.tenantCode,
  }),
);

function startCountdown() {
  smsSentAt.value = Date.now();
  smsCountdown.value = SMS_COOLDOWN_SECONDS;
  countdownTimer?.();
  countdownTimer = setInterval(() => {
    smsCountdown.value = cooldownRemaining(
      smsSentAt.value,
      Date.now(),
      SMS_COOLDOWN_SECONDS,
    );
    if (smsCountdown.value <= 0) {
      countdownTimer?.();
      countdownTimer = null;
    }
  }, 1000);
}

async function sendSmsCode() {
  sending.value = true;
  try {
    await sendLoginSmsApi({
      phone: smsForm.phone,
      tenantCode: smsForm.tenantCode,
    });
    startCountdown();
  } finally {
    sending.value = false;
  }
}

async function submitSmsLogin() {
  await smsFormRef.value?.validate();
  applyTenantCodeMemory(localStorage, smsForm.tenantCode, true);
  const outcome = await authStore.authLoginBySms({
    code: smsForm.code,
    phone: smsForm.phone,
    tenantCode: smsForm.tenantCode,
  });
  if (outcome.twoFactor) {
    twoFactor.value = outcome.twoFactor;
  }
}

// ---- 双因素第二步 ----
const twoFactorFormRef = ref();
const twoFactorForm = reactive({ code: '' });
const twoFactorRules = { code: smsCodeRule };

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

onMounted(() => {
  const saved = recalledTenantCode(localStorage);
  if (saved) {
    passwordForm.tenantCode = saved;
    smsForm.tenantCode = saved;
  }
});

onUnmounted(() => {
  countdownTimer?.();
});
</script>

<template>
  <Card v-if="!twoFactor" class="w-full max-w-[400px]" :bordered="false">
    <div class="mb-2 text-center">
      <div class="text-xl font-semibold">临床科研数据一体化平台</div>
      <div class="text-muted-foreground mt-1 text-sm">
        医院端登录。演示环境只有虚构账号。
      </div>
    </div>
    <Tabs v-model:active-key="activeTab" centered>
      <TabPane key="password" tab="账号密码">
        <Form
          ref="passwordFormRef"
          :label-col="{ span: 0 }"
          :model="passwordForm"
          :rules="passwordRules"
          layout="vertical"
          @keyup.enter.prevent
        >
          <FormItem name="tenantCode">
            <Input
              v-model:value="passwordForm.tenantCode"
              placeholder="租户编码，如 demoa"
            />
          </FormItem>
          <FormItem name="username">
            <Input v-model:value="passwordForm.username" placeholder="账号" />
          </FormItem>
          <FormItem name="password">
            <InputPassword
              v-model:value="passwordForm.password"
              placeholder="密码"
            />
          </FormItem>
          <FormItem>
            <Checkbox v-model:checked="passwordForm.rememberTenant">
              记住医院编码（只存编码，不存密码）
            </Checkbox>
          </FormItem>
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
      </TabPane>
      <TabPane key="sms" tab="短信验证码">
        <Form
          ref="smsFormRef"
          :label-col="{ span: 0 }"
          :model="smsForm"
          :rules="smsRules"
          layout="vertical"
          @keyup.enter.prevent
        >
          <FormItem name="tenantCode">
            <Input
              v-model:value="smsForm.tenantCode"
              placeholder="租户编码，如 demoa"
            />
          </FormItem>
          <FormItem name="phone">
            <Input
              v-model:value="smsForm.phone"
              :maxlength="11"
              placeholder="绑定手机号"
            />
          </FormItem>
          <FormItem name="code">
            <div class="flex gap-2">
              <Input
                v-model:value="smsForm.code"
                :maxlength="6"
                placeholder="短信验证码"
              />
              <Button
                :disabled="!canSendSms"
                :loading="sending"
                @click="sendSmsCode"
              >
                {{
                  smsCountdown > 0
                    ? `${smsCountdown} 秒后重发`
                    : sending
                      ? '发送中'
                      : '获取验证码'
                }}
              </Button>
            </div>
          </FormItem>
          <Button
            block
            :loading="authStore.smsLoginLoading"
            size="large"
            type="primary"
            @click="submitSmsLogin"
          >
            登录
          </Button>
        </Form>
      </TabPane>
    </Tabs>
  </Card>

  <!-- 双因素第二步（PLT-AUTH-003）：验证码已发送至账号绑定手机号 -->
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
