<script lang="ts" setup>
import type { ReferencePatient } from '#/reference/types';

import { computed, ref } from 'vue';
import { useRouter } from 'vue-router';

import { StatusTag } from '@crdp/status-tag';
import { colors, sizes } from '@crdp/tokens';
import {
  Alert,
  Button,
  Card,
  Form,
  FormItem,
  Input,
  Modal,
  Progress,
  Segmented,
  Select,
  Space,
  Table,
} from 'ant-design-vue';

import MaskedField from '#/components/followup/masked-field.vue';
import { patientsOf } from '#/reference/fixtures';
import { useBreakpoint } from '#/reference/use-breakpoint';
import { usePermission } from '#/reference/use-permission';

defineOptions({ name: 'FollowupPatient' });

const router = useRouter();
// E13-T01：新建患者入口按权限显隐（followup:patient:create，后端仍校验）
const { hasPermission } = usePermission();
const canCreate = computed(() => hasPermission('followup:patient:create'));
const { isMobile, isTablet } = useBreakpoint();
const variant = ref<'a' | 'c'>('a');
const keyword = ref('');
const advancedOpen = ref(false);

const rows = computed(() => {
  const source = patientsOf(variant.value);
  const text = keyword.value.trim();
  if (!text) return source;
  return source.filter(
    (item) =>
      item.nameMasked.includes(text) ||
      item.namePlain.includes(text) ||
      item.patientNo.includes(text),
  );
});

const columns = computed(() => {
  const common = [
    { dataIndex: 'index', title: '序号', width: 64 },
    { dataIndex: 'name', title: '姓名' },
    {
      dataIndex: 'patientNo',
      title: variant.value === 'c' ? '随访编号' : '编号',
    },
  ];
  const aRest = [
    { dataIndex: 'legacyNo', title: '原系统编号' },
    { dataIndex: 'gender', title: '性别', width: 64 },
    { dataIndex: 'age', title: '年龄', width: 72, align: 'right' as const },
    { dataIndex: 'wardName', title: '病区' },
    { dataIndex: 'responsibleName', title: '责任医生' },
    { dataIndex: 'dischargeDate', title: '出院日期' },
    { dataIndex: 'next', title: '下次随访' },
    { dataIndex: 'status', title: '状态' },
    { dataIndex: 'baseline', title: '基线%', align: 'right' as const },
    { dataIndex: 'followup', title: '随访%', align: 'right' as const },
    { dataIndex: 'action', fixed: 'right' as const, title: '操作', width: 120 },
  ];
  const cRest = [
    { dataIndex: 'gender', title: '性别' },
    { dataIndex: 'age', title: '年龄', align: 'right' as const },
    { dataIndex: 'device', title: '当前器械' },
    { dataIndex: 'duration', title: '植入时长' },
    { dataIndex: 'next', title: '下次计划' },
    { dataIndex: 'eri', title: 'ERI' },
    { dataIndex: 'wardName', title: '科室病区' },
    { dataIndex: 'status', title: '状态' },
    { dataIndex: 'action', fixed: 'right' as const, title: '操作', width: 120 },
  ];
  const rest = variant.value === 'c' ? cRest : aRest;
  if (isTablet.value) {
    return [
      ...common,
      { dataIndex: 'wardName', title: '病区' },
      { dataIndex: 'next', title: '下次随访' },
      { dataIndex: 'status', title: '状态' },
      { dataIndex: 'action', title: '操作', width: 90 },
    ];
  }
  return [...common, ...rest];
});

const dataSource = computed(() =>
  rows.value.map((item, index) => ({
    ...item,
    index: index + 1,
    key: item.id,
  })),
);

function open(row: ReferencePatient) {
  void router.push(`/followup/patient/${row.id}`);
}
</script>

