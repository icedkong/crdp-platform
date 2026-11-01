<script lang="ts" setup>
import type {
  OrgTreeView,
  RoleScopeType,
  TenantRoleMemberView,
  TenantRoleMenuNode,
  TenantRoleView,
} from '@crdp/api';

import { computed, ref } from 'vue';
import { useRouter } from 'vue-router';

import { tenantRoleScopeTypeText } from '@crdp/api';
import { StatusTag } from '@crdp/status-tag';
import { colors, sizes } from '@crdp/tokens';
import {
  Alert,
  Button,
  Card,
  Checkbox,
  CheckboxGroup,
  Empty,
  Form,
  FormItem,
  Input,
  message,
  Modal,
  Result,
  Select,
  Space,
  Spin,
  Table,
  TabPane,
  Tabs,
  Tag,
  Textarea,
  Tree,
  TreeSelect,
} from 'ant-design-vue';

import { orgTree } from '#/api/core/org';
import {
  changeRoleStatus,
  copyRole,
  createRole,
  deleteRole,
  listRoles,
  roleFromTemplate,
  roleMembers,
  roleMenus,
  roleScope,
  updateRole,
  updateRoleMembers,
  updateRoleMenus,
  updateRoleScope,
} from '#/api/core/role';
import UserSelector from '#/components/system/user-selector.vue';
import { useBreakpoint } from '#/reference/use-breakpoint';
import { usePermission } from '#/reference/use-permission';

import {
  checkedMenuIds,
  copyNameSuggestion,
  deptTreeOptions,
  filterRoles,
  isCustomRole,
  isScopeTypeRequiresDept,
  menuTreeData,
  validateReason,
  validateRoleCode,
  validateRoleDescription,
  validateRoleName,
  withAncestorDirs,
} from './role-logic';

defineOptions({ name: 'HospitalRole' });

/**
 * 角色管理（E06-T05 / PLT-ORG-004、005 · pages.md §10）：列表（内置 / 模板 / 自定义）+
 * 编辑抽屉三页签（菜单按钮仅可分配范围 / 数据范围 / 成员）+ 复制与模板应用 + 停用。
 * 字段策略页签随字段级权限（E07）开放，本页先占位说明。查看 tenant:role:view，维护 tenant:role:edit。
 * 内置角色不可删、不可改权限；模板只读，复制后调整；停用对在线会话即时生效。
 */
const router = useRouter();
const { isMobile } = useBreakpoint();
const { hasPermission } = usePermission();
const canEdit = computed(() => hasPermission('tenant:role:edit'));

const loading = ref(false);
const roles = ref<TenantRoleView[]>([]);
const filter = ref({
  keyword: '',
  type: 'ALL' as 'ALL' | 'BUILT_IN' | 'CUSTOM' | 'TEMPLATE',
});

const visibleRoles = computed(() => filterRoles(roles.value, filter.value));

const typeFilterOptions = [
  { label: '全部类型', value: 'ALL' },
  { label: '内置', value: 'BUILT_IN' },
  { label: '模板', value: 'TEMPLATE' },
  { label: '自定义', value: 'CUSTOM' },
];

const columns = [
  { dataIndex: 'code', title: '编码', width: 190 },
  { dataIndex: 'name', title: '名称', width: 140 },
  { dataIndex: 'roleType', title: '类型', width: 88 },
  { dataIndex: 'memberCount', title: '成员', width: 72 },
  { dataIndex: 'menuCount', title: '菜单数', width: 80 },
  { dataIndex: 'status', title: '状态', width: 96 },
  { dataIndex: 'description', title: '说明' },
  { dataIndex: 'action', fixed: 'right' as const, title: '操作', width: 230 },
];

async function reload() {
  loading.value = true;
  try {
    roles.value = await listRoles();
  } catch {
    // 错误提示由统一拦截器弹出（含 traceId）；保留旧数据便于重试
  } finally {
    loading.value = false;
  }
}
void reload();

// ---- 新建 / 编辑基本信息 ----

const infoForm = ref({
  open: false,
  editingId: null as null | number,
  code: '',
  name: '',
  description: '',
  codeError: '',
  nameError: '',
  descriptionError: '',
  submitting: false,
});

function openCreate() {
  infoForm.value = {
    open: true,
    editingId: null,
    code: '',
    name: '',
    description: '',
    codeError: '',
    nameError: '',
    descriptionError: '',
    submitting: false,
  };
}

