<script lang="ts" setup>
import type { TenantParamLogView, TenantParamView } from '@crdp/api';

import type { OverrideFormModel } from './params';

import { h, onMounted, reactive, ref } from 'vue';

import {
  paramLogActionText,
  paramRangeText,
  paramValueTypeText,
} from '@crdp/api';
import { colors } from '@crdp/tokens';
import {
  Alert,
  Button,
  Empty,
  Input,
  message,
  Modal,
  Popconfirm,
  Spin,
  Table,
  Tag,
  Tooltip,
} from 'ant-design-vue';

import {
  listParamLogs,
  listParams,
  overrideParam,
  resetParam,
} from '#/api/core/config';

import {
  effectiveValueText,
  emptyOverrideModel,
  rangeHint,
  toOverrideModel,
  validateOverrideModel,
} from './params';

defineOptions({ name: 'HospitalSettingsParam' });

/**
 * E06-T10 / PLT-CFG-004、011 · pages.md §10「机构设置 · 租户参数」（tenant:param:edit）：
 * 在平台允许的范围内覆盖参数默认值；锁定参数不可覆盖（覆盖失效并提示）；
 * 覆盖与恢复都写变更记录（最近变更列表）与审计。
 */
const loading = ref(false);
const loadError = ref('');
const params = ref<TenantParamView[]>([]);
const logs = ref<TenantParamLogView[]>([]);
const logsOpen = ref(false);

const dialogOpen = ref(false);
const saving = ref(false);
const target = ref<null | TenantParamView>(null);
const form = reactive<OverrideFormModel>(emptyOverrideModel());

const columns = [
  { dataIndex: 'name', title: '参数' },
  { dataIndex: 'valueType', title: '值类型', width: 80 },
  { dataIndex: 'effectiveValue', title: '生效值' },
  { dataIndex: 'valueRange', title: '允许范围' },
  { dataIndex: 'overrideState', title: '覆盖状态' },
  { dataIndex: 'action', title: '操作', width: 150 },
];

async function load() {
  loading.value = true;
  loadError.value = '';
  try {
    params.value = await listParams();
  } catch (error) {
    loadError.value = error instanceof Error ? error.message : String(error);
  } finally {
    loading.value = false;
  }
}

function openOverride(row: TenantParamView) {
  target.value = row;
  Object.assign(form, toOverrideModel(row));
  dialogOpen.value = true;
}

async function submitOverride() {
  if (!target.value) {
    return;
  }
  const errors = validateOverrideModel(form, target.value);
  if (errors.length > 0) {
    message.warning(errors[0]);
    return;
  }
  saving.value = true;
  try {
    await overrideParam(target.value.paramKey, {
      reason: form.reason.trim() || null,
      value: form.value.trim(),
    });
    dialogOpen.value = false;
    message.success('已覆盖，即时生效并留变更记录');
    await load();
  } finally {
    saving.value = false;
  }
}

async function handleReset(row: TenantParamView) {
  try {
    await resetParam(row.paramKey);
    message.success('已恢复平台默认值');
    await load();
  } catch {
    // 错误提示由统一拦截器处理
  }
}

async function openLogs() {
  logsOpen.value = true;
  try {
    logs.value = await listParamLogs();
  } catch {
    logs.value = [];
  }
}

function emptyNode() {
  return h(Empty, { image: Empty.PRESENTED_IMAGE_SIMPLE }, () => '暂无参数');
}

onMounted(load);
</script>

