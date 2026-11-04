<script lang="ts" setup>
import type {
  PlanCycleType,
  PlanDetailView,
  PlanStartDateField,
  PlanView,
} from '@crdp/api';

import type { PlanNodeRow } from './logic';

import { computed, onMounted, reactive, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';

import { Page } from '@vben/common-ui';

import {
  PLAN_ENROLL_MODE_TEXT,
  PLAN_NODE_BASIS_TEXT,
  PLAN_OFFSET_UNIT_TEXT,
  PLAN_OWNER_TYPE_TEXT,
  PLAN_PASSED_WINDOW_TEXT,
  PLAN_REMIND_UNIT_TEXT,
  PLAN_REPEAT_MODE_TEXT,
  PLAN_START_DATE_FIELD_OPTIONS,
  PLAN_TASK_TYPE_TEXT,
  planCycleText,
  planStartDateFieldText,
  planStatusText,
} from '@crdp/api';
import { computeNodeSchedule } from '@crdp/rules';
import { StatusTag } from '@crdp/status-tag';
import {
  Alert,
  Button,
  Card,
  DatePicker,
  Descriptions,
  DescriptionsItem,
  Empty,
  Form,
  FormItem,
  Input,
  InputNumber,
  message,
  Modal,
  Popconfirm,
  Result,
  Select,
  SelectOption,
  Spin,
  Steps,
  Table,
  TabPane,
  Tabs,
} from 'ant-design-vue';

import {
  copyPlan,
  fetchPlanDetail,
  publishPlan,
  savePlanNodes,
  updatePlan,
} from '#/api/core/plan';
import { useBreakpoint } from '#/reference/use-breakpoint';
import { usePermission } from '#/reference/use-permission';

import {
  buildNodeInputs,
  canEditPlan,
  newNodeRow,
  newSubplanRow,
  planActionsFor,
  previewBaseLabel,
  toNodeRows,
  unitValueText,
  validateBasicInfo,
  validateNodeRows,
} from './logic';

defineOptions({ name: 'FollowupPlanDetail' });

/**
 * E14-T02 / A-FU-002、A-FU-016、A-FU-018、A-FU-019 · 计划详情
 * （/followup/plan/:planId，pages.md §8）：页签「基本信息 / 节点」；患者列表与变更记录
 * 页签随 E14-T08、C 器械映射随 E21。仅未发布可编辑（基本信息与节点整体保存，乐观锁
 * baseVersion，冲突 40900）；已发布 / 暂停 / 停用只读，提示复制为新版本。发布走
 * a-steps 确认（检查节点 → 确认影响 → 发布）。节点编辑表 + 计算预览用 @crdp/rules
 * 的 plan-date（与后端 E14-T03 共享用例：A 年度、B 3 个月、B 1 年、B 术后 1/3/5 天）。
 */
const route = useRoute();
const router = useRouter();
const { isMobile } = useBreakpoint();
const { hasPermission } = usePermission();

const canEdit = hasPermission('followup:plan:edit');
const canPublish = hasPermission([
  'followup:plan:publish',
  'followup:plan:edit',
]);

const planId = computed(() => String(route.params.planId ?? ''));

const loading = ref(false);
const loadError = ref('');
const detail = ref<null | PlanDetailView>(null);
const plan = computed<null | PlanView>(() => detail.value?.plan ?? null);
const editable = computed(() => canEditPlan(plan.value?.status ?? ''));

// ---------------------------------------------------------------- 基本信息

const basicEditing = ref(false);
const basicSaving = ref(false);
const basicErrors = ref<string[]>([]);
const basicForm = reactive({
  cycleType: 'ONGOING' as PlanCycleType,
  cycleYears: null as null | number,
  name: '',
  remark: '',
  startDateField: 'DISCHARGE_DATE' as PlanStartDateField,
});

function startEditBasic() {
  const current = plan.value;
  if (!current) return;
  basicForm.name = current.name;
  basicForm.startDateField = current.startDateField as PlanStartDateField;
  basicForm.cycleType = current.cycleType as PlanCycleType;
  basicForm.cycleYears = current.cycleYears;
  basicForm.remark = current.remark ?? '';
  basicErrors.value = [];
  basicEditing.value = true;
}

async function submitBasic() {
  const current = plan.value;
  if (!current) return;
  basicErrors.value = validateBasicInfo({
    code: current.code,
    cycleType: basicForm.cycleType,
    cycleYears: basicForm.cycleYears,
    name: basicForm.name,
  });
  if (basicErrors.value.length > 0) return;
  basicSaving.value = true;
  try {
    await updatePlan(planId.value, {
      baseVersion: current.lockVersion,
      cycleType: basicForm.cycleType,
      cycleYears: basicForm.cycleType === 'YEARS' ? basicForm.cycleYears : null,
      name: basicForm.name.trim(),
      remark: basicForm.remark.trim() || null,
      startDateField: basicForm.startDateField,
    });
    message.success('基本信息已保存（未发布前可继续修改）');
    basicEditing.value = false;
    await reload();
  } catch {
    // 统一拦截器已提示（已发布 40901、乐观锁冲突 40900）；冲突后重载拿最新版本
    basicEditing.value = false;
    await reload();
  } finally {
    basicSaving.value = false;
  }
}

// ---------------------------------------------------------------- 节点编辑

const rows = ref<PlanNodeRow[]>([]);
const nodeErrors = ref<string[]>([]);
const nodesSaving = ref(false);
const dirty = ref(false);

/** 计算预览的示例起算日期（pages.md §8 示例：出院 2025-03-15）。 */
const previewDate = ref('2025-03-15');

function resetRows() {
  rows.value = detail.value ? toNodeRows(detail.value) : [];
  nodeErrors.value = [];
  dirty.value = false;
}

function markDirty() {
  dirty.value = true;
}

function addSubplan() {
  rows.value.push(newSubplanRow());
  markDirty();
}

function addNode(parentKey: string | undefined) {
  const target =
    parentKey ?? rows.value.find((row) => row.kind === 'SUBPLAN')?.key;
  if (!target) {
    message.warning('请先添加子计划（里程碑），节点挂在子计划下');
    return;
  }
  rows.value.push(newNodeRow(target));
  markDirty();
}

function removeRow(key: string) {
  const index = rows.value.findIndex((row) => row.key === key);
  if (index === -1) return;
  // 子计划被删除时其下节点一并移除（整体保存按行序替换）
  const [removed] = rows.value.splice(index, 1);
  if (removed?.kind === 'SUBPLAN') {
    rows.value = rows.value.filter(
      (row) => row.kind !== 'NODE' || row.parentKey !== removed.key,
    );
  }
  markDirty();
}

function subplanOptions() {
  return rows.value
    .filter(
      (row): row is Extract<PlanNodeRow, { kind: 'SUBPLAN' }> =>
        row.kind === 'SUBPLAN',
    )
    .map((row) => ({ label: row.name, value: row.key }));
}

/** 节点行的计算预览（@crdp/rules plan-date，与后端共享用例）。 */
function scheduleOf(row: PlanNodeRow) {
  if (row.kind !== 'NODE') return null;
  return computeNodeSchedule(
    {
      offsetUnit: row.offsetUnit,
      offsetValue: row.offsetValue,
      remindUnit: row.remindUnit,
      remindValue: row.remindValue,
      windowAfterUnit: row.windowAfterUnit,
      windowAfterValue: row.windowAfterValue,
      windowBeforeUnit: row.windowBeforeUnit,
      windowBeforeValue: row.windowBeforeValue,
    },
    previewDate.value,
  );
}

async function submitNodes() {
  const current = plan.value;
  if (!current) return;
  nodeErrors.value = validateNodeRows(rows.value);
  if (nodeErrors.value.length > 0) return;
  nodesSaving.value = true;
  try {
    detail.value = await savePlanNodes(planId.value, {
      baseVersion: current.lockVersion,
      nodes: buildNodeInputs(rows.value),
    });
    dirty.value = false;
    nodeErrors.value = [];
    message.success('节点已保存（未发布前可继续调整）');
  } catch {
    // 统一拦截器已提示（结构 40001 / 冲突 40900 / 已发布 40901）；重载最新
    await reload();
  } finally {
    nodesSaving.value = false;
  }
}

// ---------------------------------------------------------------- 发布 / 复制

const publishOpen = ref(false);
const publishStep = ref(0);
const publishing = ref(false);

const subplanCount = computed(
  () => rows.value.filter((row) => row.kind === 'SUBPLAN').length,
);
const nodeCount = computed(
  () => rows.value.filter((row) => row.kind === 'NODE').length,
);
const publishCheckErrors = computed(() => validateNodeRows(rows.value));

function openPublish() {
  if (dirty.value) {
    message.warning('节点有未保存的修改，请先保存再发布');
    return;
  }
  publishStep.value = 0;
  publishOpen.value = true;
}

/** a-steps 发布确认：第 0 步检查节点，第 1 步确认影响后才可点「确认发布」。 */
function nextPublishStep() {
  if (publishStep.value === 1) {
    confirmPublish();
    return;
  }
  if (publishCheckErrors.value.length > 0) return;
  publishStep.value = 1;
}

async function confirmPublish() {
  publishing.value = true;
  try {
    const published = await publishPlan(planId.value);
    publishOpen.value = false;
    message.success(
      `「${published.name}」已发布，可以入组；同族旧版本已自动停用（在组患者沿用原版本）`,
    );
    await reload();
  } catch {
    // 统一拦截器已提示（无节点 40001 / 状态机 40901）
  } finally {
    publishing.value = false;
  }
}

const copying = ref(false);

async function copyAsNewVersion() {
  const current = plan.value;
  if (!current) return;
  copying.value = true;
  try {
    const copied = await copyPlan(planId.value);
    message.success(
      `已复制为新版本 v${copied.version}（未发布）；修改后发布，在组患者沿用原版本`,
    );
    // 等导航完成（route.params 已指向新版本）后再加载详情
    await router.replace(`/followup/plan/${copied.id}`);
    await reload();
  } catch {
    // 统一拦截器已提示（同族已有草稿 40901）
  } finally {
    copying.value = false;
  }
}

// ---------------------------------------------------------------- 装载

async function reload() {
  loading.value = true;
  loadError.value = '';
  try {
    detail.value = await fetchPlanDetail(planId.value);
    resetRows();
  } catch (error) {
    loadError.value = error instanceof Error ? error.message : String(error);
    detail.value = null;
  } finally {
    loading.value = false;
  }
}

onMounted(reload);

const nodeColumns = [
  { key: 'name', title: '节点名称', width: 210 },
  { key: 'repeat', title: '重复方式', width: 190 },
  { key: 'basis', title: '基准', width: 130 },
  { key: 'offset', title: '偏移', width: 150 },
  { key: 'remind', title: '提醒提前量', width: 150 },
  { key: 'windowBefore', title: '窗口前', width: 140 },
  { key: 'windowAfter', title: '窗口后', width: 140 },
  { key: 'task', title: '任务类型', width: 230 },
  { key: 'owner', title: '负责人类型', width: 140 },
  { key: 'passedWindow', title: '入组前已过窗口', width: 130 },
  { key: 'enrollMode', title: 'ERI 模式（C）', width: 120 },
  { key: 'preview', title: '计算预览', width: 260 },
  { key: 'action', title: '操作', width: 80 },
];
</script>

<template>
  <Result v-if="isMobile" status="info" title="请在电脑或平板上操作">
    <template #extra>
      <Button type="primary" @click="router.push('/dashboard')">
        返回首页
      </Button>
    </template>
  </Result>
  <Page v-else-if="plan" :title="`计划详情 · ${plan.name}`">
    <template #description>
      <Button size="small" type="link" @click="router.push('/followup/plan')">
        ← 返回计划列表
      </Button>
      <StatusTag :status="planStatusText(plan.status)" />
      <span class="header-meta">
        {{ plan.code }} · v{{ plan.version }} ·
        {{ planStartDateFieldText(plan.startDateField) }}起算 ·
        {{ planCycleText(plan.cycleType, plan.cycleYears) }} · 在组
        {{ plan.enrolledCount }} 人
      </span>
    </template>

    <div class="actions">
      <Button
        v-if="canPublish && planActionsFor(plan.status).includes('publish')"
        type="primary"
        @click="openPublish"
      >
        发布计划
      </Button>
      <Button
        v-if="canEdit && planActionsFor(plan.status).includes('copy')"
        :loading="copying"
        @click="copyAsNewVersion"
      >
        复制为新版本
      </Button>
      <Alert
        v-if="!editable"
        class="readonly-banner"
        message="已发布的计划不能修改，请复制为新版本（A-FU-019）"
        show-icon
        type="info"
      />
    </div>

    <Alert
      v-if="loadError"
      :message="loadError"
      show-icon
      type="error"
      style="margin-bottom: 12px"
    />

    <Tabs default-active-key="basic">
      <TabPane key="basic" tab="基本信息">
        <Card>
          <Descriptions v-if="!basicEditing" :column="2" bordered size="small">
            <DescriptionsItem label="计划编码">
              {{ plan.code }}
            </DescriptionsItem>
            <DescriptionsItem label="版本">
              v{{ plan.version }}
            </DescriptionsItem>
            <DescriptionsItem label="名称">{{ plan.name }}</DescriptionsItem>
            <DescriptionsItem label="状态">
              <StatusTag :status="planStatusText(plan.status)" />
            </DescriptionsItem>
            <DescriptionsItem label="起算日期">
              {{ planStartDateFieldText(plan.startDateField) }}
            </DescriptionsItem>
            <DescriptionsItem label="管理周期">
              {{ planCycleText(plan.cycleType, plan.cycleYears) }}
            </DescriptionsItem>
            <DescriptionsItem label="发布时间">
              {{ plan.publishedAt ?? '—（未发布）' }}
            </DescriptionsItem>
            <DescriptionsItem label="在组人数">
              {{ plan.enrolledCount }}
            </DescriptionsItem>
            <DescriptionsItem label="备注" :span="2">
              {{ plan.remark ?? '—' }}
            </DescriptionsItem>
          </Descriptions>
          <template v-else>
            <Alert
              v-if="basicErrors.length > 0"
              :message="basicErrors.join('；')"
              show-icon
              type="error"
              style="margin-bottom: 12px"
            />
            <Form class="basic-form" layout="vertical">
              <FormItem label="计划名称" required>
                <Input v-model:value="basicForm.name" maxlength="128" />
              </FormItem>
              <FormItem label="起算日期" required>
                <Select v-model:value="basicForm.startDateField">
                  <SelectOption
                    v-for="option in PLAN_START_DATE_FIELD_OPTIONS"
                    :key="option.value"
                    :value="option.value"
                  >
                    {{ option.label }}
                  </SelectOption>
                </Select>
              </FormItem>
              <FormItem label="管理周期" required>
                <Select v-model:value="basicForm.cycleType">
                  <SelectOption value="ONGOING">
                    长期（不设结束年度）
                  </SelectOption>
                  <SelectOption value="YEARS">固定 N 年</SelectOption>
                </Select>
              </FormItem>
              <FormItem
                v-if="basicForm.cycleType === 'YEARS'"
                label="管理周期年数"
                required
              >
                <InputNumber
                  v-model:value="basicForm.cycleYears"
                  :min="1"
                  style="width: 100%"
                />
              </FormItem>
              <FormItem class="form-span" label="备注">
                <Input v-model:value="basicForm.remark" maxlength="255" />
              </FormItem>
            </Form>
          </template>
          <div v-if="editable && canEdit" class="tab-actions">
            <template v-if="!basicEditing">
              <Button type="primary" @click="startEditBasic">
                编辑基本信息
              </Button>
            </template>
            <template v-else>
              <Button :loading="basicSaving" @click="basicEditing = false">
                取消
              </Button>
              <Button
                :loading="basicSaving"
                type="primary"
                @click="submitBasic"
              >
                保存基本信息
              </Button>
            </template>
          </div>
        </Card>
      </TabPane>

      <TabPane key="nodes" tab="节点">
        <Card>
          <div class="node-toolbar">
            <div class="preview-bar">
              <span class="preview-label">
                计算预览：{{ previewBaseLabel(plan.startDateField) }}
              </span>
              <DatePicker
                v-model:value="previewDate"
                placeholder="示例起算日期"
                size="small"
                value-format="YYYY-MM-DD"
              />
              <span class="preview-hint">
                基准为「上次实际随访」的节点按上次随访日期滚动，这里按示例起算日期演示
              </span>
            </div>
            <div v-if="editable && canEdit" class="node-buttons">
              <Button @click="addSubplan">+ 添加子计划</Button>
              <Button @click="addNode(undefined)">+ 添加节点</Button>
              <Button
                :loading="nodesSaving"
                type="primary"
                @click="submitNodes"
              >
                保存节点
              </Button>
            </div>
          </div>

          <Alert
            v-if="nodeErrors.length > 0"
            :message="nodeErrors.join('；')"
            show-icon
            type="error"
            style="margin-bottom: 12px"
          />

          <Spin :spinning="loading">
            <Empty
              v-if="rows.length === 0 && !loading"
              description="暂无节点：先添加子计划（里程碑），再在其下添加节点"
            />
            <Table
              v-else
              :columns="nodeColumns"
              :data-source="rows"
              :pagination="false"
              row-key="key"
              size="small"
            >
              <template #bodyCell="{ column, record }">
                <!-- 名称：子计划加粗、节点缩进 -->
                <template v-if="column.key === 'name'">
                  <template v-if="editable && canEdit">
                    <div :class="record.kind === 'NODE' ? 'node-name' : ''">
                      <span v-if="record.kind === 'NODE'" class="kind-tag">
                        节点
                      </span>
                      <Input
                        v-model:value="record.name"
                        maxlength="128"
                        size="small"
                      />
                    </div>
                  </template>
                  <template v-else>
                    <span
                      :class="
                        record.kind === 'SUBPLAN' ? 'subplan-name' : 'node-name'
                      "
                    >
                      {{ record.kind === 'SUBPLAN' ? '子计划 · ' : ''
                      }}{{ record.name }}
                    </span>
                  </template>
                </template>

                <!-- 重复方式（子计划行） -->
                <template v-else-if="column.key === 'repeat'">
                  <template v-if="record.kind === 'SUBPLAN'">
                    <template v-if="editable && canEdit">
                      <div class="cell-pair">
                        <Select
                          :value="record.repeatMode"
                          size="small"
                          @change="
                            (value) => {
                              record.repeatMode = value;
                              markDirty();
                            }
                          "
                        >
                          <SelectOption value="CYCLE">
                            {{ PLAN_REPEAT_MODE_TEXT.CYCLE }}
                          </SelectOption>
                          <SelectOption value="FIXED">
                            {{ PLAN_REPEAT_MODE_TEXT.FIXED }}
                          </SelectOption>
                        </Select>
                        <template v-if="record.repeatMode === 'FIXED'">
                          <InputNumber
                            :min="1"
                            :value="record.repeatCount"
                            addon-before="次数"
                            size="small"
                            @change="
                              (value) => {
                                record.repeatCount = value;
                                markDirty();
                              }
                            "
                          />
                        </template>
                        <template v-else>
                          <InputNumber
                            :min="1"
                            :value="record.cycleValue"
                            size="small"
                            @change="
                              (value) => {
                                record.cycleValue = value;
                                markDirty();
                              }
                            "
                          />
                          <Select
                            :value="record.cycleUnit"
                            size="small"
                            style="width: 84px"
                            @change="
                              (value) => {
                                record.cycleUnit = value;
                                markDirty();
                              }
                            "
                          >
                            <SelectOption
                              v-for="(text, unit) in PLAN_OFFSET_UNIT_TEXT"
                              :key="unit"
                              :value="unit"
                            >
                              {{ text }}
                            </SelectOption>
                          </Select>
                        </template>
                      </div>
                    </template>
                    <template v-else>
                      {{
                        record.repeatMode === 'FIXED'
                          ? `固定次数 ${record.repeatCount ?? '—'} 次`
                          : `持续循环 · 每 ${record.cycleValue ?? '—'} ${
                              PLAN_OFFSET_UNIT_TEXT[record.cycleUnit] ?? ''
                            }`
                      }}
                    </template>
                  </template>
                  <template v-else>—</template>
                </template>

                <!-- 基准（节点行） -->
                <template v-else-if="column.key === 'basis'">
                  <template v-if="record.kind === 'NODE'">
                    <Select
                      v-if="editable && canEdit"
                      :value="record.basis"
                      size="small"
                      @change="
                        (value) => {
                          record.basis = value;
                          markDirty();
                        }
                      "
                    >
                      <SelectOption
                        v-for="(text, value) in PLAN_NODE_BASIS_TEXT"
                        :key="value"
                        :value="value"
                      >
                        {{ text }}
                      </SelectOption>
                    </Select>
                    <template v-else>
                      {{ PLAN_NODE_BASIS_TEXT[record.basis] ?? record.basis }}
                    </template>
                  </template>
                  <template v-else>—</template>
                </template>

                <!-- 偏移 / 提醒 / 窗口前 / 窗口后（数值 + 单位） -->
                <template
                  v-else-if="
                    [
                      'offset',
                      'remind',
                      'windowBefore',
                      'windowAfter',
                    ].includes(column.key)
                  "
                >
                  <template v-if="record.kind === 'NODE'">
                    <div v-if="editable && canEdit" class="cell-pair">
                      <InputNumber
                        :min="0"
                        :value="record[`${column.key}Value`]"
                        size="small"
                        @change="
                          (value) => {
                            record[`${column.key}Value`] = value ?? 0;
                            markDirty();
                          }
                        "
                      />
                      <Select
                        :value="record[`${column.key}Unit`]"
                        size="small"
                        style="width: 84px"
                        @change="
                          (value) => {
                            record[`${column.key}Unit`] = value;
                            markDirty();
                          }
                        "
                      >
                        <SelectOption
                          v-for="(text, unit) in column.key === 'remind'
                            ? PLAN_REMIND_UNIT_TEXT
                            : PLAN_OFFSET_UNIT_TEXT"
                          :key="unit"
                          :value="unit"
                        >
                          {{ text }}
                        </SelectOption>
                      </Select>
                    </div>
                    <template v-else>
                      <template v-if="column.key === 'remind'">
                        {{
                          record.remindValue === 0
                            ? '当天'
                            : unitValueText(
                                record.remindValue,
                                record.remindUnit,
                                PLAN_REMIND_UNIT_TEXT,
                              )
                        }}
                      </template>
                      <template v-else>
                        {{
                          unitValueText(
                            record[`${column.key}Value`],
                            record[`${column.key}Unit`],
                            PLAN_OFFSET_UNIT_TEXT,
                          )
                        }}
                      </template>
                    </template>
                  </template>
                  <template v-else>—</template>
                </template>

                <!-- 任务类型 + 表单 code（节点行） -->
                <template v-else-if="column.key === 'task'">
                  <template v-if="record.kind === 'NODE'">
                    <template v-if="editable && canEdit">
                      <div class="cell-stack">
                        <Select
                          :value="record.taskType"
                          size="small"
                          @change="
                            (value) => {
                              record.taskType = value;
                              markDirty();
                            }
                          "
                        >
                          <SelectOption value="FORM">
                            {{ PLAN_TASK_TYPE_TEXT.FORM }}
                          </SelectOption>
                          <SelectOption value="REMINDER">
                            {{ PLAN_TASK_TYPE_TEXT.REMINDER }}
                          </SelectOption>
                        </Select>
                        <Input
                          v-if="record.taskType === 'FORM'"
                          :value="record.formCode"
                          maxlength="64"
                          placeholder="随访记录表单 code"
                          size="small"
                          @change="
                            (event) => {
                              record.formCode = event.target.value;
                              markDirty();
                            }
                          "
                        />
                      </div>
                    </template>
                    <template v-else>
                      {{
                        PLAN_TASK_TYPE_TEXT[record.taskType] ?? record.taskType
                      }}
                      <span v-if="record.taskType === 'FORM'" class="cell-sub">
                        {{ record.formCode }}
                      </span>
                    </template>
                  </template>
                  <template v-else>—</template>
                </template>

                <!-- 负责人类型（节点行） -->
                <template v-else-if="column.key === 'owner'">
                  <template v-if="record.kind === 'NODE'">
                    <Select
                      v-if="editable && canEdit"
                      :value="record.ownerType"
                      size="small"
                      @change="
                        (value) => {
                          record.ownerType = value;
                          markDirty();
                        }
                      "
                    >
                      <SelectOption
                        v-for="(text, value) in PLAN_OWNER_TYPE_TEXT"
                        :key="value"
                        :value="value"
                      >
                        {{ text }}
                      </SelectOption>
                    </Select>
                    <template v-else>
                      {{
                        PLAN_OWNER_TYPE_TEXT[record.ownerType] ??
                        record.ownerType
                      }}
                    </template>
                  </template>
                  <template v-else>—</template>
                </template>

                <!-- 入组前已过窗口（节点行） -->
                <template v-else-if="column.key === 'passedWindow'">
                  <template v-if="record.kind === 'NODE'">
                    <Select
                      v-if="editable && canEdit"
                      allow-clear
                      :value="record.passedWindowAction || undefined"
                      placeholder="默认"
                      size="small"
                      @change="
                        (value) => {
                          record.passedWindowAction = value ?? '';
                          markDirty();
                        }
                      "
                    >
                      <SelectOption
                        v-for="(text, value) in PLAN_PASSED_WINDOW_TEXT"
                        :key="value"
                        :value="value"
                      >
                        {{ text }}
                      </SelectOption>
                    </Select>
                    <template v-else>
                      {{
                        record.passedWindowAction
                          ? PLAN_PASSED_WINDOW_TEXT[record.passedWindowAction]
                          : '默认'
                      }}
                    </template>
                  </template>
                  <template v-else>—</template>
                </template>

                <!-- ERI 模式（C，节点行） -->
                <template v-else-if="column.key === 'enrollMode'">
                  <template v-if="record.kind === 'NODE'">
                    <Select
                      v-if="editable && canEdit"
                      allow-clear
                      :value="record.enrollMode || undefined"
                      placeholder="全部"
                      size="small"
                      @change="
                        (value) => {
                          record.enrollMode = value ?? '';
                          markDirty();
                        }
                      "
                    >
                      <SelectOption
                        v-for="(text, value) in PLAN_ENROLL_MODE_TEXT"
                        :key="value"
                        :value="value"
                      >
                        {{ text }}
                      </SelectOption>
                    </Select>
                    <template v-else>
                      {{
                        record.enrollMode
                          ? PLAN_ENROLL_MODE_TEXT[record.enrollMode]
                          : '全部'
                      }}
                    </template>
                  </template>
                  <template v-else>—</template>
                </template>

                <!-- 计算预览（节点行；plan-date 与后端共享用例） -->
                <template v-else-if="column.key === 'preview'">
                  <template v-if="record.kind === 'NODE' && scheduleOf(record)">
                    <div class="cell-sub">
                      计划 {{ scheduleOf(record)!.planDate }} · 开启
                      {{ scheduleOf(record)!.openDate }}
                    </div>
                    <div class="cell-sub">
                      窗口 {{ scheduleOf(record)!.windowStart }} ~
                      {{ scheduleOf(record)!.windowEnd }}
                    </div>
                  </template>
                  <template v-else>—</template>
                </template>

                <!-- 操作 -->
                <template v-else-if="column.key === 'action'">
                  <template v-if="editable && canEdit">
                    <div class="cell-pair">
                      <Select
                        v-if="record.kind === 'NODE'"
                        :value="record.parentKey"
                        size="small"
                        style="width: 120px"
                        @change="
                          (value) => {
                            record.parentKey = value;
                            markDirty();
                          }
                        "
                      >
                        <SelectOption
                          v-for="option in subplanOptions()"
                          :key="option.value"
                          :value="option.value"
                        >
                          {{ option.label }}
                        </SelectOption>
                      </Select>
                      <Popconfirm
                        :title="
                          record.kind === 'SUBPLAN'
                            ? '删除该子计划将同时删除其下节点，确定？'
                            : '确定删除该节点？'
                        "
                        @confirm="removeRow(record.key)"
                      >
                        <Button danger size="small" type="link">删除</Button>
                      </Popconfirm>
                    </div>
                  </template>
                  <template v-else>—</template>
                </template>
              </template>
            </Table>
          </Spin>
        </Card>
      </TabPane>
    </Tabs>

    <!-- 发布确认（a-steps：检查节点 → 确认影响 → 发布） -->
    <Modal
      v-model:open="publishOpen"
      :confirm-loading="publishing"
      :ok-button-props="{
        disabled: publishStep === 0 && publishCheckErrors.length > 0,
      }"
      :ok-text="publishStep === 1 ? '确认发布' : '下一步'"
      cancel-text="取消"
      title="发布计划"
      width="560px"
      @ok="nextPublishStep"
    >
      <Steps
        :current="publishStep"
        :items="[{ title: '检查节点' }, { title: '确认影响' }]"
        size="small"
        style="margin-bottom: 16px"
      />
      <template v-if="publishStep === 0">
        <Alert
          v-if="publishCheckErrors.length > 0"
          :message="publishCheckErrors.join('；')"
          show-icon
          type="error"
        />
        <Alert
          v-else
          :message="`检查通过：子计划 ${subplanCount} 个、节点 ${nodeCount} 个`"
          show-icon
          type="success"
        />
        <p class="publish-note">
          发布前请确认偏移、提醒提前量、窗口前后与周期设置无误（可在「计算预览」列验算）。
        </p>
      </template>
      <template v-else>
        <ul class="publish-note">
          <li>
            发布后计划内容锁定，不可再修改；需要调整须复制为新版本（A-FU-019）。
          </li>
          <li>发布后可以入组；有患者入组后状态显示「随访中」。</li>
          <li>
            同族旧已发布版本会自动停用：新入组用新版本，在组患者沿用原版本。
          </li>
        </ul>
      </template>
    </Modal>
  </Page>
  <Page v-else title="计划详情">
    <Card>
      <Alert v-if="loadError" :message="loadError" show-icon type="error" />
      <Empty v-else description="计划不存在或无权查看" />
      <Button
        style="margin-top: 12px"
        type="primary"
        @click="router.push('/followup/plan')"
      >
        返回计划列表
      </Button>
    </Card>
  </Page>
