<script lang="ts" setup>
import type {
  TenantDictItemView,
  TenantDictLogView,
  TenantDictTypeView,
} from '@crdp/api';

import type { TenantDictEditModel, TenantDictItemEditModel } from './dicts';

import { computed, h, onMounted, reactive, ref } from 'vue';

import {
  dictLogActionText,
  dictLogTargetText,
  dictSourceText,
  dictStatusText,
} from '@crdp/api';
import { colors } from '@crdp/tokens';
import {
  Alert,
  Button,
  Card,
  Empty,
  Form,
  FormItem,
  Input,
  InputNumber,
  message,
  Modal,
  Spin,
  Table,
  Tag,
  Textarea,
} from 'ant-design-vue';

import {
  createDict,
  createDictItem,
  listDictItems,
  listDictLogs,
  listDicts,
  updateDict,
  updateDictItem,
} from '#/api/core/config';

import {
  emptyDictModel,
  emptyItemModel,
  itemCountText,
  toDictModel,
  toItemModel,
  validateDictModel,
  validateDictUpdate,
  validateItemModel,
} from './dicts';

defineOptions({ name: 'HospitalSettingsDict' });

/**
 * E06-T10 / PLT-CFG-002 · pages.md §10「机构设置 · 租户字典」（tenant:dict:edit）：
 * 继承平台字典——本院可停用 / 恢复平台项（文字与排序不可改）、可新增本院自有项；
 * 平台新增项自动出现；本院自有字典可建可改（平台字典类型不可改）。
 * E11-T08 / A-FLD-012、A-FLD-009、C-FLD-002、C-FLD-009：本院自有项可维护别名；
 * 增改停用恢复留变更记录（前后值、操作人、时间），「变更记录」按字典查看。
 */
const loading = ref(false);
const loadError = ref('');
const dicts = ref<TenantDictTypeView[]>([]);
const selectedCode = ref('');
const itemsLoading = ref(false);
const items = ref<TenantDictItemView[]>([]);

const dictDialogOpen = ref(false);
const dictCreating = ref(true);
const dictSaving = ref(false);
const dictForm = reactive<TenantDictEditModel>(emptyDictModel());

const itemDialogOpen = ref(false);
const itemCreating = ref(true);
const itemSaving = ref(false);
const itemForm = reactive<TenantDictItemEditModel>(emptyItemModel());

const logsOpen = ref(false);
const logsLoading = ref(false);
const logs = ref<TenantDictLogView[]>([]);

const dictColumns = [
  { dataIndex: 'code', title: '编码' },
  { dataIndex: 'name', title: '名称' },
  { dataIndex: 'source', title: '来源', width: 90 },
  { dataIndex: 'itemCount', title: '生效项' },
  { dataIndex: 'status', title: '状态', width: 90 },
  { dataIndex: 'action', title: '操作', width: 130 },
];

const itemColumns = [
  { dataIndex: 'itemCode', title: '项编码' },
  { dataIndex: 'label', title: '显示文字' },
  { key: 'aliases', title: '别名' },
  { dataIndex: 'sortNo', title: '排序', width: 80 },
  { dataIndex: 'source', title: '来源', width: 90 },
  { dataIndex: 'status', title: '状态', width: 90 },
  { dataIndex: 'action', title: '操作', width: 130 },
];

const logColumns = [
  { key: 'createdAt', title: '时间', width: 170 },
  { key: 'action', title: '动作', width: 100 },
  { key: 'target', title: '对象' },
  { key: 'operatorName', title: '操作人', width: 120 },
  { key: 'change', title: '调整内容（前 → 后）' },
];

const selectedDict = computed(() =>
  dicts.value.find((row) => row.code === selectedCode.value),
);

const existingItemCodes = computed(() =>
  items.value.map((row) => row.itemCode),
);

async function load() {
  loading.value = true;
  loadError.value = '';
  try {
    dicts.value = await listDicts();
    if (!dicts.value.some((row) => row.code === selectedCode.value)) {
      selectedCode.value = dicts.value[0]?.code ?? '';
    }
    await loadItems();
  } catch (error) {
    loadError.value = error instanceof Error ? error.message : String(error);
  } finally {
    loading.value = false;
  }
}