function openInfoEdit(row: TenantRoleView) {
  infoForm.value = {
    open: true,
    editingId: row.id,
    code: row.code,
    name: row.name,
    description: row.description ?? '',
    codeError: '',
    nameError: '',
    descriptionError: '',
    submitting: false,
  };
}

async function submitInfo() {
  const form = infoForm.value;
  form.codeError =
    form.editingId === null ? (validateRoleCode(form.code) ?? '') : '';
  form.nameError = validateRoleName(form.name) ?? '';
  form.descriptionError = validateRoleDescription(form.description) ?? '';
  if (form.codeError || form.nameError || form.descriptionError) return;
  form.submitting = true;
  const payload = {
    code: form.code.trim(),
    description:
      form.description.trim() === '' ? null : form.description.trim(),
    name: form.name.trim(),
  };
  try {
    if (form.editingId === null) {
      await createRole(payload);
      message.success('角色已创建');
    } else {
      await updateRole(form.editingId, payload);
      message.success('角色已保存');
    }
    form.open = false;
    await reload();
  } catch {
    // 统一拦截器已提示；保持弹窗
  } finally {
    form.submitting = false;
  }
}

// ---- 复制 / 模板应用 ----

const copyForm = ref({
  open: false,
  source: null as null | TenantRoleView,
  code: '',
  name: '',
  description: '',
  codeError: '',
  nameError: '',
  submitting: false,
});

function openCopy(source: TenantRoleView) {
  copyForm.value = {
    open: true,
    source,
    code: '',
    name: copyNameSuggestion(source),
    description: source.description ?? '',
    codeError: '',
    nameError: '',
    submitting: false,
  };
}

async function submitCopy() {
  const form = copyForm.value;
  if (!form.source) return;
  form.codeError = validateRoleCode(form.code) ?? '';
  form.nameError = validateRoleName(form.name) ?? '';
  if (form.codeError || form.nameError) return;
  form.submitting = true;
  const payload = {
    code: form.code.trim(),
    description:
      form.description.trim() === '' ? null : form.description.trim(),
    name: form.name.trim(),
  };
  try {
    if (form.source.roleType === 'TEMPLATE') {
      await roleFromTemplate({ ...payload, templateId: form.source.id });
      message.success('已从模板生成自定义角色，可在副本上调整');
    } else {
      await copyRole(form.source.id, payload);
      message.success('角色已复制');
    }
    form.open = false;
    await reload();
  } catch {
    // 统一拦截器已提示；保持弹窗
  } finally {
    form.submitting = false;
  }
}

// ---- 停用 / 启用 / 删除 ----

const statusForm = ref({
  open: false,
  role: null as null | TenantRoleView,
  action: 'DISABLE' as 'DISABLE' | 'ENABLE',
  reason: '',
  reasonError: '',
  submitting: false,
});

function openStatus(row: TenantRoleView) {
  statusForm.value = {
    open: true,
    role: row,
    action: row.status === 'ENABLED' ? 'DISABLE' : 'ENABLE',
    reason: '',
    reasonError: '',
    submitting: false,
  };
}

const statusVerb = computed(() =>
  statusForm.value.action === 'DISABLE' ? '停用' : '启用',
);

async function submitStatus() {
  const form = statusForm.value;
  if (!form.role) return;
  form.reasonError = validateReason(form.reason) ?? '';
  if (form.reasonError) return;
  form.submitting = true;
  try {
    await changeRoleStatus(form.role.id, {
      action: form.action,
      reason: form.reason.trim() === '' ? undefined : form.reason.trim(),
    });
    message.success(
      `${statusVerb.value}成功，在线成员的下一次请求即按新权限生效`,
    );
    form.open = false;
    await reload();
  } catch {
    // 统一拦截器已提示；保持弹窗
  } finally {
    form.submitting = false;
  }
}

async function removeRole(row: TenantRoleView) {
  try {
    await deleteRole(row.id);
    message.success('角色已删除');
    await reload();
  } catch {
    // 统一拦截器已提示（内置 / 模板 / 有成员时 40901）
  }
}

// ---- 编辑抽屉：菜单按钮 / 数据范围 / 成员（字段策略随 E07 开放）----

const editor = ref({
  open: false,
  role: null as null | TenantRoleView,
  tab: 'menus' as 'members' | 'menus' | 'scope',
  menus: [] as TenantRoleMenuNode[],
  menuChecked: [] as number[],
  menusSaving: false,
  scopeTypes: [] as RoleScopeType[],
  scopeDeptIds: [] as number[],
  orgTree: { orgs: [], singleOrg: false } as OrgTreeView,
  scopeSaving: false,
  members: [] as TenantRoleMemberView[],
  memberIds: [] as number[],
  membersSaving: false,
});

