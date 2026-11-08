<script lang="ts" setup>
import type {
  AdvancedSearchField,
  CompletenessDrill,
  ConditionGroup,
  PatientFilterState,
  PatientSearchRow,
  SavedFilter,
} from '@crdp/api';

import { computed, onMounted, reactive, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';

import {
  buildPatientSearchPayload,
  completenessDrillOfQuery,
  conditionRowCount,
  emptyPatientFilters,
  hasActiveFilters,
  isKeywordValid,
  KEYWORD_MAX_LENGTH,
  PATIENT_STATUS_TEXT,
  patientEmptyText,
  patientStatusOfQuery,
  patientStatusOptions,
} from '@crdp/api';
import { StatusTag } from '@crdp/status-tag';
import { sizes } from '@crdp/tokens';
import {
  Button,
  Card,
  DatePicker,
  Dropdown,
  Empty,
  Form,
  FormItem,
  Input,
  Menu,
  MenuItem,
  message,
  Popconfirm,
  Progress,
  Select,
  Space,
  Table,
  Tag,
} from 'ant-design-vue';

import {
  createSavedFilter,
  deleteSavedFilter,
  fetchPatientFilterOptions,
  fetchPatientSearchFields,
  fetchSavedFilters,
  searchPatients,
  updateSavedFilter,
} from '#/api/core/patient';
import AdvancedSearch from '#/components/followup/advanced-search.vue';
import BatchAssignDoctorModal from '#/components/followup/batch-assign-doctor-modal.vue';
import DeletePatientModal from '#/components/followup/delete-patient-modal.vue';
import PatientExportModal from '#/components/followup/patient-export-modal.vue';
import TransferWardModal from '#/components/followup/transfer-ward-modal.vue';
import { useBreakpoint } from '#/reference/use-breakpoint';
import { usePermission } from '#/reference/use-permission';

import {
  applySavedFilter,
  savedFilterPayloadIsEmpty,
  savedFilterPayloadOf,
} from './advanced-logic';
import { stashBatchSelection } from './batch-logic';

defineOptions({ name: 'FollowupPatient' });

/**
 * E13-T04 / A-PAT-001、A-PAT-002、C-PAT-009 · 患者列表（pages.md §3）：
 * 病区隔离由后端数据范围承担（行级，越范围不可见）；「姓名/编号」为精确检索
 * （盲索引相等，非模糊）。姓名与编号列只显示后端脱敏值，列表不提供逐行明文
 * （AGENTS.md §5.3）；检索词只放组件状态，不写 URL（components.md §11.1）。
 *
 * E13-T06 / A-PAT-014、A-PAT-015、A-NFR-009、C-PAT-013 · 高级检索与「我的筛选」：
 * 条件树为结构化 JSON，字段只能选 search-fields 目录（字段字典生成）中的字段，
 * 后端编译为参数化查询并自动追加病区条件（不允许原始 SQL）；个人筛选仅本人可见。
 * C 的器械列随 E21 植入数据开放；手机高级检索抽屉随 E24-T03。
 *
 * E13-T09 / A-PAT-009、A-PAT-012 · 导出与删除：顶部「导出」提交导出作业
 * （租户管理员免审批、普通用户待审批，文件含水印与水印码、姓名/病案号脱敏）；
 * 行操作「⋮ 更多 → 删除」软删除（原因必填 + 二次认证，数据保留可恢复）。
 */
const router = useRouter();
// E19-T02 指标卡钻取：首页点状态指标带 status 进患者列表（未知值不筛选）
const route = useRoute();
const { hasPermission } = usePermission();
const canCreate = computed(() => hasPermission('followup:patient:create'));
const canTransfer = computed(() => hasPermission('followup:patient:transfer'));
// E13-T07 / A-PAT-016、A-PAT-017：批量入组（followup:plan:enroll，按钮 221）与
// 批量指定责任医生（followup:task:assign，OQ-18，按钮 224）；手机不提供批量（pages.md §3）
const canBatchEnroll = computed(() => hasPermission('followup:plan:enroll'));
const canBatchAssign = computed(() => hasPermission('followup:task:assign'));
// E13-T09 / A-PAT-009、A-PAT-012：导出（敏感权限，走审批作业）与删除患者（软删除，敏感）
const canExport = computed(() => hasPermission('followup:patient:export'));
const canDelete = computed(() => hasPermission('followup:patient:delete'));
const canApproveExport = computed(() =>
  hasPermission('followup:patient:export:approve'),
);
// E18-T06 / A-PAT-008、A-PAT-018：历史数据「导入」进导入向导（隐藏路由 /followup/patient/import，
// 权限 followup:patient:import；功能开关 wide_import 关闭时后端 40301，pages.md §3 [导入] 按钮）
const canImport = computed(() => hasPermission('followup:patient:import'));
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

// E19-T05 / A-DASH-010、A-DASH-011 · 完整度缺失字段钻取：首页完整度卡片最常缺失字段
// 下钻进入（路由 query 带 missingField / missingKind + 卡片筛选），列表按后端与卡片
// 同一计算的 ID 集过滤（缺失人数 = total）；关闭标签即取消钻取恢复普通检索。
const completenessDrill = ref<null | {
  drill: CompletenessDrill;
  label: string;
}>(null);

function clearCompletenessDrill() {
  completenessDrill.value = null;
  pageNo.value = 1;
  void load();
}

// ---------------------------------------------------------------- 高级检索与我的筛选（E13-T06）

const advancedOpen = ref(false);
const fieldCatalog = ref<AdvancedSearchField[]>([]);
/** 最近一次查询实际生效的条件树（弹窗续编用 appliedCondition，不跟草稿实时联动）。 */
const appliedCondition = ref<ConditionGroup | null>(null);
const savedFilters = ref<SavedFilter[]>([]);
const renamingId = ref('');
const renamingName = ref('');

// E13-T08 / A-WARD-005：行操作「⋮ 更多 → 转病区」（pages.md §3；暂停随 E14-T06）
const transferOpen = ref(false);
const transferTarget = ref<null | PatientSearchRow>(null);

// E13-T09 / A-PAT-009：列表「导出」走导出作业与审批（PLT-AUD-007，pages.md §3）
const exportOpen = ref(false);

// E13-T07 / A-PAT-016、A-PAT-017：勾选 + 批量操作条（pages.md §3「已选 N 项」）。
// 换页 / 查询保留勾选（preserveSelectedRowKeys），手机不提供批量（pages.md §3）。
const selectedIds = ref<string[]>([]);
const assignOpen = ref(false);

// E13-T09 / A-PAT-012：行操作「⋮ 更多 → 删除」（pages.md §3：红色 + 确认 + 原因，软删除）
const deleteOpen = ref(false);
const deleteTarget = ref<null | PatientSearchRow>(null);

function openTransfer(row: PatientSearchRow) {
  transferTarget.value = row;
  transferOpen.value = true;
}

function openDelete(row: PatientSearchRow) {
  deleteTarget.value = row;
  deleteOpen.value = true;
}

/** 删除成功：行已不可见（软删除），刷新列表。 */
function handleDeleted(patientId: string) {
  if (deleteTarget.value?.id === patientId) {
    deleteTarget.value = null;
  }
  void load();
}

/** 转病区成功：刷新列表——原病区医生立即失去访问权，行随之消失或病区列变化。 */
function handleTransferred() {
  void load();
}

/** E13-T07 / A-PAT-016：勾选患者跳批量入组页（三栏：筛选 / 勾选 / 计划与责任医生）。 */
function openBatchEnroll() {
  if (selectedIds.value.length === 0) return;
  stashBatchSelection(selectedIds.value);
  void router.push('/followup/patient/batch-enroll');
}

/** E13-T07 / A-PAT-017：批量指定责任医生完成后刷新列表（责任医生列变化）。 */
function handleBatchAssigned() {
  void load();
}

const appliedConditionCount = computed(() =>
  conditionRowCount(
    appliedCondition.value ?? { logic: 'and', items: [] },
    fieldCatalog.value,
  ),
);

const hasAdvancedActive = computed(() => appliedConditionCount.value > 0);

function handleApplyAdvanced(group: ConditionGroup) {
  appliedCondition.value = group;
  pageNo.value = 1;
  void load();
}

function clearAdvanced() {
  appliedCondition.value = null;
  pageNo.value = 1;
  void load();
}

async function handleSaveAs(name: string, group: ConditionGroup) {
  const payload = savedFilterPayloadOf({ ...filters }, group);
  if (savedFilterPayloadIsEmpty(payload)) {
    message.warning('筛选条件为空，无法保存');
    return;
  }
  try {
    await createSavedFilter({ name, payload });
    message.success(`已保存个人筛选「${name}」`);
    await loadSavedFilters();
  } catch {
    message.error('保存筛选失败，请稍后重试');
  }
}

async function loadSavedFilters() {
  try {
    const result = await fetchSavedFilters();
    savedFilters.value = result.items;
  } catch {
    savedFilters.value = [];
  }
}

function handleApplySavedFilter(item: SavedFilter) {
  const applied = applySavedFilter(item.payload);
  Object.assign(filters, applied.state);
  appliedCondition.value = applied.condition;
  pageNo.value = 1;
  void load();
}

/** 用当前检索条件覆盖该筛选（A-PAT-015「修改」；检索词不进保存的筛选）。 */
async function handleUpdateSavedFilter(item: SavedFilter) {
  const payload = savedFilterPayloadOf({ ...filters }, appliedCondition.value);
  if (savedFilterPayloadIsEmpty(payload)) {
    message.warning('当前没有可保存的筛选条件');
    return;
  }
  try {
    await updateSavedFilter(item.id, { payload });
    message.success(`已更新「${item.name}」`);
    await loadSavedFilters();
  } catch {
    message.error('更新筛选失败，请稍后重试');
  }
}

function startRename(item: SavedFilter) {
  renamingId.value = item.id;
  renamingName.value = item.name;
}

async function confirmRename(item: SavedFilter) {
  const name = renamingName.value.trim();
  renamingId.value = '';
  if (!name || name === item.name) return;
  try {
    await updateSavedFilter(item.id, { name });
    await loadSavedFilters();
  } catch {
    message.error('重命名失败，请稍后重试');
  }
}

async function handleDeleteSavedFilter(item: SavedFilter) {
  try {
    await deleteSavedFilter(item.id);
    message.success(`已删除「${item.name}」`);
    await loadSavedFilters();
  } catch {
    message.error('删除筛选失败，请稍后重试');
  }
}

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
        completenessDrill.value?.drill,
      ),
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
  try {
    const catalog = await fetchPatientSearchFields();
    fieldCatalog.value = catalog.fields;
  } catch {
    fieldCatalog.value = [];
  }
  await loadSavedFilters();
}