async function loadItems() {
  if (!selectedCode.value) {
    items.value = [];
    return;
  }
  itemsLoading.value = true;
  try {
    items.value = await listDictItems(selectedCode.value);
  } catch {
    items.value = [];
  } finally {
    itemsLoading.value = false;
  }
}

function onSelect(record: TenantDictTypeView) {
  selectedCode.value = record.code;
  void loadItems();
}

// ---------------------------------------------------------------- 本院字典

function openDictCreate() {
  dictCreating.value = true;
  Object.assign(dictForm, emptyDictModel());
  dictDialogOpen.value = true;
}

function openDictEdit(row: TenantDictTypeView) {
  dictCreating.value = false;
  Object.assign(dictForm, toDictModel(row));
  dictDialogOpen.value = true;
}

async function submitDict() {
  const errors = dictCreating.value
    ? validateDictModel(dictForm, dicts.value)
    : validateDictUpdate(dictForm);
  if (errors.length > 0) {
    message.warning(errors[0]);
    return;
  }
  dictSaving.value = true;
  try {
    if (dictCreating.value) {
      const created = await createDict({
        code: dictForm.code.trim(),
        description: dictForm.description.trim() || null,
        name: dictForm.name.trim(),
      });
      selectedCode.value = created.code;
    } else {
      await updateDict(dictForm.code, {
        description: dictForm.description.trim() || null,
        name: dictForm.name.trim(),
        status: dictForm.status,
      });
    }
    dictDialogOpen.value = false;
    message.success('已保存');
    await load();
  } finally {
    dictSaving.value = false;
  }
}

// ---------------------------------------------------------------- 字典项

function openItemCreate() {
  if (!selectedCode.value) {
    return;
  }
  itemCreating.value = true;
  Object.assign(itemForm, emptyItemModel());
  itemDialogOpen.value = true;
}

function openItemEdit(row: TenantDictItemView) {
  itemCreating.value = false;
  Object.assign(itemForm, toItemModel(row));
  itemDialogOpen.value = true;
}

async function submitItem() {
  const errors = validateItemModel(
    itemForm,
    existingItemCodes.value,
    itemCreating.value,
  );
  if (errors.length > 0) {
    message.warning(errors[0]);
    return;
  }
  itemSaving.value = true;
  try {
    await (itemCreating.value
      ? createDictItem(selectedCode.value, {
          aliases: itemForm.aliases.trim() || null,
          itemCode: itemForm.itemCode.trim(),
          label: itemForm.label.trim(),
          sortNo: itemForm.sortNo,
        })
      : updateDictItem(selectedCode.value, itemForm.itemCode, {
          aliases: itemForm.aliases.trim() || null,
          label: itemForm.label.trim(),
          sortNo: itemForm.sortNo,
          status: itemForm.status,
        }));
    itemDialogOpen.value = false;
    message.success('已保存，并已记录变更');
    await loadItems();
    await load();
  } finally {
    itemSaving.value = false;
  }
}

/** 平台项：停用 / 恢复（后端只认 status，带文字 / 排序 / 别名会 40001）。本院项：全量覆盖可维护列。 */
async function toggleItem(row: TenantDictItemView) {
  const next = row.status === 'ENABLED' ? 'DISABLED' : 'ENABLED';
  try {
    await updateDictItem(
      selectedCode.value,
      row.itemCode,
      row.editable
        ? {
            aliases: row.aliases,
            label: row.label,
            sortNo: row.sortNo,
            status: next,
          }
        : { status: next },
    );
    message.success(next === 'ENABLED' ? '已启用' : '已停用（可恢复）');
    await loadItems();
    await load();
  } catch {
    // 错误提示由统一拦截器处理
  }
}

function dictEmpty() {
  return h(Empty, { image: Empty.PRESENTED_IMAGE_SIMPLE }, () => '暂无字典');
}

function itemEmpty() {
  return h(Empty, { image: Empty.PRESENTED_IMAGE_SIMPLE }, () => '暂无字典项');
}

