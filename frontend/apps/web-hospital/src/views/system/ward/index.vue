<script lang="ts" setup>
import type {
  UserOptionView,
  WardFormState,
  WardMemberView,
  WardView,
} from '@crdp/api';

import { computed, onMounted, reactive, ref } from 'vue';

import { Page } from '@vben/common-ui';

import {
  buildWardPayload,
  emptyWardForm,
  userOptionLabel,
  validateWardForm,
  WARD_STATUS_TEXT,
  wardDisableBlockedText,
  wardFormOf,
  wardTypeText,
} from '@crdp/api';
import {
  Alert,
  Button,
  Card,
  Drawer,
  Empty,
  Form,
  FormItem,
  Input,
  InputNumber,
  message,
  Modal,
  Popconfirm,
  Result,
  Select,
  SelectOption,
  Spin,
  Table,
  Tag,
} from 'ant-design-vue';

import { userOptions } from '#/api/core/user';
import {
  changeWardStatus,
  createWard,
  fetchWardMembers,
  fetchWards,
  updateWard,
} from '#/api/core/ward';
import { useBreakpoint } from '#/reference/use-breakpoint';

defineOptions({ name: 'HospitalWard' });

/**
 * E13-T08 / A-WARD-001、A-WARD-008、C-WARD-001 · 病区管理（/system/ward，pages.md §10）：
 * 病区 / 中心独立字典（不挂科室下，医院已确认）：编码唯一且创建后不可改；类型、别名、
 * 默认随访负责人、排序可维护；查看成员与患者数；停用不删除——病区下仍有在册患者时
 * 不能停用，须先转出（40901）。增改停由后端写审计；成员分配在用户管理「负责病区」
 * （A-WARD-002）。仅桌面 / 平板。
 */
const { isMobile } = useBreakpoint();

const loading = ref(false);
const wards = ref<WardView[]>([]);
const users = ref<UserOptionView[]>([]);

const saveOpen = ref(false);
const saving = ref(false);
const editing = ref<null | WardView>(null);
const form = reactive<WardFormState>(emptyWardForm());
const errors = ref<Partial<Record<keyof WardFormState, string>>>({});

const membersOpen = ref(false);
const membersLoading = ref(false);
const membersWard = ref<null | WardView>(null);
const members = ref<WardMemberView[]>([]);

const columns = [
  { dataIndex: 'sortNo', key: 'sortNo', title: '排序', width: 70 },
  { dataIndex: 'code', key: 'code', title: '编码', width: 140 },
  { dataIndex: 'name', key: 'name', title: '名称' },
  { key: 'wardType', title: '类型', width: 90 },
  { key: 'aliases', title: '别名' },
  { key: 'defaultOwnerName', title: '默认随访负责人', width: 150 },
  {
    dataIndex: 'patientCount',
    key: 'patientCount',
    title: '患者数',
    width: 90,
  },
  { dataIndex: 'memberCount', key: 'memberCount', title: '成员数', width: 90 },
  { key: 'status', title: '状态', width: 90 },
  { key: 'action', title: '操作', width: 200 },
];

const memberColumns = [
  { dataIndex: 'username', title: '账号' },
  { dataIndex: 'displayName', title: '姓名' },
];

const userSelectOptions = computed(() =>
  users.value.map((user) => ({
    label: userOptionLabel(user),
    value: user.id,
  })),
);

async function load() {
  loading.value = true;
  try {
    const result = await fetchWards();
    wards.value = result.items;
  } catch (error) {
    wards.value = [];
    message.error(error instanceof Error ? error.message : '病区列表加载失败');
  } finally {
    loading.value = false;
  }
}

async function loadUsers() {
  try {
    users.value = await userOptions();
  } catch {
    // 负责人候选加载失败不阻断页面：下拉为空，可重开页面重试
    users.value = [];
  }
}

onMounted(() => {
  void load();
  void loadUsers();
});

// ---------------------------------------------------------------- 新建 / 编辑（A-WARD-001）

function openCreate() {
  editing.value = null;
  Object.assign(form, emptyWardForm());
  errors.value = {};
  saveOpen.value = true;
}

