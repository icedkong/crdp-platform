<script lang="ts" setup>
import { computed, onUnmounted, ref } from 'vue';

import {
  buildPlaintextRevealRequest,
  normalizePatientPlainField,
} from '@crdp/api';
import { timings } from '@crdp/tokens';
import { Button, message, Modal, Popconfirm } from 'ant-design-vue';

import { revealPatientPlaintext } from '#/api/core';
import { usePlaintextReveal } from '#/reference/plaintext';
import { useBreakpoint } from '#/reference/use-breakpoint';
import { usePermission } from '#/reference/use-permission';

const props = withDefaults(
  defineProps<{
    field: string;
    patientId: string;
    permission?: string;
    revealable?: boolean;
    value: string;
  }>(),
  {
    permission: 'followup:patient:plain',
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
// A1（M3 审查）：明文入口只认权限码，不存在绕过 hasPermission 的演示通道；
// 样板页与正式页面同一条链路（reference-pages.md §怎么抄 6）。
const allowed = computed(
  () => props.revealable && hasPermission(props.permission),
);

const revealApi = usePlaintextReveal({
  reveal: async () => {
    const request = buildPlaintextRevealRequest(props.field, 'detail');
    if (!request) {
      // 目录外字段不发起明文请求，保持脱敏值
      return props.value;
    }
    const result = await revealPatientPlaintext(
      props.patientId,
      request.fields,
      request.scene,
    );
    const field = normalizePatientPlainField(props.field);
    // 明文只进入组件内存（usePlaintextReveal），60 秒后自动恢复脱敏
    return (field && result.values[field]) || props.value;
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
