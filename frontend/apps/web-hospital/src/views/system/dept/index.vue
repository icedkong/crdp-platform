<script lang="ts" setup>
import type {
  DeptImportReport,
  DeptNodeView,
  DeptSaveRequest,
  OrgCampusNode,
  OrgNodeStatus,
  OrgTreeView,
} from '@crdp/api';

import type { DropTarget } from './org-tree';

import { computed, reactive, ref, watch } from 'vue';

import {
  DEPT_IMPORT_COLUMNS,
  DEPT_TYPE_OPTIONS,
  deptImportActionText,
  deptImportSummary,
  failedRows,
  isXlsxFile,
  orgStatusText,
} from '@crdp/api';
import { StatusTag } from '@crdp/status-tag';
import { colors, sizes } from '@crdp/tokens';
import {
  Alert,
  Button,
  Card,
  Descriptions,
  Empty,
  Form,
  FormItem,
  Input,
  InputNumber,
  message,
  Modal,
  Result,
  Select,
  Space,
  Spin,
  Table,
  Tree,
  TreeSelect,
  Upload,
} from 'ant-design-vue';

import {
  changeDeptStatus,
  changeOrgStatus,
  createDept,
  createOrg,
  deptImpact,
  downloadDeptImportTemplate,
  importDepts,
  orgImpact,
  orgTree,
  updateDept,
  updateOrg,
} from '#/api/core/org';
import UserSelector from '#/components/system/user-selector.vue';
import { useBreakpoint } from '#/reference/use-breakpoint';
import { usePermission } from '#/reference/use-permission';

import {
  deptPath,
  filterCampus,
  findCampus,
  findDeptNode,
  isSelfOrDescendant,
  planDrop,
  selectableCampuses,
  sortChangePayloads,
  validateCode,
  validateName,
  validateReason,
} from './org-tree';

defineOptions({ name: 'HospitalDept' });

/**
 * 组织架构（E06-T02 / PLT-ORG-001、002 · pages.md §10）：左侧院区与科室树、右侧详情；
 * 新增 / 编辑 / 拖拽排序 / 停用（停用前弹影响面，可填原因，后端写审计）。
 * 查看 tenant:org:view，维护 tenant:org:edit。患者数影响面随患者数据（E08+）落地补充；
 * 科室批量导入（PLT-ORG-003）是 E06-T03。手机端显示 MobileBlocker 文案（页面不响应式）。
 */
const { isMobile } = useBreakpoint();
const { hasPermission } = usePermission();
const canEdit = computed(() => hasPermission('tenant:org:edit'));

const loading = ref(false);
const tree = ref<OrgTreeView>({ orgs: [], singleOrg: false });
const keyword = ref('');
const selectedKey = ref('');

/** 单院区医院可隐藏院区层级（PLT-ORG-001）：仅当确实只有一个院区时收起。 */
const hideCampusLevel = computed(
  () => tree.value.singleOrg && tree.value.orgs.length === 1,
);
const visibleOrgs = computed(() =>
  filterCampus(tree.value.orgs, keyword.value),
);

interface TreeDatum {
  children?: TreeDatum[];
  key: string;
  kind: 'campus' | 'dept';
  status: OrgNodeStatus;
  title: string;
}

function campusDatum(campus: OrgCampusNode): TreeDatum {
  return {
    children: campus.depts.map((dept) => deptDatum(dept)),
    key: `o-${campus.id}`,
    kind: 'campus',
    status: campus.status,
    title: campus.name,
  };
}

function deptDatum(dept: DeptNodeView): TreeDatum {
  return {
    children: dept.children.map((child) => deptDatum(child)),
    key: `d-${dept.id}`,
    kind: 'dept',
    status: dept.status,
    title: dept.name,
  };
}

const treeData = computed(() =>
  hideCampusLevel.value
    ? visibleOrgs.value.flatMap((campus) =>
        campus.depts.map((dept) => deptDatum(dept)),
      )
    : visibleOrgs.value.map((campus) => campusDatum(campus)),
);

