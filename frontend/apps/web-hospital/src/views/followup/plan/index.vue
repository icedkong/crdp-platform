<script lang="ts" setup>
import type { PlanCycleType, PlanStartDateField, PlanView } from '@crdp/api';

import { computed, onMounted, reactive, ref } from 'vue';
import { useRouter } from 'vue-router';

import { Page } from '@vben/common-ui';

import {
  PLAN_START_DATE_FIELD_OPTIONS,
  planCycleText,
  planStartDateFieldText,
  planStatusText,
} from '@crdp/api';
import { StatusTag } from '@crdp/status-tag';
import {
  Alert,
  Button,
  Card,
  Empty,
  Form,
  FormItem,
  Input,
  InputNumber,
  message,
  Modal,
  Pagination,
  Popconfirm,
  Result,
  Select,
  SelectOption,
  Spin,
  Table,
  Textarea,
} from 'ant-design-vue';

import {
  copyPlan,
  createPlan,
  disablePlan,
  listPlans,
  pausePlan,
  resumePlan,
} from '#/api/core/plan';
import { useBreakpoint } from '#/reference/use-breakpoint';
import { usePermission } from '#/reference/use-permission';

import { planActionsFor, planDetailPath, validateBasicInfo } from './logic';

defineOptions({ name: 'FollowupPlan' });

/**
 * E14-T02 / A-FU-002、A-FU-016、A-FU-018、A-FU-019 · 随访计划列表（/followup/plan，
 * pages.md §8）：名称、版本、起算日期、周期、状态（含派生「随访中」）、在组人数；
 * 新建（未发布）、复制为新版本、暂停 / 恢复 / 停用。节点配置、发布在详情页。
 * 编辑 followup:plan:edit；发布 / 暂停 / 停用 followup:plan:publish（C 并入
 * followup:plan:edit，任一命中即放行，OQ-13）。仅桌面 / 平板；手机 MobileBlocker。
 */
const router = useRouter();
const { isMobile } = useBreakpoint();
const { hasPermission } = usePermission();

const canEdit = hasPermission('followup:plan:edit');
const canPublish = hasPermission([
  'followup:plan:publish',
  'followup:plan:edit',
]);

const loading = ref(false);
const loadError = ref('');
const plans = ref<PlanView[]>([]);
const acting = ref('');

const createOpen = ref(false);
const creating = ref(false);
const createErrors = ref<string[]>([]);
const createForm = reactive({
  code: '',
  cycleType: 'ONGOING' as PlanCycleType,
  cycleYears: null as null | number,
  name: '',
  remark: '',
  startDateField: 'DISCHARGE_DATE' as PlanStartDateField,
});

const pageNo = ref(1);
const pageSize = 20;

const columns = [
  { key: 'name', title: '名称' },
  { dataIndex: 'code', key: 'code', title: '编码', width: 150 },
  { dataIndex: 'version', key: 'version', title: '版本', width: 70 },
  { key: 'startDateField', title: '起算日期', width: 100 },
  { key: 'cycle', title: '周期', width: 90 },
  { key: 'status', title: '状态', width: 100 },
  {
    dataIndex: 'enrolledCount',
    key: 'enrolledCount',
    title: '在组人数',
    width: 90,
  },
  { key: 'action', title: '操作', width: 250 },
];

const pagedPlans = computed(() =>
  plans.value.slice((pageNo.value - 1) * pageSize, pageNo.value * pageSize),
);

async function reload() {
  loading.value = true;
  loadError.value = '';
  try {
    plans.value = await listPlans();
  } catch (error) {
    loadError.value = error instanceof Error ? error.message : String(error);
    plans.value = [];
  } finally {
    loading.value = false;
  }
}

function openCreate() {
  createForm.code = '';
  createForm.cycleType = 'ONGOING';
  createForm.cycleYears = null;
  createForm.name = '';
  createForm.remark = '';
  createForm.startDateField = 'DISCHARGE_DATE';
  createErrors.value = [];
  createOpen.value = true;
}

