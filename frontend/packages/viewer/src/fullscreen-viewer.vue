<script lang="ts" setup>
import type { PreviewableFile, WatermarkViewer } from './typings';

import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';

import { useBreakpoint } from '@crdp/responsive';
import { sizes } from '@crdp/tokens';
import { theme as antdTheme, Button } from 'ant-design-vue';

import {
  canZoomIn,
  canZoomOut,
  clampFileIndex,
  fileIndicatorText,
  flipHorizontally,
  INITIAL_VIEWER_TRANSFORM,
  rotateClockwise,
  transformEnabledFor,
  viewerTransformCss,
  zoomIn,
  zoomOut,
  zoomPercentText,
} from './fullscreen';
import { previewModeOf } from './preview';
import PreviewContent from './preview-content.vue';
import { createWatermarkGuard, watermarkText } from './watermark';

/**
 * E16-T06 / A-ATT-005、C-SCAN-015：全屏查看器外壳（components.md §12）——全屏深色
 * 遮罩 + 顶部工具栏（下载（有权限才显示）/ 旋转 / 翻转 / 缩小 / 放大 / 关闭，多文件时
 * 另有上一份 / 下一份翻页）。图片 / PDF / DOCX 页内预览并参与变换；音频原生播放、
 * AMR / XLSX 仅下载（不参与变换，按钮置灰）。
 *
 * 水印挂在遮罩根上、**不随内容变换**（翻转 / 旋转后水印文字始终正向可读），不可关闭。
 * 内容短时链接由父级解析后经 `url` 传入（null = 解析中）；Esc 或「关闭」抛 close，
 * 由父级收起遮罩。手机 / 平板工具栏图标 ≥ 40px（sizes.touchTarget）。
 */
const props = withDefaults(
  defineProps<{
    canDownload?: boolean;
    file: PreviewableFile;
    /** 文件总数（≤ 1 不显示翻页）。 */
    fileCount?: number;
    /** 当前第几份（0 基；翻页抛 update:index，父级换 file 与 url）。 */
    index?: number;
    /** 当前文件的预览短时链接（null = 正在解析）。 */
    url?: null | string;
    viewer: WatermarkViewer;
  }>(),
  { canDownload: false, fileCount: 1, index: 0, url: null },
);

const emit = defineEmits<{
  close: [];
  download: [file: PreviewableFile];
  'update:index': [index: number];
}>();

const { token } = antdTheme.useToken();
const { isMobile } = useBreakpoint();

const root = ref<HTMLDivElement>();
const transform = ref({ ...INITIAL_VIEWER_TRANSFORM });

const decision = computed(() => previewModeOf(props.file));
const transformEnabled = computed(() =>
  transformEnabledFor(decision.value.mode),
);
const currentIndex = computed(() =>
  clampFileIndex(props.index, props.fileCount),
);
const hasPager = computed(() => props.fileCount > 1);
const indicator = computed(() =>
  fileIndicatorText(currentIndex.value, props.fileCount),
);
const zoomOn = computed(
  () => transformEnabled.value && canZoomIn(transform.value),
);
const zoomOff = computed(
  () => transformEnabled.value && canZoomOut(transform.value),
);
/** 变换只作用于内容画布；水印与工具栏固定在遮罩上。 */
const stageCss = computed(() =>
  transformEnabled.value ? viewerTransformCss(transform.value) : 'none',
);
const zoomText = computed(() => zoomPercentText(transform.value));

/** 工具栏触达尺寸：手机 / 平板 ≥ 40px（components.md §12），桌面随控件高。 */
const cssVars = computed(() => {
  const size = isMobile.value ? sizes.touchTarget : token.value.controlHeight;
  return {
    '--viewer-tool-size': `${size}px`,
    '--viewer-mask': token.value.colorBgMask,
    '--viewer-text': token.value.colorTextLightSolid,
    '--viewer-shell-z': String(token.value.zIndexPopupBase + 90),
  } as Record<string, string>;
});

let guard: ReturnType<typeof createWatermarkGuard> | undefined;

onMounted(() => {
  if (root.value) {
    guard = createWatermarkGuard(root.value, {
      text: watermarkText(props.viewer),
    });
  }
  document.addEventListener('keydown', onKeydown);
});

watch(
  () => props.file.fileId,
  () => {
    // 换一份文件：变换复位（每份从 100% / 0° 开始）
    transform.value = { ...INITIAL_VIEWER_TRANSFORM };
  },
);

watch(
  () => props.viewer,
  (viewer) => {
    guard?.destroy();
    if (root.value) {
      guard = createWatermarkGuard(root.value, { text: watermarkText(viewer) });
    }
  },
  { deep: true },
);

onBeforeUnmount(() => {
  document.removeEventListener('keydown', onKeydown);
  guard?.destroy();
  guard = undefined;
});

