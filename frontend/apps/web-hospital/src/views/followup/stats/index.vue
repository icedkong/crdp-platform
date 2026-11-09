<script lang="ts" setup>
import { onActivated, onMounted, ref } from 'vue';
import { useRouter } from 'vue-router';

import { Button, Result, Select, Space } from 'ant-design-vue';

import { fetchDashboardWards } from '#/api/core/dashboard';
import { useBreakpoint } from '#/reference/use-breakpoint';
import { useFeature } from '#/reference/use-feature';

import CompletenessArea from '../../dashboard/completeness-area.vue';
import RateArea from '../../dashboard/rate-area.vue';
import DoctorWorkload from './doctor-workload.vue';

defineOptions({ name: 'FollowupStats' });

/**
 * E19-T07 / A-DASH-009、C-DASH-010 · 统计页（pages.md §9）：页签「每年随访率 /
 * 节点随访率 / 数据完整度 / 医护负责患者数」（C 的器械分布等页签随 E21）。前三页与
 * 首页卡片**同一组接口**（stats.md §1「一个口径一个查询」，组件复用首页统计区 /
 * 完整度区）；本任务落地医护负责患者数（GET /stats/doctor-workload，按患者责任
 * 医生一行：负责患者数 / 在随访 / 本月应随访 / 逾期 / 每年随访率，可按病区筛选）。
 *
 * 病区筛选（A-DASH-005）：头部「病区 [全部▾]」全页共用——候选 = 本人全部可见病区，
 * 未选 = 全部可见病区。节点随访率页签受 node_follow_rate 开关控制（关闭后端 40301，
 * 入口隐藏，AGENTS §5.7）。仅桌面 / 平板；手机显示「请在电脑或平板上操作」。
 */
const router = useRouter();
const { isMobile } = useBreakpoint();
const { isEnabled } = useFeature();
const responsive = false;
const title = '统计';

type StatsTab = 'completeness' | 'doctor' | 'node' | 'yearly';

const activeTab = ref<StatsTab>('yearly');

const showNodeTab = () => isEnabled('node_follow_rate');

// ---------------------------------------------------------------- 病区筛选（A-DASH-005 / C-DASH-008）

const wardId = ref<number | undefined>();
const wardOptions = ref<Array<{ label: string; value: number }>>([]);

async function loadWardOptions() {
  try {
    const view = await fetchDashboardWards();
    wardOptions.value = view.wards.map((item) => ({
      label: item.name,
      value: Number(item.id),
    }));
  } catch {
    // 候选加载失败不阻断页面（不选病区 = 全部可见病区）
  }
}

onMounted(loadWardOptions);
// 多标签页 keep-alive：从列表 / 工作台回统计页时刷新病区候选
onActivated(loadWardOptions);
</script>

<template>
  <Result
    v-if="isMobile && !responsive"
    status="info"
    title="请在电脑或平板上操作"
  >
    <template #extra>
      <Button type="primary" @click="router.push('/dashboard')">
        返回首页
      </Button>
    </template>
  </Result>
  <div v-else class="page">
    <div class="title-row">
      <h1 class="crdp-page-title">{{ title }}</h1>
      <Space>
        <!-- 病区筛选（A-DASH-005）：全页共用，未选 = 本人全部可见病区 -->
        <Select
          v-model:value="wardId"
          allow-clear
          :options="wardOptions"
          placeholder="全部病区"
          :style="{ width: 180 }"
          data-testid="stats-ward"
        />
      </Space>
    </div>

    <!-- 页签（pages.md §9；节点随访率受 node_follow_rate 开关显隐，其余常驻） -->
    <div class="tab-bar" role="tablist">
      <button
        :class="{ active: activeTab === 'yearly' }"
        class="tab-btn"
        data-testid="stats-tab-yearly"
        role="tab"
        type="button"
        @click="activeTab = 'yearly'"
      >
        每年随访率
      </button>
      <button
        v-if="showNodeTab()"
        :class="{ active: activeTab === 'node' }"
        class="tab-btn"
        data-testid="stats-tab-node"
        role="tab"
        type="button"
        @click="activeTab = 'node'"
      >
        节点随访率
      </button>
      <button
        :class="{ active: activeTab === 'completeness' }"
        class="tab-btn"
        data-testid="stats-tab-completeness"
        role="tab"
        type="button"
        @click="activeTab = 'completeness'"
      >
        数据完整度
      </button>
      <button
        :class="{ active: activeTab === 'doctor' }"
        class="tab-btn"
        data-testid="stats-tab-doctor"
        role="tab"
        type="button"
        @click="activeTab = 'doctor'"
      >
        医护负责患者数
      </button>
    </div>

    <!-- 前三页与首页卡片同一组接口（同一查询，保证首页与统计页数字一致） -->
    <RateArea
      v-if="activeTab === 'yearly'"
      :show-node="false"
      :show-yearly="true"
      :ward-id="wardId"
    />
    <RateArea
      v-else-if="activeTab === 'node'"
      :show-node="true"
      :show-yearly="false"
      :ward-id="wardId"
    />
    <CompletenessArea
      v-else-if="activeTab === 'completeness'"
      :show-baseline="true"
      :show-followup="true"
      :ward-id="wardId"
    />
    <DoctorWorkload v-else :ward-id="wardId" />
  </div>
</template>

<style scoped>
.page {
  padding: 16px;
}

.title-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8px;
}

.tab-bar {
  display: flex;
  gap: 4px;
  margin-bottom: 16px;
  border-bottom: 1px solid rgb(0 0 0 / 6%);
}

.tab-btn {
  padding: 8px 16px;
  font-size: 14px;
  color: rgb(0 0 0 / 65%);
  cursor: pointer;
  background: none;
  border: none;
  border-bottom: 2px solid transparent;
}

.tab-btn:focus-visible {
  outline: 2px solid rgb(58 117 255 / 40%);
}

.tab-btn.active {
  color: rgb(58 117 255);
  border-bottom-color: rgb(58 117 255);
}
</style>
