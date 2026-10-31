<script lang="ts" setup>
import type { CaptchaImage, TwoFactorChallenge } from '@crdp/api';

import { computed, onMounted, onUnmounted, reactive, ref } from 'vue';
import { useRouter } from 'vue-router';

import {
  cooldownRemaining,
  lockedMessage,
  loginRejectionInfo,
  remainingAttemptsMessage,
} from '@crdp/api';
import {
  Alert,
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

import { getCaptchaApi, sendLoginSmsApi } from '#/api';
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
const router = useRouter();

const activeTab = ref<'password' | 'sms'>('password');
const twoFactor = ref<null | TwoFactorChallenge>(null);

// ---- E04-T05 / PLT-AUTH-001、007：图形验证码（连续失败 3 次后出现）与锁定提示 ----
const captcha = ref<CaptchaImage | null>(null);
const captchaCode = ref('');
const captchaLoading = ref(false);
/** 登录失败的结构化提示（剩余次数 / 锁定文案），来自 40101 / 42900 的 data。 */
const loginHint = ref('');

async function loadCaptcha() {
  captchaLoading.value = true;
  try {
    captcha.value = await getCaptchaApi();
    captchaCode.value = '';
  } finally {
    captchaLoading.value = false;
  }
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

function refreshCaptcha() {
  loadCaptcha();
}

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
  loginHint.value = '';
  try {
    const outcome = await authStore.authLogin({
      captchaCode: captchaCode.value || undefined,
      captchaId: captcha.value?.captchaId,
      password: passwordForm.password,
      tenantCode: passwordForm.tenantCode,
      username: passwordForm.username,
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

/** E04-T07 / PLT-AUTH-011：忘记密码（短信验证后重置）。 */
async function goForgotPassword() {
  await router.push('/password/forgot');
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
          <!-- E04-T05 / PLT-AUTH-007：连续失败 3 次后出现图形验证码（失败达阈值后出现） -->
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
                @click="refreshCaptcha"
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
          <!-- E04-T07 / PLT-AUTH-011：忘记密码（短信验证后重置，pages.md §1 线框） -->
          <Button block class="mt-1" type="link" @click="goForgotPassword">
            忘记密码
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
