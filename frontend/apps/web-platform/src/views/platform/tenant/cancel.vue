<script lang="ts" setup>
import type { TenantCancelOverview } from '@crdp/api';

import type { ApplyFormModel, RetentionFormItem } from './cancel';

import { computed, onMounted, reactive, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';

import { Page } from '@vben/common-ui';

import { StatusTag } from '@crdp/status-tag';
import {
  Alert,
  Button,
  Card,
  Col,
  DatePicker,
  Descriptions,
  DescriptionsItem,
  Form,
  FormItem,
  Input,
  message,
  Modal,
  Popconfirm,
  Row,
  Select,
  Space,
  Spin,
  Step,
  Steps,
  Table,
  Upload,
} from 'ant-design-vue';
import dayjs from 'dayjs';

import { listPlatformAccounts } from '#/api/core/platform-account';
import {
  ackTenantExport,
  applyTenantCancel,
  approveTenantCancel,
  destroyTenantCancel,
  destroyTenantRetention,
  downloadTenantCertificate,
  downloadTenantExport,
  generateTenantExport,
  revokeTenantCancel,
  saveTenantRetention,
  sealTenantRetention,
  tenantCancelOverview,
  uploadCancelAck,
  uploadCancelConfirmation,
} from '#/api/core/tenant-cancel';
import ReauthModal from '#/components/reauth-modal.vue';
import { usePermission } from '#/reference/use-permission';

import {
  canAck,
  canApply,
  canApprove,
  CANCEL_STAGE_TEXT,
  CANCEL_STEPS,
  canDestroy,
  canGenerateExport,
  canRevoke,
  canSeal,
  coolingRemainingDays,
  EMPTY_APPLY_FORM,
  EMPTY_RETENTION_ITEM,
  EXPORT_LINK_HINT,
  exportSummaryRows,
  RETENTION_STATUS_TEXT,
  retentionDue,
  sizeText,
  stepIndex,
  toRetentionSaveItems,
  validateApplyForm,
  validateRetentionItems,
} from './cancel';
import { statusText } from './detail';

defineOptions({ name: 'PlatformTenantCancel' });

const route = useRoute();
const router = useRouter();
const tenantId = Number(route.params.id);
const { hasPermission } = usePermission();
const canCancel = hasPermission('platform:tenant:cancel');
const canApprovePerm = hasPermission('platform:tenant:cancel:approve');
const canExport = hasPermission('platform:tenant:export');
const canRetain = hasPermission('platform:tenant:retain');
const canDestroyPerm = hasPermission('platform:tenant:destroy');

const reauthModal = ref<InstanceType<typeof ReauthModal>>();
const overview = ref<null | TenantCancelOverview>(null);
const loading = ref(false);
const errorMessage = ref('');
const today = new Date().toISOString().slice(0, 10);

// 复核人下拉（销毁 / 期满销毁双人复核）
const platformAccounts = ref<{ id: number; label: string }[]>([]);

async function load() {
  loading.value = true;
  errorMessage.value = '';
  try {
    overview.value = await tenantCancelOverview(tenantId);
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : String(error);
  } finally {
    loading.value = false;
  }
}

async function loadAccounts() {
  try {
    const accounts = await listPlatformAccounts();
    platformAccounts.value = accounts
      .filter((item: Record<string, any>) => item.status === 'ENABLED')
      .map((item: Record<string, any>) => ({
        id: Number(item.id),
        label: `${item.displayName}（${item.username}）`,
      }));
  } catch {
    platformAccounts.value = [];
  }
}

const flow = computed(() => overview.value?.flow ?? null);
const registeredCount = computed(
  () =>
    (overview.value?.retentionItems ?? []).filter(
      (item) => item.status === 'REGISTERED',
    ).length,
);

const retentionColumns = [
  { title: '保留范围', dataIndex: 'scope', key: 'scope' },
  { title: '依据', dataIndex: 'legalBasis', key: 'legalBasis' },
  { title: '封存表', key: 'tables' },
  { title: '保留期满日', dataIndex: 'retainUntil', key: 'retainUntil' },
  { title: '状态', key: 'status' },
  { title: '补充证明', key: 'certificate' },
  { title: '操作', key: 'action' },
];

const certificateColumns = [
  { title: '证明编号', dataIndex: 'certNo', key: 'certNo' },
  { title: '类型', key: 'kind' },
  { title: '执行人', dataIndex: 'executedByName', key: 'executedByName' },
  { title: '复核人', dataIndex: 'reviewedByName', key: 'reviewedByName' },
  { title: '执行时间', dataIndex: 'executedAt', key: 'executedAt' },
  { title: '操作', key: 'action' },
];

// ---------------------------------------------------------------- 发起申请（PLT-TEN-018）

const applyOpen = ref(false);
const applySaving = ref(false);
const applyError = ref('');
const applyForm = reactive<ApplyFormModel>({ ...EMPTY_APPLY_FORM });
const retentionDraft = ref<RetentionFormItem[]>([]);
const retentionErrors = computed(() =>
  validateRetentionItems(retentionDraft.value, today),
);
const applyErrors = computed(() => validateApplyForm(applyForm));

function draftFrom(
  items: TenantCancelOverview['retentionItems'],
): RetentionFormItem[] {
  return items
    .filter((item) => item.status === 'REGISTERED')
    .map((item) => ({
      legalBasis: item.legalBasis,
      retainUntil: item.retainUntil,
      scope: item.scope,
      tablesCsv: item.tables.join(','),
    }));
}

function openApply() {
  applyForm.reason = '';
  applyForm.confirmationFileId = null;
  applyForm.confirmationFileName = '';
  applyError.value = '';
  retentionDraft.value = draftFrom(overview.value?.retentionItems ?? []);
  applyOpen.value = true;
}

async function onConfirmationUpload(file: File) {
  try {
    const uploaded = await uploadCancelConfirmation(tenantId, file);
    applyForm.confirmationFileId = uploaded.id;
    applyForm.confirmationFileName = uploaded.fileName;
  } catch (error) {
    applyError.value = error instanceof Error ? error.message : String(error);
  }
  return false;
}

async function submitApply() {
  if (Object.keys(applyErrors.value).length > 0) return;
  if (retentionErrors.value.some((error) => error !== '')) return;
  applySaving.value = true;
  applyError.value = '';
  try {
    await applyTenantCancel(tenantId, {
      confirmationFileId: applyForm.confirmationFileId as number,
      reason: applyForm.reason.trim(),
      retentionItems: toRetentionSaveItems(retentionDraft.value).map(
        (item) => ({
          legalBasis: item.legalBasis,
          retainUntil: item.retainUntil,
          scope: item.scope,
        }),
      ),
    });
    applyOpen.value = false;
    message.success('注销申请已提交，待另一名平台管理员审批');
    await load();
  } catch (error) {
    applyError.value = error instanceof Error ? error.message : String(error);
  } finally {
    applySaving.value = false;
  }
}

// ---------------------------------------------------------------- 审批 / 撤销（PLT-TEN-018）

const approveOpen = ref(false);
const approveSaving = ref(false);
const approveError = ref('');
const approveNote = ref('');

function openApprove() {
  approveNote.value = '';
  approveError.value = '';
  approveOpen.value = true;
}

async function submitApprove(decision: 'APPROVE' | 'REJECT') {
  approveSaving.value = true;
  approveError.value = '';
  try {
    await approveTenantCancel(tenantId, {
      decision,
      ...(approveNote.value.trim() ? { note: approveNote.value.trim() } : {}),
    });
    approveOpen.value = false;
    message.success(
      decision === 'APPROVE'
        ? '审批通过：租户进入注销中，冷静期 30 天，期内可撤销'
        : '已驳回：租户保持停用，可重新发起',
    );
    await load();
  } catch (error) {
    approveError.value = error instanceof Error ? error.message : String(error);
  } finally {
    approveSaving.value = false;
  }
}

const revokeOpen = ref(false);
const revokeSaving = ref(false);
const revokeError = ref('');
const revokeReason = ref('');

function openRevoke() {
  revokeReason.value = '';
  revokeError.value = '';
  revokeOpen.value = true;
}

async function submitRevoke() {
  if (!revokeReason.value.trim()) {
    revokeError.value = '必须填写撤销原因';
    return;
  }
  revokeSaving.value = true;
  revokeError.value = '';
  try {
    await revokeTenantCancel(tenantId, revokeReason.value.trim());
    revokeOpen.value = false;
    message.success('已撤销：租户回到停用，可重新发起注销');
    await load();
  } catch (error) {
    revokeError.value = error instanceof Error ? error.message : String(error);
  } finally {
    revokeSaving.value = false;
  }
}

// ---------------------------------------------------------------- 迁出包（PLT-TEN-019）

const generating = ref(false);

async function generateExport() {
  generating.value = true;
  try {
    const result = await generateTenantExport(tenantId);
    message.success(
      `迁出包已生成：${result.tableCount} 张表、原始文件 ${result.fileCount} 个（加密包 ${sizeText(result.sizeBytes)}）；${EXPORT_LINK_HINT}`,
    );
    await load();
  } catch (error) {
    message.error(error instanceof Error ? error.message : String(error));
  } finally {
    generating.value = false;
  }
}

async function downloadExport(item: Record<string, any>) {
  try {
    await reauthModal.value?.run((headers) =>
      downloadTenantExport(
        tenantId,
        Number(item.id),
        String(item.packageNo),
        headers,
      ),
    );
    await load();
  } catch (error) {
    message.error(error instanceof Error ? error.message : String(error));
  }
}

const ackOpen = ref(false);
const ackSaving = ref(false);
const ackError = ref('');
const ackNote = ref('');
const ackFileId = ref<null | number>(null);
const ackFileName = ref('');

function openAck() {
  ackNote.value = '';
  ackFileId.value = null;
  ackFileName.value = '';
  ackError.value = '';
  ackOpen.value = true;
}

async function onAckUpload(file: File) {
  try {
    const uploaded = await uploadCancelAck(tenantId, file);
    ackFileId.value = uploaded.id;
    ackFileName.value = uploaded.fileName;
  } catch (error) {
    ackError.value = error instanceof Error ? error.message : String(error);
  }
  return false;
}

async function submitAck() {
  if (!ackNote.value.trim()) {
    ackError.value = '必须填写签收说明';
    return;
  }
  ackSaving.value = true;
  ackError.value = '';
  try {
    const latest = (overview.value?.exports ?? [])[0];
    if (!latest) {
      ackError.value = '没有可签收的迁出包';
      return;
    }
    await ackTenantExport(
      tenantId,
      latest.id,
      ackNote.value.trim(),
      ackFileId.value ?? undefined,
    );
    ackOpen.value = false;
    message.success('已签收：封存保留数据后即可执行销毁');
    await load();
  } catch (error) {
    ackError.value = error instanceof Error ? error.message : String(error);
  } finally {
    ackSaving.value = false;
  }
}

// ---------------------------------------------------------------- 法定保留封存（PLT-TEN-021）

const retentionSaveOpen = ref(false);
const retentionSaving = ref(false);
const retentionSaveError = ref('');
const retentionDraftEdit = ref<RetentionFormItem[]>([]);
const retentionEditErrors = computed(() =>
  validateRetentionItems(retentionDraftEdit.value, today),
);

function openRetentionSave() {
  retentionDraftEdit.value = draftFrom(overview.value?.retentionItems ?? []);
  retentionSaveError.value = '';
  retentionSaveOpen.value = true;
}

async function submitRetentionSave() {
  if (retentionEditErrors.value.some((error) => error !== '')) return;
  retentionSaving.value = true;
  retentionSaveError.value = '';
  try {
    await saveTenantRetention(tenantId, {
      items: toRetentionSaveItems(retentionDraftEdit.value),
    });
    retentionSaveOpen.value = false;
    message.success('法定保留清单已保存（未封存项整体替换）');
    await load();
  } catch (error) {
    retentionSaveError.value =
      error instanceof Error ? error.message : String(error);
  } finally {
    retentionSaving.value = false;
  }
}

const sealing = ref(false);
const sealOpen = ref(false);
const sealReason = ref('');
const sealError = ref('');

function openSeal() {
  sealReason.value = '';
  sealError.value = '';
  sealOpen.value = true;
}

async function submitSeal() {
  if (!sealReason.value.trim()) {
    sealError.value = '必须填写封存原因';
    return;
  }
  sealing.value = true;
  sealError.value = '';
  try {
    await sealTenantRetention(tenantId, sealReason.value.trim());
    sealOpen.value = false;
    message.success('已封存：独立密码加密；访问须两名平台管理员到场');
    await load();
  } catch (error) {
    sealError.value = error instanceof Error ? error.message : String(error);
  } finally {
    sealing.value = false;
  }
}

const dueOpen = ref(false);
const dueSaving = ref(false);
const dueItem = ref<null | Record<string, any>>(null);
const dueForm = reactive({
  reason: '',
  reviewerId: undefined as number | undefined,
});

function openDue(item: Record<string, any>) {
  dueItem.value = item;
  dueForm.reason = '';
  dueForm.reviewerId = undefined;
  dueOpen.value = true;
}

async function submitDue() {
  if (!dueItem.value || !dueForm.reviewerId || !dueForm.reason.trim()) {
    message.warning('须选择复核人（另一名平台管理员）并填写销毁原因');
    return;
  }
  dueSaving.value = true;
  try {
    await destroyTenantRetention(tenantId, Number(dueItem.value.id), {
      reason: dueForm.reason.trim(),
      reviewerId: dueForm.reviewerId,
    });
    dueOpen.value = false;
    message.success('保留数据已期满销毁，补充销毁证明已出具');
    await load();
  } catch (error) {
    message.error(error instanceof Error ? error.message : String(error));
  } finally {
    dueSaving.value = false;
  }
}

// ---------------------------------------------------------------- 销毁与证明（PLT-TEN-020）

const destroyOpen = ref(false);
const destroySaving = ref(false);
const destroyError = ref('');
const destroyForm = reactive({
  reason: '',
  reviewerId: undefined as number | undefined,
});

function openDestroy() {
  destroyForm.reason = '';
  destroyForm.reviewerId = undefined;
  destroyError.value = '';
  destroyOpen.value = true;
}

async function submitDestroy() {
  if (!destroyForm.reviewerId || !destroyForm.reason.trim()) {
    destroyError.value = '须选择复核人（另一名平台管理员）并填写销毁原因';
    return;
  }
  destroySaving.value = true;
  destroyError.value = '';
  try {
    // 敏感操作（PLT-AUTH-012）：先二次认证（X-Reauth-Token）
    await reauthModal.value?.run((headers) =>
      destroyTenantCancel(
        tenantId,
        {
          reason: destroyForm.reason.trim(),
          reviewerId: destroyForm.reviewerId as number,
        },
        headers,
      ),
    );
    destroyOpen.value = false;
    message.success('数据销毁已执行，销毁证明已出具（可下载 PDF）');
    await load();
  } catch (error) {
    destroyError.value = error instanceof Error ? error.message : String(error);
  } finally {
    destroySaving.value = false;
  }
}

async function downloadCert(item: Record<string, any>) {
  try {
    await reauthModal.value?.run((headers) =>
      downloadTenantCertificate(
        tenantId,
        Number(item.id),
        String(item.certNo),
        headers,
      ),
    );
  } catch (error) {
    message.error(error instanceof Error ? error.message : String(error));
  }
}

onMounted(async () => {
  await load();
  await loadAccounts();
});
</script>

<template>
  <Page
    :title="
      flow?.tenantName
        ? `${flow.tenantName} · 注销与数据处理`
        : '注销与数据处理'
    "
    :description="`注销审批、冷静期、迁出交付、法定保留封存与销毁证明（PLT-TEN-018~021）；${EXPORT_LINK_HINT}，解压密码经短信另行发送。`"
  >
    <Card :bordered="false" class="crdp-card">
      <Spin :spinning="loading">
        <Alert
          v-if="errorMessage"
          :message="`加载失败：${errorMessage}`"
          show-icon
          type="error"
          class="mb-4"
        />
        <template v-if="flow">
          <Steps :current="stepIndex(flow.stage)" class="mb-6" size="small">
            <Step
              v-for="step in CANCEL_STEPS"
              :key="step.key"
              :title="step.title"
            />
          </Steps>

          <Descriptions bordered :column="3" class="mb-6" size="middle">
            <DescriptionsItem label="租户">
              {{ flow.tenantName }}（{{ flow.tenantCode }}）
            </DescriptionsItem>
            <DescriptionsItem label="租户状态">
              <StatusTag :status="statusText(flow.tenantStatus)" />
            </DescriptionsItem>
            <DescriptionsItem label="注销阶段">
              {{ flow.stage ? CANCEL_STAGE_TEXT[flow.stage] : '未发起' }}
            </DescriptionsItem>
            <DescriptionsItem label="发起人 / 时间">
              {{ flow.appliedByName ?? '—' }} · {{ flow.appliedAt ?? '—' }}
            </DescriptionsItem>
            <DescriptionsItem label="审批人 / 时间">
              {{ flow.approvedByName ?? '—' }} · {{ flow.approvedAt ?? '—' }}
            </DescriptionsItem>
            <DescriptionsItem label="冷静期">
              <template v-if="flow.coolingUntil">
                {{ flow.coolingUntil }} 截止
                <template v-if="flow.stage === 'COOLING'">
                  （剩余
                  {{ coolingRemainingDays(flow.coolingUntil) ?? '—' }} 天）
                </template>
              </template>
              <template v-else>—</template>
            </DescriptionsItem>
            <DescriptionsItem label="注销原因" :span="2">
              {{ flow.reason ?? '—' }}
            </DescriptionsItem>
            <DescriptionsItem label="医院书面确认">
              {{ flow.confirmationFile ? flow.confirmationFile.fileName : '—' }}
            </DescriptionsItem>
            <DescriptionsItem
              v-if="flow.approveNote"
              label="审批意见"
              :span="3"
            >
              {{ flow.approveNote }}
            </DescriptionsItem>
            <DescriptionsItem v-if="flow.revokeReason" label="撤销" :span="3">
              {{ flow.revokedByName }} · {{ flow.revokedAt }}：{{
                flow.revokeReason
              }}
            </DescriptionsItem>
          </Descriptions>

          <Space class="mb-6" wrap>
            <Button
              v-if="canCancel && canApply(flow)"
              type="primary"
              data-testid="cancel-apply"
              @click="openApply"
            >
              发起注销申请
            </Button>
            <Button
              v-if="canApprovePerm && canApprove(flow)"
              data-testid="cancel-approve"
              @click="openApprove"
            >
              审批注销
            </Button>
            <Button
              v-if="canCancel && canRevoke(flow)"
              danger
              data-testid="cancel-revoke"
              @click="openRevoke"
            >
              冷静期内撤销
            </Button>
            <Button
              v-if="canExport && canGenerateExport(flow)"
              :loading="generating"
              data-testid="cancel-export"
              @click="generateExport"
            >
              {{ flow.stage === 'EXPORTED' ? '重新生成迁出包' : '生成迁出包' }}
            </Button>
            <Button
              v-if="canExport && canAck(flow)"
              data-testid="cancel-ack"
              @click="openAck"
            >
              医院签收
            </Button>
          </Space>

          <h4 class="section-title">迁出包</h4>
          <Alert
            v-if="(overview?.exports ?? []).length === 0"
            message="尚未生成迁出包；冷静期满后可生成（每表 CSV+JSON、数据字典、原始文件、清单 SHA-256，整包 AES-256 加密）。"
            show-icon
            type="info"
            class="mb-4"
          />
          <Descriptions
            v-for="item in overview?.exports ?? []"
            :key="item.id"
            bordered
            class="mb-4"
            :column="2"
            size="small"
          >
            <DescriptionsItem
              v-for="row in exportSummaryRows(item)"
              :key="row[0]"
              :label="row[0]"
            >
              {{ row[1] }}
            </DescriptionsItem>
            <DescriptionsItem label="操作">
              <Button
                size="small"
                type="link"
                :disabled="!canExport"
                @click="downloadExport(item as unknown as Record<string, any>)"
              >
                下载（.crdpa，须二次认证）
              </Button>
            </DescriptionsItem>
          </Descriptions>

          <h4 class="section-title">法定保留数据（封存清单）</h4>
          <Table
            :columns="retentionColumns"
            :data-source="overview?.retentionItems ?? []"
            :pagination="false"
            row-key="id"
            size="small"
            class="mb-4"
          >
            <template #bodyCell="{ column, record }">
              <template v-if="column.key === 'status'">
                {{ RETENTION_STATUS_TEXT[(record as any).status] }}
              </template>
              <template v-else-if="column.key === 'tables'">
                {{
                  (record as any).tables.length > 0
                    ? (record as any).tables.join('、')
                    : '（仅登记清单）'
                }}
              </template>
              <template v-else-if="column.key === 'certificate'">
                {{
                  (record as any).certificateId
                    ? `证明 #${(record as any).certificateId}`
                    : '—'
                }}
              </template>
              <template v-else-if="column.key === 'action'">
                <Popconfirm
                  v-if="canRetain && retentionDue(record as any, today)"
                  title="保留期满销毁将删除封存包并补充出具销毁证明，确认继续？"
                  ok-text="继续"
                  cancel-text="取消"
                  @confirm="openDue(record as any)"
                >
                  <Button danger size="small" type="link">期满销毁</Button>
                </Popconfirm>
                <span v-else>—</span>
              </template>
            </template>
          </Table>
          <Space
            v-if="
              flow.stage === 'COOLING' ||
              flow.stage === 'EXPORTED' ||
              flow.stage === 'ACKED'
            "
            class="mb-6"
            wrap
          >
            <Button
              v-if="canRetain"
              data-testid="retention-save"
              @click="openRetentionSave"
            >
              维护保留清单
            </Button>
            <Button
              v-if="canRetain && canSeal(flow, registeredCount)"
              data-testid="retention-seal"
              @click="openSeal"
            >
              封存保留数据
            </Button>
          </Space>

          <h4 class="section-title">销毁与证明</h4>
          <Alert
            v-if="
              flow.stage === 'ACKED' &&
              (overview?.retentionItems ?? []).some(
                (item) => item.status === 'REGISTERED',
              )
            "
            message="存在未封存的法定保留数据：须先封存（或从清单移除）才能执行销毁。"
            show-icon
            type="warning"
            class="mb-4"
          />
          <Space class="mb-4" wrap>
            <Button
              v-if="canDestroyPerm && canDestroy(flow)"
              danger
              data-testid="cancel-destroy"
              @click="openDestroy"
            >
              执行数据销毁
            </Button>
          </Space>
          <Table
            v-if="(overview?.certificates ?? []).length > 0"
            :columns="certificateColumns"
            :data-source="overview?.certificates ?? []"
            :pagination="false"
            row-key="id"
            size="small"
          >
            <template #bodyCell="{ column, record }">
              <template v-if="column.key === 'kind'">
                {{
                  (record as any).kind === 'TENANT'
                    ? '租户整体销毁'
                    : '保留数据期满销毁'
                }}
              </template>
              <template v-else-if="column.key === 'action'">
                <Button
                  size="small"
                  type="link"
                  :disabled="!canDestroyPerm"
                  @click="downloadCert(record as any)"
                >
                  下载证明 PDF（须二次认证）
                </Button>
              </template>
            </template>
          </Table>
        </template>
      </Spin>
      <Space class="mt-6">
        <Button @click="router.push(`/platform/tenant/${tenantId}`)">
          返回租户详情
        </Button>
      </Space>
    </Card>

    <!-- 发起注销（PLT-TEN-018） -->
    <Modal
      v-model:open="applyOpen"
      :confirm-loading="applySaving"
      title="发起注销申请"
      :width="780"
      :ok-button-props="{
        disabled:
          Object.keys(applyErrors).length > 0 ||
          retentionErrors.some((error) => error !== ''),
      }"
      ok-text="提交申请"
      cancel-text="取消"
      @ok="submitApply"
    >
      <Alert
        v-if="applyError"
        :message="`提交失败：${applyError}`"
        show-icon
        type="error"
        class="mb-4"
      />
      <Form layout="vertical" class="crdp-form">
        <FormItem
          label="注销原因"
          required
          :validate-status="applyErrors.reason ? 'error' : undefined"
          :help="applyErrors.reason"
        >
          <Input
            v-model:value="applyForm.reason"
            :maxlength="500"
            placeholder="如：医院终止合作（示例）"
            show-count
            type="textarea"
          />
        </FormItem>
        <FormItem
          label="医院书面确认"
          required
          :validate-status="applyErrors.confirmation ? 'error' : undefined"
          :help="applyErrors.confirmation ?? '支持 PDF / JPG / PNG，最大 20MB'"
        >
          <Upload
            :before-upload="onConfirmationUpload"
            :max-count="1"
            accept=".pdf,.jpg,.jpeg,.png"
          >
            <Button>
              {{
                applyForm.confirmationFileId
                  ? `已上传：${applyForm.confirmationFileName}`
                  : '选择书面确认文件'
              }}
            </Button>
          </Upload>
        </FormItem>
        <FormItem label="需依法或按合同保留的数据（可留空；销毁前须封存）">
          <div
            v-for="(item, index) in retentionDraft"
            :key="index"
            class="retention-row"
          >
            <Row :gutter="8">
              <Col :span="7">
                <Input
                  v-model:value="item.scope"
                  :maxlength="200"
                  placeholder="保留范围（如 RCT 原始资料）"
                />
              </Col>
              <Col :span="7">
                <Input
                  v-model:value="item.legalBasis"
                  :maxlength="200"
                  placeholder="法律或合同依据"
                />
              </Col>
              <Col :span="5">
                <DatePicker
                  :value="
                    item.retainUntil ? dayjs(item.retainUntil) : undefined
                  "
                  placeholder="保留期满日"
                  style="width: 100%"
                  value-format="YYYY-MM-DD"
                  @change="
                    (_: unknown, date: string | string[]) =>
                      (item.retainUntil = Array.isArray(date)
                        ? ''
                        : (date ?? ''))
                  "
                />
              </Col>
              <Col :span="4">
                <Input
                  v-model:value="item.tablesCsv"
                  placeholder="封存表（逗号分隔，可空）"
                />
              </Col>
              <Col :span="1">
                <Button
                  danger
                  size="small"
                  type="link"
                  @click="retentionDraft.splice(index, 1)"
                >
                  删除
                </Button>
              </Col>
            </Row>
            <div v-if="retentionErrors[index]" class="retention-error">
              {{ retentionErrors[index] }}
            </div>
          </div>
          <Button
            size="small"
            @click="retentionDraft.push({ ...EMPTY_RETENTION_ITEM })"
          >
            添加保留项
          </Button>
        </FormItem>
      </Form>
    </Modal>

    <!-- 审批（PLT-TEN-018）：另一名平台管理员 -->
    <Modal
      v-model:open="approveOpen"
      :title="`审批注销${flow?.appliedByName ? `（发起人：${flow.appliedByName}，不能自审）` : ''}`"
      :width="560"
      cancel-text="取消"
    >
      <Alert
        v-if="approveError"
        :message="`审批失败：${approveError}`"
        show-icon
        type="error"
        class="mb-4"
      />
      <Form layout="vertical" class="crdp-form">
        <FormItem label="审批意见">
          <Input
            v-model:value="approveNote"
            :maxlength="500"
            placeholder="如：书面确认已核（示例）"
            type="textarea"
          />
        </FormItem>
      </Form>
      <template #footer>
        <Button @click="approveOpen = false">取消</Button>
        <Button
          danger
          :loading="approveSaving"
          @click="submitApprove('REJECT')"
        >
          驳回
        </Button>
        <Button
          type="primary"
          :loading="approveSaving"
          @click="submitApprove('APPROVE')"
        >
          通过（进入注销中 + 冷静期 30 天）
        </Button>
      </template>
    </Modal>

    <!-- 冷静期内撤销（PLT-TEN-018） -->
    <Modal
      v-model:open="revokeOpen"
      :confirm-loading="revokeSaving"
      title="冷静期内撤销注销"
      :width="520"
      ok-text="确认撤销"
      ok-type="danger"
      cancel-text="取消"
      @ok="submitRevoke"
    >
      <Alert
        v-if="revokeError"
        :message="`撤销失败：${revokeError}`"
        show-icon
        type="error"
        class="mb-4"
      />
      <Alert
        message="撤销后租户回到停用状态；流程可重新发起。冷静期已满则不能撤销。"
        show-icon
        type="warning"
        class="mb-4"
      />
      <Form layout="vertical" class="crdp-form">
        <FormItem label="撤销原因" required>
          <Input
            v-model:value="revokeReason"
            :maxlength="500"
            type="textarea"
          />
        </FormItem>
      </Form>
    </Modal>

    <!-- 医院签收（PLT-TEN-019） -->
    <Modal
      v-model:open="ackOpen"
      :confirm-loading="ackSaving"
      title="医院签收迁出包"
      :width="560"
      ok-text="确认签收"
      cancel-text="取消"
      @ok="submitAck"
    >
      <Alert
        v-if="ackError"
        :message="`签收失败：${ackError}`"
        show-icon
        type="error"
        class="mb-4"
      />
      <Form layout="vertical" class="crdp-form">
        <FormItem
          label="签收说明"
          required
          help="医院校验无误的确认（如：清单与校验和核对一致（示例））"
        >
          <Input v-model:value="ackNote" :maxlength="500" type="textarea" />
        </FormItem>
        <FormItem label="签收单（可上传 PDF / JPG / PNG）">
          <Upload
            :before-upload="onAckUpload"
            :max-count="1"
            accept=".pdf,.jpg,.jpeg,.png"
          >
            <Button>
              {{ ackFileId ? `已上传：${ackFileName}` : '选择签收单文件' }}
            </Button>
          </Upload>
        </FormItem>
      </Form>
    </Modal>

    <!-- 维护保留清单（PLT-TEN-021） -->
    <Modal
      v-model:open="retentionSaveOpen"
      :confirm-loading="retentionSaving"
      title="维护法定保留清单"
      :width="780"
      :ok-button-props="{
        disabled: retentionEditErrors.some((error) => error !== ''),
      }"
      ok-text="保存（替换未封存项）"
      cancel-text="取消"
      @ok="submitRetentionSave"
    >
      <Alert
        v-if="retentionSaveError"
        :message="`保存失败：${retentionSaveError}`"
        show-icon
        type="error"
        class="mb-4"
      />
      <div
        v-for="(item, index) in retentionDraftEdit"
        :key="index"
        class="retention-row"
      >
        <Row :gutter="8">
          <Col :span="7">
            <Input
              v-model:value="item.scope"
              :maxlength="200"
              placeholder="保留范围"
            />
          </Col>
          <Col :span="7">
            <Input
              v-model:value="item.legalBasis"
              :maxlength="200"
              placeholder="法律或合同依据"
            />
          </Col>
          <Col :span="5">
            <DatePicker
              :value="item.retainUntil ? dayjs(item.retainUntil) : undefined"
              placeholder="保留期满日"
              style="width: 100%"
              value-format="YYYY-MM-DD"
              @change="
                (_: unknown, date: string | string[]) =>
                  (item.retainUntil = Array.isArray(date) ? '' : (date ?? ''))
              "
            />
          </Col>
          <Col :span="4">
            <Input
              v-model:value="item.tablesCsv"
              placeholder="封存表（可空）"
            />
          </Col>
          <Col :span="1">
            <Button
              danger
              size="small"
              type="link"
              @click="retentionDraftEdit.splice(index, 1)"
            >
              删除
            </Button>
          </Col>
        </Row>
        <div v-if="retentionEditErrors[index]" class="retention-error">
          {{ retentionEditErrors[index] }}
        </div>
      </div>
      <Button
        size="small"
        @click="retentionDraftEdit.push({ ...EMPTY_RETENTION_ITEM })"
      >
        添加保留项
      </Button>
    </Modal>

    <!-- 封存（PLT-TEN-021） -->
    <Modal
      v-model:open="sealOpen"
      :confirm-loading="sealing"
      title="封存法定保留数据"
      :width="520"
      ok-text="确认封存"
      cancel-text="取消"
      @ok="submitSeal"
    >
      <Alert
        v-if="sealError"
        :message="`封存失败：${sealError}`"
        show-icon
        type="error"
        class="mb-4"
      />
      <Alert
        message="按登记表导出并用独立密码加密封存；销毁前必须完成封存；访问封存包须两名平台管理员到场。"
        show-icon
        type="info"
        class="mb-4"
      />
      <Form layout="vertical" class="crdp-form">
        <FormItem label="封存原因" required>
          <Input v-model:value="sealReason" :maxlength="500" type="textarea" />
        </FormItem>
      </Form>
    </Modal>

    <!-- 保留期满销毁（PLT-TEN-021，双人复核） -->
    <Modal
      v-model:open="dueOpen"
      :confirm-loading="dueSaving"
      title="保留期满销毁（双人复核）"
      :width="520"
      ok-text="确认销毁"
      ok-type="danger"
      cancel-text="取消"
      @ok="submitDue"
    >
      <Form layout="vertical" class="crdp-form">
        <FormItem label="复核人（另一名平台管理员）" required>
          <Select
            v-model:value="dueForm.reviewerId"
            :options="platformAccounts"
            placeholder="选择复核人"
          />
        </FormItem>
        <FormItem label="销毁原因" required>
          <Input
            v-model:value="dueForm.reason"
            :maxlength="500"
            type="textarea"
          />
        </FormItem>
      </Form>
    </Modal>

    <!-- 执行销毁（PLT-TEN-020，双人复核 + 二次认证） -->
    <Modal
      v-model:open="destroyOpen"
      :confirm-loading="destroySaving"
      title="执行数据销毁"
      :width="560"
      ok-text="执行销毁（须二次认证）"
      ok-type="danger"
      cancel-text="取消"
      @ok="submitDestroy"
    >
      <Alert
        v-if="destroyError"
        :message="`销毁失败：${destroyError}`"
        show-icon
        type="error"
        class="mb-4"
      />
      <Alert
        message="将删除租户库与专属账号、清理对象存储与缓存并注销连接池；KMS 密钥计划删除与云备份自然失效为云侧人工步骤，写入销毁证明。平台库保留租户档案与审计。"
        show-icon
        type="warning"
        class="mb-4"
      />
      <Form layout="vertical" class="crdp-form">
        <FormItem label="复核人（另一名平台管理员）" required>
          <Select
            v-model:value="destroyForm.reviewerId"
            :options="platformAccounts"
            placeholder="选择复核人"
          />
        </FormItem>
        <FormItem label="销毁原因" required>
          <Input
            v-model:value="destroyForm.reason"
            :maxlength="500"
            type="textarea"
          />
        </FormItem>
      </Form>
    </Modal>

    <ReauthModal ref="reauthModal" />
  </Page>
</template>

<style scoped>
.section-title {
  margin: 16px 0 8px;
  font-size: 14px;
  font-weight: 600;
}

.retention-row {
  margin-bottom: 8px;
}

.retention-error {
  margin-top: 2px;
  font-size: 12px;
  color: #ff4d4f;
}
</style>
