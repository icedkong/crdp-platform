<script lang="ts" setup>
import type { ContactAudioFile } from '@crdp/api';

import { ref } from 'vue';

import { Button, message, Tooltip } from 'ant-design-vue';

import { getFileDownloadUrl, getFilePreviewUrl } from '#/api/core/file';

/**
 * E15-T04 / A-FU-025 · AudioPlayer（components.md §13、pages.md §4.8 录音列）：
 * 原生 `audio` 播放预览代理短时链接（不缓存、每次签发写审计），
 * `controlsList="nodownload"` 且**播放器里不放下载按钮**（下载走 followup:scan:download，
 * 父级按权限给下载入口）；AMR 浏览器不能直接播放 → 不渲染播放器，只提示下载
 * （inlinePlayable=false，A-FU-025「只保存与下载」）；不转码。
 */
const props = defineProps<{
  audio: ContactAudioFile;
  /** 是否显示 AMR 的下载按钮（followup:scan:download，父级用 hasPermission 判定）。 */
  canDownload?: boolean;
}>();

const url = ref('');
const loading = ref(false);
const failed = ref(false);

/** 播放前换短时链接（≤ 5 分钟，用完即弃；重复播放重新获取）。 */
async function prepare(): Promise<void> {
  if (url.value || loading.value || !props.audio.inlinePlayable) return;
  loading.value = true;
  failed.value = false;
  try {
    const grant = await getFilePreviewUrl(props.audio.fileId);
    url.value = grant.url;
  } catch {
    failed.value = true;
  } finally {
    loading.value = false;
  }
}

/** 下载（AMR 与失败兜底）：换 5 分钟签名链接后由浏览器下载。 */
async function download(): Promise<void> {
  try {
    const grant = await getFileDownloadUrl(props.audio.fileId);
    window.location.assign(grant.url);
  } catch (error) {
    const body = error as { message?: string };
    message.error(body?.message || '下载失败，请稍后重试');
  }
}
</script>

<template>
  <div
    v-if="audio.inlinePlayable"
    class="audio-player"
    :data-testid="`audio-${audio.fileId}`"
  >
    <audio
      v-if="url"
      :src="url"
      controls
      controlslist="nodownload"
      preload="none"
      @play="prepare"
    ></audio>
    <Button v-else :loading="loading" size="small" type="link" @click="prepare">
      播放录音
    </Button>
    <span v-if="failed" class="hint">录音加载失败</span>
    <!-- 播放器里不放下载按钮（components.md §13；下载走权限按钮） -->
  </div>
  <div v-else class="audio-player amr" :data-testid="`audio-${audio.fileId}`">
    <Tooltip title="AMR 格式浏览器不能直接播放，只保存与下载">
      <span class="hint">{{ audio.originalName }}（AMR，不能在线播放）</span>
    </Tooltip>
    <Button
      v-if="canDownload"
      :data-testid="`audio-download-${audio.fileId}`"
      size="small"
      type="link"
      @click="download"
    >
      下载
    </Button>
  </div>
</template>

<style scoped>
.audio-player {
  display: flex;
  gap: 8px;
  align-items: center;
  min-width: 0;
}

audio {
  width: 220px;
  height: 32px;
}

.hint {
  font-size: 12px;
  color: #8c8c8c;
}
</style>
