<script lang="ts" setup>
import type { PatientFilterState, PatientSearchRow } from '@crdp/api';

import { computed, onMounted, reactive, ref } from 'vue';
import { useRouter } from 'vue-router';

import {
  buildPatientSearchPayload,
  emptyPatientFilters,
  hasActiveFilters,
  isKeywordValid,
  KEYWORD_MAX_LENGTH,
  PATIENT_STATUS_TEXT,
  patientEmptyText,
  patientStatusOptions,
} from '@crdp/api';
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
  Progress,
  Select,
  Space,
  Table,
} from 'ant-design-vue';

import { fetchPatientFilterOptions, searchPatients } from '#/api/core/patient';
import { useBreakpoint } from '#/reference/use-breakpoint';
import { usePermission } from '#/reference/use-permission';

defineOptions({ name: 'FollowupPatient' });

/**
 * E13-T04 / A-PAT-001、A-PAT-002、C-PAT-009 · 患者列表（pages.md §3）：
 * 病区隔离由后端数据范围承担（行级，越范围不可见）；「姓名/编号」为精确检索
 * （盲索引相等，非模糊）。姓名与编号列只显示后端脱敏值，列表不提供逐行明文
 * （AGENTS.md §5.3）；检索词只放组件状态，不写 URL（components.md §11.1）。
 * 高级检索与个人筛选随 E13-T06；C 的器械列随 E21 植入数据开放。
 */
const router = useRouter();
const { hasPermission } = usePermission();
const canCreate = computed(() => hasPermission('followup:patient:create'));
const { isMobile, isTablet } = useBreakpoint();

const RangePicker = DatePicker.RangePicker;

// ---------------------------------------------------------------- 筛选与分页状态

const filters = reactive<PatientFilterState>(emptyPatientFilters());
/** 最近一次查询实际生效的筛选（空态文案按它分流，不跟输入框实时联动）。 */
const appliedFilters = ref<PatientFilterState>(emptyPatientFilters());
const pageNo = ref(1);
const pageSize = ref(sizes.tablePageSize);

const loading = ref(false);
const rows = ref<PatientSearchRow[]>([]);
const total = ref(0);
const wardOptions = ref<Array<{ label: string; value: number }>>([]);
const statusOptions = patientStatusOptions();

// ---------------------------------------------------------------- 加载

async function load() {
  loading.value = true;
  try {
    const page = await searchPatients(
      buildPatientSearchPayload({ ...filters }, pageNo.value, pageSize.value),
    );
    rows.value = page.items;
    total.value = page.total;
    appliedFilters.value = { ...filters };
  } catch {
    message.error('患者列表加载失败，请稍后重试');
  } finally {
    loading.value = false;
  }
}

async function loadOptions() {
  try {
    const options = await fetchPatientFilterOptions();
    wardOptions.value = options.wards.map((item) => ({
      label: item.name,
      value: item.id,
    }));
  } catch {
    // 候选加载失败不阻断列表；病区下拉显示为空，仍可按其余条件检索
    wardOptions.value = [];
  }
}

onMounted(() => {
  void loadOptions();
  void load();
});

function handleSearch() {
  const keyword = filters.keyword.trim();
  if (keyword.length > 0 && !isKeywordValid(keyword)) {
    message.warning(`检索词最多 ${KEYWORD_MAX_LENGTH} 个字符`);
    return;
  }
  pageNo.value = 1;
  void load();
}

function handleReset() {
  Object.assign(filters, emptyPatientFilters());
  pageNo.value = 1;
  void load();
}

function handleTableChange(nextPage: number, nextSize: number) {
  pageNo.value = nextPage;
  pageSize.value = nextSize;
  void load();
}

function openDetail(row: PatientSearchRow) {
  void router.push(`/followup/patient/${row.id}`);
}

// ---------------------------------------------------------------- 列定义（pages.md §3）

