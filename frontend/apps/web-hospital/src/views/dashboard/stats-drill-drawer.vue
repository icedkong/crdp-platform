<script setup lang="ts">
import type { StatsDrillPage, StatsDrillQuery } from '@crdp/api';

import { computed, onBeforeUnmount, ref, watch } from 'vue';
import { useRouter } from 'vue-router';

import { useUserStore } from '@vben/stores';

import { sizes } from '@crdp/tokens';
import {
  Alert,
  Button,
  Drawer,
  Empty,
  Space,
  Spin,
  Table,
} from 'ant-design-vue';

import { fetchStatsDrill } from '#/api/core/stats-drill';
import { usePermission } from '#/reference/use-permission';

const props = defineProps<{ query: null | StatsDrillQuery }>();
const emit = defineEmits<{ (e: 'close'): void }>();
const router = useRouter();
const { hasPermission } = usePermission();
const canViewRecords = computed(() => hasPermission('followup:task:view'));
const userStore = useUserStore();
const data = ref<null | StatsDrillPage>(null);
const loading = ref(false);
const error = ref('');
const pageNo = ref(1);
let generation = 0;
const columns = [
  { title: '患者', key: 'patient' },
  { title: '对应年度、节点次数与记录', key: 'contexts' },
];
async function load() {
  const current = ++generation;
  data.value = null;
  error.value = '';
  if (!props.query) return;
  loading.value = true;
  try {
    const result = await fetchStatsDrill({
      ...props.query,
      pageNo: pageNo.value,
      pageSize: 20,
    });
    if (current === generation) data.value = result;
  } catch (error_) {
    if (current === generation)
      error.value =
        error_ instanceof Error ? error_.message : '统计明细加载失败';
  } finally {
    if (current === generation) loading.value = false;
  }
}
function changePage(pagination: { current?: number }) {
  pageNo.value = pagination.current ?? 1;
  void load();
}
watch(
  () => props.query,
  () => {
    pageNo.value = 1;
    void load();
  },
  { immediate: true, deep: true },
);
watch(
  () =>
    (userStore.userInfo as null | { permissionVersion?: number })
      ?.permissionVersion,
  () => {
    if (props.query) void load();
  },
);
onBeforeUnmount(() => {
  ++generation;
  data.value = null;
});
</script>

<template>
  <Drawer
    :open="query !== null"
    title="统计明细"
    placement="right"
    width="80%"
    @close="emit('close')"
  >
    <Spin :spinning="loading">
      <Space
        direction="vertical"
        :size="sizes.gutter"
        :style="{ width: '100%' }"
      >
        <Alert v-if="error" type="error" :message="error" show-icon />
        <Button v-if="error" @click="load">重新加载</Button>
        <template v-if="data">
          <Alert
            v-if="data.changed"
            type="info"
            message="统计结果或可见范围已变化，以下为当前可查看的明细。"
            show-icon
          />
          <div>
            共 {{ data.total }} {{ data.unit }} ·
            {{ data.timeBasis === 'DAILY' ? '每日汇总' : '实时查询' }} ·
            {{ data.refreshedAt ?? '刷新时间未知（旧汇总）' }}
          </div>
          <Empty
            v-if="data.items.length === 0"
            description="当前范围内暂无明细"
          />
          <Table
            v-else
            :columns="columns"
            :data-source="data.items"
            row-key="key"
            :pagination="{
              current: pageNo,
              pageSize: 20,
              total: data.total,
              showSizeChanger: false,
            }"
            @change="changePage"
          >
            <template #bodyCell="{ column, record }">
              <template v-if="column.key === 'patient'">
                <Button
                  type="link"
                  @click="router.push(`/followup/patient/${record.patientId}`)"
                >
                  {{ record.name ?? '查看患者' }}
                </Button>
              </template>
              <template v-else-if="column.key === 'contexts'">
                <Space direction="vertical">
                  <div v-for="(context, index) in record.contexts" :key="index">
                    <span
                      v-if="context.planName"
                      v-text="`${context.planName} · `"
                    ></span>
                    <span
                      v-if="context.nodeName"
                      v-text="`${context.nodeName} · `"
                    ></span>
                    <span
                      v-if="context.year"
                      v-text="`第 ${context.year} 年 · `"
                    ></span>
                    <span
                      v-else-if="context.occurrence"
                      v-text="`第 ${context.occurrence} 次 · `"
                    ></span>
                    <span
                      v-if="context.planDate"
                      v-text="`${context.planDate} · `"
                    ></span>
                    <span v-if="context.virtual">尚未生成任务</span>
                    <Button
                      v-if="context.recordId && canViewRecords"
                      type="link"
                      @click="
                        router.push(`/followup/record/${context.recordId}`)
                      "
                    >
                      查看记录
                    </Button>
                  </div>
                  <span v-if="record.contexts.length === 0">基线资料</span>
                </Space>
              </template>
            </template>
          </Table>
        </template>
      </Space>
    </Spin>
  </Drawer>
</template>
