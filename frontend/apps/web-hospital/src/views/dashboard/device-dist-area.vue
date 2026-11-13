<script lang="ts" setup>
import type { DeviceDistView } from '@crdp/api';

import { computed, onMounted, ref, watch } from 'vue';

import { colors } from '@crdp/tokens';
import { Card, Empty, message, Progress, Spin, Table } from 'ant-design-vue';

import { fetchDeviceDistCard } from '#/api/core/dashboard';

defineOptions({ name: 'DashboardDeviceDistArea' });

/**
 * E21-T19 / C-DASH-002 · 首页器械类型分布卡（pages.md §2 医院 C 默认顺序「器械类型分布
 * 与随访率并列」的位次；接口 GET /dashboard/cards/device_dist，开关 implant——布局接口
 * 已按开关隐藏卡片，组件渲染即代表开关开启）。按起搏器类型统计在随访患者数、可按品牌
 * 拆分；分母 = 范围内在随访患者数（与指标卡「在随访患者数」同条件树），无在用器械的
 * 患者归「未登记在用器械」行；病区为首页页面级筛选（C-DASH-008）。
 *
 * 数字按本人数据范围统计（后端 @DataScope），范围内无患者显示 0 而非报错。
 */
const props = defineProps<{
  /** 页面级病区筛选（A-DASH-005 / C-DASH-008）：全部卡片共用，未选 = 本人全部可见病区。 */
  wardId?: number;
}>();

const loading = ref(false);
const view = ref<DeviceDistView | null>(null);

const hasData = computed(() => (view.value?.rows.length ?? 0) > 0);

const columns = [
  { dataIndex: 'label', title: '起搏器类型', width: 140 },
  {
    align: 'right' as const,
    dataIndex: 'count',
    title: '在随访患者数',
    width: 120,
  },
  { key: 'pct', title: '占比' },
  { key: 'brands', title: '品牌拆分' },
];

/** 品牌拆分（可按品牌拆分，C-DASH-002）：「品牌名 ×n」小标签行。 */
function brandText(brands: Array<{ count: number; label: string }>): string {
  return brands.map((brand) => `${brand.label} ×${brand.count}`).join('　');
}

async function load() {
  loading.value = true;
  try {
    view.value = await fetchDeviceDistCard({ wardId: props.wardId });
  } catch {
    message.error('器械类型分布加载失败，请稍后重试');
  } finally {
    loading.value = false;
  }
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
  <Card class="device-dist-card" title="器械类型分布">
    <template #extra>
      <!-- 分母与指标卡「在随访患者数」同条件树（一个口径一个查询） -->
      <span class="denominator">在随访 {{ view?.followedCount ?? 0 }} 人</span>
    </template>
    <Spin :spinning="loading">
      <Empty v-if="!loading && !hasData" description="暂无在随访患者" />
      <Table
        v-else
        :columns="columns"
        :data-source="view?.rows ?? []"
        :loading="loading"
        :pagination="false"
        row-key="label"
        size="small"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'pct'">
            <!-- 占比 = 类型人数 ÷ 在随访患者数（后端同口径百分整数） -->
            <div
              class="pct-cell"
              :data-testid="`device-dist-pct-${record.label}`"
            >
              <Progress
                :percent="record.pct"
                :show-info="false"
                :stroke-color="colors.primary"
                :style="{ width: 120 }"
              />
              <span class="pct-text">{{ record.pct }}%</span>
            </div>
          </template>
          <template v-else-if="column.key === 'brands'">
            <span class="brands">{{ brandText(record.brands) }}</span>
          </template>
        </template>
      </Table>
    </Spin>
  </Card>
</template>

<style scoped>
.device-dist-card {
  margin-bottom: 16px;
  border-radius: 8px;
}

.denominator {
  font-size: 13px;
  color: #8c8c8c;
}

.pct-cell {
  display: flex;
  gap: 8px;
  align-items: center;
}

.pct-text {
  width: 40px;
  font-size: 13px;
  color: #262626;
}

.brands {
  font-size: 13px;
  color: #595959;
}
</style>