const expandedKeys = ref<string[]>([]);
watch(
  treeData,
  (data) => {
    // 树规模是院级配置数据（几十到几百节点），重载后全部展开，避免丢展开状态
    const keys: string[] = [];
    const walk = (nodes: TreeDatum[]) => {
      for (const node of nodes) {
        keys.push(node.key);
        walk(node.children ?? []);
      }
    };
    walk(data);
    expandedKeys.value = keys;
  },
  { immediate: true },
);

// ---- 选中与详情 ----

const selectedDept = computed(() => {
  if (!selectedKey.value.startsWith('d-')) return null;
  return findDeptNode(tree.value.orgs, Number(selectedKey.value.slice(2)));
});
const selectedCampus = computed(() => {
  if (!selectedKey.value.startsWith('o-')) return null;
  return findCampus(tree.value.orgs, Number(selectedKey.value.slice(2)));
});
const selectedCount = computed(() => {
  const campus = selectedCampus.value;
  if (!campus) return 0;
  const walk = (nodes: DeptNodeView[]): number =>
    nodes.reduce((sum, node) => sum + 1 + walk(node.children), 0);
  return walk(campus.depts);
});

function selectDefault() {
  const first = treeData.value[0];
  selectedKey.value = first ? (first.children?.[0]?.key ?? first.key) : '';
}

function onTreeSelect(keys: Array<number | string>) {
  if (keys.length > 0) {
    selectedKey.value = String(keys[0]);
  }
}

async function reload(keepSelection = true) {
  loading.value = true;
  try {
    tree.value = await orgTree();
    const previous = selectedKey.value;
    const exists =
      keepSelection &&
      previous !== '' &&
      (tree.value.orgs.some((campus) => `o-${campus.id}` === previous) ||
        findDeptNode(tree.value.orgs, Number(previous.slice(2))) !== null);
    if (!exists) {
      selectDefault();
    }
  } catch {
    // 错误提示由统一拦截器弹出（含 traceId）；保留旧数据便于重试
  } finally {
    loading.value = false;
  }
}
void reload();

// ---- 院区表单 ----

const campusForm = ref({
  open: false,
  editingId: null as null | number,
  code: '',
  name: '',
  codeError: '',
  nameError: '',
  submitting: false,
});

function openCampusCreate() {
  campusForm.value = {
    open: true,
    editingId: null,
    code: '',
    name: '',
    codeError: '',
    nameError: '',
    submitting: false,
  };
}

function openCampusEdit(campus: OrgCampusNode) {
  campusForm.value = {
    open: true,
    editingId: campus.id,
    code: campus.code,
    name: campus.name,
    codeError: '',
    nameError: '',
    submitting: false,
  };
}

async function submitCampus() {
  const form = campusForm.value;
  form.codeError = validateCode(form.code) ?? '';
  form.nameError = validateName(form.name) ?? '';
  if (form.codeError || form.nameError) return;
  form.submitting = true;
  try {
    if (form.editingId === null) {
      await createOrg({ code: form.code.trim(), name: form.name.trim() });
      message.success('院区已新增');
    } else {
      await updateOrg(form.editingId, {
        code: form.code.trim(),
        name: form.name.trim(),
      });
      message.success('院区已保存');
    }
    form.open = false;
    await reload();
  } catch {
    // 错误提示由统一拦截器弹出（含 traceId）；弹窗保持打开以便修改
  } finally {
    form.submitting = false;
  }
}

// ---- 科室表单 ----

const deptForm = ref({
  open: false,
  editingId: null as null | number,
  orgId: null as null | number,
  parentDeptId: 0,
  code: '',
  name: '',
  deptType: null as null | string,
  leaderUserId: null as null | number,
  hisCode: '',
  sortNo: 10,
  codeError: '',
  nameError: '',
  submitting: false,
});
function campusOptions() {
  return selectableCampuses(tree.value.orgs).map((campus) => ({
    label: campus.name,
    value: campus.id,
  }));
}

