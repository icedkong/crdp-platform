<script lang="ts" setup>
import type { AbnormalRecordItem, AbnormalRecordsView } from '@crdp/api';

import { computed, onMounted, ref, watch } from 'vue';
import { useRouter } from 'vue-router';

import { abnormalRecordRoute } from '@crdp/api';
import { colors } from '@crdp/tokens';
import {
  Card,
  Empty,
  message,
  Select,
  Space,
  Spin,
  Table,
} from 'ant-design-vue';

import { fetchAbnormalRecordsCard } from '#/api/core/dashboard';

defineOptions({ name: 'DashboardAbnormalRecordsArea' });

/**
 * E21-T19 / C-DASH-006 · 首页异常标红记录卡（接口
 * GET /dashboard/cards/abnormal_records，开关 implant——布局接口已按开关隐藏卡片，组件
 * 渲染即代表开关开启）。近 N 天（默认 30，可切 7 / 30 / 90 / 365）提交的含标红参数的
 * 随访记录，显示标红项目与变化率；点击进入记录（患者详情随访记录页签定位该记录）；
 * 只做查看，不需处理（无任何处置动作）。N=30 时 total 与指标卡「近 30 天含标红参数的
 * 记录数」同条件树（一个口径一个查询）。
 *
 * 标红命中来自 E17-T04 对比重算（fu_record.highlight，与录入页实时标红同源）；姓名为
 * 脱敏值；数字按本人数据范围统计（后端 @DataScope）。
 */
const props = defineProps<{
  /** 页面级病区筛选（C-DASH-008）：全部卡片共用，未选 = 本人全部可见病区。 */
  wardId?: number;
}>();

const router = useRouter();
const loading = ref(false);
const view = ref<AbnormalRecordsView | null>(null);
const days = ref<number>(30);

/** 近 N 天窗口选项（C-DASH-006「近 N 天（默认 30 天）」；365 为后端上界）。 */
const DAY_OPTIONS = [
  { label: '近 7 天', value: 7 },
  { label: '近 30 天', value: 30 },
  { label: '近 90 天', value: 90 },
  { label: '近 365 天', value: 365 },
];

const hasData = computed(() => (view.value?.items.length ?? 0) > 0);

const columns = [
  { dataIndex: 'name', title: '患者', width: 150 },
  { dataIndex: 'visitDate', title: '随访日期', width: 110 },
  { key: 'hits', title: '标红项目与变化率' },
];

/** 标红命中行文案：规则说明 + 变化率（红色，design-system「标红」用色）。 */
function hitText(hit: {
  changeText?: null | string;
  message?: null | string;
}): { change: string; label: string } {
  return {
    change: hit.changeText ?? '',
    label: hit.message ?? '',
  };
}

/** 行点击进入该记录（患者详情随访记录页签 + 记录锚点，C-DASH-006「点击进入记录」）。 */
function openRecord(item: AbnormalRecordItem) {
  void router.push(abnormalRecordRoute(item));
}

async function load() {
  loading.value = true;
  try {
    view.value = await fetchAbnormalRecordsCard({
      days: days.value,
      wardId: props.wardId,
    });
  } catch {
    message.error('异常标红记录加载失败，请稍后重试');
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
  <Card class="abnormal-card" title="异常标红记录">
    <template #extra>
      <Space wrap>
        <!-- C-DASH-006「近 N 天（默认 30 天）」窗口切换；病区为页面级筛选 -->
        <Select
          v-model:value="days"
          data-testid="abnormal-days"
          :options="DAY_OPTIONS"
          :style="{ width: 110 }"
          @change="handleFilterChange"
        />
        <span class="total" data-testid="abnormal-total">
          共 {{ view?.total ?? 0 }} 条
        </span>
      </Space>
    </template>
    <Spin :spinning="loading">
      <Empty
        v-if="!loading && !hasData"
        description="近 N 天暂无含标红参数的记录"
      />
      <Table
        v-else
        :columns="columns"
        :custom-row="
          (item: AbnormalRecordItem) => ({ onClick: () => openRecord(item) })
        "
        :data-source="view?.items ?? []"
        :loading="loading"
        :pagination="false"
        row-key="recordId"
        size="small"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'hits'">
            <!-- 标红项目与变化率：规则文案 + 变化率（红色，只查看不处理） -->
            <ul class="hits" data-testid="abnormal-hits">
              <li v-for="(hit, index) in record.hits" :key="index">
                <span>{{ hitText(hit).label }}</span>
                <span
                  v-if="hitText(hit).change"
                  class="hit-change"
                  :style="{ color: colors.error }"
                >
                  {{ hitText(hit).change }}
                </span>
              </li>
            </ul>
          </template>
          <template v-else-if="column.dataIndex === 'visitDate'">
            <span>{{ record.visitDate ?? '—' }}</span>
          </template>
        </template>
      </Table>
    </Spin>
  </Card>
</template>

<style scoped>
.abnormal-card {
  margin-bottom: 16px;
  border-radius: 8px;
}

.abnormal-card :deep(.ant-table-row) {
  cursor: pointer;
}

.total {
  font-size: 13px;
  color: #8c8c8c;
}

.hits {
  padding: 0;
  margin: 0;
  list-style: none;
}

.hits li {
  font-size: 13px;
  line-height: 22px;
}

.hit-change {
  margin-left: 8px;
  font-weight: 500;
}
</style>