async function submitCreate() {
  createErrors.value = validateBasicInfo({
    code: createForm.code,
    cycleType: createForm.cycleType,
    cycleYears: createForm.cycleYears,
    name: createForm.name,
  });
  if (createErrors.value.length > 0) return;
  creating.value = true;
  try {
    const created = await createPlan({
      code: createForm.code.trim(),
      cycleType: createForm.cycleType,
      cycleYears:
        createForm.cycleType === 'YEARS' ? createForm.cycleYears : null,
      name: createForm.name.trim(),
      remark: createForm.remark.trim() || null,
      startDateField: createForm.startDateField,
    });
    createOpen.value = false;
    message.success(`已创建「${created.name}」（未发布），请继续配置节点`);
    router.push(planDetailPath(created.id));
  } catch {
    // 统一拦截器已提示（code 重复 40902、校验 40001 等）
  } finally {
    creating.value = false;
  }
}

/** 列表行操作（详情页签：基本信息 / 节点；患者列表与变更记录随 E14-T08）。 */
function goDetail(plan: PlanView) {
  router.push(planDetailPath(plan.id));
}

async function copyAsNewVersion(plan: PlanView) {
  acting.value = `copy-${plan.id}`;
  try {
    const copied = await copyPlan(plan.id);
    message.success(
      `已复制为新版本 v${copied.version}（未发布），修改后发布生效；在组患者沿用原版本`,
    );
    await reload();
    router.push(planDetailPath(copied.id));
  } catch {
    // 统一拦截器已提示（同族已有草稿 40901）
  } finally {
    acting.value = '';
  }
}

async function runStatusAction(
  plan: PlanView,
  action: 'disable' | 'pause' | 'resume',
) {
  acting.value = `${action}-${plan.id}`;
  try {
    const runners = {
      disable: disablePlan,
      pause: pausePlan,
      resume: resumePlan,
    };
    const updated = await runners[action](plan.id);
    const actionText = { disable: '停用', pause: '暂停', resume: '恢复' }[
      action
    ];
    message.success(`「${updated.name}」已${actionText}`);
    await reload();
  } catch {
    // 统一拦截器已提示（状态机拦截 40901）
  } finally {
    acting.value = '';
  }
}

onMounted(reload);
</script>

