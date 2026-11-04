<script lang="ts" setup>
import type {
  AdvancedSearchField,
  BatchResult,
  ConditionGroup,
  DoctorOption,
  PatientFilterState,
  PatientSearchRow,
  PlanView,
} from '@crdp/api';

import { computed, onMounted, reactive, ref } from 'vue';
import { useRouter } from 'vue-router';

import {
  buildPatientSearchPayload,
  doctorOptionLabel,
  emptyPatientFilters,
  isKeywordValid,
  KEYWORD_MAX_LENGTH,
} from '@crdp/api';
import { sizes } from '@crdp/tokens';
import {
  Alert,
  Button,
  Card,
  DatePicker,
  Form,
  FormItem,
  Input,
  message,
  Modal,
  Select,
  Space,
  Table,
} from 'ant-design-vue';

import {
  fetchDoctorOptions,
  fetchPatientFilterOptions,
  fetchPatientSearchFields,
  searchPatients,
} from '#/api/core/patient';
import { batchEnroll, batchEnrollPreview, listPlans } from '#/api/core/plan';
import AdvancedSearch from '#/components/followup/advanced-search.vue';
import BatchResultPanel from '#/components/followup/batch-result-modal.vue';
import { usePermission } from '#/reference/use-permission';

import {
  enrollablePlanOptions,
  mergeSelection,
  previewSummaryText,
  takeStashedSelection,
  validateBatchEnrollForm,
} from './batch-logic';

defineOptions({ name: 'FollowupPatientBatchEnroll' });

/**
 * E13-T07 / A-PAT-016 批量入组（pages.md §8）：左侧筛选（普通筛选或高级检索）→
 * 中间结果列表可勾选 → 右侧选择目标随访计划与责任医生（A-PAT-017，可选）→
 * 预检（确认前显示将加入人数与将跳过人数）→ 确认入组 → 成功与跳过（已在组、
 * 已死亡、已移除）明细逐条可查（tasks.csv 验收）。患者候选与筛选复用检索接口
 * （E13-T04 / T06，后端自动追加病区条件）；批量入组权限 followup:plan:enroll
 * （按钮 plt_menu 221）；仅桌面 / 平板（pages.md §8，手机 MobileBlocker 由外壳承担）。
 */
const router = useRouter();
const { hasPermission } = usePermission();
const canEnroll = computed(() => hasPermission('followup:plan:enroll'));

const RangePicker = DatePicker.RangePicker;

// ---------------------------------------------------------------- 左：筛选（复用患者检索）

const filters = reactive<PatientFilterState>(emptyPatientFilters());
const advancedOpen = ref(false);
const fieldCatalog = ref<AdvancedSearchField[]>([]);
const appliedCondition = ref<ConditionGroup | null>(null);
const wardOptions = ref<Array<{ label: string; value: number }>>([]);
const statusOptions = [
  { label: '随访中', value: 'ACTIVE' },
  { label: '已暂停', value: 'PAUSED' },
  { label: '已死亡', value: 'DEAD' },
  { label: '已终止', value: 'TERMINATED' },
  { label: '失访', value: 'LOST' },
  { label: '已完成', value: 'FINISHED' },
];

// ---------------------------------------------------------------- 中：结果列表与勾选

const loading = ref(false);
const rows = ref<PatientSearchRow[]>([]);
const total = ref(0);
const pageNo = ref(1);
const pageSize = ref(sizes.tablePageSize);
const selectedIds = ref<string[]>([]);

const columns = [
  { dataIndex: 'name', title: '姓名' },
  { dataIndex: 'patientNo', title: '编号' },
  { dataIndex: 'wardName', title: '病区' },
  { dataIndex: 'doctorName', title: '责任医生' },
  { dataIndex: 'dischargeDate', title: '出院日期', width: 110 },
  { dataIndex: 'status', title: '状态', width: 96 },
];

const dataSource = computed(() =>
  rows.value.map((item) => ({
    ...item,
    key: item.id,
    doctorName: item.doctorName ?? '—',
    wardName: item.wardName ?? '—',
  })),
);

// ---------------------------------------------------------------- 右：计划与责任医生

const plans = ref<PlanView[]>([]);
const planId = ref<null | string>(null);
const doctorOptions = ref<Array<{ label: string; value: string }>>([]);
const doctorId = ref<string | undefined>(undefined);
const previewing = ref(false);
const submitting = ref(false);
const preview = ref<BatchResult | null>(null);
const resultOpen = ref(false);
const result = ref<BatchResult | null>(null);

const planOptions = computed(() => enrollablePlanOptions(plans.value));

