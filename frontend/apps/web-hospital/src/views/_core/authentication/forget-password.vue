<script lang="ts" setup>
import { computed, onMounted, onUnmounted, reactive, ref } from 'vue';
import { useRouter } from 'vue-router';

import {
  cooldownRemaining,
  PASSWORD_HISTORY_COUNT,
  passwordRuleItems,
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

import { requestPasswordResetSmsApi, resetPasswordApi } from '#/api';

import {
  canRequestResetCode,
  canSubmitReset,
  SMS_COOLDOWN_SECONDS,
  TENANT_CODE_PATTERN,
  USERNAME_PATTERN,
} from './forgot-password-flow';
import { recalledTenantCode } from './tenant-code-memory';

/**
 * E04-T07 / PLT-AUTH-011：忘记密码（医院端登录页入口，免登录流程）。
 * 第一步输入租户编码 + 账号，重置验证码发到该账号绑定的手机号（后端防探测：无论账号
 * 是否存在都提示已发送）；第二步输入验证码与新密码，按密码策略实时提示（PLT-AUTH-006）；
 * 成功后该账号全部会话失效，回登录页用新密码登录。
 */
defineOptions({ name: 'ForgetPassword' });

const router = useRouter();

/** 'send' 第一步（发码）；'reset' 第二步（验证码 + 新密码）；'done' 完成。 */
const step = ref<'done' | 'reset' | 'send'>('send');
const sending = ref(false);
const submitting = ref(false);

const form = reactive({
  code: '',
  confirmPassword: '',
  newPassword: '',
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
const usernameRule = [
  { message: '账号格式不正确', pattern: USERNAME_PATTERN, trigger: 'blur' },
];
const codeRule = [
  { message: '验证码为 6 位数字', pattern: /^\d{6}$/, trigger: 'blur' },
];

const sendFormRef = ref();
const sendRules = {
  tenantCode: tenantCodeRule,
  username: usernameRule,
};
const resetFormRef = ref();
const resetRules = {
  code: codeRule,
  confirmPassword: [
    {
      message: '两次输入的新密码不一致',
      trigger: 'blur',
      validator: (_rule: unknown, value: string) =>
        value === form.newPassword
          ? Promise.resolve()
          : Promise.reject(new Error('两次输入的新密码不一致')),
    },
  ],
  newPassword: [{ message: '密码长度至少 8 位', min: 8, trigger: 'blur' }],
};

/** 按密码策略实时提示（PLT-AUTH-006；历史密码由后端比对，只列规则）。 */
const ruleItems = computed(() =>
  passwordRuleItems(form.newPassword, form.username),
);

// ---- 第一步：发送重置验证码（60 秒冷却与登录验证码一致） ----
const smsSentAt = ref(0);
const smsCountdown = ref(0);
let countdownTimer: null | ReturnType<typeof setInterval> = null;

const canSend = computed(() =>
  canRequestResetCode({
    countdown: smsCountdown.value,
    sending: sending.value,
    tenantCode: form.tenantCode,
    username: form.username,
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

async function sendResetCode() {
  // 第二步里的「重新获取」也走同一入口；第一步先做表单校验
  if (step.value === 'send') {
    await sendFormRef.value?.validate();
  }
  sending.value = true;
  try {
    await requestPasswordResetSmsApi({
      tenantCode: form.tenantCode,
      username: form.username,
    });
    startCountdown();
    // 后端防探测：无论账号是否存在都到这里；验证码只在账号已绑定手机号时真正下发
    step.value = 'reset';
  } finally {
    sending.value = false;
  }
}

// ---- 第二步：验证码 + 新密码 ----
const canSubmit = computed(() =>
  canSubmitReset(
    {
      code: form.code,
      confirmPassword: form.confirmPassword,
      newPassword: form.newPassword,
    },
    form.username,
  ),
);

async function submitReset() {
  await resetFormRef.value?.validate();
  submitting.value = true;
  try {
    await resetPasswordApi({
      code: form.code,
      newPassword: form.newPassword,
      tenantCode: form.tenantCode,
      username: form.username,
    });
    step.value = 'done';
  } finally {
    submitting.value = false;
  }
}

function backToSendStep() {
  step.value = 'send';
  form.code = '';
}

async function backToLogin() {
  await router.replace('/login');
}

onMounted(() => {
  const saved = recalledTenantCode(localStorage);
  if (saved) {
    form.tenantCode = saved;
  }
});

onUnmounted(() => {
  countdownTimer?.();
});
</script>

<template>
  <Card class="w-full max-w-[400px]" :bordered="false">
    <div class="mb-2 text-center">
      <div class="text-xl font-semibold">忘记密码</div>
      <div class="text-muted-foreground mt-1 text-sm">
        重置验证码将发送到账号绑定的手机号，5 分钟内有效
      </div>
    </div>

    <!-- 第一步：租户编码 + 账号 → 发送验证码 -->
    <Form
      v-if="step === 'send'"
      ref="sendFormRef"
      :label-col="{ span: 0 }"
      :model="form"
      :rules="sendRules"
      layout="vertical"
      @keyup.enter.prevent
    >
      <FormItem name="tenantCode">
        <Input
          v-model:value="form.tenantCode"
          placeholder="租户编码，如 demoa"
        />
      </FormItem>
      <FormItem name="username">
        <Input v-model:value="form.username" placeholder="账号" />
      </FormItem>
      <Button
        block
        :loading="sending"
        size="large"
        type="primary"
        @click="sendResetCode"
      >
        {{ sending ? '发送中' : '获取短信验证码' }}
      </Button>
      <Button block class="mt-3" type="link" @click="backToLogin">
        返回登录
      </Button>
    </Form>

    <!-- 第二步：验证码 + 新密码（按密码策略实时提示） -->
    <Form
      v-else-if="step === 'reset'"
      ref="resetFormRef"
      :label-col="{ span: 0 }"
      :model="form"
      :rules="resetRules"
      layout="vertical"
      @keyup.enter.prevent
    >
      <Alert
        class="mb-3"
        :message="`验证码已发送至账号 ${form.username} 绑定的手机号`"
        show-icon
        type="info"
      />
      <FormItem name="code">
        <div class="flex gap-2">
          <Input
            v-model:value="form.code"
            :maxlength="6"
            placeholder="短信验证码"
            style="flex: 1"
          />
          <Button
            :disabled="!canSend"
            :loading="sending"
            @click="sendResetCode"
          >
            {{
              smsCountdown > 0
                ? `${smsCountdown} 秒后重发`
                : sending
                  ? '发送中'
                  : '重新获取'
            }}
          </Button>
        </div>
      </FormItem>
      <FormItem name="newPassword">
        <InputPassword v-model:value="form.newPassword" placeholder="新密码" />
      </FormItem>
      <FormItem name="confirmPassword">
        <InputPassword
          v-model:value="form.confirmPassword"
          placeholder="确认新密码"
        />
      </FormItem>
      <ul class="text-muted-foreground mb-2 list-inside list-disc text-sm">
        <li
          v-for="item in ruleItems"
          :key="item.label"
          :class="item.ok ? 'text-[#1DC29E]' : ''"
        >
          {{ item.ok ? '✅' : '⭕' }} {{ item.label }}
        </li>
        <li>不能与最近 {{ PASSWORD_HISTORY_COUNT }} 次使用过的密码相同</li>
      </ul>
      <Button
        block
        :disabled="!canSubmit"
        :loading="submitting"
        size="large"
        type="primary"
        @click="submitReset"
      >
        重置密码
      </Button>
      <Button block class="mt-3" type="link" @click="backToSendStep">
        返回上一步
      </Button>
    </Form>

    <!-- 完成：回登录页用新密码登录 -->
    <div v-else>
      <Alert
        class="mb-4"
        message="密码已重置，该账号的其他登录已全部下线，请使用新密码登录"
        show-icon
        type="success"
      />
      <Button block size="large" type="primary" @click="backToLogin">
        返回登录
      </Button>
    </div>
  </Card>
</template>
