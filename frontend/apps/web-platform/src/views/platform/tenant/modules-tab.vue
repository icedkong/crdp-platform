<script lang="ts" setup>
import type {
  TenantModuleCloseImpact,
  TenantModuleConfig,
  TenantModuleItem,
  TenantModulesView,
} from '@crdp/api';

import { computed, onMounted, reactive, ref } from 'vue';

import { StatusTag } from '@crdp/status-tag';
import {
  Alert,
  Button,
  Descriptions,
  DescriptionsItem,
  Form,
  FormItem,
  Input,
  Modal,
  Space,
  Spin,
  Table,
  Textarea,
} from 'ant-design-vue';

import {
  closeTenantModule,
  listTenantModules,
  openTenantModule,
  tenantModuleCloseImpact,
  tenantModuleConfig,
  updateTenantModuleConfig,
} from '#/api/core/tenant-module';
import { usePermission } from '#/reference/use-permission';

import {
  canClose,
  canConfigure,
  canOpen,
  dependsOnText,
  impactLines,
  moduleStatusText,
  timeText,
  toConfigFormModel,
  toConfigUpdateRequest,
  validateCloseReason,
  validateConfigForm,
  validateOpenReason,
  versionText,
} from './tenant-modules';

/**
 * 租户详情 · 模块页签（E05-T05 / PLT-MOD-004、005、006、008）：各模块开通状态与版本；
 * 开通（可填原因）、关闭（影响评估 + 二次确认 + 原因必填）、模块级配置。
 */
defineOptions({ name: 'PlatformTenantModulesTab' });

const props = defineProps<{ tenantId: number }>();

const { hasPermission } = usePermission();
const canOpenModule = hasPermission('platform:tenant:module:open');
const canCloseModule = hasPermission('platform:tenant:module:close');
const canEditTenant = hasPermission('platform:tenant:edit');

const view = ref<null | TenantModulesView>(null);
const loading = ref(false);
const errorMessage = ref('');

// 开通对话框（PLT-MOD-004：原因可留空）
const openTarget = ref<null | TenantModuleItem>(null);
const openReason = ref('');
const openError = ref('');
const openSaving = ref(false);

// 关闭对话框（PLT-MOD-005：影响评估 → 原因必填）
const closeTarget = ref<null | TenantModuleItem>(null);
const closeImpact = ref<null | TenantModuleCloseImpact>(null);
const closeReason = ref('');
const closeReasonError = ref('');
const closeError = ref('');
const closeSaving = ref(false);
const closeImpactLoading = ref(false);

// 配置对话框（PLT-MOD-008：默认值来自模块定义，租户覆盖优先）
const configTarget = ref<null | TenantModuleItem>(null);
const configView = ref<null | TenantModuleConfig>(null);
const configOpen = ref(false);
const configSaving = ref(false);
const configError = ref('');
const configForm = reactive({ values: {} as Record<string, string> });
const configErrors = computed(() =>
  configView.value ? validateConfigForm(configForm, configView.value) : {},
);

const columns = [
  { title: '模块', dataIndex: 'name', key: 'name' },
  { title: '编码', dataIndex: 'code', key: 'code' },
  { title: '依赖', key: 'dependsOn' },
  { title: '版本（功能 / 迁移）', key: 'version' },
  { title: '开通状态', key: 'status' },
  { title: '开通时间', key: 'openedAt' },
  { title: '关闭时间', key: 'closedAt' },
  { title: '操作', key: 'actions' },
];

async function load() {
  loading.value = true;
  errorMessage.value = '';
  try {
    view.value = await listTenantModules(props.tenantId);
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : String(error);
  } finally {
    loading.value = false;
  }
}

function startOpen(item: TenantModuleItem) {
  openTarget.value = item;
  openReason.value = '';
  openError.value = '';
}

async function submitOpen() {
  if (!openTarget.value) return;
  openSaving.value = true;
  openError.value = '';
  try {
    await openTenantModule(
      props.tenantId,
      openTarget.value.code,
      openReason.value.trim() || undefined,
    );
    openTarget.value = null;
    await load();
  } catch (error) {
    openError.value = error instanceof Error ? error.message : String(error);
  } finally {
    openSaving.value = false;
  }
}