function onKeydown(event: KeyboardEvent): void {
  if (event.key === 'Escape') {
    emit('close');
  }
}

function goPrev(): void {
  if (currentIndex.value > 0) {
    emit('update:index', currentIndex.value - 1);
  }
}

function goNext(): void {
  if (currentIndex.value < props.fileCount - 1) {
    emit('update:index', currentIndex.value + 1);
  }
}

function onRotate(): void {
  transform.value = rotateClockwise(transform.value);
}

function onFlip(): void {
  transform.value = flipHorizontally(transform.value);
}

function onZoomIn(): void {
  transform.value = zoomIn(transform.value);
}

function onZoomOut(): void {
  transform.value = zoomOut(transform.value);
}

function onDownload(): void {
  emit('download', props.file);
}
</script>

<template>
  <Teleport to="body">
    <div
      ref="root"
      aria-label="报告单全屏预览"
      aria-modal="true"
      class="viewer-fullscreen"
      data-testid="fullscreen-viewer"
      role="dialog"
      :style="cssVars"
    >
      <header class="viewer-toolbar">
        <span class="viewer-title" :title="file.name ?? ''">
          {{ file.name ?? '报告单预览' }}
        </span>
        <div v-if="hasPager" class="viewer-pager">
          <Button
            data-testid="viewer-prev"
            :disabled="currentIndex <= 0"
            size="small"
            @click="goPrev"
          >
            上一份
          </Button>
          <span class="viewer-indicator" data-testid="viewer-indicator">
            {{ indicator }}
          </span>
          <Button
            data-testid="viewer-next"
            :disabled="currentIndex >= fileCount - 1"
            size="small"
            @click="goNext"
          >
            下一份
          </Button>
        </div>
        <div class="viewer-tools">
          <Button
            v-if="canDownload"
            data-testid="viewer-download"
            size="small"
            @click="onDownload"
          >
            下载
          </Button>
          <Button :disabled="!transformEnabled" size="small" @click="onRotate">
            旋转
          </Button>
          <Button :disabled="!transformEnabled" size="small" @click="onFlip">
            翻转
          </Button>
          <Button
            data-testid="viewer-zoom-out"
            :disabled="!zoomOff"
            size="small"
            @click="onZoomOut"
          >
            缩小
          </Button>
          <span class="viewer-zoom" data-testid="viewer-zoom">{{
            zoomText
          }}</span>
          <Button
            data-testid="viewer-zoom-in"
            :disabled="!zoomOn"
            size="small"
            @click="onZoomIn"
          >
            放大
          </Button>
          <Button
            data-testid="viewer-close"
            size="small"
            type="primary"
            @click="emit('close')"
          >
            关闭
          </Button>
        </div>
      </header>
      <div class="viewer-stage">
        <p v-if="url === null" class="viewer-loading">正在加载预览…</p>
        <div
          v-else
          class="viewer-canvas"
          data-testid="viewer-stage"
          :style="{ transform: stageCss }"
        >
          <PreviewContent
            :can-download="canDownload"
            :file="file"
            :url="url"
            @download="onDownload"
          />
        </div>
      </div>
      <!-- 水印层由 createWatermarkGuard 直接管理（不进 vdom，防删改重绘） -->
    </div>
  </Teleport>
</template>

<style scoped>
.viewer-fullscreen {
  position: fixed;
  inset: 0;
  z-index: var(--viewer-shell-z);
  display: flex;
  flex-direction: column;
  color: var(--viewer-text);
  background: var(--viewer-mask);
}

.viewer-toolbar {
  display: flex;
  gap: 12px;
  align-items: center;
  justify-content: space-between;
  padding: 8px 16px;
  background: var(--viewer-mask);
}

.viewer-title {
  max-width: 30%;
  overflow: hidden;
  text-overflow: ellipsis;
  font-size: 14px;
  white-space: nowrap;
}

.viewer-pager {
  display: flex;
  gap: 8px;
  align-items: center;
}

.viewer-indicator {
  font-size: 13px;
  white-space: nowrap;
}

.viewer-tools {
  display: flex;
  gap: 8px;
  align-items: center;
}

.viewer-zoom {
  min-width: 44px;
  font-size: 13px;
  text-align: center;
}

/* 手机 / 平板：工具栏图标 ≥ 40px（components.md §12） */
.viewer-toolbar :deep(button) {
  min-width: var(--viewer-tool-size);
  min-height: var(--viewer-tool-size);
}

.viewer-stage {
  position: relative;
  display: flex;
  flex: 1;
  align-items: center;
  justify-content: center;
  overflow: auto;
}

.viewer-canvas {
  max-width: 100%;
  margin: auto;
  transform-origin: center center;
}

.viewer-loading {
  font-size: 14px;
}
</style>
