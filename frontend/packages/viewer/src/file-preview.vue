<script lang="ts" setup>
import type { PreviewableFile, WatermarkViewer } from './typings';

import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';

import { colors } from '@crdp/tokens';

import { previewModeOf } from './preview';
import { renderDocxDocument, renderPdfDocument } from './renderers';
import { createWatermarkGuard, watermarkText } from './watermark';

/**
 * E10-T04 文件预览（A-FU-025、A-ATT-005；components.md §12 WatermarkPreview 的预览内核）：
 * 内容走预览代理短时链接，**水印不可关闭**（createWatermarkGuard 直接管理 DOM，防删改）。
 * MP3 / M4A / WAV 原生 audio（controlsList=nodownload，播放器里不放下载按钮）；
 * AMR 与 XLSX 只下载（下载按钮按权限显示，点击交给父级走 download-url，E10-T05）。
 * 缩放 / 旋转 / 翻转工具栏与全屏外壳是 E16-T06 全屏查看器的职责，不在本组件。
 */
const props = withDefaults(
  defineProps<{
    canDownload?: boolean;
    file: PreviewableFile;
    url: string;
    viewer: WatermarkViewer;
  }>(),
  { canDownload: false },
);

const emit = defineEmits<{
  download: [file: PreviewableFile];
}>();

const host = ref<HTMLDivElement>();
const documentHost = ref<HTMLDivElement>();
const renderError = ref(false);

const decision = computed(() => previewModeOf(props.file));

/** 颜色全部取自 tokens（不写魔法数）；stylelint 不认 v-bind()，走 CSS 变量注入。 */
const cssVars = {
  '--viewer-surface': colors.tableHeader,
  '--viewer-text-secondary': colors.textSecondary,
} as const;

let guard: ReturnType<typeof createWatermarkGuard> | undefined;

onMounted(() => {
  if (host.value) {
    guard = createWatermarkGuard(host.value, {
      text: watermarkText(props.viewer),
    });
  }
  void renderDocument();
});

watch(
  () => [props.url, decision.value.mode] as const,
  () => void renderDocument(),
);

watch(
  () => props.viewer,
  (viewer) => {
    // 换查看人（罕见）：销毁重建，时间戳随重绘更新
    guard?.destroy();
    if (host.value) {
      guard = createWatermarkGuard(host.value, { text: watermarkText(viewer) });
    }
  },
  { deep: true },
);

onBeforeUnmount(() => {
  guard?.destroy();
  guard = undefined;
});

async function renderDocument(): Promise<void> {
  const mode = decision.value.mode;
  if (mode !== 'docx' && mode !== 'pdf') {
    return;
  }
  const container = documentHost.value;
  if (!container || !props.url) {
    return;
  }
  renderError.value = false;
  try {
    await (mode === 'pdf'
      ? renderPdfDocument(container, props.url)
      : renderDocxDocument(container, props.url));
  } catch {
    renderError.value = true;
  }
}

function onDownload(): void {
  emit('download', props.file);
}
</script>

<template>
  <div ref="host" class="file-preview" :style="cssVars">
    <img
      v-if="decision.mode === 'image'"
      :alt="file.name ?? '预览图片'"
      class="preview-image"
      :src="url"
    />
    <div
      v-else-if="decision.mode === 'pdf' || decision.mode === 'docx'"
      ref="documentHost"
      class="preview-document"
    >
      <p v-if="renderError" class="preview-fallback">
        在线预览加载失败，请下载后查看
        <button v-if="canDownload" type="button" @click="onDownload">
          下载
        </button>
      </p>
    </div>
    <div v-else-if="decision.mode === 'audio'" class="preview-audio">
      <audio
        class="preview-audio-element"
        controls
        controlslist="nodownload"
        preload="metadata"
        :src="url"
      ></audio>
      <p class="preview-audio-note">如需保存录音，请使用下载（需下载权限）</p>
    </div>
    <div v-else class="preview-download-only">
      <p class="preview-download-reason">{{ decision.reason }}</p>
      <button v-if="canDownload" type="button" @click="onDownload">下载</button>
      <p v-else class="preview-no-permission">无下载权限时无法获取该文件</p>
    </div>
    <!-- 水印层由 createWatermarkGuard 直接管理（不进 vdom，防删改重绘） -->
  </div>
</template>

<style scoped>
.file-preview {
  position: relative;
  min-height: 160px;
  overflow: auto;
  background: var(--viewer-surface);
}

.preview-image {
  display: block;
  max-width: 100%;
  margin: 0 auto;
}

.preview-document {
  min-height: 200px;
}

.preview-document :deep(.viewer-pdf-page) {
  display: block;
  margin: 12px auto;
  box-shadow: 0 2px 8px rgb(0 0 0 / 12%);
}

.preview-audio {
  display: flex;
  flex-direction: column;
  gap: 8px;
  align-items: center;
  justify-content: center;
  min-height: 160px;
}

.preview-audio-element {
  width: 100%;
  max-width: 480px;
}

.preview-audio-note,
.preview-download-reason,
.preview-no-permission,
.preview-fallback {
  margin: 8px 0;
  font-size: 13px;
  color: var(--viewer-text-secondary);
}

.preview-download-only {
  display: flex;
  flex-direction: column;
  gap: 8px;
  align-items: center;
  justify-content: center;
  min-height: 160px;
}
</style>
