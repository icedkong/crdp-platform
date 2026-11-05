<script lang="ts" setup>
import type {
  FormSchema,
  RecordPatientBrief,
  RecordTaskContext,
} from '@crdp/api';
import type { CategoryProgress } from '@crdp/rules';

import { computed, onBeforeUnmount, onMounted, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';

import { taskDisplayStatus } from '@crdp/api';
import { FormRenderer } from '@crdp/form-engine';
import { StatusTag } from '@crdp/status-tag';
import {
  Alert,
  Button,
  Card,
  message,
  Modal,
  Result,
  Space,
  Spin,
} from 'ant-design-vue';

import { fetchFormSchema } from '#/api/core/form';
import {
  fetchRecordDetail,
  fetchTaskQueue,
  saveRecordDraft,
  startTask,
  submitRecord,
} from '#/api/core/task';
import CategoryBar from '#/components/followup/category-bar.vue';
import { useBreakpoint } from '#/reference/use-breakpoint';
import { usePermission } from '#/reference/use-permission';

import { queueFilterOfQuery } from '../task/logic';
import {
  bannersOf,
  deathFieldKey,
  diffChanges,
  hasChanges,
  headerSummary,
  headerTitle,
  missingRequiredText,
  nextVisitText,
  readonlyBanner,
  submitSuccessMessage,
} from './logic';

defineOptions({ name: 'FollowupRecordForm' });

/**
 * E15-T02 / A-FU-005、C-FU-020 · 随访工作台（pages.md §5 随访整页表单，新标签页）：
 * 任务开启时已创建草稿记录（/tasks/{id}/start），统一用 recordId 路由。
 *
 * - FormRenderer mode=edit 按模板渲染（分组 / 校验 / 派生 / 带入上次值全部来自模板）；
 *   CategoryBar 竖向锚点 + 已填 / 应填（E11-T11 progress 事件）；
 * - 暂存（Ctrl/⌘+S）→ PUT /records/{id}/draft（changes 只带变更字段），成功提示
 *   「已暂存到服务器（草稿），任务变为进行中」（OQ-77②）；提交（Ctrl/⌘+Enter）→
 *   渲染器硬校验闸门 → 确认弹窗 → POST /records/{id}/submit，成功后任务完成
 *   （A-FU-005 验收「提交后任务完成」）；Enter 不提交；
 * - 上一条 / 下一条顺当前列表（GET /tasks/queue，筛选由列表页随链接带出）；
 *   dirty 时弹「有未保存的修改，离开前怎么处理？」（暂存并离开 / 放弃修改 / 取消）；
 * - 只读：无 followup:record:edit 或记录已提交（整体只读，修改随后续任务）；
 * - 横幅：逾期 / 窗口外 / 补录（pages.md §5.1 文案照抄）。
 *
 * 每 2 分钟自动保存与离线本机草稿（IndexedDB）随 E25-T02；对照分屏与报告单带入
 * （A-ATT-019 / C-FU-017）随 E16 / E17；平板 / 手机响应式随 E24-T02。
 */
const route = useRoute();
const router = useRouter();
const { hasPermission } = usePermission();
const canEdit = computed(() => hasPermission('followup:record:edit'));
const { isMobile } = useBreakpoint();

// ---------------------------------------------------------------- 状态

const recordId = computed(() => String(route.params.recordId ?? ''));
const loading = ref(true);
const schema = ref<FormSchema | null>(null);
const data = ref<Record<string, unknown>>({});
const meta = ref<Record<string, unknown>>({});
/** 上次服务器版本的 data 快照（暂存差异与 dirty 判定的基准）。 */
const savedData = ref<Record<string, unknown>>({});
const version = ref(0);
const status = ref<'DRAFT' | 'SUBMITTED'>('DRAFT');
const context = ref<null | RecordTaskContext>(null);
/** 补录 / 计划外记录的患者摘要与来源（E15-T07：context 为 null 时的头幅与横幅）。 */
const patient = ref<null | RecordPatientBrief>(null);
const source = ref<null | string>(null);
const recordVisitDate = ref<null | string>(null);
const progressItems = ref<CategoryProgress[]>([]);
const activeKey = ref('');
const showErrors = ref(false);
const submitting = ref(false);
const saving = ref(false);
const submitted = ref(false);
const queue = ref<{ nextId: null | string; prevId: null | string }>({
  nextId: null,
  prevId: null,
});
const renderer = ref<InstanceType<typeof FormRenderer> | null>(null);
/** 离开确认（dirty 上一条 / 下一条）：暂存并离开 / 放弃修改 / 取消（pages.md §5.1）。 */
const leaveConfirm = ref<{ open: boolean; taskId: null | string }>({
  open: false,
  taskId: null,
});

const filter = queueFilterOfQuery(route.query);
const readonly = computed(() => !canEdit.value || status.value === 'SUBMITTED');
const banners = computed(() =>
  readonly.value
    ? [readonlyBanner(status.value), ...bannersOf(context.value, visitDate())]
    : bannersOf(context.value, visitDate()),
);

/** 随访日期（窗口外横幅判定用；字段编码 followup_date，与后端口径一致）。 */
function visitDate(): null | string {
  if (!schema.value) return null;
  for (const category of schema.value.categories) {
    for (const field of category.fields) {
      if (field.code === 'followup_date') {
        const value = data.value[field.key];
        return typeof value === 'string' ? value : null;
      }
    }
  }
  return null;
}

const deathSelected = computed(() => {
  const key = deathFieldKey(schema.value);
  if (!key) return false;
  return data.value[key] === 1 || data.value[key] === '1';
});

// ---------------------------------------------------------------- 加载

async function load() {
  loading.value = true;
  try {
    const detail = await fetchRecordDetail(recordId.value);
    schema.value = await fetchFormSchema(detail.formCode, detail.formVersion);
    data.value = { ...detail.data };
    savedData.value = { ...detail.data };
    meta.value = { ...detail.dataMeta };
    version.value = detail.version;
    status.value = detail.status;
    context.value = detail.context ?? null;
    patient.value = detail.patient ?? null;
    source.value = detail.source ?? null;
    recordVisitDate.value = detail.visitDate ?? null;
    if (detail.context) {
      const queueView = await fetchTaskQueue(detail.context.taskId, {
        scope: filter.scope,
        status: filter.status,
        wardIds: filter.wardIds,
      });
      queue.value = {
        nextId: queueView.nextId ?? null,
        prevId: queueView.prevId ?? null,
      };
    }
  } catch {
    message.error('随访记录加载失败，请稍后重试');
  } finally {
    loading.value = false;
  }
}

onMounted(() => {
  void load();
  window.addEventListener('keydown', handleKeydown);
});

onBeforeUnmount(() => {
  window.removeEventListener('keydown', handleKeydown);
});

// ---------------------------------------------------------------- 暂存与提交

async function handleSaveDraft() {
  if (readonly.value || saving.value) return false;
  const changes = diffChanges(savedData.value, data.value);
  if (Object.keys(changes).length === 0) {
    message.info('没有需要暂存的修改');
    return true;
  }
  saving.value = true;
  try {
    const saved = await saveRecordDraft(recordId.value, {
      baseVersion: version.value,
      changes,
    });
    version.value = saved.version;
    savedData.value = { ...data.value };
    message.success('已暂存到服务器（草稿），任务变为进行中');
    return true;
  } catch (error) {
    message.error('暂存失败，请稍后重试');
    console.error(error);
    return false;
  } finally {
    saving.value = false;
  }
}

async function handleSubmit() {
  if (readonly.value || submitting.value || submitted.value) return;
  const result: RendererValidateResult | undefined =
    await renderer.value?.validate();
  if (result && !result.ok) {
    showErrors.value = true;
    message.warning(missingRequiredText(result.missingRequired.length));
    renderer.value?.focusFirstError();
    return;
  }
  Modal.confirm({
    title: '确认提交',
    content: '提交后任务完成，按计划生成下次随访任务。',
    okText: '确认提交',
    cancelText: '取消',
    onOk: () => doSubmit(),
  });
}

async function doSubmit() {
  submitting.value = true;
  try {
    await submitRecord(recordId.value, {
      baseVersion: version.value,
      data: data.value,
      dataMeta: meta.value,
    });
    submitted.value = true;
    status.value = 'SUBMITTED';
    message.success(
      submitSuccessMessage(deathSelected.value, context.value !== null),
    );
  } catch (error) {
    message.error('提交失败，请稍后重试');
    console.error(error);
  } finally {
    submitting.value = false;
  }
}

// ---------------------------------------------------------------- 键盘（Enter 不提交；Ctrl/⌘+S 暂存；Ctrl/⌘+Enter 提交）

function handleKeydown(event: KeyboardEvent) {
  if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === 's') {
    event.preventDefault();
    void handleSaveDraft();
    return;
  }
  if ((event.metaKey || event.ctrlKey) && event.key === 'Enter') {
    event.preventDefault();
    void handleSubmit();
  }
}

