<script lang="ts">
/**
 * E16-T06 / A-ATT-005、C-SCAN-015（components.md §12 WatermarkPreview）：
 * 全屏查看器业务组件——对上接收 §12 契约（open / reportId / files / index /
 * canDownload / viewer），对下把预览短时链接解析交给 #/api/core/file，把工具栏与
 * 水印交给 @crdp/viewer 的 FullscreenViewer。下载只抛事件，由父级换签名链接
 * （followup:scan:download，A-ATT-006）；水印不可关闭（FullscreenViewer 内置）。
 */
export interface WatermarkPreviewFile {
  fileId: number | string;
  format?: null | string;
  mime?: null | string;
  name?: null | string;
  /** §12 契约字段：PDF 页数留空，页数由 pdf.js 解析（OQ-61④）。 */
  pages?: null | number;
}
</script>

<script lang="ts" setup>
import type { WatermarkViewer } from '@crdp/viewer';

import { computed, ref, watch } from 'vue';

import { FullscreenViewer } from '@crdp/viewer';
import { Button } from 'ant-design-vue';

import { getFilePreviewUrl } from '#/api/core/file';

const props = withDefaults(
  defineProps<{
    canDownload?: boolean;
    files: WatermarkPreviewFile[];
    index?: number;
    open: boolean;
    /** §12 契约字段：所属报告单（事件归因用，组件内部不使用）。 */
    reportId?: string;
    viewer: WatermarkViewer;
  }>(),
  { canDownload: false, index: 0, reportId: undefined },
);

const emit = defineEmits<{
  download: [fileId: number | string];
  'update:index': [index: number];
  'update:open': [open: boolean];
}>();

/** 会话内的短时链接（打开期间翻回已看过的份不重复签发；关闭即弃，不跨会话缓存）。 */
const urls = ref<Record<string, string>>({});
const failed = ref(false);

/** 当前文件（索引夹取；空清单 = 不渲染查看器）。 */
const currentFile = computed(() =>
  props.files.length > 0
    ? props.files[Math.min(Math.max(props.index, 0), props.files.length - 1)]
    : undefined,
);

/** 当前文件的短时链接（未解析 = null → FullscreenViewer 显示加载态）。 */
const currentUrl = computed(() => {
  const file = currentFile.value;
  return file ? (urls.value[String(file.fileId)] ?? null) : null;
});

async function resolveCurrent(): Promise<void> {
  const file = currentFile.value;
  if (!file || urls.value[String(file.fileId)]) {
    return;
  }
  failed.value = false;
  try {
    const grant = await getFilePreviewUrl(file.fileId);
    urls.value = {
      ...urls.value,
      [String(file.fileId)]: grant.url,
    };
  } catch {
    failed.value = true;
  }
}

watch(
  () => [props.open, props.index, props.files] as const,
  ([open]) => {
    if (!open) {
      // 会话结束即弃（短时链接不缓存；下次打开重新签发、重新写审计）
      urls.value = {};
      failed.value = false;
      return;
    }
    void resolveCurrent();
  },
  { deep: true, immediate: true },
);

function handleClose(): void {
  emit('update:open', false);
}

function handleIndex(next: number): void {
  emit('update:index', next);
}

function handleDownload(file: { fileId: number | string }): void {
  emit('download', file.fileId);
}
</script>

<template>
  <!-- 打开且有文件才挂全屏查看器；链接签发失败时单独给出重试面板 -->
  <FullscreenViewer
    v-if="open && currentFile && !failed"
    :can-download="canDownload"
    :file="currentFile"
    :file-count="files.length"
    :index="index"
    :url="currentUrl"
    :viewer="viewer"
    @close="handleClose"
    @download="handleDownload"
    @update:index="handleIndex"
  />
  <div
    v-else-if="open && failed"
    class="preview-failure"
    data-testid="watermark-preview-failure"
  >
    <p>预览链接获取失败，请稍后重试</p>
    <Button data-testid="watermark-preview-retry" @click="resolveCurrent()">
      重试
    </Button>
    <Button @click="handleClose">关闭</Button>
  </div>
</template>

<style scoped>
.preview-failure {
  position: fixed;
  inset: 0;
  z-index: 1100;
  display: flex;
  flex-direction: column;
  gap: 12px;
  align-items: center;
  justify-content: center;
  color: #fff;
  background: rgb(0 0 0 / 70%);
}
</style>
