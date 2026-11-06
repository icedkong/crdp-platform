<script lang="ts" setup>
import type { TenantListItem, TenantViewPurpose } from '@crdp/api';

import { computed, onUnmounted, ref, watch } from 'vue';

import { tenantViewPurposeText, tenantViewRemainingText } from '@crdp/api';
import {
  Alert,
  Button,
  Descriptions,
  DescriptionsItem,
  Form,
  FormItem,
  Modal,
  RadioButton,
  RadioGroup,
  Space,
  Textarea,
} from 'ant-design-vue';

import {
  createTenantViewEntryTicket,
  currentTenantView,
  enterTenantView,
  exitTenantView,
} from '#/api/core/tenant-view';

import {
  openHospitalView,
  TENANT_VIEW_PURPOSE_OPTIONS,
  tickRemaining,
  toEnterViewModel,
  validateEnterModel,
  viewTimeText,
} from './view';

/**
 * 进入租户视角弹窗（E20-T01 / PLT-VIEW-001、004 + E20-T04 / PLT-VIEW-002、003 ·
 * pages.md §11「进入租户视角」）：选目的（排障 / 配置核对 / 培训支持 / 其他）+ 必填说明进入；
 * 进行中会话可「打开医院端界面」（60 秒一次性凭据新标签页打开医院端视角页，菜单与该院一致、
 * 顶部视角横幅常驻）并显示倒计时与一键退出（单次最长 2 小时、空闲 15 分钟自动退出）。
 * 同一账号同时只能有一个视角会话：打开弹窗先查进行中会话，有则直接展示。
 */
const props = defineProps<{
  open: boolean;
  tenant: null | TenantListItem;
}>();

const emit = defineEmits<{
  (e: 'update:open', open: boolean): void;
}>();

const form = ref(toEnterViewModel());
const errors = ref<string[]>([]);
const submitting = ref(false);
const submitError = ref('');
/** 进行中的视角会话（含进入成功后的回显）。 */
const active = ref<null | {
  purpose: string;
  remainingSeconds: number;
  sessionId: number;
  sessionNo: string;
  startedAt: string;
  tenantName: string;
}>(null);
const exiting = ref(false);
const opening = ref(false);
const notice = ref('');
let timer: null | ReturnType<typeof setInterval> = null;

const okDisabled = computed(() => errors.value.length > 0);

function stopTimer() {
  if (timer) {
    clearInterval(timer);
    timer = null;
  }
}

function startTimer() {
  stopTimer();
  timer = setInterval(() => {
    if (active.value) {
      active.value = {
        ...active.value,
        remainingSeconds: tickRemaining(active.value.remainingSeconds),
      };
      if (active.value.remainingSeconds <= 0) {
        // 到时自动退出（服务端 Redis TTL 已失效）：刷新实际状态
        void refreshCurrent('视角会话已到时，可重新进入');
      }
    }
  }, 1000);
}

async function refreshCurrent(message = '') {
  try {
    const session = await currentTenantView();
    active.value = session
      ? {
          purpose: session.purpose,
          remainingSeconds: session.remainingSeconds,
          sessionId: session.sessionId,
          sessionNo: session.sessionNo,
          startedAt: session.startedAt,
          tenantName: session.tenantName,
        }
      : null;
    notice.value = message;
    if (active.value) {
      startTimer();
    } else {
      stopTimer();
    }
  } catch (error) {
    submitError.value = error instanceof Error ? error.message : String(error);
  }
}

function onFormChange() {
  errors.value = validateEnterModel(form.value);
}

async function submit() {
  if (!props.tenant || okDisabled.value) return;
  submitting.value = true;
  submitError.value = '';
  try {
    const result = await enterTenantView({
      note: form.value.note,
      purpose: form.value.purpose as TenantViewPurpose,
      tenantId: props.tenant.id,
    });
    active.value = {
      purpose: result.purpose,
      remainingSeconds: result.expiresInSeconds,
      sessionId: result.sessionId,
      sessionNo: result.sessionNo,
      startedAt: result.startedAt,
      tenantName: result.tenantName,
    };
    form.value = toEnterViewModel();
    errors.value = [];
    startTimer();
  } catch (error) {
    submitError.value = error instanceof Error ? error.message : String(error);
  } finally {
    submitting.value = false;
  }
}

/**
 * 打开医院端界面（E20-T04 / PLT-VIEW-002）：现场签发 60 秒一次性凭据并新标签页打开
 * 医院端视角页（凭据只在 URL 片段，视角令牌只出现在医院端换取响应）。
 */
