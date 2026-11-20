<script lang="ts" setup>
/**
 * E16-T06：预览内容面（从 E10-T04 FilePreview 抽出）——按格式渲染图片 / PDF / DOCX /
 * 音频 / 仅下载，**不含水印**。水印由外层负责：FilePreview（页内小预览）与
 * FullscreenViewer（全屏遮罩）各挂自己的防篡改水印层；全屏查看器的水印盖在整个
 * 遮罩上、不随内容缩放旋转翻转。
 */
import type { PreviewableFile } from './typings';

import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';

import { previewModeOf } from './preview';
import { PREVIEW_REVOKED_EVENT } from './preview-events';
import { renderDocxDocument, renderPdfDocument } from './renderers';

const props = withDefaults(
  defineProps<{
    canDownload?: boolean;
    file: PreviewableFile;
    url: string;
  }>(),
  { canDownload: false },
);

const emit = defineEmits<{
  download: [file: PreviewableFile];
}>();

const documentHost = ref<HTMLDivElement>();
const renderError = ref(false);
const invalid = ref(false);
let sequence = 0;
function revoke(event: Event) {
  if ((event as CustomEvent<string>).detail !== props.url) return;
  invalid.value = true;
  sequence++;
  documentHost.value?.replaceChildren();
}

const decision = computed(() => previewModeOf(props.file));

onMounted(() => {
  window.addEventListener(PREVIEW_REVOKED_EVENT, revoke);
  void renderDocument();
});

watch(
  () => [props.url, decision.value.mode] as const,
  () => {
    invalid.value = false;
    void renderDocument();
  },
  { flush: 'post' },
);

onBeforeUnmount(() => {
  sequence++;
  window.removeEventListener(PREVIEW_REVOKED_EVENT, revoke);
});

async function renderDocument(): Promise<void> {
  const request = ++sequence;
  const mode = decision.value.mode;
  if (mode !== 'docx' && mode !== 'pdf') {
    return;
  }
  const container = documentHost.value;
  if (!container || !props.url) {
    return;
  }
  container.replaceChildren();
  renderError.value = false;
  try {
    const staging = document.createElement('div');
    await (mode === 'pdf'
      ? renderPdfDocument(staging, props.url)
      : renderDocxDocument(staging, props.url));
    if (
      request === sequence &&
      !invalid.value &&
      container === documentHost.value
    )
      container.replaceChildren(...staging.childNodes);
  } catch {
    if (request === sequence) renderError.value = true;
  }
}

function onDownload(): void {
  emit('download', props.file);
}
</script>

<template>
  <div class="preview-content">
    <img
      v-if="!invalid && decision.mode === 'image'"
      :alt="file.name ?? '预览图片'"
      class="preview-image"
      :src="url"
    />
    <div
      v-else-if="
        !invalid && (decision.mode === 'pdf' || decision.mode === 'docx')
      "
      class="preview-document"
    >
      <div ref="documentHost"></div>
      <p v-if="renderError" class="preview-fallback">
        在线预览加载失败，请下载后查看
        <button v-if="canDownload" type="button" @click="onDownload">
          下载
        </button>
      </p>
    </div>
    <div
      v-else-if="!invalid && decision.mode === 'audio'"
      class="preview-audio"
    >
      <audio
        class="preview-audio-element"
        controls
        controlslist="nodownload"
        preload="metadata"
        :src="url"
      ></audio>
      <p class="preview-audio-note">如需保存录音，请使用下载（需下载权限）</p>
    </div>
    <p v-else-if="invalid" class="preview-fallback">预览已失效，请重新打开</p>
    <div v-else class="preview-download-only">
      <p class="preview-download-reason">{{ decision.reason }}</p>
      <button v-if="canDownload" type="button" @click="onDownload">下载</button>
      <p v-else class="preview-no-permission">无下载权限时无法获取该文件</p>
    </div>
  </div>
</template>

<style scoped>
.preview-content {
  min-width: 0;
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
  color: inherit;
  opacity: 0.75;
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
