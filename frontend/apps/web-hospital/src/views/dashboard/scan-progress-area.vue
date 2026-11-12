<script lang="ts" setup>
import type { ScanProgressView } from '@crdp/api';

import { computed, onMounted, ref, watch } from 'vue';

import { colors } from '@crdp/tokens';
import {
  Card,
  Empty,
  message,
  Progress,
  Select,
  Space,
  Spin,
} from 'ant-design-vue';

import { fetchScanProgressCard } from '#/api/core/dashboard';

defineOptions({ name: 'DashboardScanProgressArea' });

/**
 * E21-T15 / C-DASH-007 · 首页补录进度卡（pages.md §2 医院 C 默认顺序末位；接口
 * GET /dashboard/cards/scan_progress，开关 scan_progress——布局接口已按开关隐藏卡片，
 * 组件渲染即代表开关开启）。待录入与已电子化扫描件的数量与比例（比例 = 已电子化 ÷
 *（待录入 + 已电子化），无需录入不进分母）+ 补录随访记录条数；可按上传人、文档年份
 * 查看，病区为首页页面级筛选（A-DASH-005 / C-DASH-008）。
 *
 * 卡片是否显示由布局下发（dash_card 目录 followup V85 + 角色布局 + 用户隐藏）；
 * 数字按本人数据范围统计（后端 @DataScope），范围内无扫描件显示 0 而非报错。
 */
const props = defineProps<{
  /** 页面级病区筛选（A-DASH-005）：全部卡片共用，未选 = 本人全部可见病区。 */
  wardId?: number;
}>();

const loading = ref(false);
const view = ref<null | ScanProgressView>(null);
const uploaderId = ref<null | string>(null);
const year = ref<null | number>(null);

const uploaderOptions = computed(() =>
  (view.value?.uploaders ?? []).map((item) => ({
    label: item.name,
    value: item.id,
  })),
);

const yearOptions = computed(() =>
  (view.value?.years ?? []).map((item) => ({
    label: `${item} 年`,
    value: item,
  })),
);

/** 已电子化比例显示（百分整数；范围内无扫描件时 0）。 */
const donePct = computed(() => view.value?.donePct ?? 0);

/** 范围内是否有可统计的扫描件（三种状态都为 0 = 空态）。 */
const hasData = computed(() => {
  const data = view.value;
  if (!data) return false;
  return data.pendingCount + data.doneCount + data.noEntryCount > 0;
});

async function load() {
  loading.value = true;
  try {
    view.value = await fetchScanProgressCard({
      uploaderId: uploaderId.value,
      wardId: props.wardId,
      year: year.value,
    });
  } catch {
    message.error('补录进度加载失败，请稍后重试');
  } finally {
    loading.value = false;
  }
}

function handleFilterChange() {
  void load();
}

watch(
  () => props.wardId,
  () => void load(),
);

onMounted(() => {
  void load();
});
</script>

<template>
  <Card class="scan-progress-card" title="补录进度">
    <template #extra>
      <Space wrap>
        <!-- C-DASH-007「可按上传人、文档年份查看」；病区为页面级筛选 -->
        <Select
          v-model:value="uploaderId"
          allow-clear
          data-testid="scan-progress-uploader"
          :options="uploaderOptions"
          placeholder="全部上传人"
          :style="{ width: 160 }"
          @change="handleFilterChange"
        />
        <Select
          v-model:value="year"
          allow-clear
          data-testid="scan-progress-year"
          :options="yearOptions"
          placeholder="全部年份"
          :style="{ width: 120 }"
          @change="handleFilterChange"
        />
      </Space>
    </template>
    <Spin :spinning="loading">
      <Empty
        v-if="!loading && !hasData"
        description="暂无待录入 / 已电子化扫描件"
      />
      <div v-else class="progress-body" data-testid="scan-progress-body">
        <div class="ratio">
          <span class="ratio-number">{{ donePct }}%</span>
          <span class="ratio-label">已电子化比例</span>
        </div>
        <div class="bars">
          <!-- 比例 = 已电子化 ÷（待录入 + 已电子化），无需录入不进分母（后端同口径） -->
          <Progress :percent="donePct" :stroke-color="colors.primary" />
          <div class="legend">
            <span>待录入 {{ view?.pendingCount ?? 0 }} 份</span>
            <span>已电子化 {{ view?.doneCount ?? 0 }} 份</span>
            <span>无需录入 {{ view?.noEntryCount ?? 0 }} 份</span>
          </div>
          <p class="backfill">
            补录随访记录
            <strong data-testid="scan-progress-backfill">
              {{ view?.backfillRecordCount ?? 0 }}
            </strong>
            条（历史补录，已提交）
          </p>
        </div>
      </div>
    </Spin>
  </Card>
</template>

<style scoped>
.scan-progress-card {
  margin-bottom: 16px;
  border-radius: 8px;
}

.progress-body {
  display: flex;
  gap: 24px;
  align-items: center;
  min-height: 120px;
}

.ratio {
  display: flex;
  flex: 1;
  flex-direction: column;
  align-items: center;
}

.ratio-number {
  font-size: 32px;
  font-weight: 600;
  color: #262626;
}

.ratio-label {
  margin-top: 4px;
  font-size: 12px;
  color: #8c8c8c;
}

.bars {
  flex: 2;
}

.legend {
  display: flex;
  gap: 24px;
  margin-top: 8px;
  font-size: 13px;
  color: #262626;
}

.backfill {
  margin: 8px 0 0;
  font-size: 12px;
  color: #8c8c8c;
}
</style>