// ---------------------------------------------------------------- 变更记录（A-FLD-009）

async function openLogs() {
  if (!selectedCode.value) {
    return;
  }
  logsOpen.value = true;
  logsLoading.value = true;
  try {
    logs.value = await listDictLogs(selectedCode.value);
  } catch {
    logs.value = [];
  } finally {
    logsLoading.value = false;
  }
}

/** 快照 JSON 里取一个字段（label / status 等；解析失败返回空串）。 */
function snapshotValue(json: null | string, key: string): string {
  if (!json) {
    return '';
  }
  try {
    const parsed = JSON.parse(json) as Record<string, unknown>;
    const value = parsed[key];
    return value === undefined || value === null ? '' : String(value);
  } catch {
    return '';
  }
}

/** 「调整内容」列：显示文字 / 别名 / 状态的前后变化（取值级记录）。 */
function changeText(log: TenantDictLogView): string {
  if (!log.itemCode) {
    return '字典名称与说明（快照见审计）';
  }
  const parts: string[] = [];
  const beforeLabel = snapshotValue(log.beforeJson, 'label');
  const afterLabel = snapshotValue(log.afterJson, 'label');
  if (!beforeLabel) {
    parts.push(`新增「${afterLabel}」`);
  } else if (beforeLabel !== afterLabel) {
    parts.push(`显示文字「${beforeLabel}」→「${afterLabel}」`);
  }
  const beforeAliases = snapshotValue(log.beforeJson, 'aliases');
  const afterAliases = snapshotValue(log.afterJson, 'aliases');
  if (beforeAliases !== afterAliases) {
    parts.push(
      `别名「${beforeAliases || '（无）'}」→「${afterAliases || '（无）'}」`,
    );
  }
  const beforeStatus = snapshotValue(log.beforeJson, 'status');
  const afterStatus = snapshotValue(log.afterJson, 'status');
  if (beforeStatus && beforeStatus !== afterStatus) {
    parts.push(
      afterStatus === 'DISABLED'
        ? '停用（下拉不再出现，历史值保留）'
        : '恢复启用',
    );
  }
  return parts.join('；') || '无变化';
}

onMounted(load);
</script>