function subtreeIdsOf(deptId: number): number[] {
  const found = findDeptNode(tree.value.orgs, deptId);
  if (!found) return [deptId];
  const ids: number[] = [];
  const walk = (node: DeptNodeView) => {
    ids.push(node.id);
    node.children.forEach((child) => walk(child));
  };
  walk(found.dept);
  return ids;
}

/** 上级科室候选：所选院区的科室树；编辑时本科室及其子树禁选（防环，与后端 40901 同口径）。 */
function parentTreeData(): Array<{
  children?: unknown[];
  disabled?: boolean;
  title: string;
  value: number;
}> {
  const campus = findCampus(tree.value.orgs, deptForm.value.orgId ?? -1);
  if (!campus) return [];
  const blocked = new Set(subtreeIdsOf(deptForm.value.editingId ?? -1));
  const mapNode = (dept: DeptNodeView): unknown => ({
    children: dept.children.map((child) => mapNode(child)),
    disabled: blocked.has(dept.id),
    title: dept.name,
    value: dept.id,
  });
  return [
    { title: '（院区顶层）', value: 0 },
    ...(campus.depts.map((dept) => mapNode(dept)) as Array<{
      children?: unknown[];
      title: string;
      value: number;
    }>),
  ];
}

// ---- 批量导入（E06-T03 / PLT-ORG-003）：模板下载 → 上传 → 逐行结果 ----

const importResultColumns = [
  { dataIndex: 'rowNo', title: '行号', width: 80 },
  { dataIndex: 'code', title: '科室编码', width: 180 },
  { dataIndex: 'action', title: '结果', width: 90 },
  { dataIndex: 'message', title: '原因' },
];

const importState = reactive({
  open: false,
  file: null as File | null,
  submitting: false,
  report: null as DeptImportReport | null,
});

function openImport() {
  importState.open = true;
  importState.file = null;
  importState.submitting = false;
  importState.report = null;
}

function onFileSelected(file: File) {
  if (!isXlsxFile(file)) {
    message.warning('只支持 .xlsx 文件，请使用下载的模板填写');
    return false;
  }
  importState.file = file;
  importState.report = null;
  return false; // 阻止自动上传，点「开始导入」再提交
}

async function submitImport() {
  const file = importState.file;
  if (!file) {
    message.warning('请先选择要导入的 xlsx 文件');
    return;
  }
  importState.submitting = true;
  try {
    const report = await importDepts(file);
    importState.report = report;
    if (report.failed === 0) {
      message.success('导入完成，全部行已生效');
    } else {
      message.warning(`导入完成，${report.failed} 行失败（见下方明细）`);
    }
    await load();
  } catch {
    // 统一拦截器已提示（表头不符 40001 等）；保持弹窗供修正后重试
  } finally {
    importState.submitting = false;
  }
}

function openDeptCreate(parent: DeptNodeView | null) {
  const context = parent ?? selectedDept.value?.dept ?? null;
  const orgId =
    context?.orgId ??
    selectedCampus.value?.id ??
    tree.value.orgs[0]?.id ??
    null;
  const siblings = context?.children ?? tree.value.orgs[0]?.depts ?? [];
  deptForm.value = {
    open: true,
    editingId: null,
    orgId,
    parentDeptId: context?.id ?? 0,
    code: '',
    name: '',
    deptType: null,
    leaderUserId: null,
    hisCode: '',
    sortNo: siblings.length * 10 + 10,
    codeError: '',
    nameError: '',
    submitting: false,
  };
}

function openDeptEdit(dept: DeptNodeView) {
  deptForm.value = {
    open: true,
    editingId: dept.id,
    orgId: dept.orgId,
    parentDeptId: dept.parentId,
    code: dept.code,
    name: dept.name,
    deptType: dept.deptType,
    leaderUserId: dept.leaderUserId,
    hisCode: dept.hisCode ?? '',
    sortNo: dept.sortNo,
    codeError: '',
    nameError: '',
    submitting: false,
  };
}

