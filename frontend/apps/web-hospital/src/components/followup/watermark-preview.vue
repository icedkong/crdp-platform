<script lang="ts" setup>
import { computed, onUnmounted, ref, watch } from 'vue';

import { Button, Modal } from 'ant-design-vue';

const props = defineProps<{
  account: string;
  canDownload?: boolean;
  name: string;
  open: boolean;
  reportName?: string;
  tenantName: string;
  thumbnailUrl?: string;
}>();

const emit = defineEmits<{
  'update:open': [open: boolean];
}>();

const layer = ref<HTMLElement>();
let observer: MutationObserver | undefined;
let timer: ReturnType<typeof setInterval> | undefined;

const text = computed(() => {
  const now = new Date();
  const pad = (value: number) => String(value).padStart(2, '0');
  const stamp = `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())} ${pad(now.getHours())}:${pad(now.getMinutes())}`;
  return `${props.name}（${props.account}）· ${props.tenantName} · ${stamp}`;
});

function paint() {
  const node = layer.value;
  if (!node) return;
  node.textContent = text.value;
  node.style.opacity = '0.15';
  node.style.transform = 'rotate(-20deg)';
  node.style.pointerEvents = 'none';
}

function watchLayer() {
  observer?.disconnect();
  const node = layer.value;
  if (!node?.parentElement) return;
  observer = new MutationObserver(() => paint());
  observer.observe(node.parentElement, {
    attributes: true,
    childList: true,
    subtree: true,
  });
  paint();
}

watch(
  () => props.open,
  (open) => {
    if (!open) {
      observer?.disconnect();
      if (timer) clearInterval(timer);
      return;
    }
    requestAnimationFrame(() => {
      watchLayer();
      timer = setInterval(paint, 2000);
    });
  },
);

onUnmounted(() => {
  observer?.disconnect();
  if (timer) clearInterval(timer);
});
</script>

<template>
  <Modal
    :footer="null"
    :open="open"
    title="报告单预览"
    width="80%"
    @update:open="emit('update:open', $event)"
  >
    <div class="stage">
      <img v-if="thumbnailUrl" alt="虚构样板缩略图" :src="thumbnailUrl" />
      <p v-else>{{ reportName }}</p>
      <div ref="layer" class="watermark">{{ text }}</div>
    </div>
    <div class="tools">
      <Button v-if="canDownload">下载</Button>
      <Button @click="emit('update:open', false)">关闭</Button>
    </div>
  </Modal>
</template>

<style scoped>
.stage {
  position: relative;
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 240px;
  overflow: hidden;
  background: #111;
}

.stage img {
  max-width: 100%;
  max-height: 360px;
}

.watermark {
  position: absolute;
  font-size: 18px;
  color: #fff;
  white-space: nowrap;
  pointer-events: none;
  user-select: none;
}

.tools {
  display: flex;
  gap: 8px;
  justify-content: flex-end;
  margin-top: 12px;
}
</style>