/** 列由配置决定：每列声明所需功能开关；C 的器械类列（implant / eri_cycle）随 E21 植入数据开放，不在此硬编码。 */
const desktopColumns = computed(() => [
  { dataIndex: 'index', title: '序号', width: 56 },
  { dataIndex: 'name', title: '姓名' },
  { dataIndex: 'patientNo', title: '编号' },
  { dataIndex: 'legacyNo', title: '原系统编号' },
  { dataIndex: 'gender', title: '性别', width: 64 },
  { dataIndex: 'age', title: '年龄', width: 72, align: 'right' as const },
  { dataIndex: 'wardName', title: '病区' },
  { dataIndex: 'doctorName', title: '责任医生' },
  { dataIndex: 'dischargeDate', title: '出院日期', width: 110 },
  { dataIndex: 'nextVisitDate', title: '下次随访', width: 110 },
  { dataIndex: 'status', title: '状态', width: 96 },
  {
    dataIndex: 'baselineCompleteness',
    title: '基线%',
    width: 96,
    align: 'right' as const,
  },
  {
    dataIndex: 'followupCompleteness',
    title: '随访%',
    width: 96,
    align: 'right' as const,
  },
  { dataIndex: 'action', fixed: 'right' as const, title: '操作', width: 90 },
]);

/** 平板保留主要列，其余点行展开（pages.md §3 响应式）。 */
const tabletColumns = computed(() => [
  { dataIndex: 'index', title: '序号', width: 56 },
  { dataIndex: 'name', title: '姓名' },
  { dataIndex: 'patientNo', title: '编号' },
  { dataIndex: 'wardName', title: '病区' },
  { dataIndex: 'nextVisitDate', title: '下次随访', width: 110 },
  { dataIndex: 'status', title: '状态', width: 96 },
  { dataIndex: 'action', fixed: 'right' as const, title: '操作', width: 90 },
]);

const columns = computed(() =>
  isTablet.value ? tabletColumns.value : desktopColumns.value,
);

const dataSource = computed(() =>
  rows.value.map((item, index) => ({
    ...item,
    index: (pageNo.value - 1) * pageSize.value + index + 1,
    key: item.id,
  })),
);

// ---------------------------------------------------------------- 空态（pages.md §3）

const emptyInfo = computed(() =>
  patientEmptyText(hasActiveFilters(appliedFilters.value), canCreate.value),
);

function handleEmptyAction() {
  if (hasActiveFilters(appliedFilters.value)) {
    handleReset();
    return;
  }
  if (canCreate.value) {
    void router.push('/followup/patient/new');
  }
}
</script>

