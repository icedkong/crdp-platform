<script lang="ts" setup>
import { computed, onUnmounted, ref } from 'vue';

import { timings } from '@crdp/tokens';
import { Button, message, Modal, Popconfirm } from 'ant-design-vue';

import { findPatient } from '#/reference/fixtures';
import { usePlaintextReveal } from '#/reference/plaintext';
import { useBreakpoint } from '#/reference/use-breakpoint';
import { usePermission } from '#/reference/use-permission';

const props = withDefaults(
  defineProps<{
    field: string;
    patientId: string;
    permission?: string;
    /** 样板页打开，用来演示明文交互。正式页面不要传。 */
    referenceMode?: boolean;
    revealable?: boolean;
    value: string;
  }>(),
  {
    permission: 'followup:patient:plain',
    referenceMode: false,
    revealable: false,
  },
);

const emit = defineEmits<{
  hidden: [field: string];
  revealed: [field: string];
}>();

const CONFIRM =
  '确认显示明文？本次查看写入审计日志（查看人、时间、患者、字段），60 秒后自动恢复脱敏。不需要再次输入密码。';

const { hasPermission } = usePermission();
const { isMobile } = useBreakpoint();
const confirmOpen = ref(false);
const allowed = computed(
  () =>
    props.revealable &&
    (props.referenceMode || hasPermission(props.permission)),
);

const revealApi = usePlaintextReveal({
  reveal: async () => {
    // 明文接口尚未落地（POST .../plaintext）。样板只在内存里返回虚构值。
    const patient = findPatient(props.patientId);
    if (!patient) return props.value;
    if (props.field === 'phone') return patient.phonePlain;
    return patient.namePlain;
  },
  ttlMs: timings.plaintextRevealMs,
});

const shown = computed(() =>
  revealApi.state.value === 'revealed'
    ? revealApi.plaintext.value
    : props.value,
);

async function onConfirm() {
  confirmOpen.value = false;
  await revealApi.confirm();
  message.success('已显示明文并写入审计；60 秒后自动恢复脱敏');
  emit('revealed', props.field);
}

function hide() {
  revealApi.clear();
  emit('hidden', props.field);
}

onUnmounted(hide);
</script>

<template>
  <span class="masked">
    <span>{{ shown }}</span>
    <template v-if="allowed">
      <Button
        v-if="revealApi.state.value === 'revealed'"
        size="small"
        type="link"
        @click="hide"
      >
        隐藏（60 秒后自动脱敏）
      </Button>
      <template v-else>
        <Button
          v-if="isMobile"
          size="small"
          type="link"
          @click="confirmOpen = true"
        >
          显示明文
        </Button>
        <Popconfirm
          v-else
          cancel-text="取消"
          ok-text="确认显示"
          :title="CONFIRM"
          @confirm="onConfirm"
        >
          <Button size="small" type="link">显示明文</Button>
        </Popconfirm>
        <Modal
          v-model:open="confirmOpen"
          cancel-text="取消"
          ok-text="确认显示"
          title="确认显示明文"
          width="calc(100% - 32px)"
          @ok="onConfirm"
        >
          <p>{{ CONFIRM }}</p>
        </Modal>
      </template>
    </template>
  </span>
</template>

<style scoped>
.masked {
  display: inline-flex;
  gap: 4px;
  align-items: center;
}
</style>
