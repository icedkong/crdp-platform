<script lang="ts" setup>
import type { FormSchema } from '@crdp/api';
import type { AutoSaveTrigger } from '@crdp/form-engine';
import type { CategoryProgress } from '@crdp/rules';

import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';

import { useUserStore } from '@vben/stores';

import { baselineDraftRecordKey } from '@crdp/api';
import {
  asDraftConflictDetail,
  DraftConflictError,
  FormRenderer,
  useAutoSave,
} from '@crdp/form-engine';
import { useKeyboardSafeBottom } from '@crdp/responsive';
import {
  Alert,
  Button,
  Card,
  message,
  Result,
  Space,
  Spin,
  Tooltip,
} from 'ant-design-vue';

import {
  fetchPatientBaseline,
  saveBaselineSection,
  saveBaselineSectionDraft,
} from '#/api/core/baseline';
import { fetchFormSchema } from '#/api/core/form';
import CategoryBar from '#/components/followup/category-bar.vue';
import ConflictBanner from '#/components/followup/conflict-banner.vue';
import FormStatusBar from '#/components/followup/form-status-bar.vue';
import { useBreakpoint } from '#/reference/use-breakpoint';
import { usePermission } from '#/reference/use-permission';

import {
  BASELINE_CONFLICT_SAVE_TEXT,
  BASELINE_LOCKED_SAVE_TEXT,
  pickSection,
  sectionSaveSuccessText,
} from './baseline-edit-logic';

defineOptions({ name: 'FollowupBaselineEdit' });

/**
 * E13-T02 / A-PAT-003、A-PAT-005 · 基线分节编辑整页（pages.md §4.3，新标签页）：
 * 左侧分类锚点（已填 / 应填）+ FormRenderer edit + 底部 取消 / 暂存 / 保存。
 *
 * - 按分类单独保存（PUT …/baseline/{patientId}/sections/{code}）：执行完整校验
 *   （42200 标红）、写字段级历史并清草稿；未填完的分类先「暂存」保留（A-PAT-003
 *   可分次补录）；病案号等敏感身份字段在患者主档（编辑不走分节 JSON）；
 * - 自动保存（E25-T02 useAutoSave，kind=baseline）：2 分钟服务器草稿 + 30 秒 /
 *   blur 本机草稿；40900 → ConflictBanner 二选一（不做字段级合并）；
 * - 锁定（A-PAT-006）：患者基线已锁定时整页只读兜底（详情页入口已置灰，
 *   直接打开 URL 也要只读）；Ctrl/⌘+S 暂存、Ctrl/⌘+Enter 保存、Enter 不提交。
 */
const route = useRoute();
const router = useRouter();
const userStore = useUserStore();
const { hasPermission } = usePermission();
const canEdit = computed(() => hasPermission('followup:patient:edit'));
const { isDesktop, isMobile } = useBreakpoint();
const keyboardSafeBottom = useKeyboardSafeBottom();

const patientId = computed(() => String(route.params.id ?? ''));

const loading = ref(true);
const loadFailed = ref(false);
const schema = ref<FormSchema | null>(null);
const progressItems = ref<CategoryProgress[]>([]);
const locked = ref(false);
const sections = ref<Array<{ code: string; label: null | string }>>([]);
const activeKey = ref('');
const data = ref<Record<string, unknown>>({});
const meta = ref<Record<string, unknown>>({});
/** 上次服务器版本的 data 快照（暂存差异与 dirty 判定的基准）。 */
const savedData = ref<Record<string, unknown>>({});
const version = ref(0);
const showErrors = ref(false);
const saving = ref(false);
const renderer = ref<InstanceType<typeof FormRenderer> | null>(null);

const readonly = computed(() => !canEdit.value || locked.value);

/** 暂存差异（E25 契约：changes 只带与上次服务器版本相比有变化的键，null = 清空）。 */
function diffOf(): Record<string, unknown> {
  const changes: Record<string, unknown> = {};
  const base = savedData.value;
  const current = data.value;
  for (const key of new Set([...Object.keys(base), ...Object.keys(current)])) {
    const before = base[key];
    const after = current[key];
    if (!Object.is(before, after)) {
      changes[key] = after === undefined ? null : after;
    }
  }
  return changes;
}

const dirty = computed(() => Object.keys(diffOf()).length > 0);

/** 当前分节的模板切片（FormRenderer 只渲染当前分类，保存也只存它）。 */
const activeSchema = computed(() => {
  if (!schema.value || !activeKey.value) return null;
  const category = schema.value.categories.find(
    (item) => item.key === activeKey.value,
  );
  if (!category) return null;
  return { ...schema.value, categories: [category] };
});

const activeLabel = computed(
  () => sections.value.find((item) => item.code === activeKey.value)?.label,
);

// ---------------------------------------------------------------- 自动保存（E25-T02，kind=baseline）

const recordKey = computed(() =>
  baselineDraftRecordKey(
    userStore.userInfo?.tenantCode,
    userStore.userInfo?.userId,
    patientId.value,
    activeKey.value,
  ),
);

