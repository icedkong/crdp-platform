<script lang="ts" setup>
import type {
  OrgTreeView,
  TenantRoleView,
  TenantUserDetailView,
  TenantUserView,
  UserStatus,
  WardOptionView,
} from '@crdp/api';

import { computed, reactive, ref } from 'vue';
import { useRouter } from 'vue-router';

import { nextStatusActions, userStatusText } from '@crdp/api';
import { StatusTag } from '@crdp/status-tag';
import { colors, sizes } from '@crdp/tokens';
import {
  Alert,
  Button,
  Card,
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
  Tag,
  Textarea,
  TreeSelect,
} from 'ant-design-vue';

import { orgTree } from '#/api/core/org';
import { listRoles } from '#/api/core/role';
import {
  changeUserStatus,
  createUser,
  listUsers,
  resetUserPassword,
  unlockUser,
  updateUser,
  userDetail,
  wardOptions,
} from '#/api/core/user';
import { useBreakpoint } from '#/reference/use-breakpoint';
import { usePermission } from '#/reference/use-permission';

import PermissionPreviewDrawer from './permission-preview-drawer.vue';
import {
  deptTreeOptions,
  mainDeptText,
  responsibleScopeHint,
  toFormModel,
  validateAssignments,
  validateForm,
  validateReason,
  wardSelectOptions,
} from './user-logic';

defineOptions({ name: 'HospitalUser' });

/**
 * 用户管理（E06-T07 / PLT-ORG-006、008 · pages.md §10）：分页列表（关键字 / 科室 / 病区 /
 * 角色 / 状态筛选，手机号脱敏）+ 新建 / 编辑弹窗（主科室、角色、负责病区与负责科室多选，
 * 变更立即生效）+ 停用 / 启用 / 离职（离职终态、立即下线）+ 重置密码（临时密码只下发一次）与解锁。
 * 权限预览（E06-T09 / PLT-ORG-009、A-WARD-009）在操作列打开抽屉，展示最终菜单、权限码与
 * 数据范围（含可见病区）及来源角色。批量导入（E06-T08）与导出随后续任务开放。
 * 查看 tenant:user:view，维护 tenant:user:edit，重置密码与解锁 tenant:user:reset。
 */
const router = useRouter();
const { isMobile } = useBreakpoint();
const { hasPermission } = usePermission();
const canEdit = computed(() => hasPermission('tenant:user:edit'));
const canReset = computed(() => hasPermission('tenant:user:reset'));

const PAGE_SIZE = 20;
const loading = ref(false);
const users = ref<TenantUserView[]>([]);
const total = ref(0);
const filter = reactive({
  keyword: '',
  status: undefined as undefined | UserStatus,
  deptId: undefined as number | undefined,
  roleId: undefined as number | undefined,
  wardId: undefined as number | undefined,
});
const page = reactive({ pageNo: 1, pageSize: PAGE_SIZE });

const roles = ref<TenantRoleView[]>([]);
const wards = ref<WardOptionView[]>([]);
const orgTreeData = ref<OrgTreeView>({ orgs: [], singleOrg: false });

const statusOptions = [
  { label: '全部状态', value: undefined },
  { label: '启用', value: 'ENABLED' },
  { label: '停用', value: 'DISABLED' },
  { label: '离职', value: 'RESIGNED' },
];

const columns = [
  { dataIndex: 'username', title: '账号', width: 150 },
  { dataIndex: 'displayName', title: '姓名', width: 130 },
  { dataIndex: 'staffNo', title: '工号', width: 110 },
  { dataIndex: 'mainDeptName', title: '主科室', width: 130 },
  { dataIndex: 'roleNames', title: '角色', width: 170 },
  { dataIndex: 'phoneMasked', title: '手机号', width: 130 },
  { dataIndex: 'status', title: '状态', width: 84 },
  { dataIndex: 'createdAt', title: '创建时间', width: 170 },
  { dataIndex: 'action', fixed: 'right' as const, title: '操作', width: 320 },
];