async function openHospital() {
  if (!active.value) return;
  opening.value = true;
  submitError.value = '';
  try {
    await openHospitalView(await createTenantViewEntryTicket());
    notice.value =
      '已在新的标签页打开医院端视角（凭据 60 秒内有效，请勿转发链接）';
  } catch (error) {
    submitError.value = error instanceof Error ? error.message : String(error);
  } finally {
    opening.value = false;
  }
}

async function exitView() {
  if (!active.value) return;
  exiting.value = true;
  submitError.value = '';
  try {
    await exitTenantView(active.value.sessionId);
    stopTimer();
    active.value = null;
    notice.value = '已退出租户视角，可重新进入';
  } catch (error) {
    submitError.value = error instanceof Error ? error.message : String(error);
  } finally {
    exiting.value = false;
  }
}

watch(
  () => props.open,
  async (open) => {
    if (!open) {
      stopTimer();
      return;
    }
    errors.value = [];
    submitError.value = '';
    notice.value = '';
    form.value = toEnterViewModel();
    active.value = null;
    // 同一账号同时只能有一个视角会话：先查进行中会话（PLT-VIEW-001）
    await refreshCurrent();
  },
);

onUnmounted(stopTimer);
</script>

<template>
  <Modal
    :footer="null"
    :open="open"
    :title="
      active
        ? `租户视角：${active.tenantName}`
        : `进入租户视角：${tenant?.name ?? ''}`
    "
    :width="560"
    @cancel="emit('update:open', false)"
  >
    <Alert v-if="notice" :message="notice" show-icon type="info" class="mb-2" />
    <Descriptions v-if="active" :column="1" bordered size="small" class="mb-2">
      <DescriptionsItem label="会话号">
        <span class="code">{{ active.sessionNo }}</span>
      </DescriptionsItem>
      <DescriptionsItem label="查看目的">
        {{ tenantViewPurposeText(active.purpose) }}
      </DescriptionsItem>
      <DescriptionsItem label="开始时间">
        {{ viewTimeText(active.startedAt) }}
      </DescriptionsItem>
      <DescriptionsItem label="剩余时长">
        {{ tenantViewRemainingText(active.remainingSeconds) }}
        （单次最长 2 小时，空闲 15 分钟自动退出）
      </DescriptionsItem>
    </Descriptions>
    <p v-if="active" class="hint">
      「打开医院端界面」以 60
      秒一次性凭据在新标签页打开该院视角（菜单与该院一致、
      顶部视角横幅常驻）；进入、退出与被拒操作均记录平台审计（租户不可见）。
    </p>
    <Space v-if="active" class="mt-2">
      <Button
        :loading="opening"
        data-testid="open-hospital-view"
        type="primary"
        @click="openHospital"
      >
        打开医院端界面
      </Button>
      <Button :loading="exiting" danger @click="exitView">退出租户视角</Button>
      <Button @click="emit('update:open', false)">关闭</Button>
    </Space>

    <Form v-if="!active" layout="vertical" class="crdp-form">
      <p class="hint">
        进入前请选择查看目的并填写说明；本次进入将记录到平台审计（租户不可见）。
      </p>
      <FormItem label="查看目的" required>
        <RadioGroup v-model:value="form.purpose" @change="onFormChange">
          <RadioButton
            v-for="option in TENANT_VIEW_PURPOSE_OPTIONS"
            :key="option.value"
            :value="option.value"
          >
            {{ option.label }}
          </RadioButton>
        </RadioGroup>
      </FormItem>
      <FormItem
        label="查看说明"
        required
        :validate-status="errors.length > 0 ? 'error' : undefined"
        :help="errors.join('；') || undefined"
      >
        <Textarea
          v-model:value="form.note"
          :maxlength="500"
          placeholder="如：排查租户反馈的登录失败问题（示例）"
          show-count
          :rows="3"
          @change="onFormChange"
        />
      </FormItem>
      <Space>
        <Button
          type="primary"
          :disabled="okDisabled"
          :loading="submitting"
          data-testid="submit-enter-view"
          @click="submit"
        >
          进入视角
        </Button>
        <Button @click="emit('update:open', false)">取消</Button>
      </Space>
    </Form>
    <Alert
      v-if="submitError"
      :message="`操作失败：${submitError}`"
      show-icon
      type="error"
      class="mt-2"
    />
  </Modal>
</template>

<style scoped>
.code {
  font-family: monospace;
}

.hint {
  margin-bottom: 8px;
  font-size: 13px;
  color: #86909c;
}
</style>