<template>
  <div data-testid="tenant-dicts">
    <Alert
      v-if="loadError"
      class="mb-4"
      :message="`加载失败：${loadError}`"
      show-icon
      type="error"
    />
    <div class="title-row">
      <span class="hint">
        平台字典自动继承：本院可停用 /
        恢复平台项、新增本院自有项；平台项的文字与排序不可改。
      </span>
      <Button
        type="primary"
        data-testid="tenant-dict-create"
        @click="openDictCreate"
      >
        新建本院字典
      </Button>
    </div>
    <div class="dict-layout">
      <Card :bordered="false" class="crdp-card" size="small" title="字典类型">
        <Spin :spinning="loading">
          <Table
            :columns="dictColumns"
            :data-source="dicts"
            :pagination="false"
            row-key="code"
            size="small"
            :custom-row="
              (record: TenantDictTypeView) => ({
                class: record.code === selectedCode ? 'row-selected' : '',
                onClick: () => onSelect(record),
              })
            "
          >
            <template #bodyCell="{ column, record }">
              <template v-if="column.dataIndex === 'source'">
                <Tag
                  :color="
                    (record as TenantDictTypeView).source === 'PLATFORM'
                      ? undefined
                      : colors.primary
                  "
                >
                  {{ dictSourceText((record as TenantDictTypeView).source) }}
                </Tag>
              </template>
              <template v-else-if="column.dataIndex === 'itemCount'">
                {{ itemCountText(record as TenantDictTypeView) }}
              </template>
              <template v-else-if="column.dataIndex === 'status'">
                {{ dictStatusText((record as TenantDictTypeView).status) }}
              </template>
              <template v-else-if="column.dataIndex === 'action'">
                <Button
                  v-if="(record as TenantDictTypeView).source === 'TENANT'"
                  size="small"
                  type="link"
                  @click.stop="openDictEdit(record as TenantDictTypeView)"
                >
                  维护
                </Button>
                <span v-else class="hint">平台继承</span>
              </template>
            </template>
            <template #empty-text>
              <component :is="dictEmpty" />
            </template>
          </Table>
        </Spin>
      </Card>

      <Card
        :bordered="false"
        class="crdp-card"
        size="small"
        :title="
          selectedDict
            ? `字典项 · ${selectedDict.name}（${selectedDict.code}）`
            : '字典项'
        "
      >
        <div class="mb-3 action-row">
          <Button
            type="primary"
            data-testid="tenant-dict-item-create"
            :disabled="!selectedCode"
            @click="openItemCreate"
          >
            新增本院项
          </Button>
          <Button
            data-testid="tenant-dict-logs"
            :disabled="!selectedCode"
            @click="openLogs"
          >
            变更记录
          </Button>
        </div>
        <Table
          :columns="itemColumns"
          :data-source="items"
          :loading="itemsLoading"
          :pagination="false"
          row-key="itemCode"
          size="small"
        >
          <template #bodyCell="{ column, record }">
            <template v-if="column.key === 'aliases'">
              <span v-if="(record as TenantDictItemView).aliases">
                {{ (record as TenantDictItemView).aliases }}
              </span>
              <span v-else class="hint">—</span>
            </template>
            <template v-else-if="column.dataIndex === 'source'">
              <Tag
                :color="
                  (record as TenantDictItemView).source === 'PLATFORM'
                    ? undefined
                    : colors.primary
                "
              >
                {{ dictSourceText((record as TenantDictItemView).source) }}
              </Tag>
            </template>
            <template v-else-if="column.dataIndex === 'status'">
              {{ dictStatusText((record as TenantDictItemView).status) }}
            </template>
            <template v-else-if="column.dataIndex === 'action'">
              <Button
                v-if="(record as TenantDictItemView).editable"
                size="small"
                type="link"
                @click="openItemEdit(record as TenantDictItemView)"
              >
                维护
              </Button>
              <Button
                size="small"
                type="link"
                @click="toggleItem(record as TenantDictItemView)"
              >
                {{
                  (record as TenantDictItemView).status === 'ENABLED'
                    ? '停用'
                    : '启用'
                }}
              </Button>
            </template>
          </template>
          <template #empty-text>
            <component :is="itemEmpty" />
          </template>
        </Table>
      </Card>
    </div>

    <Modal
      v-model:open="dictDialogOpen"
      :title="dictCreating ? '新建本院字典' : `维护本院字典 · ${dictForm.code}`"
      :confirm-loading="dictSaving"
      :ok-text="dictCreating ? '创建' : '保存'"
      cancel-text="取消"
      @ok="submitDict"
    >
      <Form layout="vertical" @keyup.enter.prevent>
        <FormItem label="编码（创建后不可改；不能与平台字典重名）" required>
          <Input
            v-model:value="dictForm.code"
            :disabled="!dictCreating"
            placeholder="如 local_referral"
          />
        </FormItem>
        <FormItem label="名称" required>
          <Input
            v-model:value="dictForm.name"
            :maxlength="128"
            placeholder="如 转诊来源（本院）"
          />
        </FormItem>
        <FormItem label="说明">
          <Textarea
            v-model:value="dictForm.description"
            :maxlength="255"
            :rows="2"
            placeholder="可留空"
          />
        </FormItem>
        <FormItem v-if="!dictCreating" label="状态">
          <span class="hint">{{ dictStatusText(dictForm.status) }}</span>
          <Button
            v-if="dictForm.status === 'ENABLED'"
            class="ml-2"
            size="small"
            danger
            @click="dictForm.status = 'DISABLED'"
          >
            停用
          </Button>
          <Button
            v-else
            class="ml-2"
            size="small"
            @click="dictForm.status = 'ENABLED'"
          >
            启用
          </Button>
        </FormItem>
      </Form>
    </Modal>

    <Modal
      v-model:open="itemDialogOpen"
      :title="
        itemCreating
          ? `新增本院项${selectedDict ? ` · ${selectedDict.name}` : ''}`
          : `维护字典项 · ${itemForm.itemCode}`
      "
      :confirm-loading="itemSaving"
      :ok-text="itemCreating ? '新增' : '保存'"
      cancel-text="取消"
      @ok="submitItem"
    >
      <Form layout="vertical" @keyup.enter.prevent>
        <FormItem label="项编码（创建后不可改；不能与平台项重名）" required>
          <Input
            v-model:value="itemForm.itemCode"
            :disabled="!itemCreating"
            placeholder="如 X_OTHER"
          />
        </FormItem>
        <FormItem label="显示文字" required>
          <Input
            v-model:value="itemForm.label"
            :maxlength="128"
            placeholder="如 其他（本院）"
          />
        </FormItem>
        <FormItem
          label="别名（历史写法映射与导入匹配，顿号或逗号分隔）"
          :validate-status="itemForm.aliases.trim().length > 255 ? 'error' : ''"
          :help="
            itemForm.aliases.trim().length > 255
              ? '别名不能超过 255 个字符'
              : ''
          "
        >
          <Input
            v-model:value="itemForm.aliases"
            :maxlength="255"
            placeholder="如 老写法、旧名称（可留空）"
          />
        </FormItem>
        <FormItem label="排序号（小者在前）">
          <InputNumber
            v-model:value="itemForm.sortNo"
            :min="0"
            :precision="0"
            class="w-full"
          />
        </FormItem>
        <FormItem v-if="!itemCreating" label="状态">
          <span class="hint">
            {{ dictStatusText(itemForm.status) }}（停用不删除，可恢复）
          </span>
          <Button
            v-if="itemForm.status === 'ENABLED'"
            class="ml-2"
            size="small"
            danger
            @click="itemForm.status = 'DISABLED'"
          >
            停用
          </Button>
          <Button
            v-else
            class="ml-2"
            size="small"
            @click="itemForm.status = 'ENABLED'"
          >
            启用
          </Button>
        </FormItem>
      </Form>
    </Modal>

    <Modal
      v-model:open="logsOpen"
      :title="`变更记录 · ${selectedDict?.name ?? ''}（${selectedCode}）`"
      :footer="null"
      width="860px"
    >
      <div class="hint logs-hint">
        每次新增、维护、停用与恢复都会留痕（前后值、操作人、时间）；停用的取值不再出现在下拉，历史记录的值保留并照常显示。
      </div>
      <Spin :spinning="logsLoading">
        <Empty
          v-if="logs.length === 0 && !logsLoading"
          :image="Empty.PRESENTED_IMAGE_SIMPLE"
          description="暂无变更记录"
        />
        <Table
          v-else
          :columns="logColumns"
          :data-source="logs"
          :pagination="false"
          row-key="id"
          size="small"
        >
          <template #bodyCell="{ column, record }">
            <template v-if="column.key === 'createdAt'">
              {{ (record as TenantDictLogView).createdAt }}
            </template>
            <template v-else-if="column.key === 'action'">
              {{ dictLogActionText((record as TenantDictLogView).action) }}
            </template>
            <template v-else-if="column.key === 'target'">
              {{ dictLogTargetText(record as TenantDictLogView) }}
            </template>
            <template v-else-if="column.key === 'operatorName'">
              {{ (record as TenantDictLogView).operatorName ?? '—' }}
            </template>
            <template v-else-if="column.key === 'change'">
              {{ changeText(record as TenantDictLogView) }}
            </template>
          </template>
        </Table>
      </Spin>
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

.action-row {
  display: flex;
  gap: 8px;
}

.logs-hint {
  margin-bottom: 8px;
}

.dict-layout {
  display: grid;
  grid-template-columns: minmax(360px, 2fr) minmax(420px, 3fr);
  gap: 16px;
  align-items: start;
}

:deep(.row-selected) {
  background: #e6f0ff;
}

:deep(tbody tr) {
  cursor: pointer;
}

@media (max-width: 1279px) {
  .dict-layout {
    grid-template-columns: 1fr;
  }
}
</style>