async function startClose(item: TenantModuleItem) {
  closeTarget.value = item;
  closeReason.value = '';
  closeReasonError.value = '';
  closeError.value = '';
  closeImpact.value = null;
  closeImpactLoading.value = true;
  try {
    closeImpact.value = await tenantModuleCloseImpact(
      props.tenantId,
      item.code,
    );
  } catch (error) {
    closeError.value = error instanceof Error ? error.message : String(error);
  } finally {
    closeImpactLoading.value = false;
  }
}

async function submitClose() {
  if (!closeTarget.value) return;
  const error = validateCloseReason(closeReason.value);
  closeReasonError.value = error ?? '';
  if (error) return;
  closeSaving.value = true;
  closeError.value = '';
  try {
    await closeTenantModule(
      props.tenantId,
      closeTarget.value.code,
      closeReason.value.trim(),
    );
    closeTarget.value = null;
    await load();
  } catch (error) {
    closeError.value = error instanceof Error ? error.message : String(error);
  } finally {
    closeSaving.value = false;
  }
}

async function startConfig(item: TenantModuleItem) {
  configTarget.value = item;
  configError.value = '';
  configOpen.value = true;
  try {
    configView.value = await tenantModuleConfig(props.tenantId, item.code);
    Object.assign(configForm, toConfigFormModel(configView.value));
  } catch (error) {
    configError.value = error instanceof Error ? error.message : String(error);
  }
}

async function submitConfig() {
  if (!configTarget.value || !configView.value) return;
  if (Object.keys(configErrors.value).length > 0) return;
  configSaving.value = true;
  configError.value = '';
  try {
    configView.value = await updateTenantModuleConfig(
      props.tenantId,
      configTarget.value.code,
      toConfigUpdateRequest(configForm),
    );
    configOpen.value = false;
  } catch (error) {
    configError.value = error instanceof Error ? error.message : String(error);
  } finally {
    configSaving.value = false;
  }
}

onMounted(load);
</script>

