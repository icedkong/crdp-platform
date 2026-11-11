<script lang="ts" setup>
import type { ApiResourceItem } from '@crdp/api';

import type { ApiMarkModel } from './logic';

import { h, onMounted, onUnmounted, ref } from 'vue';
import { useRouter } from 'vue-router';

import { Page } from '@vben/common-ui';

import {
  apiActionTypeText,
  apiAuditLevelText,
  apiSensitiveLevelText,
  apiSourceText,
} from '@crdp/api';
import { breakpoints } from '@crdp/tokens';
import {
  Alert,
  Button,
  Card,
  Checkbox,
  Empty,
  Form,
  FormItem,
  Input,
  Modal,
  Result,
  Select,
  Space,
  Switch,
  Table,
  Tag,
} from 'ant-design-vue';

import {
  listApiResources,
  markApiResource,
  syncApiResources,
} from '#/api/core/api-resource';
import { usePermission } from '#/reference/use-permission';

import ResourceLogDrawer from '../components/resource-log-drawer.vue';
import {
  ACTION_TYPE_OPTIONS,
  AUDIT_LEVEL_OPTIONS,
  isUnbound,
  moduleOptions,
  SENSITIVE_LEVEL_OPTIONS,
  toMarkModel,
  toMarkRequest,
  validateMark,
} from './logic';

defineOptions({ name: 'PlatformApi' });

const router = useRouter();
const { hasPermission } = usePermission();
const canEdit = hasPermission('platform:api:edit');
// 资源变更记录入口（E05-T10 / PLT-PERM-012）：接口与菜单共用，权限是菜单查看 platform:menu:view
const canViewLog = hasPermission('platform:menu:view');

const isMobile = ref(false);
const loading = ref(false);
const errorMessage = ref('');
const items = ref<ApiResourceItem[]>([]);
const filters = ref({ moduleCode: '', permCode: '', unbound: false });
const logOpen = ref(false);

// 手动同步（与启动同一套逻辑）
const syncing = ref(false);
const syncMessage = ref('');
const syncError = ref('');

// 标记对话框
const markOpen = ref(false);
const markTarget = ref<ApiResourceItem | null>(null);
const markModel = ref<ApiMarkModel | null>(null);
const markErrors = ref<string[]>([]);
const markError = ref('');
const marking = ref(false);

const columns = [
  { dataIndex: 'httpMethod', title: '方法', width: 76 },
  { dataIndex: 'pathPattern', title: '路径模式' },
  { dataIndex: 'permCode', title: '权限码', width: 210 },
  { dataIndex: 'moduleCode', title: '模块', width: 100 },
  { dataIndex: 'publicAccess', title: '公共', width: 76 },
  { dataIndex: 'auditLevel', title: '审计级别', width: 120 },
  { dataIndex: 'sensitiveLevel', title: '敏感级别', width: 96 },
  { dataIndex: 'actionType', title: '动作类型', width: 96 },
  { dataIndex: 'source', title: '来源', width: 96 },
  { dataIndex: 'action', fixed: 'right' as const, title: '操作', width: 88 },
];

function read() {
  isMobile.value = window.matchMedia(
    `(max-width: ${breakpoints.tablet - 1}px)`,
  ).matches;
}

async function load() {
  loading.value = true;
  errorMessage.value = '';
  try {
    items.value = await listApiResources();
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : String(error);
  } finally {
    loading.value = false;
  }
}

const filtered = ref<ApiResourceItem[]>([]);

function toggleUnbound(checked: boolean) {
  filters.value.unbound = checked;
  void applyFilters();
}

async function applyFilters() {
  const { moduleCode, permCode, unbound } = filters.value;
  if (moduleCode === '' && permCode === '' && !unbound) {
    filtered.value = items.value;
    return;
  }
  filtered.value = await listApiResources({
    moduleCode: moduleCode || undefined,
    permCode: permCode || undefined,
    unbound: unbound || undefined,
  });
}

async function sync() {
  syncing.value = true;
  syncMessage.value = '';
  syncError.value = '';
  try {
    const result = await syncApiResources();
    const changed = result.outcomes.filter(
      (outcome) => outcome.action !== 'UNCHANGED',
    ).length;
    syncMessage.value =
      changed === 0
        ? '登记与代码一致，无变化'
        : `已重新登记 ${changed} 条（新增 / 变更 / 清除消失接口）`;
    await load();
    await applyFilters();
  } catch (error) {
    syncError.value = error instanceof Error ? error.message : String(error);
  } finally {
    syncing.value = false;
  }
}

function openMark(row: ApiResourceItem) {
  markTarget.value = row;
  markModel.value = toMarkModel(row);
  markErrors.value = [];
  markError.value = '';
  markOpen.value = true;
}

function validate() {
  markErrors.value = markModel.value ? validateMark(markModel.value) : [];
}

async function submitMark() {
  if (!markModel.value || !markTarget.value) return;
  validate();
  if (markErrors.value.length > 0) return;
  marking.value = true;
  markError.value = '';
  try {
    await markApiResource(markTarget.value.id, toMarkRequest(markModel.value));
    markOpen.value = false;
    await load();
    await applyFilters();
  } catch (error) {
    markError.value = error instanceof Error ? error.message : String(error);
  } finally {
    marking.value = false;
  }
}

function emptyNode() {
  return h(
    Empty,
    { image: Empty.PRESENTED_IMAGE_SIMPLE },
    () => '没有匹配的接口资源',
  );
}

onMounted(() => {
  read();
  window.addEventListener('resize', read);
  void load().then(() => {
    filtered.value = items.value;
  });
});
onUnmounted(() => window.removeEventListener('resize', read));
</script>

