<script lang="ts" setup>
import type { PreviewableFile, WatermarkViewer } from './typings';

import { onBeforeUnmount, onMounted, ref, watch } from 'vue';

import { colors } from '@crdp/tokens';

import PreviewContent from './preview-content.vue';
import { createWatermarkGuard, watermarkText } from './watermark';

/**
 * E10-T04 文件预览（A-FU-025、A-ATT-005；components.md §12 WatermarkPreview 的预览内核）：
 * 内容走预览代理短时链接，**水印不可关闭**（createWatermarkGuard 直接管理 DOM，防删改）。
 * MP3 / M4A / WAV 原生 audio（controlsList=nodownload，播放器里不放下载按钮）；
 * AMR 与 XLSX 只下载（下载按钮按权限显示，点击交给父级走 download-url，E10-T05）。
 *
 * E16-T06：内容渲染抽到 PreviewContent（多格式分支共用）；缩放 / 旋转 / 翻转与翻页
 * 在 FullscreenViewer（全屏外壳）里，页内小预览不提供变换。
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
});

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
</script>

<template>
  <div ref="host" class="file-preview" :style="cssVars">
    <PreviewContent
      :can-download="canDownload"
      :file="file"
      :url="url"
      @download="(item) => emit('download', item)"
    />
    <!-- 水印层由 createWatermarkGuard 直接管理（不进 vdom，防删改重绘） -->
  </div>
</template>

<style scoped>
.file-preview {
  position: relative;
  min-height: 160px;
  overflow: auto;
  color: var(--viewer-text-secondary);
  background: var(--viewer-surface);
}
</style>