<template>
  <Result v-if="isMobile" status="info" title="请在电脑或平板上操作">
    <template #extra>
      <Button type="primary" @click="$router.push('/dashboard')">
        返回首页
      </Button>
    </template>
  </Result>
  <Page
    v-else
    title="随访计划"
    description="计划 > 子计划 > 节点三层模板：偏移、提醒提前量、窗口前后与管理周期都在计划中设置；已发布版本只读，调整须复制出新版本（A-FU-016、A-FU-019）。"
  >
    <Card>
      <div class="toolbar">
        <Button :loading="loading" @click="reload">刷新</Button>
        <Button v-if="canEdit" type="primary" @click="openCreate">
          + 新建计划
        </Button>
      </div>

      <Alert
        v-if="loadError"
        :message="loadError"
        show-icon
        type="error"
        style="margin-bottom: 12px"
      />

      <Spin :spinning="loading">
        <Empty
          v-if="plans.length === 0 && !loading && !loadError"
          description="暂无随访计划"
        />
        <template v-else>
          <Table
            :columns="columns"
            :data-source="pagedPlans"
            :pagination="false"
            row-key="id"
            size="middle"
          >
            <template #bodyCell="{ column, record }">
              <template v-if="column.key === 'name'">
                <a @click="goDetail(record)">{{ record.name }}</a>
                <div v-if="record.remark" class="cell-sub">
                  {{ record.remark }}
                </div>
              </template>
              <template v-else-if="column.key === 'version'">
                v{{ record.version }}
              </template>
              <template v-else-if="column.key === 'startDateField'">
                {{ planStartDateFieldText(record.startDateField) }}
              </template>
              <template v-else-if="column.key === 'cycle'">
                {{ planCycleText(record.cycleType, record.cycleYears) }}
              </template>
              <template v-else-if="column.key === 'status'">
                <StatusTag :status="planStatusText(record.status)" />
              </template>
              <template v-else-if="column.key === 'action'">
                <Button size="small" type="link" @click="goDetail(record)">
                  详情
                </Button>
                <Button
                  v-if="
                    canEdit && planActionsFor(record.status).includes('copy')
                  "
                  :loading="acting === `copy-${record.id}`"
                  size="small"
                  type="link"
                  @click="copyAsNewVersion(record)"
                >
                  复制为新版本
                </Button>
                <Popconfirm
                  v-if="
                    canPublish &&
                    planActionsFor(record.status).includes('pause')
                  "
                  :title="`暂停后不开启新任务、不发通知，确定暂停「${record.name}」？`"
                  @confirm="runStatusAction(record, 'pause')"
                >
                  <Button
                    :loading="acting === `pause-${record.id}`"
                    danger
                    size="small"
                    type="link"
                  >
                    暂停
                  </Button>
                </Popconfirm>
                <Button
                  v-if="
                    canPublish &&
                    planActionsFor(record.status).includes('resume')
                  "
                  :loading="acting === `resume-${record.id}`"
                  size="small"
                  type="link"
                  @click="runStatusAction(record, 'resume')"
                >
                  恢复
                </Button>
                <Popconfirm
                  v-if="
                    canPublish &&
                    planActionsFor(record.status).includes('disable')
                  "
                  :title="`停用后不再允许入组（终态），在组患者沿用原版本继续，确定停用「${record.name}」？`"
                  @confirm="runStatusAction(record, 'disable')"
                >
                  <Button
                    :loading="acting === `disable-${record.id}`"
                    danger
                    size="small"
                    type="link"
                  >
                    停用
                  </Button>
                </Popconfirm>
              </template>
            </template>
          </Table>
          <Pagination
            v-model:current="pageNo"
            class="pager"
            :page-size="pageSize"
            :show-size-changer="false"
            :total="plans.length"
          />
        </template>
      </Spin>
    </Card>

    <Modal
      v-model:open="createOpen"
      :confirm-loading="creating"
      title="新建计划"
      width="860px"
      @ok="submitCreate"
    >
      <Alert
        v-if="createErrors.length > 0"
        :message="createErrors.join('；')"
        show-icon
        type="error"
        style="margin-bottom: 12px"
      />
      <Form layout="vertical">
        <div class="form-grid">
          <FormItem label="计划编码" required>
            <Input
              v-model:value="createForm.code"
              maxlength="64"
              placeholder="本院唯一的计划族编码，如 fu_annual；创建后不可改"
            />
          </FormItem>
          <FormItem label="计划名称" required>
            <Input
              v-model:value="createForm.name"
              maxlength="128"
              placeholder="如：冠心病术后年度随访（示例）"
            />
          </FormItem>
          <FormItem label="起算日期" required>
            <Select v-model:value="createForm.startDateField">
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
            <Select v-model:value="createForm.cycleType">
              <SelectOption value="ONGOING">长期（不设结束年度）</SelectOption>
              <SelectOption value="YEARS">固定 N 年</SelectOption>
            </Select>
          </FormItem>
          <FormItem
            v-if="createForm.cycleType === 'YEARS'"
            label="管理周期年数"
            required
          >
            <InputNumber
              v-model:value="createForm.cycleYears"
              :min="1"
              placeholder="≥1"
              style="width: 100%"
            />
          </FormItem>
          <FormItem class="form-span" label="备注">
            <Textarea
              v-model:value="createForm.remark"
              :rows="2"
              maxlength="255"
              placeholder="选填"
            />
          </FormItem>
        </div>
      </Form>
    </Modal>
  </Page>
</template>

<style scoped>
.toolbar {
  display: flex;
  gap: 8px;
  justify-content: flex-end;
  margin-bottom: 12px;
}

.cell-sub {
  font-size: 12px;
  color: rgb(0 0 0 / 45%);
}

.pager {
  margin-top: 12px;
  text-align: right;
}

/* pages.md §8：新建计划用 4 列弹窗表单（编码 / 名称 / 起算日期 / 管理周期一行） */
.form-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  column-gap: 16px;
}

.form-span {
  grid-column: 1 / -1;
}
</style>