function openEdit(row: WardView) {
  editing.value = row;
  Object.assign(form, wardFormOf(row));
  errors.value = {};
  saveOpen.value = true;
}

async function submitSave() {
  errors.value = validateWardForm(form, { codeLocked: editing.value !== null });
  if (Object.keys(errors.value).length > 0) return;
  const payload = buildWardPayload(form);
  saving.value = true;
  try {
    if (editing.value) {
      await updateWard(editing.value.id, payload);
      message.success(`已保存「${payload.name}」的调整`);
    } else {
      await createWard(payload);
      message.success(`已新建「${payload.name}」`);
    }
    saveOpen.value = false;
    await load();
  } catch (error) {
    const body = error as { message?: string };
    message.error(body?.message || '保存失败，请稍后重试');
  } finally {
    saving.value = false;
  }
}

// ---------------------------------------------------------------- 停用 / 启用（A-WARD-008）

function disableConfirmTitle(row: WardView): string {
  return row.patientCount > 0
    ? wardDisableBlockedText(row)
    : `停用后「${row.name}」不再进登记与转病区候选，确定停用？`;
}

async function toggleStatus(row: WardView) {
  const next = row.status === 'ENABLED' ? 'DISABLED' : 'ENABLED';
  try {
    await changeWardStatus(row.id, { status: next });
    message.success(
      next === 'DISABLED' ? `已停用「${row.name}」` : `已启用「${row.name}」`,
    );
    await load();
  } catch (error) {
    // 仍有在册患者等业务拦截（40901）优先展示后端 message
    const body = error as { message?: string };
    message.error(body?.message || '状态变更失败，请稍后重试');
    await load();
  }
}

// ---------------------------------------------------------------- 成员与患者数（pages.md §10）