// ---------------------------------------------------------------- 上一条 / 下一条（顺当前列表）

function gotoNeighbor(taskId: null | string) {
  if (!taskId) {
    message.info('已经到头了');
    return;
  }
  if (hasChanges(savedData.value, data.value) && !submitted.value) {
    leaveConfirm.value = { open: true, taskId };
    return;
  }
  void openTask(taskId);
}

async function confirmLeaveSave() {
  const taskId = leaveConfirm.value.taskId;
  leaveConfirm.value = { open: false, taskId: null };
  if (!taskId) return;
  const saved = await handleSaveDraft();
  if (saved) {
    await openTask(taskId);
  }
}

async function confirmLeaveDiscard() {
  const taskId = leaveConfirm.value.taskId;
  leaveConfirm.value = { open: false, taskId: null };
  if (taskId) {
    await openTask(taskId);
  }
}

/** 打开相邻任务：未开始的先 start 换 recordId，再本页切换（同一标签页继续录入）。 */
async function openTask(taskId: string) {
  try {
    const detail = await startTask(taskId);
    const query = new URLSearchParams();
    query.set('scope', filter.scope);
    if (filter.status) query.set('status', filter.status.join(','));
    if (filter.wardIds) query.set('wardIds', filter.wardIds.join(','));
    await router.replace(
      `/followup/record/${detail.recordId}?${query.toString()}`,
    );
    submitted.value = false;
    showErrors.value = false;
    await load();
  } catch {
    message.error('打开任务失败，请稍后重试');
  }
}