<template>
  <Result v-if="isMobile" status="info" title="请在电脑或平板上操作">
    <template #extra>
      <Button type="primary" @click="router.push('/dashboard')">
        返回首页
      </Button>
    </template>
  </Result>
  <Page
    v-else
    title="接口资源"
    description="启动扫描自动登记的「接口 → 权限码 → 模块」清单；未登记接口默认拒绝。"
  >
    <Card :bordered="false" class="crdp-card">
      <div class="title-row">
        <Space wrap>
          <Select
            v-model:value="filters.moduleCode"
            allow-clear
            placeholder="按模块筛选"
            style="width: 160px"
            :options="moduleOptions(items)"
            @change="applyFilters"
          />
          <Checkbox
            :checked="filters.unbound"
            @change="
              (event: any) => toggleUnbound(event.target.checked as boolean)
            "
          >
            只看未绑定权限码
          </Checkbox>
        </Space>
        <Button
          v-if="canViewLog"
          data-testid="api-resource-log"
          @click="logOpen = true"
        >
          变更记录
        </Button>
        <Button
          v-if="canEdit"
          data-testid="api-sync"
          :loading="syncing"
          @click="sync"
        >
          重新扫描登记
        </Button>
      </div>

      <Alert
        v-if="errorMessage"
        :message="`加载失败：${errorMessage}`"
        show-icon
        type="error"
        class="mb-4"
      />
      <Alert
        v-if="syncError"
        :message="`同步失败：${syncError}`"
        show-icon
        type="error"
        class="mb-4"
      />
      <Alert
        v-if="syncMessage"
        :message="syncMessage"
        show-icon
        type="success"
        class="mb-4"
      />

      <Table
        :columns="columns"
        :data-source="filtered"
        :loading="loading"
        :pagination="false"
        row-key="id"
        :locale="{ emptyText: emptyNode() }"
        size="small"
        :row-class-name="
          (record: ApiResourceItem) => (isUnbound(record) ? 'api-unbound' : '')
        "
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.dataIndex === 'httpMethod'">
            <Tag :color="record.httpMethod === 'GET' ? 'blue' : 'orange'">
              {{ record.httpMethod }}
            </Tag>
          </template>
          <template v-else-if="column.dataIndex === 'permCode'">
            <code v-if="record.permCode">{{ record.permCode }}</code>
            <Tag v-else-if="isUnbound(record)" color="warning">未绑定</Tag>
            <span v-else>—</span>
          </template>
          <template v-else-if="column.dataIndex === 'publicAccess'">
            {{ record.publicAccess ? '是' : '否' }}
          </template>
          <template v-else-if="column.dataIndex === 'auditLevel'">
            {{ apiAuditLevelText(record.auditLevel) }}
          </template>
          <template v-else-if="column.dataIndex === 'sensitiveLevel'">
            <Tag
              :color="record.sensitiveLevel === 'SENSITIVE' ? 'red' : 'default'"
            >
              {{ apiSensitiveLevelText(record.sensitiveLevel) }}
            </Tag>
          </template>
          <template v-else-if="column.dataIndex === 'actionType'">
            {{ apiActionTypeText(record.actionType) }}
          </template>
          <template v-else-if="column.dataIndex === 'source'">
            {{ apiSourceText(record.source) }}
          </template>
          <template v-else-if="column.dataIndex === 'action'">
            <Button
              v-if="canEdit"
              type="link"
              size="small"
              @click="openMark(record as ApiResourceItem)"
            >
              标记
            </Button>
          </template>
        </template>
      </Table>
    </Card>

    <Modal
      v-model:open="markOpen"
      :title="
        markTarget
          ? `标记接口：${markTarget.httpMethod} ${markTarget.pathPattern}`
          : '标记接口'
      "
      ok-text="保存"
      cancel-text="取消"
      :confirm-loading="marking"
      :ok-button-props="{ disabled: markErrors.length > 0 }"
      @ok="submitMark"
    >
      <Alert
        v-if="markErrors.length > 0"
        :message="markErrors.join('；')"
        show-icon
        type="error"
        class="mb-3"
      />
      <Alert
        v-if="markError"
        :message="`保存失败：${markError}`"
        show-icon
        type="error"
        class="mb-3"
      />
      <Form v-if="markModel" layout="vertical">
        <FormItem label="权限码（绑定后接口按该码校验）">
          <Input
            v-model:value="markModel.permCode"
            placeholder="如 followup:task:view；留空表示不绑定"
            @change="validate"
          />
        </FormItem>
        <FormItem label="公共接口（登录即可访问）">
          <Switch v-model:checked="markModel.publicAccess" @change="validate" />
        </FormItem>
        <FormItem label="审计级别">
          <Select
            v-model:value="markModel.auditLevel"
            :options="AUDIT_LEVEL_OPTIONS"
          />
        </FormItem>
        <FormItem label="敏感级别">
          <Select
            v-model:value="markModel.sensitiveLevel"
            :options="SENSITIVE_LEVEL_OPTIONS"
          />
        </FormItem>
        <FormItem label="动作类型">
          <Select
            v-model:value="markModel.actionType"
            :options="ACTION_TYPE_OPTIONS"
          />
        </FormItem>
      </Form>
    </Modal>

    <ResourceLogDrawer
      :open="logOpen"
      default-resource-type="API"
      @close="logOpen = false"
    />
  </Page>
</template>

<style scoped>
.title-row {
  display: flex;
  gap: 16px;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 16px;
}

:deep(.api-unbound) {
  background-color: rgb(250 173 20 / 10%);
}
</style>