<template>
  <div class="page" data-testid="patient-list">
    <Card :bordered="false" class="crdp-card">
      <div class="title-row">
        <h1 class="crdp-page-title">患者管理</h1>
        <Button
          v-if="canCreate"
          data-testid="create-patient"
          type="primary"
          @click="router.push('/followup/patient/new')"
        >
          + 新建患者
        </Button>
      </div>

      <Form class="search-bar" layout="inline" @submit.prevent>
        <FormItem label="姓名/编号">
          <Input
            v-model:value="filters.keyword"
            allow-clear
            data-testid="patient-keyword"
            :maxlength="KEYWORD_MAX_LENGTH"
            placeholder="姓名或病案号精确查找"
            :style="{ width: `${sizes.filterInputWidth.max}px` }"
            @keyup.enter="handleSearch"
          />
        </FormItem>
        <FormItem label="病区">
          <Select
            v-model:value="filters.wardId"
            allow-clear
            data-testid="patient-ward"
            :options="wardOptions"
            placeholder="全部"
            style="width: 160px"
          />
        </FormItem>
        <FormItem label="随访状态">
          <Select
            v-model:value="filters.status"
            allow-clear
            data-testid="patient-status"
            :options="statusOptions"
            placeholder="全部"
            style="width: 140px"
          />
        </FormItem>
        <FormItem label="出院日期">
          <RangePicker
            v-model:value="filters.dischargeRange"
            value-format="YYYY-MM-DD"
          />
        </FormItem>
        <FormItem label="下次随访">
          <RangePicker
            v-model:value="filters.nextVisitRange"
            value-format="YYYY-MM-DD"
          />
        </FormItem>
        <FormItem>
          <Space>
            <Button
              data-testid="patient-search"
              type="primary"
              @click="handleSearch"
            >
              查询
            </Button>
            <Button data-testid="patient-reset" @click="handleReset">
              重置
            </Button>
          </Space>
        </FormItem>
      </Form>

      <!-- 手机：卡片列表（姓名、状态、下次随访、病区；pages.md §3） -->
      <div v-if="isMobile" class="cards">
        <Card
          v-for="row in rows"
          :key="row.id"
          :bordered="false"
          class="person"
          @click="openDetail(row)"
        >
          <div class="person-line">
            <span class="person-name">{{ row.name }}</span>
            <StatusTag
              :status="PATIENT_STATUS_TEXT[row.status] ?? row.status"
              variant="dot"
            />
          </div>
          <div>编号 {{ row.patientNo }}</div>
          <div>下次随访 {{ row.nextVisitDate || '—' }}</div>
          <div>病区 {{ row.wardName || '—' }}</div>
        </Card>
        <Empty
          v-if="!loading && rows.length === 0"
          :description="emptyInfo.text"
        >
          <Button
            v-if="emptyInfo.actionText"
            type="primary"
            @click="handleEmptyAction"
          >
            {{ emptyInfo.actionText }}
          </Button>
        </Empty>
      </div>

      <Table
        v-else
        :columns="columns"
        :data-source="dataSource"
        :loading="loading"
        :pagination="{
          current: pageNo,
          pageSize,
          showQuickJumper: true,
          showSizeChanger: true,
          showTotal: (count: number) => `共 ${count} 条`,
          total,
        }"
        size="middle"
        @change="
          (pagination: { current?: number; pageSize?: number }) =>
            handleTableChange(
              pagination.current ?? 1,
              pagination.pageSize ?? pageSize,
            )
        "
      >
        <template #bodyCell="{ column, record }">
          <span v-if="column.dataIndex === 'name'" data-testid="patient-name">
            {{ record.name }}
          </span>
          <StatusTag
            v-else-if="column.dataIndex === 'status'"
            :status="PATIENT_STATUS_TEXT[record.status] ?? record.status"
            variant="dot"
          />
          <span v-else-if="column.dataIndex === 'gender'">
            {{
              record.gender === 'M' ? '男' : record.gender === 'F' ? '女' : '—'
            }}
          </span>
          <span v-else-if="column.dataIndex === 'nextVisitDate'">
            {{ record.nextVisitDate || '—' }}
          </span>
          <span v-else-if="column.dataIndex === 'dischargeDate'">
            {{ record.dischargeDate || '—' }}
          </span>
          <span v-else-if="column.dataIndex === 'doctorName'">
            {{ record.doctorName || '—' }}
          </span>
          <span v-else-if="column.dataIndex === 'legacyNo'">
            {{ record.legacyNo || '—' }}
          </span>
          <Progress
            v-else-if="
              column.dataIndex === 'baselineCompleteness' &&
              record.baselineCompleteness != null
            "
            :percent="record.baselineCompleteness"
            size="small"
          />
          <span v-else-if="column.dataIndex === 'baselineCompleteness'">
            —
          </span>
          <span v-else-if="column.dataIndex === 'followupCompleteness'">
            {{
              record.followupCompleteness == null
                ? '—'
                : `${record.followupCompleteness}%`
            }}
          </span>
          <Button
            v-else-if="column.dataIndex === 'action'"
            :data-testid="`open-${record.id}`"
            size="small"
            type="link"
            @click="openDetail(record)"
          >
            详情
          </Button>
        </template>
        <template v-if="isTablet" #expandedRowRender="{ record }">
          <div>
            性别
            {{
              record.gender === 'M' ? '男' : record.gender === 'F' ? '女' : '—'
            }}
          </div>
          <div>年龄 {{ record.age ?? '—' }}</div>
          <div>责任医生 {{ record.doctorName || '—' }}</div>
          <div>出院日期 {{ record.dischargeDate || '—' }}</div>
          <div>原系统编号 {{ record.legacyNo || '—' }}</div>
        </template>
        <template #emptyText>
          <Empty :description="emptyInfo.text">
            <Button
              v-if="emptyInfo.actionText"
              type="primary"
              @click="handleEmptyAction"
            >
              {{ emptyInfo.actionText }}
            </Button>
          </Empty>
        </template>
      </Table>
    </Card>
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

.search-bar {
  row-gap: 8px;
  margin-bottom: 8px;
}

.person {
  margin-bottom: 8px;
  cursor: pointer;
}

.person-line {
  display: flex;
  gap: 8px;
  align-items: center;
  justify-content: space-between;
}

.person-name {
  font-weight: 600;
}
</style>
