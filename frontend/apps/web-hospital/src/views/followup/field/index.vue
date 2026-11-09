<script lang="ts" setup>
import type { FieldDictLogView, FormFieldDef, FormSummary } from '@crdp/api';

import type { FieldEditModel } from './logic';

import { computed, onMounted, reactive, ref, watch } from 'vue';

import { Page } from '@vben/common-ui';

import {
  fieldDictStatusText,
  fieldRangeText,
  fieldStorageText,
  formFieldTypeText,
  formUsageText,
  matchFieldKeyword,
} from '@crdp/api';
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
  Pagination,
  Popconfirm,
  Result,
  Select,
  SelectOption,
  Spin,
  Switch,
  Table,
  Tag,
  Textarea,
} from 'ant-design-vue';

import {
  listFormFieldLogs,
  listFormFields,
  listForms,
  updateFormField,
} from '#/api/core/form';
import { useBreakpoint } from '#/reference/use-breakpoint';

import {
  buildFieldUpdate,
  buildStatusToggleUpdate,
  isOptionField,
  statusActionText,
  summarizeFieldChange,
  toFieldEditModel,
  validateFieldEditModel,
} from './logic';

defineOptions({ name: 'FollowupField' });

/**
 * E11-T01 / A-FLD-001、A-FLD-002、C-FLD-001 · 字段字典（/system/field，pages.md §10）：
 * 按分类列出字段、可搜索；编辑显示名、帮助、单位、软范围、必填、选项（显示名 / 排序 / 停用 / 新增）；
 * 字段可启停（停用不删除）。显示名与编码分离——表格同时展示稳定键、字段编码与显示名，
 * 选项显示「编码 · 显示名」。键、编码、类型、硬范围、敏感标记、存储方式不可改。仅桌面 / 平板。
 * E11-T08 / A-FLD-009、C-FLD-009：每个字段可查变更记录（前后值、操作人、时间）。
 */
const { isMobile } = useBreakpoint();

const loading = ref(false);
const loadError = ref('');
const forms = ref<FormSummary[]>([]);
const fields = ref<FormFieldDef[]>([]);

const selectedForm = ref('');
const selectedCategory = ref('');
const keyword = ref('');
const pageNo = ref(1);
const pageSize = 20;

const saving = ref(false);
const logsOpen = ref(false);
const logsLoading = ref(false);
const logs = ref<FieldDictLogView[]>([]);
const logsField = ref<FormFieldDef | null>(null);
const editOpen = ref(false);
const editErrors = ref<string[]>([]);
const editing = ref<FormFieldDef | null>(null);
const editForm = reactive<FieldEditModel>(
  toFieldEditModel({
    categoryKey: '',
    categoryLabel: '',
    code: '',
    formCode: '',
    formName: '',
    key: '',
    label: '',
    required: false,
    sensitive: false,
    status: 'ENABLED',
    storage: 'json',
    type: 'text',
  }),
);

const columns = [
  { dataIndex: 'label', key: 'label', title: '显示名' },
  { dataIndex: 'code', key: 'code', title: '字段编码（导入导出名）' },
  { dataIndex: 'key', key: 'key', title: '稳定键', width: 90 },
  {
    dataIndex: 'categoryLabel',
    key: 'categoryLabel',
    title: '分类',
    width: 130,
  },
  { dataIndex: 'type', key: 'type', title: '类型', width: 120 },
  { key: 'unit', title: '单位', width: 80 },
  { key: 'required', title: '必填', width: 70 },
  { key: 'sensitive', title: '敏感', width: 70 },
  { key: 'range', title: '范围（提示 / 拦截）', width: 170 },
  { dataIndex: 'status', key: 'status', title: '状态', width: 90 },
  { key: 'action', title: '操作', width: 210 },
];

const logColumns = [
  { key: 'createdAt', title: '时间', width: 180 },
  { key: 'operatorName', title: '操作人', width: 130 },
  { key: 'change', title: '调整内容（前 → 后）' },
];

const categoryOptions = computed(() => {
  const seen = new Map<string, string>();
  for (const field of fields.value) {
    seen.set(field.categoryKey, field.categoryLabel);
  }
  return [...seen.entries()].map(([value, label]) => ({ label, value }));
});

const filteredFields = computed(() =>
  fields.value.filter(
    (field) =>
      (!selectedCategory.value ||
        field.categoryKey === selectedCategory.value) &&
      matchFieldKeyword(field, keyword.value),
  ),
);

const pagedFields = computed(() =>
  filteredFields.value.slice(
    (pageNo.value - 1) * pageSize,
    pageNo.value * pageSize,
  ),
);