<template>
  <div class="page" data-testid="patient-list">
    <Alert
      banner
      message="虚构样板数据，不是真实患者。示例：测试患者01，电话 138****0001。"
      type="info"
    />
    <Card :bordered="false" class="crdp-card">
      <div class="title-row">
        <h1 class="crdp-page-title">患者管理</h1>
        <Space>
          <Segmented
            v-model:value="variant"
            data-testid="variant-switch"
            :options="[
              { label: '医院 A 样板', value: 'a' },
              { label: '医院 C 样板', value: 'c' },
            ]"
          />
          <Button
            v-if="canCreate"
            data-testid="create-patient"
            type="primary"
            @click="router.push('/followup/patient/new')"
          >
            + 新建患者
          </Button>
        </Space>
      </div>
      <Form layout="inline">
        <FormItem label="姓名/编号">
          <Input
            v-model:value="keyword"
            allow-clear
            placeholder="测试患者 / DEMO"
            :style="{ width: `${sizes.filterInputWidth.max}px` }"
          />
        </FormItem>
        <FormItem label="病区">
          <Select
            :options="[{ label: '全部', value: 'all' }]"
            style="width: 160px"
            value="all"
          />
        </FormItem>
        <FormItem label="随访状态">
          <Select
            :options="[{ label: '全部', value: 'all' }]"
            style="width: 140px"
            value="all"
          />
        </FormItem>
        <FormItem>
          <Space>
            <Button type="primary">查询</Button>
            <Button @click="keyword = ''">重置</Button>
            <Button ghost type="primary" @click="advancedOpen = true">
              高级检索
            </Button>
          </Space>
        </FormItem>
      </Form>

      <div v-if="isMobile" class="cards">
        <Card
          v-for="row in rows"
          :key="row.id"
          :bordered="false"
          class="person"
          @click="open(row)"
        >
          <MaskedField :patient-id="row.id" :value="row.nameMasked" />
          <StatusTag :status="row.status" variant="dot" />
          <div>
            下次随访 {{ row.nextVisit?.label }} {{ row.nextVisit?.planDate }}
          </div>
          <div>病区 {{ row.wardName }}</div>
        </Card>
      </div>
      <Table
        v-else
        :columns="columns"
        :data-source="dataSource"
        :pagination="{
          pageSize: sizes.tablePageSize,
          showQuickJumper: true,
          showSizeChanger: true,
          showTotal: (total: number) => `共 ${total} 条`,
        }"
        size="middle"
      >
        <template #bodyCell="{ column, record }">
          <MaskedField
            v-if="column.dataIndex === 'name'"
            :patient-id="record.id"
            :value="record.nameMasked"
          />
          <StatusTag
            v-else-if="column.dataIndex === 'status'"
            :status="record.status"
            variant="dot"
          />
          <span v-else-if="column.dataIndex === 'next'">
            {{ record.nextVisit?.label }} {{ record.nextVisit?.planDate }}
          </span>
          <span v-else-if="column.dataIndex === 'device'">
            {{ record.device?.typeLabel ?? '—' }}
          </span>
          <span v-else-if="column.dataIndex === 'duration'">
            {{ record.device?.implantDuration ?? '—' }}
          </span>
          <span v-else-if="column.dataIndex === 'eri'">
            {{ record.device?.eri ?? '—' }}
          </span>
          <Progress
            v-else-if="column.dataIndex === 'baseline'"
            :percent="record.baselineCompleteness"
            size="small"
          />
          <Progress
            v-else-if="column.dataIndex === 'followup'"
            :percent="record.followupCompleteness"
            size="small"
          />
          <Button
            v-else-if="column.dataIndex === 'action'"
            :data-testid="`open-${record.id}`"
            size="small"
            type="link"
            @click="open(record)"
          >
            详情
          </Button>
        </template>
        <template v-if="isTablet" #expandedRowRender="{ record }">
          <div>责任医生 {{ record.responsibleName }}</div>
          <div>原系统编号 {{ record.legacyNo || '—' }}</div>
        </template>
      </Table>
      <p v-if="rows.length === 0" :style="{ color: colors.textSecondary }">
        没有符合条件的患者 · 清除筛选
      </p>
    </Card>

    <Modal v-model:open="advancedOpen" title="高级检索" :width="900">
      <p>逻辑表达式预览</p>
      <p>
        （病区 = 示例一病区 并且 EF 射血分数 &lt; 40）或者 再住院类型 不为空
      </p>
      <p>样板只展示条件行，不向后端提交 SQL。</p>
    </Modal>
  </div>
</template>

<style scoped>
.page {
  padding: 16px;
}

.title-row {
  display: flex;
  gap: 16px;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
}

.person {
  margin-bottom: 8px;
  cursor: pointer;
}
</style>
