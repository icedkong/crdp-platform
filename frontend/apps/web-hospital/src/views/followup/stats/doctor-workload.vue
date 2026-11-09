<script lang="ts" setup>
import type { DoctorWorkloadRow } from '@crdp/api';

import { onMounted, ref, watch } from 'vue';
import { useRouter } from 'vue-router';

import { doctorWorkloadRoute, formatRatePct } from '@crdp/api';
import { colors, sizes } from '@crdp/tokens';
import { Button, Card, Empty, message, Spin, Table } from 'ant-design-vue';

import { fetchDoctorWorkload } from '#/api/core/dashboard';

defineOptions({ name: 'FollowupStatsDoctorWorkload' });

/**
 * E19-T07 / A-DASH-009、C-DASH-010 · 医护负责患者数（统计页页签，A 规格 §9
 * 「主要页面 5」）：表格列出每位医生的负责患者数、在随访、本月应随访、逾期、
 * 每年随访率；点击数字进入对应患者 / 任务列表（钻取条件由后端随行下发，前端只
 * 透传路由 query——患者列表 doctorId〔/ status〕、任务列表 tab=all + dueFrom/dueTo /
 * status=OVERDUE）。归口 = 患者责任医生（面板口径，OQ-118）；随访率口径 Q38
 * （已随访含逾期完成 / 补录 / 历史导入，患者 × 年度去重），数据时点 = 事实表最近
 * 每日刷新（asOf 回显）；患者 / 任务列按当前数据实时计算（本月 = asOfMonth）。
 */
const props = defineProps<{
  /** 页面级病区筛选（A-DASH-005）：与统计页其他页签共用，未选 = 本人全部可见病区。 */
  wardId?: number;
}>();

const router = useRouter();

const loading = ref(false);
const rows = ref<DoctorWorkloadRow[]>([]);
const asOfMonth = ref('');
const asOf = ref<null | string>(null);

const columns = [
  { dataIndex: 'doctorName', title: '医生', width: 180 },
  {
    align: 'right' as const,
    key: 'totalPatients',
    title: '负责患者数',
    width: 120,
  },
  {
    align: 'right' as const,
    key: 'activePatients',
    title: '在随访',
    width: 100,
  },
  { align: 'right' as const, key: 'monthDue', title: '本月应随访', width: 120 },
  { align: 'right' as const, key: 'overdue', title: '逾期', width: 100 },
  {
    align: 'right' as const,
    key: 'followRate',
    title: '每年随访率',
    width: 210,
  },
];

async function load() {
  loading.value = true;
  try {
    const view = await fetchDoctorWorkload({ wardId: props.wardId });
    rows.value = view.rows;
    asOfMonth.value = view.asOfMonth;
    asOf.value = view.asOf ?? null;
  } catch {
    message.error('医护负责患者数加载失败，请稍后重试');
  } finally {
    loading.value = false;
  }
}

/** 点数字钻取（条件由后端下发，A 规格 §9「点击数字进入对应患者或任务列表」）。 */
function drill(
  row: DoctorWorkloadRow,
  kind: 'active' | 'monthDue' | 'overdue' | 'total',
) {
  const target = doctorWorkloadRoute(row, kind);
  if (target) {
    void router.push(target);
  }
}

/** 随访率主列：醒目比率 + 小字「已随访 n / 应随访 m」（参考口径，Q38）。 */
function rateCell(row: DoctorWorkloadRow) {
  return {
    main: formatRatePct(row.followRatePct),
    ref: `已随访 ${row.rateFollowedCount} / 应随访 ${row.rateDueCount}`,
  };
}

watch(
  () => props.wardId,
  () => void load(),
);

onMounted(load);
</script>

<template>
  <Card class="workload-card" title="医护负责患者数">
    <Spin :spinning="loading">
      <Empty
        v-if="!loading && rows.length === 0"
        description="暂无负责患者数据"
        data-testid="doctor-workload-empty"
      />
      <template v-else>
        <Table
          :columns="columns"
          :data-source="rows"
          :pagination="{
            pageSize: sizes.tablePageSize,
            showSizeChanger: false,
            showTotal: (count: number) => `共 ${count} 位医生`,
          }"
          row-key="doctorId"
          size="small"
          data-testid="doctor-workload-table"
        >
          <template #bodyCell="{ column, record }">
            <template v-if="column.key === 'totalPatients'">
              <Button
                :data-testid="`workload-total-${(record as DoctorWorkloadRow).doctorId}`"
                type="link"
                @click="drill(record as DoctorWorkloadRow, 'total')"
              >
                {{ (record as DoctorWorkloadRow).totalPatients }}
              </Button>
            </template>
            <template v-else-if="column.key === 'activePatients'">
              <Button
                :data-testid="`workload-active-${(record as DoctorWorkloadRow).doctorId}`"
                type="link"
                @click="drill(record as DoctorWorkloadRow, 'active')"
              >
                {{ (record as DoctorWorkloadRow).activePatients }}
              </Button>
            </template>
            <template v-else-if="column.key === 'monthDue'">
              <Button
                :data-testid="`workload-month-due-${(record as DoctorWorkloadRow).doctorId}`"
                type="link"
                @click="drill(record as DoctorWorkloadRow, 'monthDue')"
              >
                {{ (record as DoctorWorkloadRow).monthDue }}
              </Button>
            </template>
            <template v-else-if="column.key === 'overdue'">
              <Button
                :data-testid="`workload-overdue-${(record as DoctorWorkloadRow).doctorId}`"
                type="link"
                @click="drill(record as DoctorWorkloadRow, 'overdue')"
              >
                {{ (record as DoctorWorkloadRow).overdue }}
              </Button>
            </template>
            <template v-else-if="column.key === 'followRate'">
              <span :style="{ color: colors.primary }" class="rate-main">
                {{ rateCell(record as DoctorWorkloadRow).main }}
              </span>
              <span class="rate-ref">
                {{ rateCell(record as DoctorWorkloadRow).ref }}
              </span>
            </template>
          </template>
        </Table>
        <div v-if="asOf || asOfMonth" class="as-of">
          <span v-if="asOf">随访率数据截至 {{ asOf }}（每日刷新）</span>
          <span v-if="asOf && asOfMonth"> · </span>
          <span v-if="asOfMonth">本月口径 {{ asOfMonth }}</span>
        </div>
      </template>
    </Spin>
  </Card>
</template>

<style scoped>
.workload-card {
  border-radius: 8px;
}

.rate-main {
  margin-right: 8px;
  font-size: 16px;
  font-weight: 600;
}

.rate-ref {
  font-size: 12px;
  color: rgb(0 0 0 / 45%);
}

.as-of {
  margin-top: 8px;
  font-size: 12px;
  color: rgb(0 0 0 / 45%);
  text-align: right;
}
</style>
