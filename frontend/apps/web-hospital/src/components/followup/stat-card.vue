<!--
  E19-T02 / A-DASH-001、C-DASH-001：首页统计卡 StatCard（components.md §13）：
  a-statistic 24–28/600 + 右上线性图标 + 「较昨日 +n / −n」12px。
  点卡片钻取（drill 事件）由父级换路由（packages/api kpiMetricRoute——条件由后端下发，
  前端不自行拼业务条件）；value 为 null 显示「—」（数据源未落地，父级一般已按
  available 过滤隐藏）；loading 只锁数字区。
-->
<script lang="ts" setup>
import { IconifyIcon } from '@vben/icons';

import { Statistic } from 'ant-design-vue';

defineOptions({ name: 'StatCard' });

const props = withDefaults(
  defineProps<{
    /** 「较昨日 +n / −n」变化值（数据源随后续任务接入；不传不显示）。 */
    delta?: null | number;
    /** 右上线性图标（iconify 名，如 ant-design:team-outlined）。 */
    icon?: null | string;
    /** 数字加载中。 */
    loading?: boolean;
    /** 指标编码（a-statistic title 与 data-testid 用）。 */
    metricCode: string;
    /** 指标名（如「在随访」）。 */
    title: string;
    /** 指标值；null 显示「—」。 */
    value?: null | number;
  }>(),
  { delta: null, icon: null, loading: false, value: null },
);

const emit = defineEmits<{ drill: [metricCode: string] }>();

function handleClick() {
  emit('drill', props.metricCode);
}
</script>

<template>
  <Card
    class="stat-card"
    :body-style="{ padding: '16px' }"
    :hoverable="true"
    :data-testid="`stat-card-${metricCode}`"
    @click="handleClick"
  >
    <div class="stat-card-head">
      <span class="stat-card-title">{{ title }}</span>
      <IconifyIcon v-if="icon" class="stat-card-icon" :icon="icon" />
    </div>
    <Spin :delay="200" :spinning="loading">
      <Statistic
        :data-testid="`stat-value-${metricCode}`"
        :precision="0"
        :value="value === null ? '—' : value"
        :value-style="{ fontSize: '24px', fontWeight: 600, lineHeight: '32px' }"
      />
      <div v-if="delta !== null" class="stat-card-delta">
        较昨日
        <span :class="delta >= 0 ? 'stat-card-up' : 'stat-card-down'">
          {{ delta >= 0 ? `+${delta}` : delta }}
        </span>
      </div>
    </Spin>
  </Card>
</template>

<style scoped>
.stat-card {
  cursor: pointer;
  border-radius: 8px;
}

.stat-card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8px;
}

.stat-card-title {
  font-size: 14px;
  color: rgb(0 0 0 / 65%);
}

.stat-card-icon {
  font-size: 20px;
  color: #3a75ff;
}

.stat-card-delta {
  margin-top: 4px;
  font-size: 12px;
  color: rgb(0 0 0 / 45%);
}

.stat-card-up {
  color: #1dc29e;
}

.stat-card-down {
  color: #ff4d4f;
}
</style>