</template>

<style scoped>
.header-meta {
  margin-left: 12px;
  font-size: 13px;
  color: rgb(0 0 0 / 45%);
}

.actions {
  display: flex;
  gap: 8px;
  align-items: center;
  margin-bottom: 12px;
}

.readonly-banner {
  flex: 1;
  margin-bottom: 0;
}

.tab-actions {
  display: flex;
  gap: 8px;
  justify-content: flex-end;
  margin-top: 12px;
}

.basic-form {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  column-gap: 16px;
}

.form-span {
  grid-column: 1 / -1;
}

.node-toolbar {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
}

.preview-bar {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
}

.preview-label {
  font-weight: 600;
}

.preview-hint {
  font-size: 12px;
  color: rgb(0 0 0 / 45%);
}

.node-buttons {
  display: flex;
  gap: 8px;
}

.cell-pair {
  display: flex;
  gap: 4px;
  align-items: center;
}

.cell-stack {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.cell-sub {
  font-size: 12px;
  color: rgb(0 0 0 / 45%);
}

.node-name {
  display: flex;
  gap: 4px;
  align-items: center;
}

.subplan-name {
  font-weight: 600;
}

.kind-tag {
  flex-shrink: 0;
  font-size: 12px;
  color: rgb(0 0 0 / 45%);
}

.publish-note {
  padding-inline-start: 18px;
  margin: 12px 0 0;
  font-size: 13px;
  line-height: 1.8;
  color: rgb(0 0 0 / 65%);
}
</style>