const editorRole = computed(() => editor.value.role);

async function openEditor(row: TenantRoleView) {
  editor.value = {
    open: true,
    role: row,
    tab: 'menus',
    menus: [],
    menuChecked: [],
    menusSaving: false,
    scopeTypes: [],
    scopeDeptIds: [],
    orgTree: editor.value.orgTree,
    scopeSaving: false,
    members: [],
    memberIds: [],
    membersSaving: false,
  };
  await Promise.all([
    loadEditorMenus(),
    loadEditorScope(),
    loadEditorMembers(),
  ]);
}

async function loadEditorMenus() {
  const role = editor.value.role;
  if (!role) return;
  try {
    const view = await roleMenus(role.id);
    editor.value.menus = view.menus;
    editor.value.menuChecked = checkedMenuIds(view.menus, view.menuIds);
  } catch {
    // 统一拦截器已提示
  }
}

async function saveMenus() {
  const role = editor.value.role;
  if (!role) return;
  editor.value.menusSaving = true;
  try {
    await updateRoleMenus(
      role.id,
      withAncestorDirs(editor.value.menus, editor.value.menuChecked),
    );
    message.success('菜单授权已保存，在线成员的下一次请求即生效');
    await reload();
    const updated = roles.value.find((item) => item.id === role.id) ?? role;
    editor.value.role = updated;
  } catch {
    // 统一拦截器已提示
  } finally {
    editor.value.menusSaving = false;
  }
}

async function loadEditorScope() {
  const role = editor.value.role;
  if (!role) return;
  try {
    const [view, tree] = await Promise.all([roleScope(role.id), orgTree()]);
    editor.value.scopeTypes = view.scopeTypes;
    editor.value.scopeDeptIds = view.customDeptIds;
    editor.value.orgTree = tree;
  } catch {
    // 统一拦截器已提示
  }
}

async function saveScope() {
  const state = editor.value;
  const role = state.role;
  if (!role) return;
  if (
    state.scopeTypes.some((type) => isScopeTypeRequiresDept(type)) &&
    state.scopeDeptIds.length === 0
  ) {
    message.warning('选择「自定义科室」时必须勾选至少一个科室');
    return;
  }
  state.scopeSaving = true;
  try {
    await updateRoleScope(role.id, {
      customDeptIds: state.scopeDeptIds,
      scopeTypes: state.scopeTypes,
    });
    message.success('数据范围已保存，在线成员的下一次请求即生效');
  } catch {
    // 统一拦截器已提示
  } finally {
    state.scopeSaving = false;
  }
}

async function loadEditorMembers() {
  const role = editor.value.role;
  if (!role) return;
  try {
    const view = await roleMembers(role.id);
    editor.value.members = view.members;
    editor.value.memberIds = view.members.map((member) => member.userId);
  } catch {
    // 统一拦截器已提示
  }
}

async function saveMembers() {
  const state = editor.value;
  const role = state.role;
  if (!role) return;
  state.membersSaving = true;
  try {
    const view = await updateRoleMembers(role.id, state.memberIds);
    editor.value.members = view.members;
    editor.value.memberIds = view.members.map((member) => member.userId);
    message.success('成员已保存，变动成员的下一次请求即生效');
    await reload();
    const updated = roles.value.find((item) => item.id === role.id) ?? role;
    editor.value.role = updated;
  } catch {
    // 统一拦截器已提示（模板 40901、租户管理员最后一名 40901）
  } finally {
    state.membersSaving = false;
  }
}

function toggleMember(userId: number, checked: boolean) {
  const ids = new Set(editor.value.memberIds);
  if (checked) {
    ids.add(userId);
  } else {
    ids.delete(userId);
  }
  editor.value.memberIds = [...ids];
}

const scopeOptions: Array<{ label: string; value: RoleScopeType }> = [
  'ALL',
  'WARD',
  'RESPONSIBLE_DEPT',
  'RESPONSIBLE_PATIENT',
  'DEPT',
  'SELF',
  'CUSTOM_DEPT',
].map((type) => ({
  label: tenantRoleScopeTypeText(type as RoleScopeType),
  value: type as RoleScopeType,
}));

const title = '角色管理';
const modalWidth = sizes.modal.simple;
const editorWidth = sizes.modal.multiColMax;
const textSecondary = colors.textSecondary;
</script>

