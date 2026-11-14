<script lang="ts" setup>
import type { ReportInboxRow } from '@crdp/api';

import type {
  InboxListFilters,
  InboxThumbKind,
  InboxUploadItem,
} from './inbox-logic';

import { computed, onMounted, reactive, ref } from 'vue';

import { Page } from '@vben/common-ui';

import { reportInboxSizeText } from '@crdp/api';
// E16-T10 / OQ-116⑤：压缩链路与上传报告单同一实现（长边 ≤ 2560、转 JPEG、去 EXIF）
import { compressImageFile, isHeicFile } from '@crdp/photo';
import {
  Button,
  Card,
  DatePicker,
  Empty,
  Form,
  FormItem,
  message,
  Modal,
  Select,
  Table,
  Upload,
} from 'ant-design-vue';

import { getFilePreviewUrl, uploadFile } from '#/api/core/file';
import { addFilesToReportInbox, searchReportInbox } from '#/api/core/report';
import { downloadFileById } from '#/components/followup/file-download';
import InboxArchiveModal from '#/components/followup/inbox-archive-modal.vue';
import WatermarkPreview from '#/components/followup/watermark-preview.vue';
import { useFeature } from '#/reference/use-feature';
import { usePermission } from '#/reference/use-permission';
import { useWatermarkViewer } from '#/reference/use-watermark-viewer';

import {
  buildInboxSearchPayload,
  DESKTOP_COLUMNS,
  EMPTY_FILTERS,
  formatUploadedAt,
  inboxThumbOf,
  intakeReady,
  PAGE_SIZE,
} from './inbox-logic';

defineOptions({ name: 'FollowupReportInbox' });

/**
 * E21-T16 / C-SCAN-009 · 待归档区（/followup/report-inbox，批量扫描件分拣）：
 * 先扫描后标注——「上传扫描件」把未选患者的文件放入待归档区（一次 1–50 个），
 * 录入人员在列表中逐个「归档」：指定患者、类型与位置（至少一个）后生成报告单。
 * 待归档区按上传人所在科室 / 病区隔离（C-WARD-005，后端承担）。
 *
 * 开关 report_inbox 关闭（A / B 默认；AGENTS §5.7 前端隐藏入口）：本页显示
 * 「未开通」空态、不发起请求；「上传扫描件 / 归档」需 followup:scan:upload。
 * 页面不在响应式白名单（pages.md 5 页之外），手机由响应式守卫整页拦截（MobileBlocker）。
 */
const { isEnabled } = useFeature();
const featureOn = computed(() => isEnabled('report_inbox'));
const { hasPermission } = usePermission();
const canUpload = computed(() => hasPermission('followup:scan:upload'));

// ---------------------------------------------------------------- 筛选与分页

const filters = reactive<InboxListFilters>({ ...EMPTY_FILTERS });
const pageNo = ref(1);
const loading = ref(false);
const rows = ref<ReportInboxRow[]>([]);
const total = ref(0);
const uploaderOptions = ref<Array<{ id: string; name: string }>>([]);

async function load() {
  if (!featureOn.value) return;
  loading.value = true;
  try {
    const page = await searchReportInbox({
      ...buildInboxSearchPayload({ ...filters }),
      pageNo: pageNo.value,
      pageSize: PAGE_SIZE,
    });
    rows.value = page.items;
    total.value = page.total;
    uploaderOptions.value = page.uploaders;
    void loadThumbnails(page.items);
  } catch {
    message.error('待归档区加载失败，请稍后重试');
  } finally {
    loading.value = false;
  }
}

function handleSearch() {
  pageNo.value = 1;
  void load();
}

function handleReset() {
  Object.assign(filters, { ...EMPTY_FILTERS });
  handleSearch();
}

function handleTableChange(nextPage: number) {
  pageNo.value = nextPage;
  void load();
}

onMounted(() => {
  void load();
});

// ---------------------------------------------------------------- 缩略图（首附件图片，预览短时链接懒加载）

