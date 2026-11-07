<script lang="ts" setup>
import type {
  ReportLocationInput,
  ReportLocationNode,
  ReportLocationsChangedView,
  ReportLocationsChangeRequest,
  ReportLocationTag,
} from '@crdp/api';

import { ref, watch } from 'vue';

import { message, Modal } from 'ant-design-vue';

import { fetchReportLocations, updateReportLocations } from '#/api/core/report';

import LocationPicker from './location-picker.vue';
import { locationDiff, sameReportLocation } from './report-dialog-logic';

/**
 * E16-T14 / A-ATT-020、C-SCAN-020（components.md §5 edit-location；接口 E16-T13
 * PUT /reports/{id}/locations）：左栏附件导航「修改位置 / 未指定位置组 · 指定位置」。
 *
 * 打开时取报告单位置树并回显当前位置（按四元组对齐，模板版本以树带出为准）；保存
 * 只提交差异（add / remove），结果至少保留 1 个位置（空选 40001 由前端先拦、后端
 * 兜底）；无变化不发起请求。每次保存后端写 RPT_LOCATION_CHANGE 操作日志。
 */
const props = defineProps<{
  patientId: string;
  /** 目标报告单（null = 关闭态占位；label 为弹窗标题显示名）。 */
  report: null | { id: string; label: string; locations: ReportLocationTag[] };
}>();

const emit = defineEmits<{
  (event: 'success', view: ReportLocationsChangedView): void;
}>();

const open = defineModel<boolean>('open', { default: false });

const treeOptions = ref<ReportLocationNode[]>([]);
const loading = ref(false);
const submitting = ref(false);
const locations = ref<ReportLocationInput[]>([]);
const initial = ref<ReportLocationInput[]>([]);
const showError = ref(false);
const mobileOpen = ref(false);

/** 当前报告单位置标签 → 输入四元组（版本取标签携带的固化版本，树对齐时回填）。 */
function tagToInput(tag: ReportLocationTag): ReportLocationInput {
  return {
    ...(tag.anchorKey ? { anchorKey: tag.anchorKey } : {}),
    anchorTemplateVersion: tag.templateVersion ?? 0,
    ...(tag.anchorType !== undefined && tag.anchorType !== null
      ? { anchorType: tag.anchorType }
      : {}),
    ...(tag.linkRecordId ? { linkRecordId: tag.linkRecordId } : {}),
    linkType: tag.linkType,
  };
}

/** 树带出的位置回填版本（与上传对话框同口径：树里已不存在 的旧位置保留原样，后端校验兜底）。 */
function normalizeWithTree() {
  const leaves: ReportLocationInput[] = [];
  const walk = (nodes: ReportLocationNode[]) => {
    for (const node of nodes) {
      if (node.value) leaves.push(node.value);
      if (node.children) walk(node.children);
    }
  };
  walk(treeOptions.value);
  locations.value = locations.value.map(
    (item) => leaves.find((leaf) => sameReportLocation(leaf, item)) ?? item,
  );
}

watch(
  [open, () => props.report],
  ([isOpen, report]) => {
    if (!isOpen || !report) return;
    showError.value = false;
    locations.value = report.locations.map((tag) => tagToInput(tag));
    initial.value = locations.value.map((item) => ({ ...item }));
    loading.value = true;
    treeOptions.value = [];
    fetchReportLocations(props.patientId)
      .then((nodes) => {
        treeOptions.value = nodes;
        normalizeWithTree();
      })
      .catch(() => {
        treeOptions.value = [];
      })
      .finally(() => {
        loading.value = false;
      });
  },
  { immediate: true },
);

async function handleSave() {
  const report = props.report;
  if (!report || submitting.value) return;
  if (locations.value.length === 0) {
    showError.value = true; // 至少保留 1 个位置（A-ATT-020），后端 40001 兜底
    return;
  }
  const diff = locationDiff(initial.value, locations.value);
  if (diff.add.length === 0 && diff.remove.length === 0) {
    open.value = false; // 未改动直接关闭，不写操作日志
    return;
  }
  const payload: ReportLocationsChangeRequest = {};
  if (diff.add.length > 0) payload.add = diff.add;
  if (diff.remove.length > 0) payload.remove = diff.remove;
  submitting.value = true;
  try {
    const view = await updateReportLocations(report.id, payload);
    message.success('报告单位置已更新（已记入操作日志）');
    open.value = false;
    emit('success', view);
  } catch {
    // 提示由统一拦截器弹出（如「该位置已在册」「至少保留一个位置」）
  } finally {
    submitting.value = false;
  }
}
</script>

<template>
  <Modal
    :confirm-loading="submitting"
    :open="open"
    :title="`修改位置 · ${report?.label ?? ''}`"
    :width="520"
    ok-text="保存"
    cancel-text="取消"
    @ok="handleSave"
    @update:open="open = $event"
  >
    <p class="hint">
      至少保留 1 个位置；增加 /
      移除会记入操作日志。未指定位置的旧资料在此补挂位置。
    </p>
    <LocationPicker
      v-model:mobile-open="mobileOpen"
      :options="treeOptions"
      :patient-id="patientId"
      :show-error="showError"
      :value="locations"
      @update:value="locations = $event"
    />
  </Modal>
</template>

<style scoped>
.hint {
  margin: 0 0 8px;
  font-size: 12px;
  color: #8c8c8c;
}
</style>
