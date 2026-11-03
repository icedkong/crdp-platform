<script lang="ts" setup>
import type {
  ConditionGroup,
  PatientExportJob,
  PatientExportSubmitted,
  PatientExportValueMode,
  PatientFilterState,
} from '@crdp/api';

import { computed, reactive, ref, watch } from 'vue';

import {
  buildPatientExportPayload,
  isReauthCancelled,
  jobExportStatusColor,
  jobExportStatusText,
  PATIENT_EXPORT_REASON_MAX,
  PATIENT_EXPORT_VALUE_MODE_OPTIONS,
  validatePatientExportForm,
} from '@crdp/api';
import {
  Button,
  Form,
  FormItem,
  message,
  Modal,
  RadioButton,
  RadioGroup,
  Space,
  Table,
  TabPane,
  Tabs,
  Tag,
  Textarea,
} from 'ant-design-vue';

import {
  approvePatientExport,
  downloadPatientExport,
  myPatientExports,
  pendingPatientExportApprovals,
  rejectPatientExport,
  submitPatientExport,
} from '#/api/core/patient-export';
import ReauthModal from '#/components/reauth-modal.vue';

/**
 * E13-T09 / A-PAT-009、C-PAT-014（import-export.md §3，入口 = 患者列表「导出」）：
 * 按当前筛选提交导出——租户管理员免审批、普通用户待租户管理员审批（验收「导出需审批」）；
 * 生成后 24 小时内可下载；文件含水印与水印码，姓名 / 病案号为脱敏值（OQ-20）。
 * 提交与下载是敏感操作（E04-T08），经 ReauthModal 携带 X-Reauth-Token；
 * 「待审批」页签对持有 followup:patient:export:approve 的租户管理员可见。
 * 检索词不进导出条件（可能是患者姓名，与保存的筛选同口径）。
 */
const props = defineProps<{
  /** 生效的高级检索条件树（与列表最近一次查询一致）。 */
  advancedCondition?: ConditionGroup | null;
  /** 提交时是否可审批（followup:patient:export:approve）。 */
  canApprove?: boolean;
  /** 生效的列表筛选（与列表最近一次查询一致，A-PAT-009「按当前筛选」）。 */
  filters: PatientFilterState;
}>();

const open = defineModel<boolean>('open', { default: false });

const reauthModal = ref<InstanceType<typeof ReauthModal>>();

// ---------------------------------------------------------------- 提交（当前筛选 → 导出条件）

const form = reactive({
  reason: '',
  valueMode: 'LABEL' as PatientExportValueMode,
});
const submitting = ref(false);

const conditionHint = computed(() => {
  const advanced =
    props.advancedCondition !== null &&
    props.advancedCondition !== undefined &&
    props.advancedCondition.items.length > 0
      ? ' + 高级检索条件'
      : '';
  return `当前列表筛选${advanced}（姓名 / 病案号导出脱敏值）`;
});

async function submitExport() {
  const error = validatePatientExportForm(form.reason);
  if (error) {
    message.warning(error);
    return;
  }
  submitting.value = true;
  try {
    const submitted = await reauthModal.value?.run((headers) =>
      submitPatientExport(
        buildPatientExportPayload(
          props.filters,
          props.advancedCondition ?? null,
          form.valueMode,
          form.reason,
        ),
        headers,
      ),
    );
    if (!submitted) {
      return;
    }
    const result = submitted as PatientExportSubmitted;
    message.success(
      result.status === 'GENERATING'
        ? '导出已提交，文件生成后可在「我的导出」下载'
        : '导出申请已提交，待租户管理员审批',
    );
    activeTab.value = 'mine';
    reloadMine();
  } catch (error) {
    if (isReauthCancelled(error)) {
      return;
    }
    message.error((error as { message?: string }).message ?? '提交失败');
  } finally {
    submitting.value = false;
  }
}

// ---------------------------------------------------------------- 我的导出与待审批

const activeTab = ref('submit');
const mine = ref<PatientExportJob[]>([]);
const mineLoading = ref(false);
const pending = ref<PatientExportJob[]>([]);
const pendingLoading = ref(false);
const deciding = ref(false);
const commentModal = reactive({
  approve: true,
  comment: '',
  job: null as null | PatientExportJob,
  visible: false,
});

async function reloadMine() {
  mineLoading.value = true;
  try {
    const page = await myPatientExports(1, 100);
    mine.value = page.items;
  } finally {
    mineLoading.value = false;
  }
}

async function reloadPending() {
  pendingLoading.value = true;
  try {
    const page = await pendingPatientExportApprovals(1, 100);
    pending.value = page.items;
  } finally {
    pendingLoading.value = false;
  }
}

async function download(job: PatientExportJob) {
  try {
    // 下载同为敏感操作（E04-T08）：二次认证窗口内复用同一令牌
    await reauthModal.value?.run((headers) =>
      downloadPatientExport(
        Number(job.id),
        String(job.fileName ?? ''),
        headers,
      ),
    );
    message.success('已下载（含水印与水印码，24 小时内可重复下载）');
  } catch (error) {
    if (isReauthCancelled(error)) {
      return;
    }
    message.error((error as { message?: string }).message ?? '下载失败');
  }
}

function askDecision(job: PatientExportJob, approve: boolean) {
  commentModal.job = job;
  commentModal.approve = approve;
  commentModal.comment = '';
  commentModal.visible = true;
}

