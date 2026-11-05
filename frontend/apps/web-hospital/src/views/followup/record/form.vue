<script lang="ts" setup>
import type {
  FormSchema,
  RecordPatientBrief,
  RecordTaskContext,
} from '@crdp/api';
import type { AutoSaveTrigger } from '@crdp/form-engine';
import type { CategoryProgress } from '@crdp/rules';

import { computed, onBeforeUnmount, onMounted, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';

import { useUserStore } from '@vben/stores';

import { taskDisplayStatus } from '@crdp/api';
import {
  asDraftConflictDetail,
  DraftConflictError,
  FormRenderer,
  useAutoSave,
} from '@crdp/form-engine';
import { useKeyboardSafeBottom } from '@crdp/responsive';
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
  Tooltip,
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
import ConflictBanner from '#/components/followup/conflict-banner.vue';
import FormStatusBar from '#/components/followup/form-status-bar.vue';
import { useBreakpoint } from '#/reference/use-breakpoint';
import { usePermission } from '#/reference/use-permission';

import { queueFilterOfQuery } from '../task/logic';
import {
  bannersOf,
  deathFieldKey,
  diffChanges,
  draftRecordKey,
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
 * - E25-T02 / NFR-SAVE-001/002/003 useAutoSave：每 2 分钟有改动才存服务器草稿，
 *   状态条「已自动保存 HH:mm」等四态；本地草稿每 30 秒 + blur 写 IndexedDB
 *   （断网不丢数据，恢复网络立即同步，离线不能提交）；409 → ConflictBanner 暂停
 *   并二选一（保留我的并覆盖 / 载入服务器版本）；打开时有本地草稿提示恢复；
 * - 上一条 / 下一条顺当前列表（GET /tasks/queue，筛选由列表页随链接带出）；
 *   dirty 时弹「有未保存的修改，离开前怎么处理？」（暂存并离开 / 放弃修改 / 取消），
 *   刷新 / 关标签页走 beforeunload；
 * - 只读：无 followup:record:edit 或记录已提交（整体只读，修改随后续任务）；
 * - 横幅：逾期 / 窗口外 / 补录（pages.md §5.1 文案照抄）。
 * - E24-T02 / NFR-RESP-001、002（pages.md §5.2）：平板去掉左侧锚点列，分组条
 *   吸顶横向滚动、底部栏同桌面；手机「跳到分组」下拉 + 底部抽屉（CategoryBar
 *   select 形态）、顶栏左侧「返回」、底部固定操作栏（上一条 / 下一条、暂存、
 *   提交，随 visualViewport 跟随键盘），路由已登记响应式名单（手机不再被
 *   MobileBlocker 拦下）。
 *
 * 对照分屏与报告单带入（A-ATT-019 / C-FU-017）随 E16 / E17。
 */
const route = useRoute();
const router = useRouter();
const userStore = useUserStore();
const { hasPermission } = usePermission();
const canEdit = computed(() => hasPermission('followup:record:edit'));
const { isDesktop, isMobile, isTouch } = useBreakpoint();
/** 手机底部固定操作栏距视口底部的偏移（键盘弹出时跟随可视区域，E24-T02）。 */
const keyboardSafeBottom = useKeyboardSafeBottom();

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
const dirty = computed(() => hasChanges(savedData.value, data.value));

// ---------------------------------------------------------------- 自动保存（E25-T02 / NFR-SAVE-001/002/003）

/** 本地草稿键：`${tenantId}:${userId}:${kind}:${id}`（design-system §13；租户取会话 tenantCode）。 */
const recordKey = computed(() =>
  draftRecordKey(
    userStore.userInfo?.tenantCode,
    userStore.userInfo?.userId,
    recordId.value,
  ),
);

/** 40900 响应 → DraftConflictError（useAutoSave 冲突流程），其余错误原样抛出。 */
async function saveDraftToServer(body: {
  baseVersion: number;
  changes: Record<string, unknown>;
  overwrite?: boolean;
}) {
  try {
    return await saveRecordDraft(recordId.value, body);
  } catch (error) {
    const detail = asDraftConflictDetail(error);
    if (detail) throw new DraftConflictError(detail);
    throw error;
  }
}

const autosave = useAutoSave({
  enabled: computed(() => !readonly.value && !submitted.value),
  getBaseVersion: () => version.value,
  getChanges: () => diffChanges(savedData.value, data.value),
  getSnapshot: () => ({
    baseVersion: version.value,
    data: { ...data.value },
    meta: { ...meta.value },
  }),
  isDirty: dirty,
  kind: 'followup',
  async onSaved(_result, trigger: AutoSaveTrigger) {
    version.value = _result.version;
    savedData.value = { ...data.value };
    if (trigger === 'manual') {
      message.success('已暂存到服务器（草稿），任务变为进行中');
    }
    if (trigger === 'keepMine') {
      message.success('已用你的版本覆盖服务器草稿，覆盖记入操作日志');
    }
  },
  recordKey,
  async restoreServer() {
    await load();
  },
  saveDraft: saveDraftToServer,
});

/** 冲突处理中标志（ConflictBanner 按钮 loading）。 */
const resolving = ref<'keepMine' | 'loadServer' | null>(null);

async function handleKeepMine() {
  if (resolving.value) return;
  resolving.value = 'keepMine';
  try {
    await autosave.keepMine();
  } finally {
    resolving.value = null;
  }
}

async function handleLoadServer() {
  if (resolving.value) return;
  resolving.value = 'loadServer';
  try {
    await autosave.loadServer();
    message.info('已载入服务器最新草稿；你的改动另存一份本地副本，可对照');
  } finally {
    resolving.value = null;
  }
}

/** 提交闸门：离线 / 冲突中不能提交（pages.md §5.1 文案照抄）。 */
const submitBlocked = computed(
  () => autosave.status.value === 'offline' || autosave.conflict.value !== null,
);
const submitBlockedTooltip = computed(() =>
  autosave.conflict.value
    ? '请先处理版本冲突再提交'
    : '离线时不能提交：改动已存本地草稿，恢复网络后再提交',
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
    await offerLocalDraftRestore();
  } catch {
    message.error('随访记录加载失败，请稍后重试');
  } finally {
    loading.value = false;
  }
}

/** 打开时如有本地草稿（上次断网 / 未同步），提示「发现未提交的草稿，是否恢复」。 */
async function offerLocalDraftRestore() {
  if (readonly.value || !(await autosave.hasLocalDraft())) return;
  Modal.confirm({
    title: '发现未提交的草稿',
    content: '本机有一份未同步到服务器的草稿，是否恢复？',
    okText: '恢复草稿',
    cancelText: '不恢复',
    onOk: async () => {
      const draft = await autosave.restoreLocalDraft();
      if (!draft) return;
      data.value = { ...draft.data };
      meta.value = { ...draft.meta };
      message.success('已恢复本机草稿，改动会在下次保存时同步');
    },
  });
}

onMounted(() => {
  void load();
  window.addEventListener('keydown', handleKeydown);
  window.addEventListener('beforeunload', handleBeforeUnload);
});

onBeforeUnmount(() => {
  window.removeEventListener('keydown', handleKeydown);
  window.removeEventListener('beforeunload', handleBeforeUnload);
});

/** 有改动时刷新 / 关标签页提醒（浏览器原生确认；本应用内离开走 leaveConfirm 弹窗）。 */
function handleBeforeUnload(event: BeforeUnloadEvent) {
  if (!dirty.value || submitted.value) return;
  event.preventDefault();
  event.returnValue = '';
}

// ---------------------------------------------------------------- 暂存与提交

async function handleSaveDraft() {
  if (readonly.value || saving.value) return false;
  if (!dirty.value) {
    message.info('没有需要暂存的修改');
    return true;
  }
  saving.value = true;
  try {
    // 409 由 useAutoSave 进入冲突流程（不再当作普通失败提示）
    return await autosave.saveNow();
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
  if (submitBlocked.value) {
    message.warning(submitBlockedTooltip.value);
    return;
  }
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
    // 服务器已收到全量数据：丢弃本机草稿，重开不再提示恢复
    void autosave.discardLocalDraft();
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
  <Result
    v-if="submitted"
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
  <div
    v-else
    class="page"
    :class="{ 'page-mobile': isMobile }"
    data-testid="record-form"
  >
    <Card :bordered="false" class="crdp-card">
      <div class="title-row">
        <!-- 手机顶栏左侧为「返回」（pages.md §5.2） -->
        <Button
          v-if="isMobile"
          class="back-button"
          data-testid="record-back"
          @click="backToList"
        >
          ← 返回
        </Button>
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

      <FormStatusBar
        v-if="!readonly"
        :last-saved-at="autosave.lastSavedAt.value"
        :status="autosave.status.value"
      />

      <ConflictBanner
        v-if="autosave.conflict.value"
        :conflict="autosave.conflict.value"
        :pending="resolving"
        @keep-mine="handleKeepMine"
        @load-server="handleLoadServer"
      />

      <Alert
        v-for="banner in banners"
        :key="banner.key"
        :message="banner.text"
        :type="banner.tone"
        show-icon
        :style="{ marginBottom: '8px' }"
      />

      <Spin :spinning="loading">
        <div v-if="schema" class="workbench" :class="{ stacked: !isDesktop }">
          <!-- 桌面：左侧分组锚点列；平板 / 手机收起左列（分组条吸顶横向滚动 /
               「跳到分组」下拉 + 底部抽屉，pages.md §5.2） -->
          <div v-if="isDesktop" class="anchors">
            <CategoryBar
              :active-key="activeKey"
              :items="progressItems"
              mode="bar"
              show-highlight-count
              @jump="handleJump"
            />
          </div>
          <div class="form-area">
            <CategoryBar
              v-if="!isDesktop"
              :active-key="activeKey"
              :items="progressItems"
              :mode="isMobile ? 'select' : 'bar'"
              show-highlight-count
              @jump="handleJump"
            />
            <FormRenderer
              ref="renderer"
              v-model="data"
              :meta="meta"
              :mode="readonly ? 'readonly' : 'edit'"
              :schema="schema"
              :show-errors="showErrors"
              @field-blur="autosave.noteBlur()"
              @progress="progressItems = $event"
            />
            <div v-if="nextVisitText(context)" class="next-visit">
              {{ nextVisitText(context) }}
            </div>
          </div>
        </div>
      </Spin>

      <!-- 手机改为底部固定操作栏（见 .mobile-footer）；平板 / 桌面底部栏同桌面 -->
      <div v-if="!isMobile" class="footer-bar">
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
          <Tooltip
            :title="submitBlocked ? submitBlockedTooltip : ''"
            :trigger="isTouch ? 'click' : 'hover'"
          >
            <Button
              :disabled="submitBlocked"
              :loading="submitting"
              data-testid="record-submit"
              type="primary"
              @click="handleSubmit"
            >
              提交
            </Button>
          </Tooltip>
        </Space>
      </div>
    </Card>

    <!-- 手机底部固定操作栏：上一条 / 下一条、暂存、提交；随 visualViewport 跟随键盘 -->
    <div
      v-if="isMobile"
      class="mobile-footer"
      :style="{ bottom: `${keyboardSafeBottom}px` }"
      data-testid="mobile-footer"
    >
      <Button :disabled="!queue.prevId" @click="gotoNeighbor(queue.prevId)">
        ← 上一条
      </Button>
      <Button :disabled="!queue.nextId" @click="gotoNeighbor(queue.nextId)">
        下一条 →
      </Button>
      <template v-if="!readonly">
        <Button
          :loading="saving"
          data-testid="record-save-draft"
          @click="handleSaveDraft"
        >
          暂存
        </Button>
        <Tooltip
          :title="submitBlocked ? submitBlockedTooltip : ''"
          trigger="click"
        >
          <Button
            :disabled="submitBlocked"
            :loading="submitting"
            data-testid="record-submit"
            type="primary"
            @click="handleSubmit"
          >
            提交
          </Button>
        </Tooltip>
      </template>
    </div>

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

/* 手机：底部固定操作栏不遮内容（40px 触控 + 留白 + 安全区） */
.page-mobile {
  padding-bottom: calc(76px + env(safe-area-inset-bottom));
}

.title-row {
  display: flex;
  gap: 8px;
  align-items: flex-start;
  justify-content: space-between;
  margin-bottom: 8px;
}

.back-button {
  flex: none;
}

.sub-text {
  font-size: 12px;
  color: rgb(0 0 0 / 45%);
}

.workbench {
  display: flex;
  gap: 16px;
}

/* 平板 / 手机：去掉左侧锚点列，分组导航收进表单区顶部（吸顶横向滚动 / 抽屉） */
.workbench.stacked {
  flex-direction: column;
  gap: 0;
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

/* 手机底部固定操作栏（pages.md §5.2）：上一条 / 下一条、暂存、提交；
   bottom 由 useKeyboardSafeBottom 内联控制（键盘弹出跟随可视区域） */
.mobile-footer {
  position: fixed;
  right: 0;
  left: 0;
  z-index: 20;
  display: flex;
  gap: 8px;
  align-items: center;
  padding: 8px 12px calc(8px + env(safe-area-inset-bottom));
  background: #fff;
  border-top: 1px solid rgb(0 0 0 / 6%);
  box-shadow: 0 -2px 8px rgb(0 0 0 / 4%);
}

/* 手机按钮均分一行（只读时只渲染上一条 / 下一条，§5.1 只读角色） */
.mobile-footer :deep(.ant-btn) {
  flex: 1 1 0;
  min-width: 0;
}
</style>