const selectedCount = computed(() => selectedIds.value.length);

// ---------------------------------------------------------------- 加载

async function load() {
  loading.value = true;
  try {
    const page = await searchPatients(
      buildPatientSearchPayload(
        { ...filters },
        pageNo.value,
        pageSize.value,
        undefined,
        appliedCondition.value ?? undefined,
      ),
    );
    rows.value = page.items;
    total.value = page.total;
  } catch {
    message.error('患者列表加载失败，请稍后重试');
  } finally {
    loading.value = false;
  }
}

async function loadSideOptions() {
  try {
    plans.value = await listPlans();
  } catch {
    plans.value = [];
  }
  try {
    const fields = await fetchPatientSearchFields();
    fieldCatalog.value = fields.fields;
  } catch {
    fieldCatalog.value = [];
  }
  try {
    const options = await fetchPatientFilterOptions();
    wardOptions.value = options.wards.map((item) => ({
      label: item.name,
      value: item.id,
    }));
  } catch {
    wardOptions.value = [];
  }
  try {
    const options = await fetchDoctorOptions();
    doctorOptions.value = options.doctors.map((doctor: DoctorOption) => ({
      label: doctorOptionLabel(doctor),
      value: doctor.id,
    }));
  } catch {
    doctorOptions.value = [];
  }
}

