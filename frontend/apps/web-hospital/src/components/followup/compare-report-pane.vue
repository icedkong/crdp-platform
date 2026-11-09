<script lang="ts">
/**
 * E16-T11 / A-ATT-019、C-FU-017（pages.md §5 线框「（可选）对照报告单分屏」、
 * §5.1 对照录入规则）：对照录入右侧分屏面板——一侧表单、一侧报告单。
 *
 * 报告单元信息（名称 / 类型 / 报告时间）+ 首附件**带水印**页内预览（@crdp/viewer
 * FilePreview，水印不可关闭、防篡改；内容走预览代理短时链接，每次签发写审计、
 * 不缓存，OQ-62）。缩放 / 旋转 / 翻转 / 逐份翻页经「全屏查看」复用 E16-T06 全屏
 * 查看器（由父级打开）；平板 / 手机不分屏、直接全屏切换（pages.md §5.1）。
 */
export interface CompareReportPaneReport {
  fileCount: number;
  firstFile: null | { fileId: string; format: string };
  id: string;
  name: null | string;
  reportDate: string;
  typeName: string;
}
</script>

<script lang="ts" setup>
import type { WatermarkViewer } from '@crdp/viewer';

import { computed, ref, watch } from 'vue';

import { sizes } from '@crdp/tokens';
import { FilePreview } from '@crdp/viewer';
import { Button, Tooltip } from 'ant-design-vue';

import { getFilePreviewUrl } from '#/api/core/file';

const props = defineProps<{
  /** 下载权限（followup:scan:download）：仅下载格式与失败兜底里的下载按钮。 */
  canDownload?: boolean;
  report: CompareReportPaneReport;
  viewer: WatermarkViewer;
}>();

const emit = defineEmits<{
  close: [];
  download: [fileId: string];
  fullscreen: [];
}>();

const url = ref<null | string>(null);
const failed = ref(false);

const file = computed(() => props.report.firstFile);

async function resolve(): Promise<void> {
  const current = file.value;
  if (!current) return;
  failed.value = false;
  url.value = null;
  try {
    const grant = await getFilePreviewUrl(current.fileId);
    url.value = grant.url;
  } catch {
    failed.value = true;
  }
}

watch(
  () => props.report.id,
  () => void resolve(),
  { immediate: true },
);

function onDownload(item: { fileId: number | string }): void {
  emit('download', String(item.fileId));
}
</script>

<template>
  <aside
    class="compare-pane"
    data-testid="compare-pane"
    :style="{ width: `${sizes.comparePaneWidth}px` }"
  >
    <header class="pane-header">
      <div class="pane-caption">对照报告单</div>
      <h2 class="pane-title" data-testid="compare-pane-title">
        {{ report.name ?? report.typeName }}
      </h2>
      <div class="pane-meta" data-testid="compare-pane-meta">
        {{ report.typeName }} · {{ report.reportDate }}
      </div>
      <p
        v-if="report.fileCount > 1"
        class="pane-note"
        data-testid="compare-pane-files-note"
      >
        共 {{ report.fileCount }} 个附件，当前显示第 1
        份（逐份翻页随报告单详情接口开放）
      </p>
      <div class="pane-actions">
        <Tooltip
          title="全屏查看支持缩放 / 旋转 / 翻转（E16-T06）；平板与手机对照录入即为全屏切换"
        >
          <Button
            data-testid="compare-pane-fullscreen"
            size="small"
            @click="emit('fullscreen')"
          >
            全屏查看
          </Button>
        </Tooltip>
        <Button
          data-testid="compare-pane-close"
          size="small"
          type="primary"
          @click="emit('close')"
        >
          关闭对照
        </Button>
      </div>
    </header>
    <div class="pane-body">
      <p v-if="!file" class="pane-empty" data-testid="compare-pane-empty">
        该报告单暂无可预览的附件
      </p>
      <template v-else-if="failed">
        <p class="pane-empty" data-testid="compare-pane-error">
          预览链接获取失败，请稍后重试
        </p>
        <Button data-testid="compare-pane-retry" size="small" @click="resolve">
          重试
        </Button>
      </template>
      <p v-else-if="url === null" class="pane-empty">正在加载预览…</p>
      <div
        v-else
        class="pane-file"
        data-testid="compare-pane-file"
        :style="{ width: '100%' }"
      >
        <FilePreview
          :can-download="canDownload ?? false"
          :file="{
            fileId: file.fileId,
            format: file.format,
            name: report.name ?? report.typeName,
          }"
          :url="url"
          :viewer="viewer"
          @download="onDownload"
        />
      </div>
    </div>
  </aside>
</template>

<style scoped>
.compare-pane {
  display: flex;
  flex: 0 0 auto;
  flex-direction: column;
  align-self: stretch;
  min-width: 0;
  overflow: hidden;
  border: 1px solid rgb(0 0 0 / 6%);
  border-radius: 8px;
}

.pane-header {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 12px;
  border-bottom: 1px solid rgb(0 0 0 / 6%);
}

.pane-caption {
  font-size: 12px;
  color: rgb(0 0 0 / 45%);
}

.pane-title {
  margin: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  font-size: 16px;
  font-weight: 600;
  white-space: nowrap;
}

.pane-meta {
  font-size: 12px;
  color: rgb(0 0 0 / 45%);
}

.pane-note {
  margin: 0;
  font-size: 12px;
  color: rgb(0 0 0 / 45%);
}

.pane-actions {
  display: flex;
  gap: 8px;
  margin-top: 4px;
}

.pane-body {
  display: flex;
  flex: 1 1 auto;
  flex-direction: column;
  gap: 8px;
  align-items: center;
  min-height: 240px;
  padding: 8px;
  overflow: auto;
}

.pane-empty {
  margin: 24px 0 8px;
  font-size: 13px;
  color: rgb(0 0 0 / 45%);
}
</style>
