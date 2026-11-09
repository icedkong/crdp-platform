<script lang="ts" setup>
/**
 * E16-T10 / A-ATT-015、C-SCAN-008（pages.md §6.1「扫码用手机拍照上传」）：为当前患者
 * 签发一次性二维码（15 分钟、绑定本人与患者），手机扫码后照片经 H5 直接生成该患者
 * 的报告单（创建人 = 签发人）；弹窗倒计时 + 每 3 秒轮询——SUBMITTED 刷新患者内清单、
 * EXPIRED 提示重新生成（验收「二维码过期失效」）。从基线分类 / 随访分组内打开时带
 * preset（手机端下拉自动预选该位置，A-ATT-015「自动预选该位置」）。
 */
import type { ReportLocationInput } from '@crdp/api';

import type { QrPhase } from './mobile-upload-qr-logic';

import { computed, onBeforeUnmount, ref, watch } from 'vue';

import { Button, message, Modal } from 'ant-design-vue';
import QRCode from 'qrcode';

import {
  fetchMobileUploadStatus,
  issueMobileUploadToken,
} from '#/api/core/report';

import {
  countdownExpired,
  nextQrPhase,
  qrCountdownText,
  qrPhaseText,
} from './mobile-upload-qr-logic';

const props = defineProps<{
  open: boolean;
  patientId: string;
  /** 患者显示标签（脱敏名 + 编号，提示语用）。 */
  patientLabel?: string;
  /** 从分类 / 分组内打开时的预选位置（取首个）。 */
  preset?: null | ReportLocationInput[];
}>();

const emit = defineEmits<{
  /** 手机端已生成报告单（reportId；父级刷新患者内清单即可）。 */
  created: [reportId: string];
  'update:open': [open: boolean];
}>();

const POLL_INTERVAL_MS = 3000;

const phase = ref<QrPhase>('issuing');
const qrDataUrl = ref('');
const expiresAt = ref(0);
const nowSecond = ref(Math.floor(Date.now() / 1000));
const submittedReportId = ref<null | string>(null);

const countdown = computed(() =>
  expiresAt.value > 0 ? qrCountdownText(expiresAt.value, nowSecond.value) : '',
);

let pollTimer: ReturnType<typeof setInterval> | undefined;
let clockTimer: ReturnType<typeof setInterval> | undefined;

watch(
  () => props.open,
  (open) => {
    if (open) {
      void issue();
    } else {
      stopPolling();
    }
  },
);

onBeforeUnmount(stopPolling);

async function issue() {
  phase.value = 'issuing';
  qrDataUrl.value = '';
  submittedReportId.value = null;
  try {
    const issued = await issueMobileUploadToken(
      props.patientId,
      props.preset?.[0] ?? null,
    );
    // 令牌只出现在二维码内容（H5 地址片段）里，不落日志 / 不展示明文
    qrDataUrl.value = await QRCode.toDataURL(issued.qrUrl, {
      margin: 1,
      width: 240,
    });
    expiresAt.value = issued.expiresAtEpochSecond;
    phase.value = 'waiting';
    startPolling(issued.token);
  } catch {
    phase.value = 'error';
  }
}

function startPolling(token: string) {
  stopPolling();
  clockTimer = setInterval(() => {
    nowSecond.value = Math.floor(Date.now() / 1000);
    if (countdownExpired(expiresAt.value, nowSecond.value)) {
      phase.value = nextQrPhase(phase.value, 'EXPIRED');
      stopPolling();
    }
  }, 1000);
  pollTimer = setInterval(async () => {
    try {
      const status = await fetchMobileUploadStatus(token);
      phase.value = nextQrPhase(phase.value, status.status);
      if (phase.value === 'submitted') {
        submittedReportId.value = status.reportId;
        stopPolling();
        message.success('手机端已上传成功，报告单已生成');
        if (status.reportId) {
          emit('created', status.reportId);
        }
        emit('update:open', false);
      } else if (phase.value === 'expired') {
        stopPolling();
      }
    } catch {
      // 网络抖动：本轮跳过，下轮重试（倒计时兜底过期）
    }
  }, POLL_INTERVAL_MS);
}

function stopPolling() {
  if (pollTimer) {
    clearInterval(pollTimer);
    pollTimer = undefined;
  }
  if (clockTimer) {
    clearInterval(clockTimer);
    clockTimer = undefined;
  }
}

function close() {
  emit('update:open', false);
}
</script>

<template>
  <Modal
    :footer="null"
    :open="open"
    :width="420"
    title="扫码用手机拍照上传"
    @update:open="close"
  >
    <div class="qr-body" data-testid="mobile-upload-qr">
      <template v-if="phase === 'issuing'">
        <p>{{ qrPhaseText('issuing') }}</p>
      </template>
      <template v-else-if="phase === 'error'">
        <p class="error">{{ qrPhaseText('error') }}</p>
        <Button type="primary" @click="issue">重新生成</Button>
      </template>
      <template v-else-if="phase === 'expired'">
        <p class="error">{{ qrPhaseText('expired') }}</p>
        <Button type="primary" @click="issue">重新生成</Button>
      </template>
      <template v-else-if="phase === 'submitted'">
        <p class="success">{{ qrPhaseText('submitted') }}</p>
        <Button type="primary" @click="close">完成</Button>
      </template>
      <template v-else>
        <img
          v-if="qrDataUrl"
          :src="qrDataUrl"
          alt="手机上传二维码"
          class="qr"
        />
        <p>{{ qrPhaseText('waiting') }}</p>
        <p v-if="patientLabel" class="hint">患者：{{ patientLabel }}</p>
        <p class="hint">二维码 {{ countdown }} 后过期，一次性使用</p>
      </template>
    </div>
  </Modal>
</template>

<style scoped>
.qr-body {
  display: flex;
  flex-direction: column;
  gap: 8px;
  align-items: center;
  padding: 8px 0 16px;
  text-align: center;
}

.qr {
  width: 240px;
  height: 240px;
}

.hint {
  font-size: 13px;
  color: #8c8c8c;
}

.success {
  color: #1dc29e;
}

.error {
  color: #ff4d4f;
}
</style>