onMounted(() => {
  const stashed = takeStashedSelection();
  if (stashed.length > 0) {
    selectedIds.value = stashed;
    message.info(`已带入患者列表勾选的 ${stashed.length} 名患者`);
  }
  void loadSideOptions();
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
  appliedCondition.value = null;
  pageNo.value = 1;
  void load();
}

function handleApplyAdvanced(group: ConditionGroup) {
  appliedCondition.value = group;
  pageNo.value = 1;
  void load();
}

function handleTableChange(nextPage: number, nextSize: number) {
  pageNo.value = nextPage;
  pageSize.value = nextSize;
  void load();
}

// ---------------------------------------------------------------- 预检与执行

async function handlePreview() {
  const error = validateBatchEnrollForm(planId.value, selectedCount.value);
  if (error) {
    message.warning(error);
    return;
  }
  const targetPlanId = planId.value;
  if (!targetPlanId) return;
  previewing.value = true;
  preview.value = null;
  try {
    preview.value = await batchEnrollPreview(targetPlanId, {
      doctorId: doctorId.value ?? undefined,
      patientIds: selectedIds.value,
    });
  } catch (error_) {
    const body = error_ as { message?: string };
    message.error(body?.message || '预检失败，请稍后重试');
  } finally {
    previewing.value = false;
  }
}

async function handleSubmit() {
  const error = validateBatchEnrollForm(planId.value, selectedCount.value);
  if (error) {
    message.warning(error);
    return;
  }
  const targetPlanId = planId.value;
  if (!targetPlanId) return;
  submitting.value = true;
  try {
    result.value = await batchEnroll(targetPlanId, {
      doctorId: doctorId.value ?? undefined,
      patientIds: selectedIds.value,
    });
    resultOpen.value = true;
    message.success(
      `批量入组完成：成功 ${result.value.succeeded}、跳过 ${result.value.skipped}`,
    );
    preview.value = null;
    void load();
  } catch (error_) {
    const body = error_ as { message?: string };
    message.error(body?.message || '批量入组失败，请稍后重试');
  } finally {
    submitting.value = false;
  }
}

function handleDone() {
  resultOpen.value = false;
  void router.push('/followup/patient');
}
</script>

<template>
  <div class="page" data-testid="batch-enroll-page">
    <Card :bordered="false" class="crdp-card">
      <div class="title-row">
        <h1 class="crdp-page-title">批量入组</h1>
        <Button
          data-testid="batch-enroll-back"
          @click="router.push('/followup/patient')"
        >
          返回患者列表
        </Button>
      </div>

      <div class="layout">
        <!-- 左：筛选（pages.md §8：普通筛选或高级检索） -->
        <Card
          :bordered="true"
          class="pane filter-pane"
          size="small"
          title="筛选"
        >
          <Form class="search-bar" layout="vertical" @submit.prevent>
            <FormItem label="姓名/编号">
              <Input
                v-model:value="filters.keyword"
                allow-clear
                :maxlength="KEYWORD_MAX_LENGTH"
                placeholder="姓名或病案号精确查找"
                @keyup.enter="handleSearch"
              />
            </FormItem>
            <FormItem label="病区">
              <Select
                v-model:value="filters.wardId"
                allow-clear
                :options="wardOptions"
                placeholder="全部"
              />
            </FormItem>
            <FormItem label="随访状态">
              <Select
                v-model:value="filters.status"
                allow-clear
                :options="statusOptions"
                placeholder="全部"
              />
            </FormItem>
            <FormItem label="出院日期">
              <RangePicker
                v-model:value="filters.dischargeRange"
                value-format="YYYY-MM-DD"
              />
            </FormItem>
            <Space direction="vertical" style="width: 100%">
              <Button block type="primary" @click="handleSearch">查询</Button>
              <Button block @click="handleReset">重置</Button>
              <Button block ghost type="primary" @click="advancedOpen = true">
                高级检索
              </Button>
            </Space>
          </Form>
        </Card>

        <!-- 中：结果列表可勾选（pages.md §8） -->
        <Card
          :bordered="true"
          class="pane result-pane"
          size="small"
          title="患者（勾选后入组）"
        >
          <Table
            :columns="columns"
            :data-source="dataSource"
            :loading="loading"
            :pagination="{
              current: pageNo,
              pageSize,
              showTotal: (count: number) => `共 ${count} 条`,
              total,
            }"
            :row-selection="{
              preserveSelectedRowKeys: true,
              selectedRowKeys: selectedIds,
              onChange: (keys: Array<string | number>) =>
                (selectedIds = mergeSelection(
                  selectedIds,
                  rows,
                  keys.map(String),
                )),
            }"
            size="small"
            @change="
              (pagination: { current?: number; pageSize?: number }) =>
                handleTableChange(
                  pagination.current ?? 1,
                  pagination.pageSize ?? pageSize,
                )
            "
          >
            <template #bodyCell="{ column, record }">
              <span v-if="column.dataIndex === 'doctorName'">
                {{ record.doctorName || '—' }}
              </span>
              <span v-else-if="column.dataIndex === 'dischargeDate'">
                {{ record.dischargeDate || '—' }}
              </span>
            </template>
          </Table>
        </Card>

        <!-- 右：目标计划与责任医生（pages.md §8） -->
        <Card
          :bordered="true"
          class="pane target-pane"
          size="small"
          title="入组设置"
        >
          <Form layout="vertical" @submit.prevent>
            <FormItem label="目标随访计划（已发布）">
              <Select
                v-model:value="planId"
                :options="planOptions"
                placeholder="选择要加入的随访计划"
                show-search
                option-filter-prop="label"
              />
            </FormItem>
            <FormItem label="责任医生（可选）">
              <Select
                v-model:value="doctorId"
                allow-clear
                :options="doctorOptions"
                placeholder="入组同时指定（只能选该病区的医生）"
                show-search
                option-filter-prop="label"
              />
            </FormItem>
          </Form>

          <Alert
            v-if="preview"
            data-testid="batch-enroll-preview"
            :message="previewSummaryText(preview)"
            show-icon
            type="info"
          >
            <template #description>
              确认后不可批量撤销；跳过原因在结果中逐条列明（已在组、已死亡、已移除等）。
            </template>
          </Alert>

          <Space direction="vertical" style="width: 100%">
            <Button
              block
              :disabled="!canEnroll"
              :loading="previewing"
              @click="handlePreview"
            >
              预检（将加入 / 将跳过）
            </Button>
            <Button
              block
              :disabled="!canEnroll"
              :loading="submitting"
              data-testid="batch-enroll-submit"
              type="primary"
              @click="handleSubmit"
            >
              确认入组（已选 {{ selectedCount }} 名）
            </Button>
          </Space>
          <p v-if="!canEnroll" class="hint">
            需要「计划入组」权限（followup:plan:enroll）
          </p>
        </Card>
      </div>

      <AdvancedSearch
        v-model:open="advancedOpen"
        :field-catalog="fieldCatalog"
        :value="appliedCondition ?? undefined"
        @apply="handleApplyAdvanced"
      />

      <Modal
        :footer="null"
        :open="resultOpen"
        title="批量入组 · 结果"
        :width="720"
        @cancel="handleDone"
      >
        <BatchResultPanel v-model:result="result" />
        <template #footer>
          <Button type="primary" @click="handleDone">完成</Button>
        </template>
      </Modal>
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

.layout {
  display: grid;
  grid-template-columns: 240px 1fr 280px;
  gap: 16px;
  align-items: start;
}

@media (max-width: 1280px) {
  .layout {
    grid-template-columns: 200px 1fr 240px;
  }
}

.pane {
  min-width: 0;
}

.search-bar :deep(.ant-form-item) {
  margin-bottom: 12px;
}

.hint {
  margin: 8px 0 0;
  font-size: 12px;
  color: #8c8c8c;
}
</style>