<template>
  <div>
    <Alert
      v-if="errorMessage"
      :message="`加载失败：${errorMessage}`"
      show-icon
      type="error"
      class="mb-4"
    />
    <Spin :spinning="loading">
      <Table
        :columns="columns"
        :data-source="view?.modules ?? []"
        :pagination="false"
        row-key="code"
        size="middle"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'dependsOn'">
            {{ dependsOnText(record) }}
          </template>
          <template v-else-if="column.key === 'version'">
            {{ versionText(record) }}
          </template>
          <template v-else-if="column.key === 'status'">
            <Space>
              <StatusTag :status="moduleStatusText(record)" />
              <span v-if="record.reason" class="reason-hint">
                {{ record.status === 'OPEN_FAILED' ? '失败' : '关闭' }}原因：{{
                  record.reason
                }}
              </span>
            </Space>
          </template>
          <template v-else-if="column.key === 'openedAt'">
            {{ timeText(record.openedAt) }}
          </template>
          <template v-else-if="column.key === 'closedAt'">
            {{ timeText(record.closedAt) }}
          </template>
          <template v-else-if="column.key === 'actions'">
            <Space>
              <Button
                v-if="canOpenModule && canOpen(record)"
                size="small"
                type="link"
                data-testid="open-module"
                @click="startOpen(record)"
              >
                开通
              </Button>
              <Button
                v-if="canCloseModule && canClose(record)"
                danger
                size="small"
                type="link"
                data-testid="close-module"
                @click="startClose(record)"
              >
                关闭
              </Button>
              <Button
                v-if="canEditTenant && canConfigure(record)"
                size="small"
                type="link"
                data-testid="module-config"
                @click="startConfig(record)"
              >
                配置
              </Button>
              <span v-if="record.baseModule" class="reason-hint">必选</span>
            </Space>
          </template>
        </template>
      </Table>
    </Spin>

    <!-- 开通（PLT-MOD-004）：原因可留空 -->
    <Modal
      :open="openTarget !== null"
      :confirm-loading="openSaving"
      :title="`开通模块：${openTarget?.name ?? ''}`"
      :width="520"
      ok-text="开通"
      cancel-text="取消"
      @ok="submitOpen"
      @cancel="openTarget = null"
    >
      <Alert
        v-if="openError"
        :message="`开通失败：${openError}`"
        show-icon
        type="error"
        class="mb-4"
      />
      <p class="hint">
        开通将执行该模块的租户库迁移，模块菜单进入授权范围并初始化配置默认值；开通后菜单立即可见。
      </p>
      <Form layout="vertical" class="crdp-form">
        <FormItem
          label="开通原因（选填，留痕）"
          :validate-status="
            validateOpenReason(openReason) ? 'error' : undefined
          "
          :help="validateOpenReason(openReason)"
        >
          <Textarea
            v-model:value="openReason"
            :maxlength="200"
            :rows="2"
            placeholder="如：合同签订（示例）"
            show-count
          />
        </FormItem>
      </Form>
    </Modal>

    <!-- 关闭（PLT-MOD-005）：影响评估 + 二次确认 + 原因必填 -->
    <Modal
      :open="closeTarget !== null"
      :confirm-loading="closeSaving"
      :ok-button-props="{ disabled: !closeReason.trim() }"
      :title="`关闭模块：${closeTarget?.name ?? ''}`"
      :width="560"
      danger
      ok-text="确认关闭"
      cancel-text="取消"
      @ok="submitClose"
      @cancel="closeTarget = null"
    >
      <Alert
        v-if="closeError"
        :message="`关闭失败：${closeError}`"
        show-icon
        type="error"
        class="mb-4"
      />
      <Spin :spinning="closeImpactLoading">
        <Descriptions bordered :column="1" size="small" class="mb-4">
          <DescriptionsItem label="影响评估">
            <div v-if="closeImpact">
              <div v-for="line in impactLines(closeImpact)" :key="line">
                {{ line }}
              </div>
            </div>
            <span v-else>加载中…</span>
          </DescriptionsItem>
        </Descriptions>
      </Spin>
      <Form layout="vertical" class="crdp-form">
        <FormItem
          label="关闭原因"
          required
          :validate-status="closeReasonError ? 'error' : undefined"
          :help="closeReasonError"
        >
          <Textarea
            v-model:value="closeReason"
            :maxlength="200"
            :rows="2"
            placeholder="如：合同到期暂停（示例）"
            show-count
          />
        </FormItem>
      </Form>
    </Modal>

    <!-- 模块级配置（PLT-MOD-008）：默认取模块定义，租户覆盖优先 -->
    <Modal
      v-model:open="configOpen"
      :confirm-loading="configSaving"
      :ok-button-props="{ disabled: Object.keys(configErrors).length > 0 }"
      :title="`模块配置：${configTarget?.name ?? ''}`"
      :width="560"
      ok-text="保存"
      cancel-text="取消"
      @ok="submitConfig"
    >
      <Alert
        v-if="configError"
        :message="`保存失败：${configError}`"
        show-icon
        type="error"
        class="mb-4"
      />
      <Form v-if="configView" layout="vertical" class="crdp-form">
        <FormItem
          v-for="item in configView.items"
          :key="item.code"
          :label="item.name"
          :extra="item.description"
          :validate-status="configErrors[item.code] ? 'error' : undefined"
          :help="configErrors[item.code]"
        >
          <Input
            v-model:value="configForm.values[item.code]"
            :maxlength="255"
            :placeholder="item.defaultValue"
          />
        </FormItem>
        <FormItem v-if="configView.items.length === 0" label="配置项">
          该模块暂无租户级配置项
        </FormItem>
      </Form>
    </Modal>
  </div>
</template>

<style scoped>
.hint {
  color: #4e5969;
}

.reason-hint {
  font-size: 12px;
  color: #86909c;
}
</style>