async function decide() {
  if (!commentModal.job) return;
  deciding.value = true;
  try {
    if (commentModal.approve) {
      await approvePatientExport(
        Number(commentModal.job.id),
        commentModal.comment || undefined,
      );
      message.success('已通过，文件开始生成');
    } else {
      await rejectPatientExport(
        Number(commentModal.job.id),
        commentModal.comment || undefined,
      );
      message.success('已驳回');
    }
    commentModal.visible = false;
    reloadPending();
  } catch (error) {
    message.error((error as { message?: string }).message ?? '操作失败');
  } finally {
    deciding.value = false;
  }
}

const jobColumns = [
  { dataIndex: 'id', title: '申请号', width: 80 },
  { dataIndex: 'status', title: '状态', width: 100 },
  { dataIndex: 'rowCount', title: '行数', width: 80 },
  { dataIndex: 'fileName', title: '文件' },
  { dataIndex: 'createdAt', title: '申请时间', width: 170 },
  { dataIndex: 'action', title: '操作', width: 130 },
];

const approvalColumns = [
  { dataIndex: 'id', title: '申请号', width: 80 },
  { dataIndex: 'applicantName', title: '申请人', width: 120 },
  { dataIndex: 'applicantDisplayName', title: '姓名', width: 110 },
  { dataIndex: 'reason', title: '申请理由' },
  { dataIndex: 'createdAt', title: '申请时间', width: 170 },
  { dataIndex: 'action', title: '操作', width: 150 },
];

watch(open, (value) => {
  if (!value) return;
  form.reason = '';
  form.valueMode = 'LABEL';
  activeTab.value = 'submit';
  reloadMine();
});

watch(activeTab, (tab) => {
  if (tab === 'pending' && props.canApprove) {
    reloadPending();
  }
});
</script>

<template>
  <Modal
    :footer="null"
    :open="open"
    title="导出患者数据"
    :width="720"
    @cancel="open = false"
  >
    <Tabs v-model:active-key="activeTab.value">
      <TabPane key="submit" tab="提交导出">
        <Form layout="vertical" @submit.prevent>
          <FormItem label="导出范围">
            <span>患者列表（按当前筛选；基线 / 随访数据随后续版本开放）</span>
          </FormItem>
          <FormItem label="值模式（A-PAT-009 可选编码或显示名）">
            <RadioGroup v-model:value="form.valueMode">
              <RadioButton
                v-for="item in PATIENT_EXPORT_VALUE_MODE_OPTIONS"
                :key="item.value"
                :value="item.value"
              >
                {{ item.label }}
              </RadioButton>
            </RadioGroup>
          </FormItem>
          <FormItem label="导出条件">
            <Space direction="vertical" :size="4">
              <span>{{ conditionHint }}</span>
              <span class="hint">检索词不进入导出条件</span>
              <span class="hint">导出内容按您的数据范围计算</span>
            </Space>
          </FormItem>
          <FormItem
            :label="`申请理由（选填，最多 ${PATIENT_EXPORT_REASON_MAX} 字）`"
          >
            <Textarea
              v-model:value="form.reason"
              :maxlength="PATIENT_EXPORT_REASON_MAX"
              :rows="3"
              show-count
            />
          </FormItem>
          <Button
            :loading="submitting"
            type="primary"
            data-testid="patient-export-submit"
            @click="submitExport"
          >
            提交导出（需身份验证）
          </Button>
        </Form>
      </TabPane>
      <TabPane key="mine" tab="我的导出">
        <Table
          :columns="jobColumns"
          :data-source="mine"
          :loading="mineLoading"
          :pagination="false"
          row-key="id"
          size="small"
        >
          <template #bodyCell="{ column, record }">
            <Tag
              v-if="column.dataIndex === 'status'"
              :color="jobExportStatusColor(record.status)"
            >
              {{ jobExportStatusText(record.status) }}
            </Tag>
            <span v-else-if="column.dataIndex === 'rowCount'">
              {{ record.rowCount ?? '—' }}
            </span>
            <span v-else-if="column.dataIndex === 'fileName'">
              {{ record.fileName ?? '—' }}
            </span>
            <template v-else-if="column.dataIndex === 'action'">
              <Button
                v-if="record.status === 'READY'"
                size="small"
                type="link"
                @click="download(record)"
              >
                下载
              </Button>
              <span v-else-if="record.status === 'FAILED'" class="hint">
                {{ record.failReason ?? '生成失败' }}
              </span>
              <span v-else class="hint">—</span>
            </template>
          </template>
        </Table>
      </TabPane>
      <TabPane v-if="canApprove" key="pending" tab="待审批">
        <Table
          :columns="approvalColumns"
          :data-source="pending"
          :loading="pendingLoading"
          :pagination="false"
          row-key="id"
          size="small"
        >
          <template #bodyCell="{ column, record }">
            <template v-if="column.dataIndex === 'action'">
              <Button
                size="small"
                type="link"
                @click="askDecision(record, true)"
              >
                通过
              </Button>
              <Button
                danger
                size="small"
                type="link"
                @click="askDecision(record, false)"
              >
                驳回
              </Button>
            </template>
          </template>
        </Table>
      </TabPane>
    </Tabs>

    <Modal
      :confirm-loading="deciding"
      :open="commentModal.visible"
      :title="commentModal.approve ? '通过导出申请' : '驳回导出申请'"
      :width="480"
      cancel-text="取消"
      :ok-text="commentModal.approve ? '通过' : '驳回'"
      :ok-button-props="{ danger: !commentModal.approve }"
      @cancel="commentModal.visible = false"
      @ok="decide"
    >
      <Textarea
        v-model:value="commentModal.comment"
        :maxlength="200"
        :rows="3"
        placeholder="审批意见（选填）"
        show-count
      />
    </Modal>

    <ReauthModal ref="reauthModal" />
  </Modal>
</template>

<style scoped>
.hint {
  font-size: 12px;
  color: rgb(0 0 0 / 45%);
}
</style>