async function reload(pageNo = page.pageNo) {
  loading.value = true;
  try {
    const result = await listUsers({
      deptId: filter.deptId,
      keyword: filter.keyword,
      pageNo,
      pageSize: page.pageSize,
      roleId: filter.roleId,
      status: filter.status,
      wardId: filter.wardId,
    });
    users.value = result.items;
    total.value = result.total;
    page.pageNo = result.pageNo;
  } catch {
    // 错误提示由统一拦截器弹出（含 traceId）；保留旧数据便于重试
  } finally {
    loading.value = false;
  }
}

async function loadOptions() {
  try {
    const [roleList, wardList, tree] = await Promise.all([
      listRoles(),
      wardOptions(),
      orgTree(),
    ]);
    roles.value = roleList;
    wards.value = wardList;
    orgTreeData.value = tree;
  } catch {
    // 统一拦截器已提示
  }
}

void reload(1);
void loadOptions();

function search() {
  void reload(1);
}

const roleOptions = computed(() =>
  roles.value
    .filter((role) => role.roleType !== 'TEMPLATE')
    .map((role) => ({ label: role.name, value: role.id })),
);

const wardOptionsView = computed(() => wardSelectOptions(wards.value));

const mainDeptTree = computed(() => deptTreeOptions(orgTreeData.value.orgs));

// ---- 新建 / 编辑弹窗 ----

const editor = reactive({
  open: false,
  creating: false,
  userId: 0,
  username: '',
  displayName: '',
  staffNo: '',
  phone: '',
  mainDeptId: undefined as number | undefined,
  roleIds: [] as number[],
  wardIds: [] as number[],
  deptScopeIds: [] as number[],
  errors: {} as Record<string, string>,
  submitting: false,
});

function openCreate() {
  editor.open = true;
  editor.creating = true;
  editor.userId = 0;
  editor.username = '';
  editor.displayName = '';
  editor.staffNo = '';
  editor.phone = '';
  editor.mainDeptId = undefined;
  editor.roleIds = [];
  editor.wardIds = [];
  editor.deptScopeIds = [];
  editor.errors = {};
  editor.submitting = false;
}

async function openEdit(row: TenantUserView) {
  editor.open = true;
  editor.creating = false;
  editor.submitting = false;
  editor.errors = {};
  try {
    const detail: TenantUserDetailView = await userDetail(row.id);
    const model = toFormModel(detail);
    editor.userId = detail.id;
    editor.username = model.username;
    editor.displayName = model.displayName;
    editor.staffNo = model.staffNo;
    editor.phone = '';
    editor.mainDeptId = model.mainDeptId ?? undefined;
    editor.roleIds = model.roleIds;
    editor.wardIds = model.wardIds;
    editor.deptScopeIds = model.deptScopeIds;
  } catch {
    editor.open = false;
  }
}