watch([selectedCategory, keyword], () => {
  pageNo.value = 1;
});

const editingOptionField = computed(() => {
  const field = editing.value;
  return field ? isOptionField(field) : false;
});

async function loadForms() {
  const list = await listForms();
  forms.value = list;
  // E24-T07：下标替代 list.at(0)（微信 XWeb 等旧内核无 Array.prototype.at）
  const first = list[0];
  if (!selectedForm.value && first) {
    selectedForm.value = first.code;
  }
}

async function loadFields() {
  if (!selectedForm.value) {
    fields.value = [];
    return;
  }
  loading.value = true;
  loadError.value = '';
  try {
    fields.value = await listFormFields(selectedForm.value);
  } catch (error) {
    loadError.value = error instanceof Error ? error.message : String(error);
    fields.value = [];
  } finally {
    loading.value = false;
  }
}

async function reload() {
  loading.value = true;
  loadError.value = '';
  try {
    await loadForms();
    await loadFields();
  } catch (error) {
    loadError.value = error instanceof Error ? error.message : String(error);
  } finally {
    loading.value = false;
  }
}

watch(selectedForm, () => {
  selectedCategory.value = '';
  void loadFields();
});

onMounted(reload);

// ---------------------------------------------------------------- 编辑

function openEdit(row: FormFieldDef) {
  editing.value = row;
  Object.assign(editForm, toFieldEditModel(row));
  editErrors.value = [];
  editOpen.value = true;
}

function addOptionRow() {
  editForm.options.push({
    code: null,
    label: '',
    // E24-T07：下标替代 options.at(-1)（微信 XWeb 等旧内核无 Array.prototype.at）
    sortNo: (editForm.options[editForm.options.length - 1]?.sortNo ?? 0) + 10,
    status: 'ENABLED',
  });
}

async function submitEdit() {
  const field = editing.value;
  if (!field) return;
  editErrors.value = validateFieldEditModel(editForm, editingOptionField.value);
  if (editErrors.value.length > 0) return;
  saving.value = true;
  try {
    const updated = await updateFormField(
      field.formCode,
      field.key,
      buildFieldUpdate(editForm, editingOptionField.value),
    );
    const index = fields.value.findIndex((row) => row.key === field.key);
    if (index !== -1) fields.value.splice(index, 1, updated);
    editOpen.value = false;
    message.success(`已保存「${updated.label}」的调整，并已记录变更`);
  } catch (error) {
    editErrors.value = [error instanceof Error ? error.message : String(error)];
  } finally {
    saving.value = false;
  }
}

async function openLogs(row: FormFieldDef) {
  logsField.value = row;
  logsOpen.value = true;
  logsLoading.value = true;
  try {
    logs.value = await listFormFieldLogs(row.formCode, row.key);
  } catch (error) {
    logs.value = [];
    message.error(error instanceof Error ? error.message : String(error));
  } finally {
    logsLoading.value = false;
  }
}