async function saveDraftToServer(body: {
  baseVersion: number;
  changes: Record<string, unknown>;
  overwrite?: boolean;
}) {
  try {
    return await saveBaselineSectionDraft(
      patientId.value,
      activeKey.value,
      body,
    );
  } catch (error) {
    const detail = asDraftConflictDetail(error);
    if (detail) throw new DraftConflictError(detail);
    throw error;
  }
}

const autosave = useAutoSave({
  enabled: computed(() => !readonly.value),
  getBaseVersion: () => version.value,
  getChanges: () => diffOf(),
  getSnapshot: () => ({
    baseVersion: version.value,
    data: { ...data.value },
    meta: { ...meta.value },
  }),
  isDirty: dirty,
  kind: 'baseline',
  async onSaved(result, trigger: AutoSaveTrigger) {
    version.value = result.version;
    savedData.value = { ...data.value };
    if (trigger === 'manual') {
      message.success('已暂存到服务器（草稿）');
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

// ---------------------------------------------------------------- 加载

function applySection(
  code: string,
  baseline: {
    data: Record<string, unknown>;
    version: number;
  },
) {
  activeKey.value = code;
  data.value = { ...baseline.data };
  savedData.value = { ...baseline.data };
  meta.value = {};
  version.value = baseline.version;
  showErrors.value = false;
}

async function load() {
  loading.value = true;
  try {
    const baseline = await fetchPatientBaseline(patientId.value);
    schema.value = await fetchFormSchema(
      baseline.formCode,
      baseline.formVersion,
    );
    locked.value = baseline.lock?.locked ?? false;
    sections.value = baseline.sections.map((section) => ({
      code: section.code,
      label:
        schema.value?.categories.find(
          (category) => category.key === section.code,
        )?.label ?? section.code,
    }));
    progressItems.value = baseline.progress as CategoryProgress[];
    const target = pickSection(baseline.sections, route.query.cat as string);
    if (!target) {
      loadFailed.value = true;
      return;
    }
    applySection(target.code, target);
  } catch {
    loadFailed.value = true;
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

// ---------------------------------------------------------------- 切换分类（有改动先暂存，分节互不冲突）

async function switchCategory(key: string) {
  if (key === activeKey.value || readonly.value) return;
  if (dirty.value) {
    await handleSaveDraft();
  }
  const baseline = await fetchPatientBaseline(patientId.value).catch(
    () => null,
  );
  const section = baseline?.sections.find((item) => item.code === key);
  if (!section) {
    message.error('分类不存在，请刷新后重试');
    return;
  }
  applySection(key, section);
  progressItems.value = (baseline?.progress ?? []) as CategoryProgress[];
  void router.replace({ query: { ...route.query, cat: key } });
}

watch(
  () => route.query.cat,
  (key) => {
    if (typeof key === 'string' && key !== activeKey.value && !loading.value) {
      void switchCategory(key);
    }
  },
);

// ---------------------------------------------------------------- 暂存与保存

async function handleSaveDraft(): Promise<boolean> {
  if (readonly.value) return false;
  if (!dirty.value) {
    return true;
  }
  try {
    return await autosave.saveNow();
  } catch {
    message.error('暂存失败，请稍后重试');
    return false;
  }
}

async function handleSave() {
  if (readonly.value || saving.value) return;
  const result = await renderer.value?.validate();
  if (result && !result.ok) {
    showErrors.value = true;
    message.warning('还有必填 / 硬校验未通过，请先补齐或改用「暂存」保留草稿');
    renderer.value?.focusFirstError();
    return;
  }
  saving.value = true;
  try {
    const saved = await saveBaselineSection(patientId.value, activeKey.value, {
      baseVersion: version.value,
      data: data.value,
      meta: meta.value,
    });
    version.value = saved.section.version;
    savedData.value = { ...saved.section.data };
    data.value = { ...saved.section.data };
    void autosave.discardLocalDraft();
    message.success(sectionSaveSuccessText(saved.warnings.length));
    // 保存后分类进度与锁定态可能变化，静默刷新
    const baseline = await fetchPatientBaseline(patientId.value).catch(
      () => null,
    );
    if (baseline) {
      progressItems.value = baseline.progress as CategoryProgress[];
      locked.value = baseline.lock?.locked ?? false;
    }
  } catch (error) {
    const body = error as { code?: number; message?: string };
    if (body?.code === 40_901) {
      message.warning(BASELINE_LOCKED_SAVE_TEXT);
      locked.value = true;
    } else if (body?.code === 40_900) {
      message.warning(BASELINE_CONFLICT_SAVE_TEXT);
      await load();
    } else {
      message.error(body?.message || '保存失败，请稍后重试');
    }
  } finally {
    saving.value = false;
  }
}

// ---------------------------------------------------------------- 键盘（Enter 不提交；Ctrl/⌘+S 暂存；Ctrl/⌘+Enter 保存）

function handleKeydown(event: KeyboardEvent) {
  if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === 's') {
    event.preventDefault();
    void handleSaveDraft();
    return;
  }
  if ((event.metaKey || event.ctrlKey) && event.key === 'Enter') {
    event.preventDefault();
    void handleSave();
  }
}

function backToDetail() {
  void router.push(`/followup/patient/${patientId.value}`);
}
</script>

<template>
  <Result
    v-if="loadFailed"
    data-testid="baseline-edit-failed"
    status="404"
    title="患者不存在或无权查看"
  >
    <template #extra>
      <Button type="primary" @click="backToDetail">返回患者档案</Button>
    </template>
  </Result>

  <div
    v-else
    class="page"
    :class="{ 'page-mobile': isMobile }"
    data-testid="baseline-edit"
  >
    <Card :bordered="false" class="crdp-card">
      <div class="title-row">
        <div>
          <h1 class="crdp-page-title">
            基线分节编辑 · {{ activeLabel ?? '' }}
          </h1>
          <div class="sub-text">
            按分类单独保存；未填完的分类先「暂存」保留，字段修改保存后留痕（A-PAT-005）。
          </div>
        </div>
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
        v-if="locked"
        :message="BASELINE_LOCKED_SAVE_TEXT"
        show-icon
        :style="{ marginBottom: '8px' }"
        type="info"
      />
      <Alert
        v-else-if="!canEdit"
        message="只读角色：不能录入"
        show-icon
        :style="{ marginBottom: '8px' }"
        type="info"
      />

      <Spin :spinning="loading">
        <div v-if="schema" class="workbench" :class="{ stacked: !isDesktop }">
          <div v-if="isDesktop" class="anchors">
            <CategoryBar
              :active-key="activeKey"
              :items="progressItems"
              mode="bar"
              @jump="(key: string) => switchCategory(key)"
            />
          </div>
          <div class="form-area">
            <CategoryBar
              v-if="!isDesktop"
              :active-key="activeKey"
              :items="progressItems"
              :mode="isMobile ? 'select' : 'bar'"
              @jump="(key: string) => switchCategory(key)"
            />
            <FormRenderer
              v-if="activeSchema"
              ref="renderer"
              v-model="data"
              :meta="meta"
              mode="edit"
              :schema="activeSchema"
              :show-errors="showErrors"
              @field-blur="autosave.noteBlur()"
              @progress="progressItems = $event"
            />
          </div>
        </div>
      </Spin>

      <div class="bottom-bar">
        <Space>
          <Button @click="backToDetail">取消</Button>
        </Space>
        <Space>
          <Tooltip
            v-if="readonly"
            :title="locked ? BASELINE_LOCKED_SAVE_TEXT : '只读角色：不能录入'"
          >
            <Button disabled>暂存</Button>
          </Tooltip>
          <Button v-else @click="handleSaveDraft">暂存</Button>
          <Tooltip
            v-if="readonly"
            :title="locked ? BASELINE_LOCKED_SAVE_TEXT : '只读角色：不能录入'"
          >
            <Button disabled type="primary">保存</Button>
          </Tooltip>
          <Button
            v-else
            :loading="saving"
            data-testid="baseline-save"
            type="primary"
            @click="handleSave"
          >
            保存
          </Button>
        </Space>
      </div>
    </Card>

    <!-- 手机底部固定操作栏随 visualViewport 跟随键盘（E24-T02 同款） -->
    <div
      v-if="isMobile && !readonly"
      class="mobile-footer"
      :style="{ bottom: `${keyboardSafeBottom}px` }"
      data-testid="baseline-mobile-footer"
    >
      <Button @click="handleSaveDraft">暂存</Button>
      <Button :loading="saving" type="primary" @click="handleSave">
        保存
      </Button>
    </div>
  </div>
</template>

<style scoped>
.page {
  max-width: 1280px;
  padding: 16px;
  margin: 0 auto;
}

.page-mobile {
  padding: 8px;
}

.title-row {
  display: flex;
  gap: 12px;
  align-items: flex-start;
  justify-content: space-between;
  margin-bottom: 8px;
}

.sub-text {
  margin-top: 4px;
  font-size: 12px;
  color: #8c8c8c;
}

.workbench {
  display: flex;
  gap: 16px;
}

.workbench.stacked {
  flex-direction: column;
}

.anchors {
  flex-shrink: 0;
  width: 200px;
}

.form-area {
  flex: 1;
  min-width: 0;
}

.bottom-bar {
  display: flex;
  justify-content: space-between;
  padding-top: 12px;
  margin-top: 16px;
  border-top: 1px solid #f0f0f0;
}

.mobile-footer {
  position: fixed;
  right: 0;
  left: 0;
  z-index: 100;
  display: flex;
  gap: 8px;
  justify-content: flex-end;
  padding: 8px 12px;
  background: #fff;
  border-top: 1px solid #f0f0f0;
}
</style>
