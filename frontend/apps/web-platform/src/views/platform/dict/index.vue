<script lang="ts" setup>
import type { PlatformDictItemView, PlatformDictView } from '@crdp/api';

import type { DictEditModel, DictItemEditModel } from './dict';

import { computed, h, onMounted, onUnmounted, reactive, ref } from 'vue';

import { Page } from '@vben/common-ui';

import { dictStatusText } from '@crdp/api';
import { StatusTag } from '@crdp/status-tag';
import { breakpoints } from '@crdp/tokens';
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
  Result,
  Table,
  Textarea,
} from 'ant-design-vue';

import {
  createDict,
  createDictItem,
  listDictItems,
  listDicts,
  updateDict,
  updateDictItem,
} from '#/api/core/dict';

import {
  emptyDictModel,
  emptyItemModel,
  itemCountText,
  toDictModel,
  toItemModel,
  validateDictModel,
  validateDictUpdate,
  validateItemModel,
} from './dict';

defineOptions({ name: 'PlatformDict' });

/**
 * E06-T10 / PLT-CFG-001 · pages.md §11「字典管理」（/platform/dict，platform:dict:edit）：
 * 左字典类型、右字典项。编码创建后不可改；项停用不删除；保存即时下发全部租户并写平台审计。
 * 仅桌面 / 平板。
 */
const isMobile = ref(false);

const loading = ref(false);
const loadError = ref('');
const dicts = ref<PlatformDictView[]>([]);
const selectedCode = ref('');

const itemsLoading = ref(false);
const items = ref<PlatformDictItemView[]>([]);

// 字典类型对话框
const dictDialogOpen = ref(false);
const dictCreating = ref(true);
const dictSaving = ref(false);
const dictForm = reactive<DictEditModel>(emptyDictModel());

// 字典项对话框
const itemDialogOpen = ref(false);
const itemCreating = ref(true);
const itemSaving = ref(false);
const itemForm = reactive<DictItemEditModel>(emptyItemModel());

const dictColumns = [
  { dataIndex: 'code', title: '编码' },
  { dataIndex: 'name', title: '名称' },
  { dataIndex: 'description', title: '说明' },
  { dataIndex: 'itemCount', title: '生效项 / 全部', width: 130 },
  { dataIndex: 'status', title: '状态', width: 90 },
  { dataIndex: 'action', title: '操作', width: 120 },
];

const itemColumns = [
  { dataIndex: 'itemCode', title: '项编码' },
  { dataIndex: 'label', title: '显示文字' },
  { dataIndex: 'sortNo', title: '排序', width: 80 },
  { dataIndex: 'status', title: '状态', width: 90 },
  { dataIndex: 'action', title: '操作', width: 120 },
];

const selectedDict = computed(() =>
  dicts.value.find((row) => row.code === selectedCode.value),
);

const existingItemCodes = computed(() =>
  items.value.map((row) => row.itemCode),
);

function read() {
  isMobile.value = window.matchMedia(
    `(max-width: ${breakpoints.tablet - 1}px)`,
  ).matches;
}

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

function onSelect(record: PlatformDictView) {
  selectedCode.value = record.code;
  void loadItems();
}

// ---------------------------------------------------------------- 字典类型维护

function openDictCreate() {
  dictCreating.value = true;
  Object.assign(dictForm, emptyDictModel());
  dictDialogOpen.value = true;
}

function openDictEdit(row: PlatformDictView) {
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
    message.success('已保存，全部租户即时生效');
    await load();
  } finally {
    dictSaving.value = false;
  }
}

// ---------------------------------------------------------------- 字典项维护

function openItemCreate() {
  if (!selectedCode.value) {
    return;
  }
  itemCreating.value = true;
  Object.assign(itemForm, emptyItemModel());
  itemDialogOpen.value = true;
}

function openItemEdit(row: PlatformDictItemView) {
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
          itemCode: itemForm.itemCode.trim(),
          label: itemForm.label.trim(),
          sortNo: itemForm.sortNo,
        })
      : updateDictItem(selectedCode.value, itemForm.itemCode, {
          label: itemForm.label.trim(),
          sortNo: itemForm.sortNo,
          status: itemForm.status,
        }));
    itemDialogOpen.value = false;
    message.success('已保存，全部租户即时生效');
    await loadItems();
    await load();
  } finally {
    itemSaving.value = false;
  }
}