async function submitDept() {
  const form = deptForm.value;
  form.codeError =
    form.editingId === null ? (validateCode(form.code) ?? '') : '';
  form.nameError = validateName(form.name) ?? '';
  if (form.codeError || form.nameError || form.orgId === null) return;
  const request: DeptSaveRequest = {
    code: form.code.trim(),
    deptType: form.deptType,
    hisCode: form.hisCode.trim() === '' ? null : form.hisCode.trim(),
    leaderUserId: form.leaderUserId,
    name: form.name.trim(),
    orgId: form.orgId,
    parentDeptId: form.parentDeptId,
    sortNo: form.sortNo,
  };
  form.submitting = true;
  try {
    if (form.editingId === null) {
      await createDept(request);
      message.success('科室已新增');
    } else {
      await updateDept(form.editingId, request);
      message.success('科室已保存');
    }
    form.open = false;
    await reload();
  } catch {
    // 统一拦截器已提示；保持弹窗
  } finally {
    form.submitting = false;
  }
}

// ---- 停用 / 启用（影响面 + 可选原因）----

const statusModal = ref({
  open: false,
  kind: 'campus' as 'campus' | 'dept',
  id: 0,
  name: '',
  status: 'ENABLED' as OrgNodeStatus,
  action: 'DISABLE' as 'DISABLE' | 'ENABLE',
  reason: '',
  reasonError: '',
  impactText: '',
  loading: false,
  submitting: false,
  error: '',
});

function openStatus(
  kind: 'campus' | 'dept',
  id: number,
  name: string,
  status: OrgNodeStatus,
) {
  statusModal.value = {
    open: true,
    kind,
    id,
    name,
    status,
    action: status === 'ENABLED' ? 'DISABLE' : 'ENABLE',
    reason: '',
    reasonError: '',
    impactText: '',
    loading: false,
    submitting: false,
    error: '',
  };
  void loadImpact();
}

async function loadImpact() {
  const modal = statusModal.value;
  if (modal.action !== 'DISABLE') return;
  modal.loading = true;
  try {
    if (modal.kind === 'campus') {
      const view = await orgImpact(modal.id);
      modal.impactText = `下级科室 ${view.deptCount} 个（启用中 ${view.enabledDeptCount} 个）· 科室成员 ${view.memberCount} 人`;
    } else {
      const view = await deptImpact(modal.id);
      modal.impactText = `下级科室 ${view.childCount} 个（启用中 ${view.enabledChildCount} 个）· 科室成员 ${view.memberCount} 人`;
    }
  } catch {
    modal.impactText = '影响面加载失败，可取消后再试';
  } finally {
    modal.loading = false;
  }
}

const statusVerb = computed(() =>
  statusModal.value.action === 'DISABLE' ? '停用' : '启用',
);

function onReasonChange() {
  statusModal.value.reasonError =
    validateReason(statusModal.value.reason) ?? '';
}

async function submitStatus() {
  const modal = statusModal.value;
  onReasonChange();
  if (modal.reasonError) return;
  modal.submitting = true;
  modal.error = '';
  try {
    const request = {
      action: modal.action,
      reason: modal.reason.trim() === '' ? undefined : modal.reason.trim(),
    };
    await (modal.kind === 'campus'
      ? changeOrgStatus(modal.id, request)
      : changeDeptStatus(modal.id, request));
    message.success(`${statusVerb.value}成功`);
    modal.open = false;
    await reload();
  } catch (error) {
    modal.error =
      error instanceof Error ? error.message : `${statusVerb.value}失败`;
  } finally {
    modal.submitting = false;
  }
}

// ---- 拖拽排序 ----

function parseKey(key: string): DropTarget | null {
  if (key.startsWith('d-')) {
    return { type: 'dept', deptId: Number(key.slice(2)) };
  }
  if (key.startsWith('o-')) {
    return { type: 'campus', orgId: Number(key.slice(2)) };
  }
  return null;
}