async function submitEditor() {
  const errors = validateForm(
    {
      displayName: editor.displayName,
      deptScopeIds: editor.deptScopeIds,
      mainDeptId: editor.mainDeptId ?? null,
      phone: editor.phone,
      roleIds: editor.roleIds,
      staffNo: editor.staffNo,
      username: editor.username,
      wardIds: editor.wardIds,
    },
    { creating: editor.creating },
  );
  const assignmentError = validateAssignments(
    editor.mainDeptId,
    editor.roleIds,
  );
  editor.errors = { ...errors };
  if (Object.values(errors).some(Boolean) || assignmentError) {
    if (assignmentError) message.warning(assignmentError);
    return;
  }
  const mainDeptId = editor.mainDeptId;
  if (!mainDeptId) {
    message.warning('主科室必填');
    return;
  }
  editor.submitting = true;
  const phoneValue = editor.phone.trim() === '' ? null : editor.phone.trim();
  try {
    if (editor.creating) {
      const created = await createUser({
        displayName: editor.displayName.trim(),
        deptScopeIds: editor.deptScopeIds,
        mainDeptId,
        phone: editor.phone.trim(),
        roleIds: editor.roleIds,
        staffNo: editor.staffNo.trim() === '' ? null : editor.staffNo.trim(),
        username: editor.username.trim(),
        wardIds: editor.wardIds,
      });
      message.success('用户已创建，初始密码请当面告知本人（只显示一次）');
      editor.open = false;
      passwordResult.open = true;
      passwordResult.username = created.user.username;
      passwordResult.temporaryPassword = created.temporaryPassword;
      passwordResult.kind = '初始密码';
      await reload(1);
    } else {
      await updateUser(editor.userId, {
        displayName: editor.displayName.trim(),
        deptScopeIds: editor.deptScopeIds,
        mainDeptId,
        phone: phoneValue,
        roleIds: editor.roleIds,
        staffNo: editor.staffNo.trim() === '' ? null : editor.staffNo.trim(),
        wardIds: editor.wardIds,
      });
      message.success('用户已保存，负责病区与负责科室的下一次请求即生效');
      editor.open = false;
      await reload();
    }
  } catch {
    // 统一拦截器已提示；保持弹窗
  } finally {
    editor.submitting = false;
  }
}

// ---- 重置密码（PLT-ORG-008）：临时密码只下发一次 ----

const passwordResult = reactive({
  open: false,
  kind: '临时密码',
  username: '',
  temporaryPassword: '',
});

const resetConfirm = reactive({
  open: false,
  row: null as null | TenantUserView,
});

function openReset(row: TenantUserView) {
  resetConfirm.open = true;
  resetConfirm.row = row;
}

async function submitReset() {
  const row = resetConfirm.row;
  if (!row) return;
  try {
    const result = await resetUserPassword(row.id);
    resetConfirm.open = false;
    passwordResult.open = true;
    passwordResult.kind = '临时密码';
    passwordResult.username = result.username;
    passwordResult.temporaryPassword = result.temporaryPassword;
    await reload();
  } catch {
    // 统一拦截器已提示
  }
}

// ---- 解锁（PLT-ORG-008 / E04-T05 接口）----

async function unlock(row: TenantUserView) {
  try {
    await unlockUser(row.id);
    message.success(`已解锁 ${row.displayName}（${row.username}）的登录锁定`);
  } catch {
    // 统一拦截器已提示
  }
}

// ---- 权限预览（E06-T09 / PLT-ORG-009、A-WARD-009、C-WARD-008）----

const preview = reactive({
  open: false,
  row: null as null | TenantUserView,
});

function openPreview(row: TenantUserView) {
  preview.row = row;
  preview.open = true;
}

// ---- 状态变更（停用 / 启用 / 离职）----

const statusForm = reactive({
  open: false,
  row: null as null | TenantUserView,
  action: 'DISABLE' as 'DISABLE' | 'ENABLE' | 'RESIGN',
  reason: '',
  submitting: false,
});

/** 状态动作文案（与模板里操作按钮同口径，避免嵌套三元）。 */
const STATUS_ACTION_TEXT: Record<string, string> = {
  DISABLE: '停用',
  ENABLE: '启用',
  RESIGN: '离职',
};

const statusVerb = computed(
  () => STATUS_ACTION_TEXT[statusForm.action] ?? statusForm.action,
);

function openStatus(
  row: TenantUserView,
  action: 'DISABLE' | 'ENABLE' | 'RESIGN',
) {
  statusForm.open = true;
  statusForm.row = row;
  statusForm.action = action;
  statusForm.reason = '';
  statusForm.submitting = false;
}