function backToList() {
  void router.push('/followup/task');
}

// ---------------------------------------------------------------- 分类锚点

function handleJump(key: string) {
  activeKey.value = key;
  renderer.value?.scrollTo(key);
}
</script>

<template>
  <Result v-if="isMobile" status="info" title="请在电脑或平板上操作">
    <template #extra>
      <Button type="primary" @click="router.push('/dashboard')">
        返回首页
      </Button>
    </template>
  </Result>
  <Result
    v-else-if="submitted"
    data-testid="record-submitted"
    status="success"
    :sub-title="submitSuccessMessage(deathSelected, context !== null)"
    title="已提交"
  >
    <template #extra>
      <Space>
        <Button @click="backToList">返回任务列表</Button>
        <Button
          v-if="queue.nextId"
          type="primary"
          @click="gotoNeighbor(queue.nextId)"
        >
          下一条
        </Button>
      </Space>
    </template>
  </Result>
  <div v-else class="page" data-testid="record-form">
    <Card :bordered="false" class="crdp-card">
      <div class="title-row">
        <div>
          <h1 class="crdp-page-title">
            {{ headerTitle(context, patient) }}
          </h1>
          <div class="sub-text">
            {{ headerSummary(context, recordVisitDate) }}
          </div>
        </div>
        <StatusTag
          v-if="context"
          :status="taskDisplayStatus(context)"
          variant="dot"
        />
      </div>

      <Alert
        v-for="banner in banners"
        :key="banner.key"
        :message="banner.text"
        :type="banner.tone"
        show-icon
        :style="{ marginBottom: '8px' }"
      />

      <Spin :spinning="loading">
        <div v-if="schema" class="workbench">
          <div class="anchors">
            <CategoryBar
              :active-key="activeKey"
              :items="progressItems"
              mode="bar"
              show-highlight-count
              @jump="handleJump"
            />
          </div>
          <div class="form-area">
            <FormRenderer
              ref="renderer"
              v-model="data"
              :meta="meta"
              :mode="readonly ? 'readonly' : 'edit'"
              :schema="schema"
              :show-errors="showErrors"
              @progress="progressItems = $event"
            />
            <div v-if="nextVisitText(context)" class="next-visit">
              {{ nextVisitText(context) }}
            </div>
          </div>
        </div>
      </Spin>

      <div class="footer-bar">
        <Space>
          <Button :disabled="!queue.prevId" @click="gotoNeighbor(queue.prevId)">
            ← 上一条
          </Button>
          <Button :disabled="!queue.nextId" @click="gotoNeighbor(queue.nextId)">
            下一条 →
          </Button>
        </Space>
        <Space v-if="!readonly">
          <Button
            :loading="saving"
            data-testid="record-save-draft"
            @click="handleSaveDraft"
          >
            暂存
          </Button>
          <Button
            :loading="submitting"
            data-testid="record-submit"
            type="primary"
            @click="handleSubmit"
          >
            提交
          </Button>
        </Space>
      </div>
    </Card>

    <Modal
      v-model:open="leaveConfirm.open"
      :footer="null"
      title="有未保存的修改，离开前怎么处理？"
    >
      <Space style="justify-content: flex-end; width: 100%">
        <Button type="primary" @click="confirmLeaveSave">暂存并离开</Button>
        <Button danger @click="confirmLeaveDiscard">放弃修改</Button>
        <Button @click="leaveConfirm = { open: false, taskId: null }">
          取消
        </Button>
      </Space>
    </Modal>
  </div>
</template>

<style scoped>
.page {
  min-height: 100%;
  padding: 16px;
}

.title-row {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  margin-bottom: 8px;
}

.sub-text {
  font-size: 12px;
  color: rgb(0 0 0 / 45%);
}

.workbench {
  display: flex;
  gap: 16px;
}

.anchors {
  flex: 0 0 auto;
  width: 180px;
}

.form-area {
  flex: 1 1 auto;
  min-width: 0;
}

.next-visit {
  padding: 8px 12px;
  margin-top: 12px;
  color: rgb(0 0 0 / 65%);
  background: rgb(58 117 255 / 6%);
  border-radius: 6px;
}

.footer-bar {
  display: flex;
  justify-content: space-between;
  padding-top: 12px;
  margin-top: 16px;
  border-top: 1px solid rgb(0 0 0 / 6%);
}
</style>