async function onTreeDrop(info: unknown) {
  const drop = info as {
    dragNode: { key: number | string };
    dropPosition: number;
    dropToGap: boolean;
    node: { key: number | string; pos: string };
  };
  if (!canEdit.value) return;
  const drag = parseKey(String(drop.dragNode.key));
  const target = parseKey(String(drop.node.key));
  if (!drag || drag.type !== 'dept' || !target) return;
  const relative0 = drop.dropPosition - Number(drop.node.pos.split('-').pop());
  const relative: -1 | 0 | 1 = drop.dropToGap ? (relative0 < 0 ? -1 : 1) : 0;
  const dragged = findDeptNode(tree.value.orgs, drag.deptId);
  if (!dragged) return;
  if (
    target.type === 'dept' &&
    isSelfOrDescendant(dragged.dept, target.deptId)
  ) {
    message.warning('不能移动到本科室或其下级之下');
    return;
  }
  const plan = planDrop(tree.value.orgs, drag.deptId, target, relative);
  if (!plan) return;
  const payloads = sortChangePayloads(plan.siblings, plan.parentId, plan.orgId);
  if (payloads.length === 0) return;
  try {
    for (const payload of payloads) {
      await updateDept(payload.id, payload.request);
    }
    message.success('排序已保存');
  } catch {
    message.error('排序保存失败，已恢复原顺序');
  } finally {
    await reload();
  }
}

// ---- 关键字过滤后的选中兜底 ----

watch(keyword, () => {
  if (treeData.value.length === 0) {
    selectedKey.value = '';
    return;
  }
  if (
    selectedKey.value.startsWith('d-') &&
    findDeptNode(visibleOrgs.value, Number(selectedKey.value.slice(2))) === null
  ) {
    selectDefault();
  }
});

const title = '组织架构';
const filterWidth = sizes.filterInputWidth.max;
const modalWidth = sizes.modal.simple;
const textSecondary = colors.textSecondary;
</script>