async function openMembers(row: WardView) {
  membersWard.value = row;
  membersOpen.value = true;
  membersLoading.value = true;
  try {
    const result = await fetchWardMembers(row.id);
    members.value = result.members;
  } catch {
    members.value = [];
    message.error('成员加载失败，请稍后重试');
  } finally {
    membersLoading.value = false;
  }
}
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
    description="病区 / 中心是独立字典（不挂科室下）：编码本院唯一、创建后不可改；停用前须先把在册患者转出；成员在用户管理的「负责病区」分配。"
    title="病区管理"
  >
    <Card :bordered="false">
      <div class="title-row">
        <span class="hint">
          成员数 = 负责本病区的启用用户；患者数 =
          本病区在册患者（停用拦截按它计算）。
        </span>
        <Button data-testid="ward-create" type="primary" @click="openCreate">
          + 新建病区
        </Button>
      </div>

      <Table
        :columns="columns"
        :data-source="wards.map((row) => ({ ...row, key: row.id }))"
        :loading="loading"
        :pagination="false"
        size="middle"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'wardType'">
            {{ wardTypeText(record.wardType) }}
          </template>
          <template v-else-if="column.key === 'aliases'">
            <Tag v-for="alias in record.aliases" :key="alias">{{ alias }}</Tag>
            <span v-if="record.aliases.length === 0">—</span>
          </template>
          <template v-else-if="column.key === 'defaultOwnerName'">
            {{ record.defaultOwnerName ?? '—' }}
          </template>
          <template v-else-if="column.key === 'status'">
            <Tag :color="record.status === 'ENABLED' ? 'success' : 'default'">
              {{ WARD_STATUS_TEXT[record.status] ?? record.status }}
            </Tag>
          </template>
          <template v-else-if="column.key === 'action'">
            <Button size="small" type="link" @click="openMembers(record)">
              成员
            </Button>
            <Button size="small" type="link" @click="openEdit(record)">
              编辑
            </Button>
            <Popconfirm
              cancel-text="取消"
              :ok-text="record.status === 'ENABLED' ? '停用' : '启用'"
              :ok-type="record.status === 'ENABLED' ? 'danger' : 'primary'"
              :title="
                record.status === 'ENABLED'
                  ? disableConfirmTitle(record)
                  : `启用后「${record.name}」重新进登记与转病区候选，确定启用？`
              "
              @confirm="toggleStatus(record)"
            >
              <Button
                :danger="record.status === 'ENABLED'"
                size="small"
                type="link"
              >
                {{ record.status === 'ENABLED' ? '停用' : '启用' }}
              </Button>
            </Popconfirm>
          </template>
        </template>
        <template #emptyText>
          <Empty description="暂无病区，点击「新建病区」维护字典" />
        </template>
      </Table>
    </Card>

    <Modal
      :confirm-loading="saving"
      :open="saveOpen"
      :title="editing ? `编辑病区（${editing.name}）` : '新建病区'"
      :width="520"
      @cancel="saveOpen = false"
      @ok="submitSave"
    >
      <Alert
        v-if="editing && editing.patientCount > 0"
        :message="`该病区下仍有 ${editing.patientCount} 名在册患者：停用病区前须先转出这些患者（A-WARD-008）。`"
        class="notice"
        show-icon
        type="warning"
      />
      <Form layout="vertical" @submit.prevent>
        <FormItem
          :extra="errors.code"
          :validate-status="errors.code ? 'error' : undefined"
          label="病区编码（本院唯一，创建后不可改）"
        >
          <Input
            v-model:value="form.code"
            :disabled="editing !== null"
            :maxlength="64"
            placeholder="如 W-ICU-1"
          />
        </FormItem>
        <FormItem
          :extra="errors.name"
          :validate-status="errors.name ? 'error' : undefined"
          label="病区名称"
        >
          <Input
            v-model:value="form.name"
            :maxlength="128"
            placeholder="如 心外一病区"
          />
        </FormItem>
        <FormItem
          :extra="errors.wardType"
          :validate-status="errors.wardType ? 'error' : undefined"
          label="类型"
        >
          <Select v-model:value="form.wardType" placeholder="病区 / 中心">
            <SelectOption value="WARD">病区</SelectOption>
            <SelectOption value="CENTER">中心</SelectOption>
          </Select>
        </FormItem>
        <FormItem
          :extra="
            errors.aliases ?? '多个别名用分号分隔，用于匹配历史数据中的不同写法'
          "
          :validate-status="errors.aliases ? 'error' : undefined"
          label="别名（可选）"
        >
          <Input
            v-model:value="form.aliases"
            :maxlength="255"
            placeholder="如 心外一;心外1"
          />
        </FormItem>
        <FormItem label="默认随访负责人（可选）">
          <Select
            v-model:value="form.defaultOwnerId"
            allow-clear
            :filter-option="
              (input: string, option: { label?: string }) =>
                (option?.label ?? '').includes(input)
            "
            :options="userSelectOptions"
            placeholder="任务无责任医生时的默认负责人"
            show-search
          />
        </FormItem>
        <FormItem
          :extra="errors.sortNo"
          :validate-status="errors.sortNo ? 'error' : undefined"
          label="排序"
        >
          <InputNumber v-model:value="form.sortNo" :max="9999" :min="0" />
        </FormItem>
      </Form>
    </Modal>

    <Drawer
      :open="membersOpen"
      :title="`病区成员 · ${membersWard?.name ?? ''}`"
      :width="480"
      @close="membersOpen = false"
    >
      <p class="hint">
        在册患者 {{ membersWard?.patientCount ?? '—' }} 名；成员负责病区的分配在
        用户管理 → 编辑用户 →「负责病区」（A-WARD-002）。
      </p>
      <Spin :spinning="membersLoading">
        <Table
          :columns="memberColumns"
          :data-source="members.map((row) => ({ ...row, key: row.id }))"
          :pagination="false"
          size="small"
        >
          <template #emptyText>
            <Empty description="暂无成员，可在用户管理分配负责病区" />
          </template>
        </Table>
      </Spin>
    </Drawer>
  </Page>
</template>

<style scoped>
.title-row {
  display: flex;
  gap: 16px;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
}

.hint {
  margin-bottom: 8px;
  font-size: 12px;
  color: #8c8c8c;
}

.notice {
  margin-bottom: 12px;
}
</style>
