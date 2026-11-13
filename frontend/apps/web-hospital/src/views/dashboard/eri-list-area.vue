<script lang="ts" setup>
import type { EriListItem, EriListView } from '@crdp/api';

import { computed, onMounted, ref, watch } from 'vue';
import { useRouter } from 'vue-router';

import { eriStatusTone } from '@crdp/api';
import { Card, Empty, message, Spin, Table, Tag } from 'ant-design-vue';

import { fetchEriListCard } from '#/api/core/dashboard';

defineOptions({ name: 'DashboardEriListArea' });

/**
 * E21-T19 / C-DASH-005 · 首页接近 ERI 器械列表卡（接口
 * GET /dashboard/cards/eri_list，开关 eri_cycle——布局接口已按开关隐藏卡片，组件渲染即
 * 代表开关开启）。列出最近一次 ERI 状态为接近ERI或已达ERI(EOI)、或预计电池寿命低于阈值
 * （≤ 1 年）的患者，含器械、最近随访日期、下次计划日期；判定与计划切换（E21-T06 / T07）
 * 同一谓词，total 与指标卡「接近 ERI 器械数」同查询（一个口径一个查询）。
 *
 * 姓名为脱敏值（列表不提供逐行明文）；行点击进入患者详情；已死亡 / 已终止 / 失访患者
 * 不出行（无随访动作）；数字按本人数据范围统计（后端 @DataScope）。
 */
const props = defineProps<{
  /** 页面级病区筛选（C-DASH-008）：全部卡片共用，未选 = 本人全部可见病区。 */
  wardId?: number;
}>();

const router = useRouter();
const loading = ref(false);
const view = ref<EriListView | null>(null);

const hasData = computed(() => (view.value?.items.length ?? 0) > 0);

const columns = [
  { dataIndex: 'name', title: '患者', width: 150 },
  { key: 'device', title: '器械' },
  { key: 'eri', title: 'ERI 状态', width: 150 },
  { dataIndex: 'lastVisitDate', title: '最近随访日期', width: 120 },
  { key: 'next', title: '下次计划日期', width: 120 },
];

/** 患者列：脱敏姓名 + 随访编号（小字）。 */
function patientLabel(item: EriListItem): string {
  return item.visitNo ? `${item.name}（${item.visitNo}）` : item.name;
}

/** 器械列：类型（品牌 / 型号小字）；无在用器械行显示占位。 */
function deviceLabel(item: EriListItem): string {
  return item.deviceTypeLabel ?? '未登记在用器械';
}

function deviceNote(item: EriListItem): string {
  const parts = [item.brandLabel, item.model].filter(
    (part): part is string => typeof part === 'string' && part.length > 0,
  );
  return parts.length > 0 ? parts.join(' · ') : '';
}

/** ERI 状态标签（已达ERI(EOI) 错误色 / 接近ERI 警告色；电池寿命低以小字补充）。 */
function batteryNote(item: EriListItem): string {
  return typeof item.batteryLife === 'number'
    ? `电池寿命 ${item.batteryLife} 年`
    : '';
}

/** 行点击进入患者详情（C-DASH-005 列表行为作入口）。 */
function openPatient(item: EriListItem) {
  void router.push({
    path: `/followup/patient/${item.patientId}`,
    query: { tab: 'baseline' },
  });
}

async function load() {
  loading.value = true;
  try {
    view.value = await fetchEriListCard({ wardId: props.wardId });
  } catch {
    message.error('接近 ERI 器械列表加载失败，请稍后重试');
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
  <Card class="eri-list-card" title="接近 ERI 器械列表">
    <template #extra>
      <!-- total 与指标卡「接近 ERI 器械数」同查询；卡片行上限由后端 limit 下发 -->
      <span class="total" data-testid="eri-list-total">
        共 {{ view?.total ?? 0 }} 名患者（前 {{ view?.items.length ?? 0 }} 行）
      </span>
    </template>
    <Spin :spinning="loading">
      <Empty v-if="!loading && !hasData" description="暂无接近 ERI 器械" />
      <Table
        v-else
        :columns="columns"
        :custom-row="
          (item: EriListItem) => ({ onClick: () => openPatient(item) })
        "
        :data-source="view?.items ?? []"
        :loading="loading"
        :pagination="false"
        row-key="patientId"
        size="small"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.dataIndex === 'name'">
            <!-- 患者列：脱敏姓名 + 随访编号（列表不提供逐行明文） -->
            <span>{{ patientLabel(record) }}</span>
          </template>
          <template v-else-if="column.key === 'device'">
            <div class="device-cell">
              <span>{{ deviceLabel(record) }}</span>
              <span v-if="deviceNote(record)" class="device-note">
                {{ deviceNote(record) }}
              </span>
            </div>
          </template>
          <template v-else-if="column.key === 'eri'">
            <div class="eri-cell">
              <!-- 已达ERI(EOI) 错误色、接近ERI 警告色（design-system 状态色纪律） -->
              <Tag
                v-if="record.eriStatusName"
                :color="eriStatusTone(record.eriStatusCode)"
              >
                {{ record.eriStatusName }}
              </Tag>
              <span v-if="batteryNote(record)" class="battery-note">
                {{ batteryNote(record) }}
              </span>
            </div>
          </template>
          <template v-else-if="column.key === 'next'">
            <!-- 无在组计划显示「—」（E14 写入前为 null） -->
            <span>{{ record.nextPlanDate ?? '—' }}</span>
          </template>
        </template>
      </Table>
    </Spin>
  </Card>
</template>

<style scoped>
.eri-list-card {
  margin-bottom: 16px;
  border-radius: 8px;
}

.eri-list-card :deep(.ant-table-row) {
  cursor: pointer;
}

.total {
  font-size: 13px;
  color: #8c8c8c;
}

.device-cell,
.eri-cell {
  display: flex;
  gap: 8px;
  align-items: center;
}

.device-note,
.battery-note {
  font-size: 12px;
  color: #8c8c8c;
}
</style>
