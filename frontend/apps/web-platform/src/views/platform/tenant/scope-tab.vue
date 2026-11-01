<script lang="ts" setup>
import type {
  TenantScopeChangeResult,
  TenantScopeItem,
  TenantScopeLog,
  TenantScopePreview,
  TenantScopeView,
} from '@crdp/api';

import { computed, onMounted, reactive, ref } from 'vue';

import {
  Alert,
  Button,
  Checkbox,
  Descriptions,
  DescriptionsItem,
  Form,
  FormItem,
  Input,
  Modal,
  Space,
  Spin,
  Table,
  Tag,
  Textarea,
} from 'ant-design-vue';

import {
  listTenantScope,
  previewTenantScope,
  tenantScopeLogs,
  updateTenantScope,
} from '#/api/core/tenant-scope';
import { usePermission } from '#/reference/use-permission';

import {
  flattenItems,
  newSensitiveReleases,
  previewLines,
  timeText,
  toScopeRows,
  validateReason,
  validateReleaseReason,
} from './scope';

/**
 * 租户详情 · 授权范围页签（E05-T07 / PLT-SCOPE-001..006、008）：按模块勾选可分配
 * 项（三种状态：未开通不可选、已开通未放开、可分配），敏感项放开需原因；保存前
 * 弹出影响分析（受影响角色与用户数），确认后后端自动收回租户角色超出项。
 */
defineOptions({ name: 'PlatformTenantScopeTab' });

const props = defineProps<{ tenantId: number }>();

const { hasPermission } = usePermission();
const canEdit = hasPermission('platform:tenant:scope:edit');

const view = ref<null | TenantScopeView>(null);
const loading = ref(false);
const errorMessage = ref('');

// 勾选输入态：菜单 id → 是否勾选（初始 = 后端生效值；base 固定项只读）
const checked = ref<Map<number, boolean>>(new Map());
const dirty = ref(false);

// 敏感放开原因（menuId 字符串 → 原因）
const releaseReasons = reactive<Record<string, string>>({});
const reason = ref('');
const reasonError = ref('');

// 影响分析 + 二次确认（PLT-SCOPE-005 → PLT-SCOPE-006）
const confirmOpen = ref(false);
const confirmLoading = ref(false);
const preview = ref<null | TenantScopePreview>(null);
const previewError = ref('');
const saveError = ref('');
const result = ref<null | TenantScopeChangeResult>(null);

const logs = ref<TenantScopeLog[]>([]);

const groups = computed(() =>
  view.value
    ? view.value.modules.map((module) => ({
        ...module,
        rows: toScopeRows(module),
      }))
    : [],
);

const releases = computed(() => {
  const wanted = new Set(desiredIds.value);
  const nodes: TenantScopeItem[] = [];
  for (const module of view.value?.modules ?? []) {
    nodes.push(...newSensitiveReleases(flattenItems(module.items), wanted));
  }
  return nodes;
});

const desiredIds = computed(() => {
  const ids: number[] = [];
  for (const [id, value] of checked.value) {
    if (value) ids.push(id);
  }
  return ids.toSorted((a, b) => a - b);
});

const releaseErrors = computed(() =>
  validateReleaseReason(releaseReasons, releases.value),
);

const confirmDisabled = computed(
  () =>
    !reason.value.trim() ||
    Boolean(reasonError.value) ||
    Object.keys(releaseErrors.value).length > 0,
);

const columns = [
  { title: '菜单 / 按钮', dataIndex: 'title', key: 'title' },
  { title: '类型', dataIndex: 'typeText', key: 'type', width: 80 },
  { title: '权限码', dataIndex: 'permCode', key: 'permCode', width: 220 },
  { title: '状态', key: 'state', width: 130 },
];

const logColumns = [
  { title: '时间', dataIndex: 'createdAt', key: 'createdAt', width: 150 },
  { title: '前后差异', dataIndex: 'detail', key: 'detail' },
  { title: '原因', dataIndex: 'reason', key: 'reason', width: 200 },
];

