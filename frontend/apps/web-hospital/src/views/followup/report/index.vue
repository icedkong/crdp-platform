<script lang="ts" setup>
import type { ReportSearchRow } from '@crdp/api';

import type { ReportListFilters, ThumbnailKind } from './list-logic';

import { computed, onMounted, reactive, ref } from 'vue';
import { useRoute } from 'vue-router';

import { Page } from '@vben/common-ui';

import { foldReportLocations, reportRowDigitizeStatusText } from '@crdp/api';
import { StatusTag } from '@crdp/status-tag';
import { sizes } from '@crdp/tokens';
import {
  Button,
  Card,
  DatePicker,
  Empty,
  Form,
  FormItem,
  Input,
  message,
  Select,
  Table,
  Tag,
  Tooltip,
} from 'ant-design-vue';

import { getFilePreviewUrl } from '#/api/core/file';
import { searchReports } from '#/api/core/report';
import DigitizeStatusModal from '#/components/followup/digitize-status-modal.vue';
import { downloadFileById } from '#/components/followup/file-download';
import WatermarkPreview from '#/components/followup/watermark-preview.vue';
import { useBreakpoint } from '#/reference/use-breakpoint';
import { useFeature } from '#/reference/use-feature';
import { usePermission } from '#/reference/use-permission';
import { useWatermarkViewer } from '#/reference/use-watermark-viewer';

import {
  buildSearchPayload,
  DESKTOP_COLUMNS,
  DIGITIZE_STATUS_OPTIONS,
  EMPTY_FILTERS,
  filtersFromRouteQuery,
  formatUploadedAt,
  LOCATION_SCOPE_OPTIONS,
  PAGE_SIZE,
  patientReportsPath,
  patientSubText,
  previewFilesOf,
  TABLET_COLUMNS,
  thumbnailOf,
} from './list-logic';

defineOptions({ name: 'FollowupReport' });

/**
 * E16-T05 / A-ATT-016、C-SCAN-014 · 全院报告单（/followup/report，pages.md §6.4）：
 * 独立菜单列出本人可见病区的全部报告单——病区隔离由后端数据范围承担（越范围患者不可见），
 * 行内姓名 / 病案号只显示脱敏值；「位置」列显示标签、超过 2 个折叠 +N（悬停显示全部），
 * 位置筛选含「未指定位置」（无位置报告单位置列显示橙色「未指定位置」）；点击行进入该患者的
 * 报告单页签（A-ATT-016）。电子化状态为医院 C 启用项（A 行为空不显示）。
 *
 * E21-T15 / C-SCAN-011：行内「改状态」手工修改电子化状态（改为「无需录入」须填原因、
 * 每次修改记入操作日志）——scan_progress 开关关闭或有 followup:scan:upload 权限缺少时
 * 隐藏入口（后端 40301 / 40300 兜底）；路由 query 的 digitizeStatus 预填筛选（首页
 * 「待录入扫描件数」钻取，C-DASH-007）。
 *
 * 响应式：平板保留主要列；手机卡片列表（本页属「报告单上传与查看」响应式范围）。
 * 上传入口在患者档案的报告单页签（须先有患者上下文），本列表不放「添加报告单 / 批量上传」。
 */
const { isMobile, isTablet } = useBreakpoint();
const RangePicker = DatePicker.RangePicker;
const route = useRoute();

// ---------------------------------------------------------------- 筛选与分页状态

const filters = reactive<ReportListFilters>({
  ...EMPTY_FILTERS,
  ...filtersFromRouteQuery(route.query as Record<string, unknown>),
});
const pageNo = ref(1);
const loading = ref(false);
const rows = ref<ReportSearchRow[]>([]);
const total = ref(0);
const typeOptions = ref<Array<{ code: string; name: string }>>([]);
const uploaderOptions = ref<Array<{ id: string; name: string }>>([]);
const wardOptions = ref<Array<{ id: string; name: string }>>([]);

