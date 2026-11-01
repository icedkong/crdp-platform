<script lang="ts" setup>
import type { ProfileView } from '@crdp/api';

import type { PhoneBindState } from './phone-bind-form';

import { computed, onMounted, onUnmounted, reactive, ref } from 'vue';

import { Profile } from '@vben/common-ui';
import { useUserStore } from '@vben/stores';

import { colors } from '@crdp/tokens';
import {
  Alert,
  Button,
  Card,
  Descriptions,
  DescriptionsItem,
  Form,
  FormItem,
  Input,
  message,
  Modal,
  Spin,
} from 'ant-design-vue';

import {
  getProfile,
  rebindPhone,
  requestPhoneBindSms,
  updateDisplayName,
} from '#/api/core/profile';

import ProfileLoginLogs from './login-logs.vue';
import ProfilePasswordSetting from './password-setting.vue';
import {
  canRequestBindCode,
  canSubmitBind,
  isValidDisplayName,
} from './phone-bind-form';
import ProfileSessions from './sessions.vue';

defineOptions({ name: 'HospitalProfile' });

/**
 * E06-T11 / PLT-ORG-011 · pages.md §10「个人中心」：基本信息（姓名、换绑手机号）、
 * 安全设置（修改密码 / 在线会话）与登录记录。登录即可访问（/profile 核心路由 + 顶栏入口）。
 * 修改密码接口复用 /auth/password（E04-T05）；在线会话复用 /auth/sessions（E04-T06）。
 * 通知偏好（A-NTF-007）随通知任务落地，不在本任务范围。
 */
const userStore = useUserStore();

const loading = ref(false);
const profile = ref<null | ProfileView>(null);
const loadError = ref('');

const tabs = [
  { label: '基本信息', value: 'basic' },
  { label: '修改密码', value: 'password' },
  { label: '登录记录', value: 'logs' },
  { label: '在线会话', value: 'sessions' },
];
const tabsValue = ref('basic');

const textSecondary = colors.textSecondary;
const labelWidth = 120;

// ---- 姓名修改 ----
const nameForm = reactive({ displayName: '' });
const nameSaving = ref(false);

async function saveDisplayName() {
  if (!isValidDisplayName(nameForm.displayName)) {
    message.warning('姓名为 1–64 个字符');
    return;
  }
  nameSaving.value = true;
  try {
    const view = await updateDisplayName(nameForm.displayName.trim());
    profile.value = view;
    nameForm.displayName = view.displayName;
    // 顶栏与水印用的用户资料同步刷新
    if (userStore.userInfo) {
      userStore.setUserInfo({
        ...userStore.userInfo,
        realName: view.displayName,
      });
    }
    message.success('姓名已更新');
  } finally {
    nameSaving.value = false;
  }
}

// ---- 换绑手机号 ----
const bindOpen = ref(false);
const bindSubmitting = ref(false);
const bindState = reactive<PhoneBindState>({
  code: '',
  countdown: 0,
  phone: '',
  sending: false,
});
let countdownTimer: ReturnType<typeof setInterval> | undefined;

function stopCountdown() {
  if (countdownTimer) {
    clearInterval(countdownTimer);
    countdownTimer = undefined;
  }
}

function openBind() {
  bindState.phone = '';
  bindState.code = '';
  bindState.countdown = 0;
  bindState.sending = false;
  bindOpen.value = true;
}

function startCountdown() {
  stopCountdown();
  bindState.countdown = 60;
  countdownTimer = setInterval(() => {
    bindState.countdown -= 1;
    if (bindState.countdown <= 0) {
      stopCountdown();
    }
  }, 1000);
}

async function sendBindCode() {
  bindState.sending = true;
  try {
    await requestPhoneBindSms(bindState.phone);
    message.success('验证码已发送到新手机号');
    startCountdown();
  } finally {
    bindState.sending = false;
  }
}

async function submitBind() {
  bindSubmitting.value = true;
  try {
    const view = await rebindPhone(bindState.code, bindState.phone);
    profile.value = view;
    bindOpen.value = false;
    message.success('手机号已换绑');
  } finally {
    bindSubmitting.value = false;
  }
}

async function load() {
  loading.value = true;
  loadError.value = '';
  try {
    const view = await getProfile();
    profile.value = view;
    nameForm.displayName = view.displayName;
  } catch (error) {
    loadError.value = error instanceof Error ? error.message : '加载失败';
  } finally {
    loading.value = false;
  }
}

const rolesText = computed(() => profile.value?.roles.join('、') ?? '—');

onMounted(load);
onUnmounted(stopCountdown);
</script>