const thumbnailUrls = ref<Record<string, string>>({});

async function loadThumbnails(items: ReportInboxRow[]) {
  const targets = items
    .map((row) => inboxThumbOf(row))
    .filter((item): item is { fileId: string; kind: 'image' } => {
      if (!item || item.kind !== 'image') return false;
      return !thumbnailUrls.value[item.fileId];
    });
  await Promise.all(
    targets.map(async (item) => {
      try {
        const grant = await getFilePreviewUrl(item.fileId);
        thumbnailUrls.value = {
          ...thumbnailUrls.value,
          [item.fileId]: grant.url,
        };
      } catch {
        // 换不到链接时降级为文件图标（不阻塞列表）
      }
    }),
  );
}

function thumbnailSrc(row: ReportInboxRow): null | string {
  const thumb = inboxThumbOf(row);
  return thumb && thumb.kind === 'image'
    ? (thumbnailUrls.value[thumb.fileId] ?? null)
    : null;
}

function thumbView(row: ReportInboxRow): InboxThumbKind {
  return inboxThumbOf(row);
}

// ---------------------------------------------------------------- 列与行渲染

const dataSource = computed(() =>
  rows.value.map((row) => ({
    ...row,
    createdAtText: formatUploadedAt(row.createdAt),
    formatText: row.format.toUpperCase(),
    key: row.id,
    sizeText: reportInboxSizeText(row.sizeBytes),
  })),
);

// ---------------------------------------------------------------- E16-T06 全屏查看器（A-ATT-005）

const viewer = useWatermarkViewer();
/** 下载是敏感权限（followup:scan:download）：无权限时查看器不显示下载按钮。 */
const canDownloadFile = computed(() => hasPermission('followup:scan:download'));
const previewOpen = ref(false);
const previewFiles = ref<
  Array<{ fileId: string; format: string; name: string }>
>([]);

function openPreview(row: ReportInboxRow) {
  previewFiles.value = [
    { fileId: row.fileId, format: row.format, name: row.fileName },
  ];
  previewOpen.value = true;
}

/** 查看器「下载」：换 5 分钟签名链接（后端写 DOWNLOAD 审计）交给浏览器。 */
async function handlePreviewDownload(fileId: number | string) {
  const ok = await downloadFileById(fileId);
  if (!ok) {
    message.error('下载失败，请稍后重试');
  }
}

// ---------------------------------------------------------------- 先扫描：上传扫描件放入待归档区

const uploadOpen = ref(false);
const uploadItems = ref<InboxUploadItem[]>([]);
const uploadingCount = ref(0);
const intaking = ref(false);

/** 图片先压缩（与上传报告单同一链路）；PDF 等按原样上传。 */
async function customRequest(options: {
  file: File;
  onError: (error: Error) => void;
  onSuccess: (body: unknown) => void;
}) {
  const seq = uploadItems.value.length + 1;
  const item: InboxUploadItem = {
    fileId: `pending-${seq}-${options.file.name}`,
    name: options.file.name,
    status: 'uploading',
  };
  uploadItems.value = [...uploadItems.value, item];
  uploadingCount.value += 1;
  try {
    const compressed = await compressImageFile(options.file);
    if (compressed.note === 'undecodable' && isHeicFile(options.file)) {
      message.warning('该图片无法解码，将按原文件上传');
    }
    const uploaded = await uploadFile(compressed.file);
    uploadItems.value = uploadItems.value.map((row) =>
      row === item
        ? { ...row, fileId: uploaded.fileId, status: 'done' as const }
        : row,
    );
    options.onSuccess(uploaded);
  } catch (error) {
    uploadItems.value = uploadItems.value.map((row) =>
      row === item ? { ...row, status: 'failed' as const } : row,
    );
    options.onError(error as Error);
  } finally {
    uploadingCount.value -= 1;
  }
}

function removeUploadItem(item: InboxUploadItem) {
  uploadItems.value = uploadItems.value.filter((row) => row !== item);
}