/** 项的启停快捷切换（停用不删除；停用项不进租户生效集）。 */
async function toggleItem(row: PlatformDictItemView) {
  const next = row.status === 'ENABLED' ? 'DISABLED' : 'ENABLED';
  try {
    await updateDictItem(selectedCode.value, row.itemCode, {
      label: row.label,
      sortNo: row.sortNo,
      status: next,
    });
    message.success(next === 'ENABLED' ? '已启用' : '已停用（不删除，可恢复）');
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

onMounted(() => {
  read();
  window.addEventListener('resize', read);
  void load();
});
onUnmounted(() => window.removeEventListener('resize', read));
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
    title="字典管理"
    description="平台全局字典（性别、证件类型、科室类型等）。字典与项编码创建后不可改；项停用不删除；保存即时下发全部租户。"
  >
    <Alert
      v-if="loadError"
      class="mb-4"
      :message="`加载失败：${loadError}`"
      show-icon
      type="error"
    />
    <div class="dict-layout">
      <Card :bordered="false" class="crdp-card dict-types" title="字典类型">
        <div class="mb-3">
          <Button
            type="primary"
            data-testid="dict-create"
            @click="openDictCreate"
          >
            新建字典
          </Button>
        </div>
        <Table
          :columns="dictColumns"
          :data-source="dicts"
          :loading="loading"
          :pagination="false"
          row-key="code"
          size="small"
          :custom-row="
            (record: PlatformDictView) => ({
              class: record.code === selectedCode ? 'row-selected' : '',
              onClick: () => onSelect(record),
            })
          "
        >
          <template #bodyCell="{ column, record }">
            <template v-if="column.dataIndex === 'itemCount'">
              {{ itemCountText(record as PlatformDictView) }}
            </template>
            <template v-else-if="column.dataIndex === 'status'">
              <StatusTag
                :status="dictStatusText((record as PlatformDictView).status)"
              />
            </template>
            <template v-else-if="column.dataIndex === 'action'">
              <Button
                size="small"
                type="link"
                @click.stop="openDictEdit(record as PlatformDictView)"
              >
                维护
              </Button>
            </template>
          </template>
          <template #empty-text>
            <component :is="dictEmpty" />
          </template>
        </Table>
      </Card>

      <Card
        :bordered="false"
        class="crdp-card dict-items"
        :title="
          selectedDict
            ? `字典项 · ${selectedDict.name}（${selectedDict.code}）`
            : '字典项'
        "
      >
        <div class="mb-3">
          <Button
            type="primary"
            data-testid="dict-item-create"
            :disabled="!selectedCode"
            @click="openItemCreate"
          >
            新增项
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
            <template v-if="column.dataIndex === 'status'">
              <StatusTag
                :status="
                  dictStatusText((record as PlatformDictItemView).status)
                "
              />
            </template>
            <template v-else-if="column.dataIndex === 'action'">
              <Button
                size="small"
                type="link"
                @click="openItemEdit(record as PlatformDictItemView)"
              >
                维护
              </Button>
              <Button
                size="small"
                type="link"
                @click="toggleItem(record as PlatformDictItemView)"
              >
                {{
                  (record as PlatformDictItemView).status === 'ENABLED'
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
      :title="dictCreating ? '新建字典' : `维护字典 · ${dictForm.code}`"
      :confirm-loading="dictSaving"
      :ok-text="dictCreating ? '创建' : '保存'"
      cancel-text="取消"
      @ok="submitDict"
    >
      <Form layout="vertical" @keyup.enter.prevent>
        <FormItem label="编码（创建后不可改）" required>
          <Input
            v-model:value="dictForm.code"
            :disabled="!dictCreating"
            placeholder="如 dept_type"
            data-testid="dict-code"
          />
        </FormItem>
        <FormItem label="名称" required>
          <Input
            v-model:value="dictForm.name"
            :maxlength="128"
            placeholder="如 科室类型"
          />
        </FormItem>
        <FormItem label="说明">
          <Textarea
            v-model:value="dictForm.description"
            :maxlength="255"
            :rows="2"
            placeholder="用途说明，可留空"
          />
        </FormItem>
        <FormItem v-if="!dictCreating" label="状态">
          <Button
            v-if="dictForm.status === 'ENABLED'"
            size="small"
            danger
            @click="dictForm.status = 'DISABLED'"
          >
            停用
          </Button>
          <Button v-else size="small" @click="dictForm.status = 'ENABLED'">
            启用
          </Button>
          <span class="ml-2 text-xs text-gray-500">
            当前：{{ dictStatusText(dictForm.status) }}
          </span>
        </FormItem>
      </Form>
    </Modal>

    <Modal
      v-model:open="itemDialogOpen"
      :title="
        itemCreating
          ? `新增字典项${selectedDict ? ` · ${selectedDict.name}` : ''}`
          : `维护字典项 · ${itemForm.itemCode}`
      "
      :confirm-loading="itemSaving"
      :ok-text="itemCreating ? '新增' : '保存'"
      cancel-text="取消"
      @ok="submitItem"
    >
      <Form layout="vertical" @keyup.enter.prevent>
        <FormItem label="项编码（创建后不可改）" required>
          <Input
            v-model:value="itemForm.itemCode"
            :disabled="!itemCreating"
            placeholder="如 CLINICAL"
            data-testid="dict-item-code"
          />
        </FormItem>
        <FormItem label="显示文字" required>
          <Input
            v-model:value="itemForm.label"
            :maxlength="128"
            placeholder="如 临床科室"
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
          <Button
            v-if="itemForm.status === 'ENABLED'"
            size="small"
            danger
            @click="itemForm.status = 'DISABLED'"
          >
            停用
          </Button>
          <Button v-else size="small" @click="itemForm.status = 'ENABLED'">
            启用
          </Button>
          <span class="ml-2 text-xs text-gray-500">
            当前：{{ dictStatusText(itemForm.status) }}（停用不删除，可恢复）
          </span>
        </FormItem>
      </Form>
    </Modal>
  </Page>
</template>

<style scoped>
.dict-layout {
  display: grid;
  grid-template-columns: minmax(360px, 2fr) minmax(420px, 3fr);
  gap: 16px;
  align-items: start;
}

.dict-types :deep(.row-selected) {
  background: #e6f0ff;
}

.dict-types :deep(tbody tr) {
  cursor: pointer;
}

@media (max-width: 1279px) {
  .dict-layout {
    grid-template-columns: 1fr;
  }
}
</style>