<template>
  <div class="page" data-testid="hospital-profile">
    <Card :bordered="false" class="crdp-card">
      <div class="head">
        <h1 class="crdp-page-title">个人中心</h1>
        <p class="hint" :style="{ color: textSecondary }">
          维护本人信息与安全设置；登录记录与在线会话只展示本人数据。
        </p>
      </div>
      <Alert
        v-if="loadError"
        class="mb-4"
        :message="loadError"
        show-icon
        type="error"
      />
      <Spin :spinning="loading">
        <Profile
          v-model:model-value="tabsValue"
          title="个人中心"
          :user-info="userStore.userInfo"
          :tabs="tabs"
        >
          <template #content>
            <!-- 基本信息：姓名修改 + 换绑手机号 -->
            <div v-if="tabsValue === 'basic'" class="tab-body">
              <Descriptions
                :column="1"
                :label-style="{ width: `${labelWidth}px` }"
                size="middle"
              >
                <DescriptionsItem label="账号">
                  {{ profile?.username ?? '—' }}
                </DescriptionsItem>
                <DescriptionsItem label="医院名称">
                  {{ profile?.tenantName ?? '—' }}
                </DescriptionsItem>
                <DescriptionsItem label="角色">
                  {{ rolesText }}
                </DescriptionsItem>
                <DescriptionsItem label="创建时间">
                  {{ profile?.createdAt ?? '—' }}
                </DescriptionsItem>
                <DescriptionsItem label="绑定手机号">
                  <span>{{ profile?.phoneMasked ?? '未绑定' }}</span>
                  <Button class="ml-3" size="small" @click="openBind">
                    换绑手机号
                  </Button>
                </DescriptionsItem>
              </Descriptions>

              <Form
                class="name-form"
                layout="vertical"
                @keyup.enter.prevent
                @submit.prevent
              >
                <FormItem label="姓名" name="displayName">
                  <div class="name-row">
                    <Input
                      v-model:value="nameForm.displayName"
                      :maxlength="64"
                      placeholder="1–64 个字符"
                      :style="{ width: '320px' }"
                    />
                    <Button
                      type="primary"
                      :loading="nameSaving"
                      data-testid="profile-name-save"
                      @click="saveDisplayName"
                    >
                      保存
                    </Button>
                  </div>
                  <p class="hint" :style="{ color: textSecondary }">
                    账号创建后不可修改；姓名用于界面展示、任务分派与报告单水印。
                  </p>
                </FormItem>
              </Form>
            </div>

            <ProfilePasswordSetting v-else-if="tabsValue === 'password'" />
            <ProfileLoginLogs v-else-if="tabsValue === 'logs'" />
            <ProfileSessions v-else-if="tabsValue === 'sessions'" />
          </template>
        </Profile>
      </Spin>
    </Card>

    <!-- 换绑手机号：验证码发到新手机号（与登录 / 重置验证码互不通用） -->
    <Modal
      v-model:open="bindOpen"
      :confirm-loading="bindSubmitting"
      :ok-button-props="{ disabled: !canSubmitBind(bindState, bindSubmitting) }"
      ok-text="确认换绑"
      title="换绑手机号"
      :width="480"
      @ok="submitBind"
    >
      <Alert
        class="mb-3"
        message="验证码将发送到新手机号；新手机号须未被本院其他账号绑定。"
        show-icon
        type="info"
      />
      <Form layout="vertical" @keyup.enter.prevent>
        <FormItem label="新手机号" required>
          <Input
            v-model:value="bindState.phone"
            :maxlength="11"
            placeholder="11 位手机号"
            data-testid="bind-phone"
          />
        </FormItem>
        <FormItem label="验证码" required>
          <div class="name-row">
            <Input
              v-model:value="bindState.code"
              :maxlength="6"
              placeholder="6 位数字"
              data-testid="bind-code"
            />
            <Button
              :disabled="!canRequestBindCode(bindState)"
              :loading="bindState.sending"
              data-testid="bind-send-code"
              @click="sendBindCode"
            >
              {{
                bindState.countdown > 0
                  ? `${bindState.countdown} 秒后重发`
                  : '获取验证码'
              }}
            </Button>
          </div>
        </FormItem>
      </Form>
      <p class="hint" :style="{ color: textSecondary }">
        换绑后短信登录与双因素使用新手机号；换绑记录会写入审计。
      </p>
    </Modal>
  </div>
</template>

<style scoped>
.page {
  padding: 16px;
}

.head {
  margin-bottom: 12px;
}

.hint {
  margin: 4px 0 0;
  font-size: 12px;
}

.tab-body {
  max-width: 640px;
}

.name-form {
  margin-top: 16px;
}

.name-row {
  display: flex;
  gap: 8px;
  align-items: center;
}
</style>