<template>
  <Result v-if="isMobile" status="info" title="请在电脑或平板上操作">
    <template #extra>
      <Button type="primary" @click="$router.push('/dashboard')">
        返回首页
      </Button>
    </template>
  </Result>
  <div v-else class="page" data-testid="org-page">
    <Card :bordered="false" class="crdp-card">
      <div class="head">
        <h1 class="crdp-page-title">{{ title }}</h1>
        <p class="hint" :style="{ color: textSecondary }">
          维护院区与多级科室；科室不删除、只停用，停用后不再出现在业务选择器。
        </p>
      </div>
      <Alert
        v-if="!loading && tree.orgs.length === 0"
        class="mb-3"
        message="还没有院区 · 先新增本院的第一个院区"
        show-icon
        type="info"
      />
      <div class="layout">
        <div class="left">
          <div class="toolbar">
            <Input
              v-model:value="keyword"
              allow-clear
              placeholder="搜索名称或编码"
              :style="{ width: `${filterWidth}px` }"
            />
            <template v-if="canEdit">
              <Button
                type="primary"
                data-testid="add-campus"
                @click="openCampusCreate"
              >
                新增院区
              </Button>
              <Button
                data-testid="add-dept"
                :disabled="tree.orgs.length === 0"
                @click="openDeptCreate(null)"
              >
                新增科室
              </Button>
              <Button data-testid="import-depts" @click="openImport">
                批量导入
              </Button>
            </template>
          </div>
          <Spin :spinning="loading">
            <Empty
              v-if="!loading && treeData.length === 0"
              :image="Empty.PRESENTED_IMAGE_SIMPLE"
              description="没有匹配的院区或科室 · 清除搜索"
            />
            <Tree
              v-else
              v-model:expanded-keys="expandedKeys"
              block-node
              data-testid="org-tree"
              :draggable="canEdit"
              :selected-keys="[selectedKey]"
              :tree-data="treeData"
              @drop="onTreeDrop"
              @select="onTreeSelect"
            >
              <template #title="{ kind, status, title: nodeTitle }">
                <span
                  :style="
                    kind === 'dept' && status === 'DISABLED'
                      ? { color: textSecondary }
                      : undefined
                  "
                >
                  {{ nodeTitle }}
                </span>
                <StatusTag
                  v-if="kind === 'campus'"
                  class="node-status"
                  :status="orgStatusText(status)"
                  variant="dot"
                />
              </template>
            </Tree>
          </Spin>
          <p v-if="canEdit" class="drag-hint" :style="{ color: textSecondary }">
            拖拽科室可调整顺序或上级；停用院区不接收新科室
          </p>
        </div>
        <div class="right" data-testid="org-detail">
          <template v-if="selectedCampus">
            <div class="detail-head">
              <h2 class="detail-title">{{ selectedCampus.name }}</h2>
              <StatusTag :status="orgStatusText(selectedCampus.status)" />
            </div>
            <Descriptions :column="1" size="small">
              <Descriptions.Item label="编码">
                {{ selectedCampus.code }}
              </Descriptions.Item>
              <Descriptions.Item label="科室数">
                {{ selectedCount }}
              </Descriptions.Item>
            </Descriptions>
            <Space v-if="canEdit" class="actions">
              <Button type="primary" @click="openCampusEdit(selectedCampus)">
                编辑
              </Button>
              <Button
                :danger="selectedCampus.status === 'ENABLED'"
                @click="
                  openStatus(
                    'campus',
                    selectedCampus.id,
                    selectedCampus.name,
                    selectedCampus.status,
                  )
                "
              >
                {{ selectedCampus.status === 'ENABLED' ? '停用' : '启用' }}
              </Button>
            </Space>
          </template>
          <template v-else-if="selectedDept">
            <div class="detail-head">
              <h2 class="detail-title">{{ selectedDept.dept.name }}</h2>
              <StatusTag :status="orgStatusText(selectedDept.dept.status)" />
            </div>
            <Descriptions :column="1" size="small">
              <Descriptions.Item label="路径">
                {{ deptPath(tree.orgs, selectedDept.dept.id) }}
              </Descriptions.Item>
              <Descriptions.Item label="编码">
                {{ selectedDept.dept.code }}
              </Descriptions.Item>
              <Descriptions.Item label="科室类型">
                {{ selectedDept.dept.deptType ?? '—' }}
              </Descriptions.Item>
              <Descriptions.Item label="负责人">
                {{ selectedDept.dept.leaderName ?? '—' }}
              </Descriptions.Item>
              <Descriptions.Item label="HIS 科室编码">
                {{ selectedDept.dept.hisCode ?? '—' }}
              </Descriptions.Item>
              <Descriptions.Item label="排序号">
                {{ selectedDept.dept.sortNo }}
              </Descriptions.Item>
            </Descriptions>
            <Space v-if="canEdit" class="actions">
              <Button type="primary" @click="openDeptEdit(selectedDept.dept)">
                编辑
              </Button>
              <Button
                :danger="selectedDept.dept.status === 'ENABLED'"
                @click="
                  openStatus(
                    'dept',
                    selectedDept.dept.id,
                    selectedDept.dept.name,
                    selectedDept.dept.status,
                  )
                "
              >
                {{ selectedDept.dept.status === 'ENABLED' ? '停用' : '启用' }}
              </Button>
            </Space>
          </template>
          <Empty
            v-else
            :image="Empty.PRESENTED_IMAGE_SIMPLE"
            description="在左侧选择院区或科室查看详情"
          />
        </div>
      </div>
    </Card>

    <!-- 批量导入（E06-T03 / PLT-ORG-003）：模板下载 + 上传 + 逐行结果 -->
    <Modal
      v-model:open="importState.open"
      :confirm-loading="importState.submitting"
      :footer="null"
      :width="modalWidth"
      title="批量导入科室"
    >
      <Alert
        class="mb-3"
        message="按模板列填写：院区须已存在；科室编码已存在即更新；上级科室编码可留空（院区顶层）或引用库内 / 本文件中的编码；负责人须为本院启用用户的账号。单行失败不影响其他行。"
        show-icon
        type="info"
      />
      <div class="import-actions">
        <Button @click="downloadDeptImportTemplate()">下载模板（xlsx）</Button>
        <Upload
          accept=".xlsx"
          :before-upload="onFileSelected"
          :file-list="[]"
          :max-count="1"
          :show-upload-list="false"
        >
          <Button>选择文件</Button>
        </Upload>
        <Button
          danger
          :disabled="!importState.file"
          :loading="importState.submitting"
          type="primary"
          @click="submitImport"
        >
          开始导入
        </Button>
      </div>
      <p v-if="importState.file" class="import-file">
        已选择：{{ importState.file.name }}
      </p>
      <template v-if="importState.report">
        <Alert
          class="mb-3"
          :message="deptImportSummary(importState.report)"
          show-icon
          :type="importState.report.failed > 0 ? 'warning' : 'success'"
        />
        <template v-if="failedRows(importState.report).length > 0">
          <p class="import-failed-title">失败行明细（未导入）：</p>
          <Table
            :columns="importResultColumns"
            :data-source="failedRows(importState.report)"
            :pagination="false"
            :row-key="(row) => row.rowNo"
            size="small"
          >
            <template #bodyCell="{ column, record }">
              <template v-if="column.dataIndex === 'action'">
                {{ deptImportActionText(record.action) }}
              </template>
            </template>
          </Table>
        </template>
      </template>
      <div v-else class="import-columns">
        模板列：{{ DEPT_IMPORT_COLUMNS.join('、') }}
      </div>
    </Modal>

    <!-- 院区新增 / 编辑（520 短表单，design-system §6.1/§7） -->
    <Modal
      v-model:open="campusForm.open"
      :confirm-loading="campusForm.submitting"
      :title="campusForm.editingId === null ? '新增院区' : '编辑院区'"
      :width="modalWidth"
      cancel-text="取消"
      ok-text="保存"
      @ok="submitCampus"
    >
      <Form layout="vertical">
        <FormItem
          label="编码"
          required
          :validate-status="campusForm.codeError ? 'error' : undefined"
          :help="campusForm.codeError"
          :extra="
            campusForm.editingId === null
              ? '租户内唯一，创建后不可修改'
              : '编码创建后不可修改'
          "
        >
          <Input
            v-model:value="campusForm.code"
            :disabled="campusForm.editingId !== null"
            :maxlength="64"
            placeholder="如 ORG-BEI（示例）"
          />
        </FormItem>
        <FormItem
          label="名称"
          required
          :validate-status="campusForm.nameError ? 'error' : undefined"
          :help="campusForm.nameError"
        >
          <Input
            v-model:value="campusForm.name"
            :maxlength="128"
            placeholder="如 示例北院区（示例）"
          />
        </FormItem>
      </Form>
    </Modal>

    <!-- 科室新增 / 编辑 -->
    <Modal
      v-model:open="deptForm.open"
      :confirm-loading="deptForm.submitting"
      :title="deptForm.editingId === null ? '新增科室' : '编辑科室'"
      :width="modalWidth"
      cancel-text="取消"
      ok-text="保存"
      @ok="submitDept"
    >
      <Form layout="vertical">
        <FormItem label="所属院区" required>
          <Select
            v-model:value="deptForm.orgId"
            :disabled="deptForm.editingId !== null"
            :options="campusOptions()"
            placeholder="选择院区"
          />
        </FormItem>
        <FormItem label="上级科室">
          <TreeSelect
            v-model:value="deptForm.parentDeptId"
            :disabled="!deptForm.orgId"
            :tree-data="parentTreeData()"
            placeholder="院区顶层或选择上级科室"
            tree-default-expand-all
          />
        </FormItem>
        <FormItem
          label="编码"
          required
          :validate-status="deptForm.codeError ? 'error' : undefined"
          :help="deptForm.codeError"
          :extra="
            deptForm.editingId === null
              ? '租户内唯一，创建后不可修改'
              : '编码创建后不可修改'
          "
        >
          <Input
            v-model:value="deptForm.code"
            :disabled="deptForm.editingId !== null"
            :maxlength="64"
            placeholder="如 DEPT-CARDIO（示例）"
          />
        </FormItem>
        <FormItem
          label="名称"
          required
          :validate-status="deptForm.nameError ? 'error' : undefined"
          :help="deptForm.nameError"
        >
          <Input
            v-model:value="deptForm.name"
            :maxlength="128"
            placeholder="如 示例心内科（示例）"
          />
        </FormItem>
        <FormItem label="科室类型">
          <Select
            v-model:value="deptForm.deptType"
            allow-clear
            :options="DEPT_TYPE_OPTIONS"
            placeholder="临床 / 医技 / 职能 / 随访中心"
          />
        </FormItem>
        <FormItem label="负责人">
          <!-- PLT-ORG-012 共用用户选择器（E06-T09 替换暂行负责人候选接口） -->
          <UserSelector
            :value="deptForm.leaderUserId"
            @change="
              (value) => (deptForm.leaderUserId = value as number | null)
            "
          />
        </FormItem>
        <FormItem label="HIS 科室编码">
          <Input
            v-model:value="deptForm.hisCode"
            :maxlength="64"
            placeholder="与院内系统映射，可留空"
          />
        </FormItem>
        <FormItem label="排序号">
          <InputNumber
            v-model:value="deptForm.sortNo"
            :max="9999"
            :min="0"
            style="width: 160px"
          />
        </FormItem>
      </Form>
    </Modal>

    <!-- 停用 / 启用确认：先看影响面（平台规格「停用前返回影响」） -->
    <Modal
      v-model:open="statusModal.open"
      :confirm-loading="statusModal.submitting"
      :ok-button-props="{
        danger: statusModal.action === 'DISABLE',
        disabled: Boolean(statusModal.reasonError),
      }"
      :title="`${statusVerb}：${statusModal.name}`"
      :width="modalWidth"
      cancel-text="取消"
      ok-text="确认"
      @cancel="statusModal.open = false"
      @ok="submitStatus"
    >
      <Alert
        :message="
          statusModal.action === 'DISABLE'
            ? '停用前请确认影响面；停用后不再出现在业务选择器，可随时重新启用。'
            : '启用后该节点恢复出现在组织树与业务选择器。'
        "
        class="mb-4"
        show-icon
        type="warning"
      />
      <Spin :spinning="statusModal.loading">
        <Form layout="vertical">
          <FormItem v-if="statusModal.action === 'DISABLE'" label="影响面">
            <span>{{ statusModal.impactText || '加载中…' }}</span>
          </FormItem>
          <FormItem
            label="变更原因（可选，记入操作日志）"
            :validate-status="statusModal.reasonError ? 'error' : undefined"
            :help="statusModal.reasonError"
          >
            <Input
              v-model:value="statusModal.reason"
              :maxlength="200"
              placeholder="如 科室合并（示例）"
              show-count
              type="textarea"
              @change="onReasonChange"
            />
          </FormItem>
        </Form>
      </Spin>
      <Alert
        v-if="statusModal.error"
        :message="`${statusVerb}失败：${statusModal.error}`"
        class="mt-2"
        show-icon
        type="error"
      />
    </Modal>
  </div>
</template>

<style scoped>
.page {
  padding: 16px;
}

.head {
  margin-bottom: 12px;
}

.hint {
  margin: 4px 0 0;
  font-size: 12px;
}

.layout {
  display: flex;
  gap: 16px;
}

.left {
  flex: 0 0 320px;
  min-width: 0;
}

.right {
  flex: 1;
  min-width: 0;
  padding-left: 16px;
  border-left: 1px solid #f0f0f0;
}

.toolbar {
  display: flex;
  gap: 8px;
  margin-bottom: 12px;
}

.mb-3 {
  margin-bottom: 12px;
}

.import-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
}

.import-file {
  margin: 8px 0 0;
  font-size: 13px;
}

.import-failed-title {
  margin: 0 0 4px;
  font-size: 13px;
}

.import-columns {
  margin-top: 12px;
  font-size: 12px;
}

.node-status {
  margin-left: 8px;
}

.drag-hint {
  margin-top: 8px;
  font-size: 12px;
}

.detail-head {
  display: flex;
  gap: 12px;
  align-items: center;
}

.detail-title {
  margin: 0;
  font-size: 16px;
  font-weight: 600;
}

.actions {
  margin-top: 12px;
}
</style>