async function load() {
  loading.value = true;
  errorMessage.value = '';
  try {
    const data = await listTenantScope(props.tenantId);
    view.value = data;
    const state = new Map<number, boolean>();
    for (const module of data.modules) {
      for (const item of flattenItems(module.items)) {
        state.set(item.id, item.assignable);
      }
    }
    checked.value = state;
    dirty.value = false;
    logs.value = await tenantScopeLogs(props.tenantId);
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : String(error);
  } finally {
    loading.value = false;
  }
}

function toggle(item: TenantScopeItem, value: boolean) {
  if (!canEdit || item.fixed || !view.value) return;
  const module = view.value.modules.find((m) => m.code === item.moduleCode);
  if (!module?.opened) return;
  checked.value.set(item.id, value);
  dirty.value = true;
}

async function startSave() {
  reasonError.value = validateReason(reason.value) ?? '';
  if (reasonError.value) return;
  if (Object.keys(releaseErrors.value).length > 0) return;
  confirmOpen.value = true;
  preview.value = null;
  previewError.value = '';
  result.value = null;
  try {
    preview.value = await previewTenantScope(props.tenantId, {
      assignableMenuIds: desiredIds.value,
      reason: reason.value.trim(),
      sensitiveReasons: releaseReasons,
    });
  } catch (error) {
    previewError.value = error instanceof Error ? error.message : String(error);
  }
}

async function submitSave() {
  confirmLoading.value = true;
  saveError.value = '';
  try {
    result.value = await updateTenantScope(props.tenantId, {
      assignableMenuIds: desiredIds.value,
      reason: reason.value.trim(),
      sensitiveReasons: releaseReasons,
    });
    reason.value = '';
    for (const key of Object.keys(releaseReasons)) {
      delete releaseReasons[key];
    }
    await load();
  } catch (error) {
    saveError.value = error instanceof Error ? error.message : String(error);
  } finally {
    confirmLoading.value = false;
  }
}

onMounted(load);
</script>