<template>
  <div data-testid="tenant-params">
    <Alert
      v-if="loadError"
      class="mb-4"
      :message="`加载失败：${loadError}`"
      show-icon
      type="error"
    />
    <div class="title-row">
      <span class="hint">
        只能覆盖「租户可覆盖」的参数，且取值不得超出平台允许范围；覆盖即时生效并留变更记录。
      </span>
      <Button data-testid="param-logs" @click="openLogs">最近变更</Button>
    </div>
    <Spin :spinning="loading">
      <Table
        :columns="columns"
        :data-source="params"
        :pagination="false"
        row-key="paramKey"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.dataIndex === 'name'">
            <div>{{ (record as TenantParamView).name }}</div>
            <div class="mono">{{ (record as TenantParamView).paramKey }}</div>
          </template>
          <template v-else-if="column.dataIndex === 'valueType'">
            {{ paramValueTypeText((record as TenantParamView).valueType) }}
          </template>
          <template v-else-if="column.dataIndex === 'effectiveValue'">
            {{ effectiveValueText(record as TenantParamView) }}
          </template>
          <template v-else-if="column.dataIndex === 'valueRange'">
            {{
              paramRangeText(
                (record as TenantParamView).valueType,
                (record as TenantParamView).valueRange,
              )
            }}
          </template>
          <template v-else-if="column.dataIndex === 'overrideState'">
            <Tooltip
              v-if="(record as TenantParamView).stale"
              title="参数已被平台锁定：本院覆盖值失效，恢复默认值生效；覆盖配置保留，平台重新放开后自动恢复"
            >
              <Tag :color="colors.warning">覆盖已失效</Tag>
            </Tooltip>
            <Tag
              v-else-if="(record as TenantParamView).overridden"
              :color="colors.success"
            >
              已覆盖
            </Tag>
            <Tag v-else>平台默认</Tag>
          </template>
          <template v-else-if="column.dataIndex === 'action'">
            <Button
              size="small"
              type="link"
              :disabled="!(record as TenantParamView).tenantOverridable"
              :title="
                (record as TenantParamView).tenantOverridable
                  ? '在平台允许范围内覆盖'
                  : '平台锁定参数不可覆盖'
              "
              @click="openOverride(record as TenantParamView)"
            >
              覆盖
            </Button>
            <Popconfirm
              v-if="(record as TenantParamView).overridden"
              title="恢复平台默认值？"
              ok-text="恢复"
              cancel-text="取消"
              @confirm="handleReset(record as TenantParamView)"
            >
              <Button size="small" type="link">恢复默认</Button>
            </Popconfirm>
          </template>
        </template>
        <template #empty-text>
          <component :is="emptyNode" />
        </template>
      </Table>
    </Spin>

    <Modal
      v-model:open="dialogOpen"
      :title="target ? `覆盖参数 · ${target.name}` : '覆盖参数'"
      :confirm-loading="saving"
      ok-text="覆盖"
      cancel-text="取消"
      @ok="submitOverride"
    >
      <template v-if="target">
        <p class="hint">
          平台默认值：{{ target.platformValue }}；允许范围：{{
            paramRangeText(target.valueType, target.valueRange)
          }}
        </p>
        <div class="field">
          <label>取值（必填）</label>
          <Input
            v-model:value="form.value"
            :placeholder="rangeHint(target) || '按值类型填写'"
            data-testid="override-value"
            @keyup.enter.prevent
          />
        </div>
        <div class="field">
          <label>变更说明（可留空，写入变更记录）</label>
          <Input
            v-model:value="form.reason"
            :maxlength="200"
            placeholder="如 试点科室较多"
            @keyup.enter.prevent
          />
        </div>
      </template>
    </Modal>

    <Modal
      v-model:open="logsOpen"
      title="最近变更（最近 100 条）"
      :footer="null"
      :width="640"
    >
      <div v-if="logs.length === 0" class="hint">暂无变更记录</div>
      <ul v-else class="logs">
        <li v-for="row in logs" :key="`${row.paramKey}-${row.createdAt}`">
          <span class="tag">{{ paramLogActionText(row.action) }}</span>
          <span class="mono">{{ row.paramKey }}</span>
          <span>
            {{ row.oldValue ?? '（默认）' }} →
            {{ row.newValue ?? '（恢复默认）' }}
          </span>
          <span v-if="row.reason" class="hint">原因：{{ row.reason }}</span>
          <span class="hint">{{ row.createdAt }}</span>
        </li>
      </ul>
    </Modal>
  </div>
</template>

<style scoped>
.title-row {
  display: flex;
  gap: 8px;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
}

.hint {
  font-size: 12px;
  color: #8c8c8c;
}

.mono {
  font-family: monospace;
  font-size: 12px;
  color: #8c8c8c;
}

.field {
  display: flex;
  flex-direction: column;
  gap: 4px;
  margin-bottom: 12px;
}

.logs {
  max-height: 360px;
  padding-left: 16px;
  margin: 0;
}

.logs li {
  margin-bottom: 8px;
  font-size: 13px;
}

.logs .tag {
  margin-right: 6px;
  color: #3a75ff;
}

.logs .hint {
  margin-left: 6px;
}
</style>