function beforeUpload() {
  // 由 customRequest 接管，阻止 antd 自动上传（与上传报告单对话框同口径）
  return false;
}

async function handleIntake() {
  const { fileIds, problem } = intakeReady(uploadItems.value);
  if (problem) {
    message.warning(problem);
    return;
  }
  intaking.value = true;
  try {
    const view = await addFilesToReportInbox({ fileIds });
    message.success(`已放入待归档区：${view.fileCount} 个文件`);
    uploadOpen.value = false;
    uploadItems.value = [];
    handleSearch();
  } catch {
    // 错误提示由统一拦截器弹出（40001 重复进区 / 已挂他处在后端校验）
  } finally {
    intaking.value = false;
  }
}

// ---------------------------------------------------------------- 后标注：归档

const archiveOpen = ref(false);
const archiveTarget = ref<null | { fileName: string; id: string }>(null);

function openArchive(row: ReportInboxRow) {
  archiveTarget.value = { fileName: row.fileName, id: row.id };
  archiveOpen.value = true;
}
</script>

<template>
  <Page
    description="先扫描后标注：未指定患者的扫描件集中分拣并指定位置。"
    title="待归档区"
  >
    <!-- 开关关闭（A / B 默认）：本页隐藏入口、不发起请求（AGENTS §5.7；后端 40301 兜底） -->
    <Card v-if="!featureOn" :bordered="false">
      <Empty
        description="待归档区未开通（医院 C 启用项，功能开关 report_inbox）"
      />
    </Card>

    <Card v-else :bordered="false">
      <!-- 筛选栏（上传日期 / 上传人；待归档文件尚无患者与类型，分拣时再指定） -->
      <Form class="filters" layout="inline" @submit.prevent>
        <FormItem label="上传日期">
          <DatePicker.RangePicker
            v-model:value="filters.uploadedRange"
            value-format="YYYY-MM-DD"
          />
        </FormItem>
        <FormItem label="上传人">
          <Select
            v-model:value="filters.uploaderId"
            allow-clear
            placeholder="全部"
            style="width: 150px"
            :options="
              uploaderOptions.map((item) => ({
                label: item.name,
                value: item.id,
              }))
            "
          />
        </FormItem>
        <FormItem>
          <Button
            data-testid="inbox-search-submit"
            type="primary"
            @click="handleSearch"
          >
            查询
          </Button>
          <Button data-testid="inbox-search-reset" @click="handleReset">
            重置
          </Button>
        </FormItem>
      </Form>

      <div class="toolbar">
        <Button
          v-if="canUpload"
          data-testid="inbox-upload-button"
          type="primary"
          @click="uploadOpen = true"
        >
          上传扫描件
        </Button>
        <span class="hint">
          共
          {{ total }}
          个待分拣文件；逐个「归档」时指定患者、类型与位置（至少一个）。
        </span>
      </div>

      <Table
        :columns="DESKTOP_COLUMNS"
        :data-source="dataSource"
        :loading="loading"
        :pagination="{
          current: pageNo,
          pageSize: PAGE_SIZE,
          showSizeChanger: false,
          total,
        }"
        row-key="id"
        size="small"
        data-testid="inbox-table"
        @change="(pag) => handleTableChange(pag.current ?? 1)"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.dataIndex === 'thumb'">
            <!-- 点缩略图 / 类型图标 → 全屏查看器（E16-T06，A-ATT-005） -->
            <img
              v-if="thumbnailSrc(record)"
              alt="打开预览"
              class="thumb clickable"
              data-testid="inbox-row-thumb"
              loading="lazy"
              :src="thumbnailSrc(record)"
              @click="openPreview(record)"
            />
            <span
              v-else-if="thumbView(record)?.kind === 'file'"
              class="file-badge clickable"
              @click="openPreview(record)"
            >
              {{ thumbView(record)?.label }}
            </span>
            <span v-else class="dim">—</span>
          </template>
          <template v-else-if="column.dataIndex === 'fileName'">
            <span :title="record.fileName">{{ record.fileName }}</span>
          </template>
          <template v-else-if="column.dataIndex === 'uploaderName'">
            {{ record.uploaderName ?? '—' }}
          </template>
          <template v-else-if="column.dataIndex === 'action'">
            <a
              v-if="canUpload"
              data-testid="inbox-row-archive"
              @click.prevent="openArchive(record)"
            >
              归档
            </a>
            <a
              data-testid="inbox-row-preview"
              @click.prevent="openPreview(record)"
            >
              预览
            </a>
          </template>
        </template>
        <template #emptyText>
          <Empty description="暂无待分拣文件 · 上传扫描件后在这里集中分拣" />
        </template>
      </Table>
    </Card>

    <!-- E16-T06 / A-ATT-005：全屏查看器（缩放 / 旋转 / 翻转 / 水印，不可关闭） -->
    <WatermarkPreview
      :can-download="canDownloadFile"
      :files="previewFiles"
      :open="previewOpen"
      :viewer="viewer"
      @download="handlePreviewDownload"
      @update:open="previewOpen = $event"
    />

    <!-- 先扫描：一批文件放入待归档区（未选患者；一次 1–50 个） -->
    <Modal
      :confirm-loading="intaking"
      :open="uploadOpen"
      ok-text="放入待归档区"
      title="上传扫描件（先扫描后标注，不选患者）"
      :width="720"
      @cancel="uploadOpen = false"
      @ok="handleIntake"
    >
      <Upload
        :before-upload="beforeUpload"
        :custom-request="customRequest"
        :file-list="[]"
        multiple
      >
        <Button :loading="uploadingCount > 0">选择文件（可多选）</Button>
      </Upload>
      <ul v-if="uploadItems.length > 0" class="file-list">
        <li v-for="item in uploadItems" :key="item.fileId + item.name">
          {{ item.name }}
          <span
            :class="{ 'queue-failed': item.status === 'failed' }"
            class="queue-status"
          >
            {{
              item.status === 'uploading'
                ? '上传中…'
                : item.status === 'done'
                  ? '已上传'
                  : '失败'
            }}
          </span>
          <Button
            danger
            size="small"
            type="link"
            @click="removeUploadItem(item)"
          >
            移除
          </Button>
        </li>
      </ul>
      <p class="hint">
        放入后可随时分拣（按上传人所在科室 / 病区隔离）；图片将压缩后上传（长边
        ≤ 2560、转
        JPEG、去除位置信息）；格式与大小按租户配置（真实类型以后端检测为准）。
      </p>
    </Modal>

    <!-- 后标注：指定患者、类型与位置（至少一个）后归档（C-SCAN-009） -->
    <InboxArchiveModal
      :file="archiveTarget"
      :open="archiveOpen"
      @archived="load()"
      @update:open="archiveOpen = $event"
    />
  </Page>
</template>

<style scoped>
.filters {
  row-gap: 8px;
}

.filters :deep(.ant-form-item) {
  margin-right: 16px;
  margin-bottom: 8px;
}

.toolbar {
  display: flex;
  gap: 12px;
  align-items: center;
  margin-bottom: 8px;
}

.thumb {
  width: 64px;
  height: 64px;
  object-fit: cover;
  border-radius: 4px;
}

.clickable {
  cursor: pointer;
}

.clickable:hover {
  opacity: 0.8;
}

.file-badge {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 64px;
  height: 64px;
  font-size: 12px;
  color: #8c8c8c;
  background: #fafafa;
  border: 1px solid #e9e9e9;
  border-radius: 4px;
}

.dim {
  color: #8c8c8c;
}

.hint {
  font-size: 12px;
  color: #8c8c8c;
}

.file-list {
  padding-left: 16px;
  margin: 8px 0 0;
}

.file-list li {
  font-size: 13px;
}

.queue-status {
  margin-left: 4px;
  color: #8c8c8c;
}

.queue-failed {
  color: #ff4d4f;
}
</style>