<template>
  <div>
    <Alert
      message="平台决定租户管理员能分配什么；用户最终权限 = 已开通模块 ∩ 可分配范围 ∩ 角色并集。缩小范围保存后自动从租户角色收回超出项。"
      show-icon
      type="info"
      class="mb-4"
    />
    <Alert
      v-if="errorMessage"
      :message="`加载失败：${errorMessage}`"
      show-icon
      type="error"
      class="mb-4"
    />
    <Spin :spinning="loading">
      <div v-for="group in groups" :key="group.code" class="scope-group">
        <div class="scope-group-title">
          <span>{{ group.name }}</span>
          <Tag v-if="group.baseModule" color="blue">固定能力（不可收回）</Tag>
          <Tag v-else-if="group.opened" color="success">已开通</Tag>
          <Tag v-else color="default">未开通（项不可选）</Tag>
        </div>
        <Table
          :columns="columns"
          :data-source="group.rows"
          :pagination="false"
          row-key="id"
          size="small"
          default-expand-all-rows
        >
          <template #bodyCell="{ column, record }">
            <template v-if="column.key === 'title'">
              <Checkbox
                :checked="checked.get(record.id)"
                :disabled="record.fixed || !group.opened || !canEdit"
                data-testid="scope-item-check"
                @change="(event: any) => toggle(record, event.target.checked)"
              >
                {{ record.title }}
                <Tag v-if="record.fixed" class="ml-2">固定</Tag>
              </Checkbox>
            </template>
            <template v-else-if="column.key === 'permCode'">
              <code v-if="record.permCode">{{ record.permCode }}</code>
              <span v-else>—</span>
            </template>
            <template v-else-if="column.key === 'state'">
              <Space>
                <Tag v-if="record.sensitive" color="red">敏感</Tag>
                <Tag v-if="record.fixed || !group.opened" color="default">
                  {{ record.fixed ? '固定能力' : '未开通' }}
                </Tag>
                <Tag v-else-if="checked.get(record.id)" color="green">
                  可分配
                </Tag>
                <Tag v-else color="default">已开通未放开</Tag>
              </Space>
            </template>
          </template>
        </Table>
      </div>

      <!-- 本次放开的敏感项：逐一填写原因（PLT-SCOPE-004） -->
      <div v-if="releases.length > 0" class="scope-group">
        <div class="scope-group-title">本次放开的敏感权限（原因必填）</div>
        <Form layout="vertical" class="crdp-form">
          <FormItem
            v-for="item in releases"
            :key="item.id"
            :label="`${item.title}（${item.permCode ?? '—'}）`"
            :validate-status="releaseErrors[item.id] ? 'error' : undefined"
            :help="releaseErrors[item.id]"
          >
            <Input
              v-model:value="releaseReasons[String(item.id)]"
              :maxlength="200"
              placeholder="如：科研协作需要（示例）"
              show-count
            />
          </FormItem>
        </Form>
      </div>

      <template v-if="canEdit">
        <Form layout="vertical" class="crdp-form">
          <FormItem
            label="变更原因"
            required
            :validate-status="reasonError ? 'error' : undefined"
            :help="reasonError"
          >
            <Textarea
              v-model:value="reason"
              :maxlength="200"
              :rows="2"
              placeholder="如：按合同调整可分配范围（示例）"
              show-count
            />
          </FormItem>
        </Form>
        <div class="toolbar">
          <Button
            type="primary"
            :disabled="!dirty"
            data-testid="save-scope"
            @click="startSave"
          >
            保存（先看影响分析）
          </Button>
        </div>
      </template>

      <div class="scope-group">
        <div class="scope-group-title">变更记录</div>
        <Table
          :columns="logColumns"
          :data-source="logs"
          :pagination="{ pageSize: 20 }"
          row-key="id"
          size="small"
        >
          <template #bodyCell="{ column, record }">
            <template v-if="column.key === 'createdAt'">
              {{ timeText(record.createdAt) }}
            </template>
          </template>
        </Table>
      </div>
    </Spin>

    <!-- 影响分析 + 二次确认（PLT-SCOPE-005 → PLT-SCOPE-006） -->
    <Modal
      v-model:open="confirmOpen"
      :confirm-loading="confirmLoading"
      :ok-button-props="{ disabled: confirmDisabled }"
      title="保存可分配范围"
      :width="640"
      ok-text="确认保存"
      cancel-text="取消"
      @ok="submitSave"
    >
      <Alert
        v-if="previewError"
        :message="`影响分析失败：${previewError}`"
        show-icon
        type="error"
        class="mb-4"
      />
      <Alert
        v-if="saveError"
        :message="`保存失败：${saveError}`"
        show-icon
        type="error"
        class="mb-4"
      />
      <Alert
        v-if="result"
        :message="`保存成功：收回 ${result.removedMenuIds.length} 项，自动移除角色授权 ${result.revokedGrants} 条，刷新 ${result.refreshedUsers} 个用户的权限版本`"
        show-icon
        type="success"
        class="mb-4"
      />
      <Descriptions
        v-if="preview"
        bordered
        :column="1"
        size="small"
        class="mb-4"
      >
        <DescriptionsItem label="影响分析">
          <div v-for="line in previewLines(preview)" :key="line">
            {{ line }}
          </div>
        </DescriptionsItem>
      </Descriptions>
      <p v-else class="hint">影响分析加载中…</p>
      <p class="hint">
        确认后从租户库角色中移除超出项，受影响用户权限版本递增（在线会话下一次请求即重算）。
      </p>
    </Modal>
  </div>
</template>

<style scoped>
.scope-group {
  margin-bottom: 16px;
}

.scope-group-title {
  display: flex;
  gap: 8px;
  align-items: center;
  margin-bottom: 8px;
  font-weight: 500;
}

.hint {
  font-size: 12px;
  color: #86909c;
}

.toolbar {
  display: flex;
  gap: 8px;
  justify-content: flex-end;
  margin: 12px 0;
}

.ml-2 {
  margin-left: 8px;
}
</style>