onMounted(() => {
  const status = patientStatusOfQuery(route.query.status);
  if (status) {
    filters.status = status;
  }
  completenessDrill.value = completenessDrillOfQuery(route.query);
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
  appliedCondition.value = null;
  completenessDrill.value = null;
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
  patientEmptyText(
    hasActiveFilters(appliedFilters.value, hasAdvancedActive.value),
    canCreate.value,
  ),
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
        <Space>
          <Button
            v-if="canImport"
            data-testid="patient-import"
            @click="router.push('/followup/patient/import')"
          >
            导入
          </Button>
          <Button
            v-if="canExport"
            data-testid="patient-export"
            @click="exportOpen = true"
          >
            导出
          </Button>
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
            <Button
              data-testid="patient-advanced-open"
              ghost
              type="primary"
              @click="advancedOpen = true"
            >
              高级检索
            </Button>
            <!-- 我的筛选（A-PAT-015：仅本人可见；点击应用，行内更新 / 重命名 / 删除） -->
            <Dropdown>
              <Button data-testid="patient-saved-filters">我的筛选 ▾</Button>
              <template #overlay>
                <Menu data-testid="saved-filter-menu">
                  <MenuItem v-if="savedFilters.length === 0" disabled>
                    暂无保存的筛选，用「高级检索」保存
                  </MenuItem>
                  <MenuItem
                    v-for="item in savedFilters"
                    :key="item.id"
                    :title="item.name"
                  >
                    <div class="saved-filter-row">
                      <template v-if="renamingId === item.id">
                        <Input
                          v-model:value="renamingName"
                          :maxlength="32"
                          size="small"
                          :style="{ width: '140px' }"
                          @keyup.enter="confirmRename(item)"
                        />
                        <Button
                          size="small"
                          type="link"
                          @click.stop="confirmRename(item)"
                        >
                          确定
                        </Button>
                      </template>
                      <template v-else>
                        <span
                          class="saved-filter-name"
                          @click.stop="handleApplySavedFilter(item)"
                        >
                          {{ item.name }}
                        </span>
                        <span @click.stop>
                          <Button
                            size="small"
                            type="link"
                            title="用当前检索条件覆盖该筛选"
                            @click="handleUpdateSavedFilter(item)"
                          >
                            更新
                          </Button>
                          <Button
                            size="small"
                            type="link"
                            @click="startRename(item)"
                          >
                            重命名
                          </Button>
                          <Popconfirm
                            title="删除该筛选？"
                            ok-text="删除"
                            cancel-text="取消"
                            @confirm="handleDeleteSavedFilter(item)"
                          >
                            <Button danger size="small" type="link">
                              删除
                            </Button>
                          </Popconfirm>
                        </span>
                      </template>
                    </div>
                  </MenuItem>
                </Menu>
              </template>
            </Dropdown>
          </Space>
        </FormItem>
      </Form>

      <!-- E19-T05 / A-DASH-010、A-DASH-011 · 完整度缺失字段钻取：显示来源与字段，
           关闭即取消钻取恢复普通检索（后端按钻取条件同一计算过滤） -->
      <div v-if="completenessDrill" class="drill-bar">
        <Tag
          closable
          color="blue"
          data-testid="patient-completeness-drill"
          @close.prevent="clearCompletenessDrill"
        >
          {{
            completenessDrill.drill.kind === 'FOLLOWUP'
              ? '随访资料缺失字段'
              : '基线资料缺失字段'
          }}：{{ completenessDrill.label }}
        </Tag>
      </div>

      <!-- 批量操作条（pages.md §3「☐ 已选 N 项」；手机不提供批量操作） -->
      <div v-if="!isMobile && selectedIds.length > 0" class="batch-bar">
        <span data-testid="patient-batch-selected">
          已选 {{ selectedIds.length }} 项
        </span>
        <Space>
          <Button
            v-if="canBatchAssign"
            data-testid="patient-batch-assign"
            @click="assignOpen = true"
          >
            批量指定责任医生
          </Button>
          <Button
            v-if="canBatchEnroll"
            data-testid="patient-batch-enroll"
            @click="openBatchEnroll"
          >
            批量入组
          </Button>
          <Button
            data-testid="patient-batch-clear"
            type="link"
            @click="selectedIds = []"
          >
            清除勾选
          </Button>
        </Space>
      </div>

      <!-- 生效的高级检索提示条（点击 ✕ 清除；pages.md §3 高级检索入口） -->
      <div v-if="hasAdvancedActive" class="advanced-chip">
        <Tag
          closable
          color="blue"
          data-testid="advanced-condition-chip"
          @close.prevent="clearAdvanced"
        >
          高级检索（{{ appliedConditionCount }} 项）
        </Tag>
      </div>

      <AdvancedSearch
        v-model:open="advancedOpen"
        :field-catalog="fieldCatalog"
        :mobile-common-only="isMobile"
        :value="appliedCondition ?? undefined"
        @apply="handleApplyAdvanced"
        @save-as="handleSaveAs"
      />

      <TransferWardModal
        v-model:open="transferOpen"
        :current-ward-id="null"
        :label="
          transferTarget
            ? `${transferTarget.name} · ${transferTarget.patientNo}`
            : null
        "
        :patient-id="transferTarget?.id ?? ''"
        @success="handleTransferred"
      />

      <!-- E13-T07 / A-PAT-017：批量指定责任医生（只能选该病区的医生；结果逐条可查） -->
      <BatchAssignDoctorModal
        v-model:open="assignOpen"
        :count="selectedIds.length"
        :patient-ids="selectedIds"
        @success="handleBatchAssigned"
      />

      <!-- 导出（A-PAT-009）：按当前筛选 + 高级检索提交导出作业；租户管理员免审批，普通用户待审批 -->
      <PatientExportModal
        v-model:open="exportOpen"
        :advanced-condition="appliedCondition"
        :can-approve="canApproveExport"
        :filters="appliedFilters"
      />

      <!-- 删除（A-PAT-012）：软删除 + 原因必填 + 二次认证；成功后刷新列表 -->
      <DeletePatientModal
        v-model:open="deleteOpen"
        :label="
          deleteTarget
            ? `${deleteTarget.name} · ${deleteTarget.patientNo}`
            : null
        "
        :patient-id="deleteTarget?.id ?? ''"
        @success="handleDeleted"
      />

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
        :row-selection="{
          preserveSelectedRowKeys: true,
          selectedRowKeys: selectedIds,
          onChange: (keys: Array<number | string>) =>
            (selectedIds = keys.map(String)),
        }"
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
          <Space v-else-if="column.dataIndex === 'action'">
            <Button
              :data-testid="`open-${record.id}`"
              size="small"
              type="link"
              @click="openDetail(record)"
            >
              详情
            </Button>
            <Dropdown v-if="canTransfer || canDelete">
              <Button data-testid="row-more" size="small" type="link">
                ⋮
              </Button>
              <template #overlay>
                <Menu>
                  <MenuItem
                    v-if="canTransfer"
                    key="transfer"
                    :data-testid="`transfer-${record.id}`"
                    @click="openTransfer(record)"
                  >
                    转病区
                  </MenuItem>
                  <MenuItem
                    v-if="canDelete"
                    key="delete"
                    :data-testid="`delete-${record.id}`"
                    class="danger-item"
                    @click="openDelete(record)"
                  >
                    删除
                  </MenuItem>
                </Menu>
              </template>
            </Dropdown>
          </Space>
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

.drill-bar {
  margin-bottom: 12px;
}

.search-bar {
  row-gap: 8px;
  margin-bottom: 8px;
}

.advanced-chip {
  margin-bottom: 8px;
}

.batch-bar {
  display: flex;
  gap: 12px;
  align-items: center;
  padding: 8px 12px;
  margin-bottom: 8px;
  background: #f5f8ff;
  border-radius: 6px;
}

.saved-filter-row {
  display: flex;
  gap: 4px;
  align-items: center;
  justify-content: space-between;
  min-width: 280px;
}

.saved-filter-name {
  max-width: 150px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  cursor: pointer;
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

/* 行操作「删除」红色（pages.md §3） */
.danger-item {
  color: #ff4d4f;
}
</style>