async function load() {
  loading.value = true;
  try {
    const page = await searchReports({
      ...buildSearchPayload({ ...filters }),
      pageNo: pageNo.value,
      pageSize: PAGE_SIZE,
    });
    rows.value = page.items;
    total.value = page.total;
    typeOptions.value = page.types;
    uploaderOptions.value = page.uploaders;
    wardOptions.value = page.wards;
    void loadThumbnails(page.items);
  } catch {
    message.error('报告单列表加载失败，请稍后重试');
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

/** 本页图片首附件逐个换预览短时链接（≤ 5 分钟、写审计，components.md §6 懒加载口径）。 */
async function loadThumbnails(items: ReportSearchRow[]) {
  const targets = items
    .map((row) => thumbnailOf(row))
    .filter((item): item is { file: { fileId: string }; kind: 'image' } => {
      if (!item || item.kind !== 'image') return false;
      return !thumbnailUrls.value[item.file.fileId];
    });
  await Promise.all(
    targets.map(async (item) => {
      try {
        const grant = await getFilePreviewUrl(item.file.fileId);
        thumbnailUrls.value = {
          ...thumbnailUrls.value,
          [item.file.fileId]: grant.url,
        };
      } catch {
        // 换不到链接时降级为文件图标（不阻塞列表）
      }
    }),
  );
}

function thumbnailSrc(row: ReportSearchRow): null | string {
  const thumb = thumbnailOf(row);
  return thumb && thumb.kind === 'image'
    ? (thumbnailUrls.value[thumb.file.fileId] ?? null)
    : null;
}

// ---------------------------------------------------------------- 列与行渲染

const columns = computed(() =>
  isTablet.value ? TABLET_COLUMNS : DESKTOP_COLUMNS,
);

const dataSource = computed(() =>
  rows.value.map((row) => ({
    ...row,
    createdAtText: formatUploadedAt(row.createdAt),
    key: row.id,
  })),
);

function foldOf(row: ReportSearchRow) {
  return foldReportLocations(row.locations);
}

function thumbView(row: ReportSearchRow): ThumbnailKind {
  return thumbnailOf(row);
}

function digitizeText(row: ReportSearchRow): null | string {
  return reportRowDigitizeStatusText(row.digitizeStatus);
}

/** 点击行 / 「查看」→ 患者档案报告单页签（新标签页，A-ATT-016）。 */
function openPatientReports(row: ReportSearchRow) {
  window.open(patientReportsPath(row.patientId), '_blank');
}

// ---------------------------------------------------------------- E16-T06 全屏查看器（A-ATT-005）

const { hasPermission } = usePermission();
const viewer = useWatermarkViewer();
/** 下载是敏感权限（followup:scan:download，A-ATT-006）：无权限时查看器不显示下载按钮。 */
const canDownloadFile = computed(() => hasPermission('followup:scan:download'));

const previewOpen = ref(false);
const previewFiles = ref<
  Array<{ fileId: string; format: string; name: string }>
>([]);

/** 点缩略图 / 文件类型图标 → 全屏查看器打开首附件（缩放 / 旋转 / 翻转 / 水印）。 */
function openPreview(row: ReportSearchRow) {
  const files = previewFilesOf(row);
  if (!files) return;
  previewFiles.value = files;
  previewOpen.value = true;
}

/** 查看器「下载」：换 5 分钟签名链接（后端写 DOWNLOAD 审计）交给浏览器。 */
async function handlePreviewDownload(fileId: number | string) {
  const ok = await downloadFileById(fileId);
  if (!ok) {
    message.error('下载失败，请稍后重试');
  }
}

// ---------------------------------------------------------------- E21-T15 电子化状态手工修改（C-SCAN-011）

const { isEnabled } = useFeature();
/** 改状态入口：scan_progress 开关 + followup:scan:upload 权限 + 行有电子化状态（A 行为 null 不显示）。 */
const canChangeDigitize = computed(
  () => isEnabled('scan_progress') && hasPermission('followup:scan:upload'),
);

function canChangeDigitizeOf(row: ReportSearchRow): boolean {
  return canChangeDigitize.value && row.digitizeStatus !== null;
}

const digitizeOpen = ref(false);
const digitizeTarget = ref<null | {
  digitizeStatus: ReportSearchRow['digitizeStatus'];
  id: string;
  label: string;
}>(null);

/** 打开改状态弹窗（label = 类型 · 名称，与删除弹窗标题同口径）。 */
function openDigitizeChange(row: ReportSearchRow) {
  digitizeTarget.value = {
    digitizeStatus: row.digitizeStatus,
    id: row.id,
    label: `${row.typeName}${row.name ? ` · ${row.name}` : ''}`,
  };
  digitizeOpen.value = true;
}
</script>

<template>
  <Page
    description="全院报告单与资料的检索入口；按本人可见病区隔离。"
    title="报告单与资料"
  >
    <Card :bordered="false">
      <!-- 筛选栏（pages.md §6.4：患者 / 类型 / 报告时间 / 位置 / 电子化（C）；C-SCAN-014 另有上传日期、上传人、科室病区） -->
      <Form class="filters" layout="inline" @submit.prevent>
        <FormItem label="患者 / 关键词">
          <Input
            v-model:value="filters.keyword"
            allow-clear
            data-testid="report-search-keyword"
            :maxlength="64"
            placeholder="姓名、病历号、随访编号、名称、所见、诊断"
            :style="{ width: `${sizes.filterInputWidth.max}px` }"
            @keyup.enter="handleSearch"
          />
        </FormItem>
        <FormItem label="类型">
          <Select
            v-model:value="filters.typeCode"
            allow-clear
            data-testid="report-search-type"
            placeholder="全部"
            style="width: 150px"
            :options="
              typeOptions.map((item) => ({
                label: item.name,
                value: item.code,
              }))
            "
          />
        </FormItem>
        <FormItem label="报告时间">
          <RangePicker
            v-model:value="filters.reportRange"
            value-format="YYYY-MM-DD"
          />
        </FormItem>
        <FormItem label="位置">
          <Select
            v-model:value="filters.locationScope"
            allow-clear
            data-testid="report-search-location"
            placeholder="全部"
            style="width: 170px"
            :options="LOCATION_SCOPE_OPTIONS"
          />
        </FormItem>
        <FormItem label="电子化">
          <Select
            v-model:value="filters.digitizeStatus"
            allow-clear
            placeholder="全部"
            style="width: 130px"
            :options="DIGITIZE_STATUS_OPTIONS"
          />
        </FormItem>
        <FormItem label="上传日期">
          <RangePicker
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
        <FormItem label="病区">
          <Select
            v-model:value="filters.wardIds"
            allow-clear
            mode="multiple"
            placeholder="全部"
            style="min-width: 180px"
            :options="
              wardOptions.map((item) => ({ label: item.name, value: item.id }))
            "
          />
        </FormItem>
        <FormItem>
          <Button
            data-testid="report-search-submit"
            type="primary"
            @click="handleSearch"
          >
            查询
          </Button>
          <Button data-testid="report-search-reset" @click="handleReset">
            重置
          </Button>
        </FormItem>
      </Form>

      <!-- 手机：卡片列表（pages.md §6.4 响应式；缩略图 + 患者 / 类型 / 时间 / 位置 +N） -->
      <div v-if="isMobile" class="cards">
        <Card
          v-for="row in rows"
          :key="row.id"
          :bordered="false"
          class="report-card"
          @click="openPatientReports(row)"
        >
          <div class="card-line">
            <!-- 点缩略图 / 类型图标 → 全屏查看器（E16-T06）；点卡片其余区域进患者页签 -->
            <img
              v-if="thumbnailSrc(row)"
              alt="打开预览"
              class="card-thumb clickable"
              data-testid="report-card-thumb"
              loading="lazy"
              :src="thumbnailSrc(row)"
              @click.stop="openPreview(row)"
            />
            <span
              v-else-if="thumbView(row)?.kind === 'file'"
              class="card-file clickable"
              @click.stop="openPreview(row)"
            >
              {{ thumbView(row)?.label }}
            </span>
            <span v-else class="card-file">—</span>
            <span class="card-patient">
              {{ row.patientName }}
              <small>{{ patientSubText(row) }}</small>
            </span>
            <StatusTag
              v-if="digitizeText(row)"
              :status="digitizeText(row) ?? ''"
            />
          </div>
          <div>
            {{ row.typeName }} · {{ row.name ?? '—' }} · {{ row.reportDate }}
          </div>
          <div v-if="row.locations.length === 0" class="unassigned">
            未指定位置
          </div>
          <div v-else class="card-locations">
            <Tag v-for="text in foldOf(row).visible" :key="text">
              {{ text }}
            </Tag>
            <Tooltip :title="foldOf(row).all">
              <Tag v-if="foldOf(row).extraCount > 0">
                +{{ foldOf(row).extraCount }}
              </Tag>
            </Tooltip>
          </div>
        </Card>
        <Empty v-if="!loading && rows.length === 0" description="暂无报告单" />
      </div>

      <Table
        v-else
        :columns="columns"
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
        data-testid="hospital-report-table"
        @change="(pag) => handleTableChange(pag.current ?? 1)"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.dataIndex === 'thumb'">
            <!-- 点缩略图 / 类型图标 → 全屏查看器（E16-T06，A-ATT-005） -->
            <img
              v-if="thumbnailSrc(record)"
              alt="打开预览"
              class="thumb clickable"
              data-testid="report-row-thumb"
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
          <template v-else-if="column.dataIndex === 'patientName'">
            <div class="patient">
              {{ record.patientName }}
              <small>{{ patientSubText(record) }}</small>
            </div>
          </template>
          <template v-else-if="column.dataIndex === 'name'">
            {{ record.name ?? '—' }}
          </template>
          <template v-else-if="column.dataIndex === 'locations'">
            <template v-if="record.locations.length === 0">
              <span class="unassigned">未指定位置</span>
            </template>
            <template v-else>
              <Tag v-for="text in foldOf(record).visible" :key="text">
                {{ text }}
              </Tag>
              <Tooltip
                v-if="foldOf(record).extraCount > 0"
                :title="foldOf(record).all"
              >
                <Tag data-testid="location-fold">
                  +{{ foldOf(record).extraCount }}
                </Tag>
              </Tooltip>
            </template>
          </template>
          <template v-else-if="column.dataIndex === 'digitizeStatus'">
            <StatusTag
              v-if="digitizeText(record)"
              :status="digitizeText(record) ?? ''"
            />
            <span v-else class="dim">—</span>
          </template>
          <template v-else-if="column.dataIndex === 'creatorName'">
            {{ record.creatorName ?? '—' }}
          </template>
          <template v-else-if="column.dataIndex === 'createdAtText'">
            {{ record.createdAtText }}
          </template>
          <template v-else-if="column.dataIndex === 'action'">
            <!-- E21-T15 / C-SCAN-011：手工修改电子化状态（改为「无需录入」须填原因、留痕） -->
            <a
              v-if="canChangeDigitizeOf(record)"
              data-testid="report-row-digitize"
              @click.prevent="openDigitizeChange(record)"
            >
              改状态
            </a>
            <a
              data-testid="report-row-open"
              @click.prevent="openPatientReports(record)"
            >
              查看
            </a>
          </template>
        </template>
        <template #emptyText>
          <Empty description="暂无报告单 · 按条件检索或在患者档案上传" />
        </template>
      </Table>
      <p class="hint">
        共
        {{ total }}
        份；缩略图取首附件，点击可在全屏查看器打开（带水印）。「未指定位置」的报告单可在患者档案内补指定位置（随修改位置功能开放）。
      </p>
    </Card>

    <!-- E16-T06 / A-ATT-005：全屏查看器（缩放 / 旋转 / 翻转 / 翻页 / 水印，不可关闭） -->
    <WatermarkPreview
      :can-download="canDownloadFile"
      :files="previewFiles"
      :open="previewOpen"
      :viewer="viewer"
      @download="handlePreviewDownload"
      @update:open="previewOpen = $event"
    />

    <!-- E21-T15 / C-SCAN-011：手工修改电子化状态（改为「无需录入」须填原因、每次留痕） -->
    <DigitizeStatusModal
      v-model:open="digitizeOpen"
      :report="digitizeTarget"
      @success="load()"
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

.thumb {
  width: 64px;
  height: 64px;
  object-fit: cover;
  border-radius: 4px;
}

/* 点缩略图 / 类型图标打开全屏查看器（E16-T06） */
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

.patient small {
  display: block;
  font-size: 12px;
  color: #8c8c8c;
}

.unassigned {
  color: #fa8c16;
}

.dim {
  color: #8c8c8c;
}

.hint {
  margin-top: 8px;
  font-size: 12px;
  color: #8c8c8c;
}

.cards {
  display: grid;
  gap: 8px;
}

.card-line {
  display: flex;
  gap: 8px;
  align-items: center;
}

.card-thumb {
  width: 56px;
  height: 56px;
  object-fit: cover;
  border-radius: 4px;
}

.card-file {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 56px;
  height: 56px;
  font-size: 12px;
  color: #8c8c8c;
  background: #fafafa;
  border: 1px solid #e9e9e9;
  border-radius: 4px;
}

.card-patient {
  flex: 1;
}

.card-patient small {
  display: block;
  font-size: 12px;
  color: #8c8c8c;
}

.card-locations {
  margin-top: 4px;
}

.report-card {
  cursor: pointer;
}
</style>