async function submitStatus() {
  const row = statusForm.row;
  if (!row) return;
  if (validateReason(statusForm.reason)) {
    message.warning('变更原因过长（最多 200 个字符）');
    return;
  }
  statusForm.submitting = true;
  try {
    await changeUserStatus(row.id, {
      action: statusForm.action,
      reason:
        statusForm.reason.trim() === '' ? undefined : statusForm.reason.trim(),
    });
    message.success(
      statusForm.action === 'ENABLE'
        ? '已启用，该用户可重新登录'
        : `${statusVerb.value}成功，该账号全部会话已下线`,
    );
    statusForm.open = false;
    await reload();
  } catch {
    // 统一拦截器已提示（最后一名租户管理员 40901 等）
  } finally {
    statusForm.submitting = false;
  }
}

const title = '用户管理';
const modalWidth = sizes.modal.multiColMax;
const simpleWidth = sizes.modal.simple;
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
  <div v-else class="page" data-testid="user-page">
    <Card :bordered="false" class="crdp-card">
      <div class="head">
        <h1 class="crdp-page-title">{{ title }}</h1>
        <p class="hint" :style="{ color: textSecondary }">
          负责病区与负责科室按用户分配、可多选，保存后立即生效；停用与离职立即下线；离职为终态。
        </p>
      </div>
      <div class="toolbar">
        <Input
          v-model:value="filter.keyword"
          allow-clear
          placeholder="搜索账号、姓名或工号"
          :style="{ width: `${sizes.filterInputWidth.max}px` }"
          @press-enter="search"
        />
        <TreeSelect
          v-model:value="filter.deptId"
          allow-clear
          placeholder="全部科室"
          :style="{ width: '168px' }"
          :tree-data="deptTreeOptions(orgTreeData.orgs, { onlyEnabled: false })"
        />
        <Select
          v-model:value="filter.wardId"
          allow-clear
          placeholder="全部病区"
          :options="wardOptionsView"
          :style="{ width: '168px' }"
        />
        <Select
          v-model:value="filter.roleId"
          allow-clear
          placeholder="全部角色"
          :options="roles.map((role) => ({ label: role.name, value: role.id }))"
          :style="{ width: '140px' }"
        />
        <Select
          v-model:value="filter.status"
          allow-clear
          placeholder="全部状态"
          :options="statusOptions"
          :style="{ width: '120px' }"
        />
        <Button @click="search">查询</Button>
        <Button
          v-if="canEdit"
          type="primary"
          data-testid="user-create"
          @click="openCreate"
        >
          新建用户
        </Button>
      </div>
      <Table
        :columns="columns"
        :data-source="users"
        :loading="loading"
        :pagination="{
          current: page.pageNo,
          pageSize: page.pageSize,
          total,
          showSizeChanger: false,
          onChange: (no: number) => reload(no),
        }"
        :row-key="(row: TenantUserView) => row.id"
        size="middle"
      >
        <template #bodyCell="{ column, record }">
          <span v-if="column.dataIndex === 'username'" class="code">
            {{ record.username }}
          </span>
          <template v-else-if="column.dataIndex === 'displayName'">
            {{ record.displayName }}
          </template>
          <span v-else-if="column.dataIndex === 'staffNo'">
            {{ record.staffNo ?? '—' }}
          </span>
          <span v-else-if="column.dataIndex === 'mainDeptName'">
            {{ mainDeptText(record as TenantUserView) }}
          </span>
          <span v-else-if="column.dataIndex === 'roleNames'">
            <Tag v-for="name in record.roleNames" :key="name">{{ name }}</Tag>
            <span v-if="record.roleNames.length === 0">—</span>
          </span>
          <span v-else-if="column.dataIndex === 'phoneMasked'" class="code">
            {{ record.phoneMasked ?? '—' }}
          </span>
          <StatusTag
            v-else-if="column.dataIndex === 'status'"
            :status="userStatusText(record.status)"
            variant="dot"
          />
          <span v-else-if="column.dataIndex === 'createdAt'" class="desc">
            {{ record.createdAt ?? '—' }}
          </span>
          <Space v-else-if="column.dataIndex === 'action'" :size="0" wrap>
            <Button
              size="small"
              type="link"
              :data-testid="`user-preview-${record.username}`"
              @click="openPreview(record as TenantUserView)"
            >
              权限预览
            </Button>
            <Button
              v-if="canEdit"
              size="small"
              type="link"
              :data-testid="`user-edit-${record.username}`"
              @click="openEdit(record as TenantUserView)"
            >
              编辑
            </Button>
            <Button
              v-for="action in nextStatusActions(record.status as UserStatus)"
              :key="action"
              v-show="canEdit"
              size="small"
              type="link"
              :danger="action !== 'ENABLE'"
              @click="openStatus(record as TenantUserView, action)"
            >
              {{
                action === 'DISABLE'
                  ? '停用'
                  : action === 'ENABLE'
                    ? '启用'
                    : '离职'
              }}
            </Button>
            <Button
              v-if="canReset"
              size="small"
              type="link"
              :data-testid="`user-reset-${record.username}`"
              @click="openReset(record as TenantUserView)"
            >
              重置密码
            </Button>
            <Button
              v-if="canReset"
              size="small"
              type="link"
              @click="unlock(record as TenantUserView)"
            >
              解锁
            </Button>
          </Space>
        </template>
        <template #emptyText>
          <Empty description="没有符合条件的用户" />
        </template>
      </Table>
      <div class="legend">
        <Tag>批量导入随 E06-T08 开放</Tag>
        <Tag>导出（敏感权限）随导出框架开放</Tag>
        <Tag>兼职科室 / 岗位职称随字典与数据范围任务开放</Tag>
      </div>
    </Card>

    <!-- 新建 / 编辑（800–960 弹窗；负责病区与负责科室多选） -->
    <Modal
      v-model:open="editor.open"
      :confirm-loading="editor.submitting"
      :title="editor.creating ? '新建用户' : `编辑用户：${editor.username}`"
      :width="modalWidth"
      cancel-text="取消"
      ok-text="保存"
      @ok="submitEditor"
    >
      <Form layout="vertical">
        <div class="grid">
          <FormItem
            label="账号"
            required
            :validate-status="editor.errors.username ? 'error' : undefined"
            :help="
              editor.errors.username ||
              (editor.creating ? '本院唯一，创建后不可修改' : '创建后不可修改')
            "
          >
            <Input
              v-model:value="editor.username"
              :disabled="!editor.creating"
              :maxlength="64"
              placeholder="如 nurse.demo（示例）"
            />
          </FormItem>
          <FormItem
            label="姓名"
            required
            :validate-status="editor.errors.displayName ? 'error' : undefined"
            :help="editor.errors.displayName"
          >
            <Input
              v-model:value="editor.displayName"
              :maxlength="64"
              placeholder="如 张医生（示例）"
            />
          </FormItem>
          <FormItem
            label="工号"
            :validate-status="editor.errors.staffNo ? 'error' : undefined"
            :help="editor.errors.staffNo"
          >
            <Input
              v-model:value="editor.staffNo"
              :maxlength="64"
              placeholder="可留空"
            />
          </FormItem>
          <FormItem
            label="手机号"
            required
            :validate-status="editor.errors.phone ? 'error' : undefined"
            :help="
              editor.errors.phone ||
              (editor.creating
                ? '本院唯一，用于短信登录与双因素'
                : '留空保持不变；显示为脱敏值')
            "
          >
            <Input
              v-model:value="editor.phone"
              :maxlength="11"
              :placeholder="
                editor.creating ? '如 13900000002（示例）' : '留空保持不变'
              "
            />
          </FormItem>
          <FormItem label="主科室" required>
            <TreeSelect
              v-model:value="editor.mainDeptId"
              :tree-data="mainDeptTree"
              tree-default-expand-all
              placeholder="选择主科室（决定「本科室」数据范围）"
              :style="{ width: '100%' }"
            />
          </FormItem>
          <FormItem label="角色" required>
            <Select
              v-model:value="editor.roleIds"
              mode="multiple"
              :options="roleOptions"
              placeholder="至少分配一个角色（模板角色请先复制）"
              :style="{ width: '100%' }"
            />
          </FormItem>
          <FormItem label="负责病区（可多选）">
            <Select
              v-model:value="editor.wardIds"
              mode="multiple"
              allow-clear
              :options="wardOptionsView"
              placeholder="决定「本人负责病区」范围；不分配则按角色范围"
              :style="{ width: '100%' }"
            />
          </FormItem>
          <FormItem label="负责科室（可多选）">
            <TreeSelect
              v-model:value="editor.deptScopeIds"
              :tree-data="mainDeptTree"
              tree-checkable
              tree-default-expand-all
              allow-clear
              placeholder="决定「本人负责科室」范围；可留空"
              :style="{ width: '100%' }"
            />
          </FormItem>
        </div>
        <Alert
          :message="
            responsibleScopeHint(
              editor.wardIds.length,
              editor.deptScopeIds.length,
            )
          "
          show-icon
          type="info"
        />
      </Form>
    </Modal>

    <!-- 状态变更确认 -->
    <Modal
      v-model:open="statusForm.open"
      :confirm-loading="statusForm.submitting"
      :ok-button-props="{ danger: statusForm.action !== 'ENABLE' }"
      :title="`${statusVerb}用户：${statusForm.row?.displayName ?? ''}（${statusForm.row?.username ?? ''}）`"
      :width="simpleWidth"
      cancel-text="取消"
      ok-text="确认"
      @ok="submitStatus"
    >
      <Alert
        class="mb-4"
        :message="
          statusForm.action === 'ENABLE'
            ? '启用后该用户可重新登录。'
            : statusForm.action === 'RESIGN'
              ? '离职为终态，不能重新启用；该账号全部会话立即下线，行保留以供审计追溯。'
              : '停用后该账号立即下线、不能登录，可随时重新启用。'
        "
        show-icon
        type="warning"
      />
      <Form layout="vertical">
        <FormItem label="变更原因（可选，记入操作日志）">
          <Textarea
            v-model:value="statusForm.reason"
            :maxlength="200"
            :rows="2"
            show-count
            placeholder="如 长期休假（示例）"
          />
        </FormItem>
      </Form>
    </Modal>

    <!-- 重置密码确认 + 一次性结果 -->
    <Modal
      v-model:open="resetConfirm.open"
      :title="`重置密码：${resetConfirm.row?.displayName ?? ''}（${resetConfirm.row?.username ?? ''}）`"
      :width="simpleWidth"
      cancel-text="取消"
      ok-text="重置"
      :ok-button-props="{ danger: true }"
      @ok="submitReset"
    >
      <Alert
        message="将生成一次性临时密码：该账号全部会话失效、解除登录锁定，首次登录强制修改密码。临时密码只在下一步显示一次，请当面告知本人。"
        show-icon
        type="warning"
      />
    </Modal>
    <Modal
      v-model:open="passwordResult.open"
      :footer="null"
      :title="`${passwordResult.kind}：${passwordResult.username}`"
      :width="simpleWidth"
      cancel-text="关闭"
    >
      <Spin :spinning="false">
        <p class="temp-password">{{ passwordResult.temporaryPassword }}</p>
        <p class="hint" :style="{ color: textSecondary }">
          只显示这一次（不落日志与审计）。请复制后当面告知本人；首次登录将强制修改密码。
        </p>
      </Spin>
    </Modal>

    <!-- 权限预览抽屉（E06-T09）：菜单、权限码与数据范围（含病区）及来源角色 -->
    <PermissionPreviewDrawer v-model:open="preview.open" :user="preview.row" />
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
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 12px;
}

.legend {
  display: flex;
  flex-wrap: wrap;
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

.grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  column-gap: 16px;
}

.temp-password {
  margin: 8px 0;
  font-family: monospace;
  font-size: 20px;
  font-weight: 600;
  letter-spacing: 1px;
}
</style>