<template>
  <Result v-if="isMobile" status="info" title="请在电脑或平板上操作">
    <template #extra>
      <Button type="primary" @click="router.push('/dashboard')">
        返回首页
      </Button>
    </template>
  </Result>
  <div v-else class="page" data-testid="role-page">
    <Card :bordered="false" class="crdp-card">
      <div class="head">
        <h1 class="crdp-page-title">{{ title }}</h1>
        <p class="hint" :style="{ color: textSecondary }">
          内置角色不可删除、不可修改权限；模板角色由模块开通下发，复制成自定义角色后调整；停用与授权变更对在线成员即时生效。
        </p>
      </div>
      <div class="toolbar">
        <Select
          v-model:value="filter.type"
          :options="typeFilterOptions"
          :style="{ width: '128px' }"
        />
        <Input
          v-model:value="filter.keyword"
          allow-clear
          placeholder="搜索编码、名称或说明"
          :style="{ width: `${sizes.filterInputWidth.max}px` }"
        />
        <Button
          v-if="canEdit"
          type="primary"
          data-testid="role-create"
          @click="openCreate"
        >
          新建角色
        </Button>
      </div>
      <Table
        :columns="columns"
        :data-source="visibleRoles"
        :loading="loading"
        :pagination="false"
        :row-key="(row: TenantRoleView) => row.id"
        size="middle"
      >
        <template #bodyCell="{ column, record }">
          <span v-if="column.dataIndex === 'code'" class="code">
            {{ record.code }}
          </span>
          <template v-else-if="column.dataIndex === 'name'">
            {{ record.name }}
          </template>
          <template v-else-if="column.dataIndex === 'roleType'">
            <Tag
              :color="
                record.roleType === 'BUILT_IN'
                  ? 'geekblue'
                  : record.roleType === 'TEMPLATE'
                    ? 'cyan'
                    : 'default'
              "
            >
              {{
                record.roleType === 'BUILT_IN'
                  ? '内置'
                  : record.roleType === 'TEMPLATE'
                    ? '模板'
                    : '自定义'
              }}
            </Tag>
          </template>
          <span v-else-if="column.dataIndex === 'memberCount'">
            {{ record.memberCount }}
          </span>
          <span v-else-if="column.dataIndex === 'menuCount'">
            {{ record.menuCount }}
          </span>
          <StatusTag
            v-else-if="column.dataIndex === 'status'"
            :status="record.status === 'ENABLED' ? '启用' : '停用'"
            variant="dot"
          />
          <span v-else-if="column.dataIndex === 'description'" class="desc">
            {{ record.description ?? '—' }}
          </span>
          <Space v-else-if="column.dataIndex === 'action'" :size="0" wrap>
            <Button
              v-if="canEdit && isCustomRole(record as TenantRoleView)"
              size="small"
              type="link"
              :data-testid="`role-config-${record.code}`"
              @click="openEditor(record as TenantRoleView)"
            >
              配置
            </Button>
            <Button
              v-if="canEdit && record.roleType !== 'BUILT_IN'"
              size="small"
              type="link"
              :data-testid="`role-copy-${record.code}`"
              @click="openCopy(record as TenantRoleView)"
            >
              {{ record.roleType === 'TEMPLATE' ? '从模板复制' : '复制' }}
            </Button>
            <Button
              v-if="canEdit && isCustomRole(record as TenantRoleView)"
              size="small"
              type="link"
              @click="openInfoEdit(record as TenantRoleView)"
            >
              编辑
            </Button>
            <Button
              v-if="canEdit && isCustomRole(record as TenantRoleView)"
              size="small"
              type="link"
              :danger="record.status === 'ENABLED'"
              @click="openStatus(record as TenantRoleView)"
            >
              {{ record.status === 'ENABLED' ? '停用' : '启用' }}
            </Button>
            <Button
              v-if="
                canEdit &&
                isCustomRole(record as TenantRoleView) &&
                record.memberCount === 0
              "
              size="small"
              type="link"
              danger
              @click="removeRole(record as TenantRoleView)"
            >
              删除
            </Button>
          </Space>
        </template>
      </Table>
      <div class="legend">
        <Tag>模板角色只读（PLT-ORG-005：复制后调整）</Tag>
        <Tag>字段策略页签随字段级权限（E07）开放</Tag>
      </div>
    </Card>

    <!-- 新建 / 编辑基本信息（520 短表单） -->
    <Modal
      v-model:open="infoForm.open"
      :confirm-loading="infoForm.submitting"
      :title="
        infoForm.editingId === null ? '新建角色' : `编辑角色：${infoForm.code}`
      "
      :width="modalWidth"
      cancel-text="取消"
      ok-text="保存"
      @ok="submitInfo"
    >
      <Form layout="vertical">
        <FormItem
          label="编码"
          required
          :validate-status="infoForm.codeError ? 'error' : undefined"
          :help="infoForm.codeError"
          :extra="
            infoForm.editingId === null
              ? '租户内唯一，创建后不可修改'
              : '编码创建后不可修改'
          "
        >
          <Input
            v-model:value="infoForm.code"
            :disabled="infoForm.editingId !== null"
            :maxlength="64"
            placeholder="如 FOLLOWUP_NURSE_A（示例）"
          />
        </FormItem>
        <FormItem
          label="名称"
          required
          :validate-status="infoForm.nameError ? 'error' : undefined"
          :help="infoForm.nameError"
        >
          <Input
            v-model:value="infoForm.name"
            :maxlength="64"
            placeholder="如 随访护士（示例）"
          />
        </FormItem>
        <FormItem
          label="说明"
          :validate-status="infoForm.descriptionError ? 'error' : undefined"
          :help="infoForm.descriptionError"
        >
          <Textarea
            v-model:value="infoForm.description"
            :maxlength="200"
            :rows="2"
            placeholder="职责说明（可留空）"
          />
        </FormItem>
      </Form>
    </Modal>

    <!-- 复制 / 模板应用（PLT-ORG-004「复制」、PLT-ORG-005） -->
    <Modal
      v-model:open="copyForm.open"
      :confirm-loading="copyForm.submitting"
      :title="
        copyForm.source?.roleType === 'TEMPLATE'
          ? `从模板复制：${copyForm.source.name}`
          : `复制角色：${copyForm.source?.name ?? ''}`
      "
      :width="modalWidth"
      cancel-text="取消"
      ok-text="复制"
      @ok="submitCopy"
    >
      <Alert
        class="mb-4"
        :message="
          copyForm.source?.roleType === 'TEMPLATE'
            ? '生成自定义角色副本，菜单与数据范围随后可在副本上调整。'
            : '菜单、数据范围与自定义科室一并复制到新角色。'
        "
        show-icon
        type="info"
      />
      <Form layout="vertical">
        <FormItem
          label="新角色编码"
          required
          :validate-status="copyForm.codeError ? 'error' : undefined"
          :help="copyForm.codeError"
        >
          <Input
            v-model:value="copyForm.code"
            :maxlength="64"
            placeholder="如 FOLLOWUP_NURSE_A（示例）"
          />
        </FormItem>
        <FormItem
          label="新角色名称"
          required
          :validate-status="copyForm.nameError ? 'error' : undefined"
          :help="copyForm.nameError"
        >
          <Input v-model:value="copyForm.name" :maxlength="64" />
        </FormItem>
        <FormItem label="说明">
          <Textarea
            v-model:value="copyForm.description"
            :maxlength="200"
            :rows="2"
            placeholder="默认继承来源角色说明，可改"
          />
        </FormItem>
      </Form>
    </Modal>

    <!-- 停用 / 启用确认 -->
    <Modal
      v-model:open="statusForm.open"
      :confirm-loading="statusForm.submitting"
      :ok-button-props="{ danger: statusForm.action === 'DISABLE' }"
      :title="`${statusVerb}角色：${statusForm.role?.name ?? ''}`"
      :width="modalWidth"
      cancel-text="取消"
      ok-text="确认"
      @ok="submitStatus"
    >
      <Alert
        class="mb-4"
        :message="
          statusForm.action === 'DISABLE'
            ? `停用后该角色的全部授权立即失效（当前成员 ${statusForm.role?.memberCount ?? 0} 人，下一次请求即生效）。`
            : '启用后成员授权恢复生效。'
        "
        show-icon
        type="warning"
      />
      <Form layout="vertical">
        <FormItem
          label="变更原因（可选，记入操作日志）"
          :validate-status="statusForm.reasonError ? 'error' : undefined"
          :help="statusForm.reasonError"
        >
          <Textarea
            v-model:value="statusForm.reason"
            :maxlength="200"
            :rows="2"
            show-count
            placeholder="如 职责调整（示例）"
          />
        </FormItem>
      </Form>
    </Modal>

    <!-- 编辑抽屉：菜单按钮 / 数据范围 / 成员（pages.md §10；字段策略随 E07） -->
    <Modal
      v-model:open="editor.open"
      :confirm-loading="false"
      :footer="null"
      :title="`配置角色：${editorRole?.name ?? ''}（${editorRole?.code ?? ''}）`"
      :width="editorWidth"
      cancel-text="关闭"
    >
      <Tabs v-model:active-key="editor.tab">
        <TabPane key="menus" tab="菜单按钮">
          <Alert
            class="mb-3"
            message="只能勾选平台授权范围内的菜单与按钮（标「不可分配」的项由平台放开后可选）。"
            show-icon
            type="info"
          />
          <Spin :spinning="editor.menus.length === 0">
            <Empty
              v-if="editor.menus.length === 0"
              :image="Empty.PRESENTED_IMAGE_SIMPLE"
              description="没有可分配的菜单"
            />
            <Tree
              v-else
              v-model:checked-keys="editor.menuChecked"
              checkable
              default-expand-all
              :tree-data="menuTreeData(editor.menus)"
            />
          </Spin>
          <div class="tab-actions">
            <Button
              type="primary"
              :loading="editor.menusSaving"
              @click="saveMenus"
            >
              保存菜单授权
            </Button>
          </div>
        </TabPane>
        <TabPane key="scope" tab="数据范围">
          <Alert
            class="mb-3"
            message="多选取并集；「本人负责病区 / 科室」按用户个人设置取值，不在角色上勾选；生效口径见 E07。"
            show-icon
            type="info"
          />
          <CheckboxGroup v-model:value="editor.scopeTypes">
            <div class="scope-list">
              <Checkbox
                v-for="option in scopeOptions"
                :key="option.value"
                :value="option.value"
              >
                {{ option.label }}
              </Checkbox>
            </div>
          </CheckboxGroup>
          <FormItem
            v-if="
              editor.scopeTypes.some((type) => isScopeTypeRequiresDept(type))
            "
            class="mt-3"
            label="自定义科室（多选）"
            required
          >
            <TreeSelect
              v-model:value="editor.scopeDeptIds"
              :tree-data="deptTreeOptions(editor.orgTree.orgs)"
              tree-checkable
              tree-default-expand-all
              placeholder="勾选该角色可见的科室"
              :style="{ width: '100%' }"
            />
          </FormItem>
          <div class="tab-actions">
            <Button
              type="primary"
              :loading="editor.scopeSaving"
              @click="saveScope"
            >
              保存数据范围
            </Button>
          </div>
        </TabPane>
        <TabPane key="members" tab="成员">
          <Alert
            class="mb-3"
            :message="`当前成员 ${editor.members.length} 人；移除或新增后，变动成员的下一次请求即按新权限生效。`"
            show-icon
            type="info"
          />
          <div v-if="editor.members.length > 0" class="member-list">
            <Tag
              v-for="member in editor.members"
              :key="member.userId"
              closable
              @close.prevent="toggleMember(member.userId, false)"
            >
              {{ member.displayName }}（{{ member.username }}）
            </Tag>
          </div>
          <Empty
            v-else
            :image="Empty.PRESENTED_IMAGE_SIMPLE"
            description="还没有成员"
          />
          <FormItem class="mt-3" label="添加成员（输入姓名或账号搜索本院用户）">
            <!-- PLT-ORG-012 共用用户选择器（E06-T09 替换暂行候选接口）：选中即加入成员 -->
            <UserSelector
              :value="null"
              @change="
                (value) => value !== null && toggleMember(Number(value), true)
              "
            />
          </FormItem>
          <div class="tab-actions">
            <Button
              type="primary"
              :loading="editor.membersSaving"
              @click="saveMembers"
            >
              保存成员
            </Button>
          </div>
        </TabPane>
        <TabPane key="fields" tab="字段策略" disabled>
          <Alert
            message="字段级权限与字段策略配置随字段策略任务（E07）开放，未配置的角色取平台默认策略。"
            show-icon
            type="info"
          />
        </TabPane>
      </Tabs>
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

.toolbar {
  display: flex;
  gap: 8px;
  margin-bottom: 12px;
}

.legend {
  display: flex;
  gap: 8px;
  margin-top: 12px;
}

.code {
  font-family: monospace;
}

.desc {
  font-size: 13px;
  color: #86909c;
}

.tab-actions {
  padding-top: 12px;
  margin-top: 16px;
  border-top: 1px solid #f0f0f0;
}

.scope-list {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px 16px;
}

.member-list {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
</style>