async function toggleStatus(row: FormFieldDef) {
  const next = row.status === 'ENABLED' ? 'DISABLED' : 'ENABLED';
  saving.value = true;
  try {
    const updated = await updateFormField(
      row.formCode,
      row.key,
      buildStatusToggleUpdate(row, next),
    );
    const index = fields.value.findIndex((item) => item.key === row.key);
    if (index !== -1) fields.value.splice(index, 1, updated);
    message.success(
      next === 'DISABLED'
        ? `已停用「${updated.label}」，表单不再下发该字段`
        : `已启用「${updated.label}」`,
    );
  } catch (error) {
    message.error(error instanceof Error ? error.message : String(error));
  } finally {
    saving.value = false;
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
    title="字段字典"
    description="表单模板驱动录入、校验、导入导出与统计；这里只调显示名、帮助、范围、必填与选项，编码与类型由字段字典统一定义，字段可停用（不删除）。"
  >
    <Card>
      <div class="filter-bar">
        <Select
          v-model:value="selectedForm"
          class="filter-item"
          placeholder="选择表单"
          style="width: 240px"
        >
          <SelectOption
            v-for="form in forms"
            :key="form.code"
            :value="form.code"
            :label="`${form.name}（${formUsageText(form.usage)}）`"
          >
            {{ form.name }}（{{ formUsageText(form.usage) }}）
          </SelectOption>
        </Select>
        <Select
          v-model:value="selectedCategory"
          allow-clear
          class="filter-item"
          placeholder="全部分类"
          style="width: 160px"
        >
          <SelectOption
            v-for="category in categoryOptions"
            :key="category.value"
            :value="category.value"
          >
            {{ category.label }}
          </SelectOption>
        </Select>
        <Input
          v-model:value="keyword"
          allow-clear
          class="filter-item"
          placeholder="搜索显示名、字段编码或稳定键"
          style="width: 260px"
        />
        <Button :loading="loading" @click="reload">刷新</Button>
      </div>

      <Alert
        v-if="loadError"
        :message="loadError"
        show-icon
        type="error"
        style="margin-bottom: 12px"
      />

      <Spin :spinning="loading">
        <Empty
          v-if="fields.length === 0 && !loading && !loadError"
          description="暂无字段（示例租户由迁移预置；正式字段由实施导入）"
        />
        <template v-else>
          <Table
            :columns="columns"
            :data-source="pagedFields"
            :pagination="false"
            row-key="key"
            size="middle"
          >
            <template #bodyCell="{ column, record }">
              <template v-if="column.key === 'label'">
                <div>{{ record.label }}</div>
                <div v-if="record.help" class="cell-sub">{{ record.help }}</div>
              </template>
              <template v-else-if="column.key === 'code'">
                <div>{{ record.code }}</div>
                <div v-if="record.type === 'derived'" class="cell-sub">
                  派生公式：{{ record.derivedDef?.fn }}（自动计算，只读）
                </div>
              </template>
              <template v-else-if="column.key === 'type'">
                {{ formFieldTypeText(record.type) }}
                <Tag v-if="record.type === 'derived'" color="blue">
                  自动计算
                </Tag>
              </template>
              <template v-else-if="column.key === 'unit'">
                {{ record.unit || '—' }}
              </template>
              <template v-else-if="column.key === 'required'">
                {{ record.required ? '必填' : '选填' }}
              </template>
              <template v-else-if="column.key === 'sensitive'">
                <Tag v-if="record.sensitive" color="red">敏感 · 默认脱敏</Tag>
                <template v-else>—</template>
              </template>
              <template v-else-if="column.key === 'range'">
                <div v-if="record.softRange">
                  提示 {{ fieldRangeText(record.softRange) }}
                </div>
                <div v-if="record.hardRange" class="cell-sub">
                  拦截 {{ fieldRangeText(record.hardRange) }}
                </div>
                <div v-if="!record.softRange && !record.hardRange">—</div>
              </template>
              <template v-else-if="column.key === 'status'">
                <Tag
                  :color="record.status === 'ENABLED' ? 'success' : 'default'"
                >
                  {{ fieldDictStatusText(record.status) }}
                </Tag>
              </template>
              <template v-else-if="column.key === 'action'">
                <Button size="small" type="link" @click="openEdit(record)">
                  编辑
                </Button>
                <Popconfirm
                  :title="
                    record.status === 'ENABLED'
                      ? `停用「${record.label}」？停用后表单不再下发该字段，已有数据保留。`
                      : `启用「${record.label}」？`
                  "
                  ok-text="确定"
                  cancel-text="取消"
                  @confirm="toggleStatus(record)"
                >
                  <Button size="small" type="link">
                    {{ statusActionText(record.status) }}
                  </Button>
                </Popconfirm>
                <Button size="small" type="link" @click="openLogs(record)">
                  变更记录
                </Button>
              </template>
            </template>
          </Table>
          <div class="pager">
            <Pagination
              v-model:current="pageNo"
              :page-size="pageSize"
              :total="filteredFields.length"
              show-quick-jumper
              size="small"
            />
          </div>
        </template>
      </Spin>
    </Card>

    <Modal
      v-model:open="editOpen"
      :confirm-loading="saving"
      :title="editing ? `编辑字段：${editing.label}` : '编辑字段'"
      width="720px"
      ok-text="保存"
      cancel-text="取消"
      @ok="submitEdit"
    >
      <Alert
        v-if="editErrors.length > 0"
        :message="editErrors.join('；')"
        show-icon
        type="error"
        style="margin-bottom: 12px"
      />
      <div v-if="editing" class="readonly-grid">
        <span>稳定键：{{ editing.key }}</span>
        <span>字段编码：{{ editing.code }}（不可改）</span>
        <span>类型：{{ formFieldTypeText(editing.type) }}</span>
        <span>存储：{{ fieldStorageText(editing.storage) }}</span>
        <span v-if="editing.hardRange">
          拦截范围：{{ fieldRangeText(editing.hardRange) }}（不可改）
        </span>
        <span v-if="editing.sensitive">敏感字段 · 默认脱敏（不可改）</span>
      </div>
      <Form layout="vertical">
        <FormItem label="显示名" required>
          <Input
            v-model:value="editForm.label"
            :maxlength="128"
            placeholder="如「吸烟史」，不带“(否=0，是=1)”编码说明"
          />
        </FormItem>
        <FormItem label="帮助说明">
          <Textarea
            v-model:value="editForm.helpText"
            :maxlength="500"
            :rows="2"
            placeholder="保留原表判定口径，如肾功能不全的判定标准"
          />
        </FormItem>
        <FormItem label="单位">
          <Input
            v-model:value="editForm.unit"
            :maxlength="32"
            placeholder="如 mm、ml、%"
          />
        </FormItem>
        <FormItem label="提示范围（超出黄色提示，可保存）">
          <div class="range-row">
            <InputNumber
              v-model:value="editForm.softRangeMin"
              :max="editForm.softRangeMax ?? undefined"
              placeholder="最小值"
              style="width: 160px"
            />
            <span class="range-sep">–</span>
            <InputNumber
              v-model:value="editForm.softRangeMax"
              :min="editForm.softRangeMin ?? undefined"
              placeholder="最大值"
              style="width: 160px"
            />
          </div>
        </FormItem>
        <FormItem label="必填">
          <Switch v-model:checked="editForm.required" />
        </FormItem>
        <FormItem label="状态">
          <Switch
            :checked="editForm.status === 'ENABLED'"
            checked-children="启用"
            un-checked-children="停用"
            @change="editForm.status = $event ? 'ENABLED' : 'DISABLED'"
          />
        </FormItem>
        <FormItem
          v-if="editingOptionField"
          label="选项（编码存数据库、页面显示显示名；编码不可改，停用不删除）"
        >
          <div class="option-list">
            <div
              v-for="(option, index) in editForm.options"
              :key="`${option.code ?? 'new'}-${index}`"
              class="option-row"
            >
              <InputNumber
                v-if="option.code === null"
                v-model:value="option.code"
                :min="0"
                placeholder="编码"
                style="width: 90px"
              />
              <span v-else class="option-code">{{ option.code }}</span>
              <Input
                v-model:value="option.label"
                :maxlength="128"
                placeholder="选项显示名"
                style="width: 220px"
              />
              <InputNumber
                v-model:value="option.sortNo"
                :min="0"
                style="width: 90px"
              />
              <Switch
                :checked="option.status === 'ENABLED'"
                checked-children="启用"
                size="small"
                un-checked-children="停用"
                @change="option.status = $event ? 'ENABLED' : 'DISABLED'"
              />
            </div>
            <Button size="small" type="dashed" @click="addOptionRow">
              新增选项
            </Button>
          </div>
        </FormItem>
      </Form>
    </Modal>

    <Modal
      v-model:open="logsOpen"
      :title="
        logsField
          ? `变更记录 · ${logsField.label}（${logsField.key}）`
          : '变更记录'
      "
      :footer="null"
      width="760px"
    >
      <div class="logs-hint">
        每次维护（含启停与选项调整）都会留痕：前后值、操作人、时间；完整快照见审计中心。
      </div>
      <Spin :spinning="logsLoading">
        <Empty
          v-if="logs.length === 0 && !logsLoading"
          description="暂无变更记录（该字段创建后未被维护过）"
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
              {{ (record as FieldDictLogView).createdAt }}
            </template>
            <template v-else-if="column.key === 'operatorName'">
              {{ (record as FieldDictLogView).operatorName ?? '—' }}
            </template>
            <template v-else-if="column.key === 'change'">
              {{
                summarizeFieldChange(
                  (record as FieldDictLogView).beforeJson,
                  (record as FieldDictLogView).afterJson,
                )
              }}
            </template>
          </template>
        </Table>
      </Spin>
    </Modal>
  </Page>
</template>

<style scoped>
.filter-bar {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  margin-bottom: 16px;
}

.filter-item + .filter-item {
  margin-left: 0;
}

.cell-sub {
  font-size: 12px;
  color: rgb(0 0 0 / 45%);
}

.pager {
  display: flex;
  justify-content: flex-end;
  margin-top: 12px;
}

.logs-hint {
  margin-bottom: 12px;
  font-size: 12px;
  color: rgb(0 0 0 / 45%);
}

.readonly-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 4px 16px;
  padding: 12px;
  margin-bottom: 12px;
  font-size: 12px;
  color: rgb(0 0 0 / 65%);
  background: rgb(0 0 0 / 3%);
  border-radius: 6px;
}

.range-row {
  display: flex;
  gap: 8px;
  align-items: center;
}

.range-sep {
  color: rgb(0 0 0 / 45%);
}

.option-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.option-row {
  display: flex;
  gap: 8px;
  align-items: center;
}

.option-code {
  display: inline-block;
  width: 90px;
  color: rgb(0 0 0 / 65%);
  text-align: center;
}
</style>
