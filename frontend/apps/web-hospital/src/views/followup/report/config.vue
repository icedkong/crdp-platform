<script lang="ts" setup>
import type {
  FormSummary,
  ReportIndicatorTplView,
  ReportNameView,
  ReportTypeView,
} from '@crdp/api';

import type {
  NameEditModel,
  TplEditModel,
  TypeEditModel,
} from './config-logic';

import { computed, onMounted, reactive, ref } from 'vue';

import { Page } from '@vben/common-ui';

import { reportConfigStatusText, reportNameRequiredText } from '@crdp/api';
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
  Popconfirm,
  Result,
  Select,
  SelectOption,
  Spin,
  Switch,
  Table,
  TabPane,
  Tabs,
  Tag,
  Textarea,
} from 'ant-design-vue';

import { listForms } from '#/api/core/form';
import {
  createReportName,
  createReportTemplate,
  createReportType,
  deleteReportName,
  deleteReportTemplate,
  deleteReportType,
  fetchReportNames,
  fetchReportTemplates,
  fetchReportTypes,
  updateReportName,
  updateReportTemplate,
  updateReportType,
} from '#/api/core/reportConfig';
import { useBreakpoint } from '#/reference/use-breakpoint';

import {
  buildNameCreatePayload,
  buildNameUpdatePayload,
  buildTplCreatePayload,
  buildTplUpdatePayload,
  buildTypeCreatePayload,
  buildTypeUpdatePayload,
  deleteTplConfirmText,
  deleteTypeConfirmText,
  emptyNameEditModel,
  emptyTplItem,
  emptyTypeEditModel,
  matchNameKeyword,
  toNameEditModel,
  toTplEditModel,
  toTypeEditModel,
  validateNameEditModel,
  validateTplEditModel,
  validateTypeEditModel,
} from './config-logic';

defineOptions({ name: 'FollowupReportConfig' });

/**
 * E16-T01 / A-ATT-003、A-ATT-011、A-ATT-012、A-ATT-018；C-SCAN-002、003、004 ·
 * 报告单设置（/system/report-config，pages.md §10）：三个页签——资料类型（编码创建后不可改、
 * 可增减 / 停用 / 排序，字段集引用报告单类型表单）、名称字典（同类型唯一、绑定指标模板、
 * 添加时下拉选择可输入搜索）、指标模板（项目名称、代码、参考范围可按性别区分、单位、顺序）。
 * 修改只影响之后新建的报告单；增删改由后端写审计。仅桌面 / 平板。
 */
const { isMobile } = useBreakpoint();

// ---------------------------------------------------------------- 资料类型（A-ATT-003）

const typesLoading = ref(false);
const types = ref<ReportTypeView[]>([]);
const reportForms = ref<FormSummary[]>([]);

const typeOpen = ref(false);
const typeSaving = ref(false);
const typeEditing = ref<null | ReportTypeView>(null);
const typeForm = reactive<TypeEditModel>(emptyTypeEditModel());
const typeErrors = ref<Partial<Record<keyof TypeEditModel, string>>>({});

const typeColumns = [
  { dataIndex: 'sortNo', key: 'sortNo', title: '排序', width: 70 },
  { dataIndex: 'code', key: 'code', title: '编码', width: 170 },
  { dataIndex: 'name', key: 'name', title: '名称' },
  { key: 'formName', title: '字段集', width: 170 },
  { key: 'nameRequired', title: '名称必填', width: 90 },
  { key: 'nameCount', title: '名称数', width: 90 },
  { key: 'status', title: '状态', width: 90 },
  { key: 'action', title: '操作', width: 200 },
];

const formOptions = computed(() =>
  reportForms.value.map((form) => ({
    label: `${form.name}（${form.code}）`,
    value: form.code,
  })),
);

async function loadTypes() {
  typesLoading.value = true;
  try {
    types.value = await fetchReportTypes();
  } catch (error) {
    types.value = [];
    message.error(error instanceof Error ? error.message : '资料类型加载失败');
  } finally {
    typesLoading.value = false;
  }
}

async function loadForms() {
  try {
    const forms = await listForms();
    reportForms.value = forms.filter((form) => form.usage === 'REPORT');
  } catch {
    reportForms.value = [];
  }
}

function openTypeCreate() {
  typeEditing.value = null;
  Object.assign(typeForm, emptyTypeEditModel());
  typeErrors.value = {};
  typeOpen.value = true;
}

function openTypeEdit(row: ReportTypeView) {
  typeEditing.value = row;
  Object.assign(typeForm, toTypeEditModel(row));
  typeErrors.value = {};
  typeOpen.value = true;
}

async function submitType() {
  typeErrors.value = validateTypeEditModel(typeForm, {
    codeLocked: typeEditing.value !== null,
  });
  if (Object.keys(typeErrors.value).length > 0) return;
  typeSaving.value = true;
  try {
    if (typeEditing.value) {
      await updateReportType(
        typeEditing.value.id,
        buildTypeUpdatePayload(typeForm),
      );
      message.success(`已保存「${typeForm.name}」`);
    } else {
      await createReportType(buildTypeCreatePayload(typeForm));
      message.success(`已新建「${typeForm.name}」`);
    }
    typeOpen.value = false;
    await loadTypes();
  } catch (error) {
    const body = error as { message?: string };
    message.error(body?.message || '保存失败，请稍后重试');
  } finally {
    typeSaving.value = false;
  }
}

async function removeType(row: ReportTypeView) {
  try {
    await deleteReportType(row.id);
    message.success(`已删除「${row.name}」`);
    await loadTypes();
  } catch (error) {
    const body = error as { message?: string };
    message.error(body?.message || '删除失败，请稍后重试');
    await loadTypes();
  }
}

// ---------------------------------------------------------------- 名称字典（A-ATT-011）

const namesLoading = ref(false);
const names = ref<ReportNameView[]>([]);
const nameTypeFilter = ref<string>('ALL');
const nameKeyword = ref('');

const nameOpen = ref(false);
const nameSaving = ref(false);
const nameEditing = ref<null | ReportNameView>(null);
const nameTypeCode = ref('');
const nameForm = reactive<NameEditModel>(emptyNameEditModel());
const nameErrors = ref<Partial<Record<keyof NameEditModel, string>>>({});

const nameColumns = [
  { key: 'typeName', title: '类型', width: 130 },
  { dataIndex: 'name', key: 'name', title: '名称' },
  { key: 'indicatorTplName', title: '绑定指标模板' },
  { dataIndex: 'sortNo', key: 'sortNo', title: '排序', width: 70 },
  { key: 'status', title: '状态', width: 90 },
  { key: 'action', title: '操作', width: 170 },
];

const typeFilterOptions = computed(() => [
  { label: '全部类型', value: 'ALL' },
  ...types.value.map((type) => ({ label: type.name, value: type.code })),
]);

const tplOptions = computed(() =>
  templates.value
    .filter((tpl) => tpl.status === 'ENABLED')
    .map((tpl) => ({ label: tpl.name, value: tpl.id })),
);

const filteredNames = computed(() =>
  names.value.filter(
    (name) =>
      (nameTypeFilter.value === 'ALL' ||
        name.typeCode === nameTypeFilter.value) &&
      matchNameKeyword(name, nameKeyword.value),
  ),
);

async function loadNames() {
  namesLoading.value = true;
  try {
    names.value = await fetchReportNames();
  } catch (error) {
    names.value = [];
    message.error(error instanceof Error ? error.message : '名称字典加载失败');
  } finally {
    namesLoading.value = false;
  }
}

function openNameCreate() {
  nameEditing.value = null;
  nameTypeCode.value =
    nameTypeFilter.value === 'ALL'
      ? (types.value[0]?.code ?? '')
      : nameTypeFilter.value;
  Object.assign(nameForm, emptyNameEditModel());
  nameErrors.value = {};
  nameOpen.value = true;
}

function openNameEdit(row: ReportNameView) {
  nameEditing.value = row;
  nameTypeCode.value = row.typeCode;
  Object.assign(nameForm, toNameEditModel(row));
  nameErrors.value = {};
  nameOpen.value = true;
}

async function submitName() {
  nameErrors.value = validateNameEditModel(nameForm);
  if (Object.keys(nameErrors.value).length > 0) return;
  nameSaving.value = true;
  try {
    if (nameEditing.value) {
      await updateReportName(
        nameEditing.value.id,
        buildNameUpdatePayload(nameForm),
      );
      message.success(`已保存「${nameForm.name}」`);
    } else {
      await createReportName(
        buildNameCreatePayload(nameForm, nameTypeCode.value),
      );
      message.success(`已新建「${nameForm.name}」`);
    }
    nameOpen.value = false;
    await Promise.all([loadNames(), loadTypes()]);
  } catch (error) {
    const body = error as { message?: string };
    message.error(body?.message || '保存失败，请稍后重试');
  } finally {
    nameSaving.value = false;
  }
}

async function removeName(row: ReportNameView) {
  try {
    await deleteReportName(row.id);
    message.success(`已删除「${row.name}」`);
    await Promise.all([loadNames(), loadTypes()]);
  } catch (error) {
    const body = error as { message?: string };
    message.error(body?.message || '删除失败，请稍后重试');
    await Promise.all([loadNames(), loadTypes()]);
  }
}

// ---------------------------------------------------------------- 指标模板（A-ATT-012 / C-SCAN-004）

const tplLoading = ref(false);
const templates = ref<ReportIndicatorTplView[]>([]);

const tplOpen = ref(false);
const tplSaving = ref(false);
const tplEditing = ref<null | ReportIndicatorTplView>(null);
const tplForm = reactive<TplEditModel>(toTplEditModel(null));
const tplErrors = ref<Partial<Record<keyof TplEditModel, string>>>({});
const tplItemErrors = ref<string[]>([]);

const tplColumns = [
  { dataIndex: 'name', key: 'name', title: '模板名称' },
  { dataIndex: 'itemCount', key: 'itemCount', title: '项目数', width: 90 },
  { key: 'boundNameCount', title: '绑定名称数', width: 110 },
  { key: 'status', title: '状态', width: 90 },
  { key: 'action', title: '操作', width: 170 },
];

async function loadTemplates() {
  tplLoading.value = true;
  try {
    templates.value = await fetchReportTemplates();
  } catch (error) {
    templates.value = [];
    message.error(error instanceof Error ? error.message : '指标模板加载失败');
  } finally {
    tplLoading.value = false;
  }
}

function openTplCreate() {
  tplEditing.value = null;
  Object.assign(tplForm, toTplEditModel(null));
  tplErrors.value = {};
  tplItemErrors.value = [];
  tplOpen.value = true;
}

function openTplEdit(row: ReportIndicatorTplView) {
  tplEditing.value = row;
  Object.assign(tplForm, toTplEditModel(row));
  tplErrors.value = {};
  tplItemErrors.value = [];
  tplOpen.value = true;
}

function addTplItem() {
  const lastSort =
    tplForm.items.length > 0
      ? tplForm.items[tplForm.items.length - 1]?.sortNo
      : undefined;
  tplForm.items.push(emptyTplItem((lastSort ?? 0) + 10));
}

function removeTplItem(index: number) {
  tplForm.items.splice(index, 1);
}

async function submitTpl() {
  const { errors, itemErrors } = validateTplEditModel(tplForm);
  tplErrors.value = errors;
  tplItemErrors.value = itemErrors;
  if (Object.keys(errors).length > 0 || itemErrors.length > 0) return;
  tplSaving.value = true;
  try {
    if (tplEditing.value) {
      await updateReportTemplate(
        tplEditing.value.id,
        buildTplUpdatePayload(tplForm),
      );
      message.success(`已保存模板「${tplForm.name}」`);
    } else {
      await createReportTemplate(buildTplCreatePayload(tplForm));
      message.success(`已新建模板「${tplForm.name}」`);
    }
    tplOpen.value = false;
    await Promise.all([loadTemplates(), loadNames()]);
  } catch (error) {
    const body = error as { message?: string };
    message.error(body?.message || '保存失败，请稍后重试');
  } finally {
    tplSaving.value = false;
  }
}

async function removeTpl(row: ReportIndicatorTplView) {
  try {
    await deleteReportTemplate(row.id);
    message.success(`已删除模板「${row.name}」`);
    await Promise.all([loadTemplates(), loadNames()]);
  } catch (error) {
    const body = error as { message?: string };
    message.error(body?.message || '删除失败，请稍后重试');
    await Promise.all([loadTemplates(), loadNames()]);
  }
}

// ---------------------------------------------------------------- 装配

onMounted(async () => {
  await Promise.all([loadTypes(), loadForms(), loadNames(), loadTemplates()]);
});
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
    description="报告单 / 资料是平台通用能力，类型、字段集、名称字典与指标模板按本院配置；修改只影响之后新建的报告单。"
    title="报告单设置"
  >
    <Card :bordered="false">
      <Tabs default-active-key="types">
        <TabPane key="types" tab="资料类型">
          <div class="toolbar">
            <span class="hint">
              类型编码创建后不可改；停用后添加报告单不再可选（A-ATT-003）。
            </span>
            <Button
              data-testid="report-type-create"
              type="primary"
              @click="openTypeCreate"
            >
              + 新建类型
            </Button>
          </div>
          <Alert
            v-if="reportForms.length === 0"
            class="block"
            message="暂无报告单类型的表单（字段集），请先在平台配置中发布后再新建类型。"
            show-icon
            type="warning"
          />
          <Spin :spinning="typesLoading">
            <Table
              :columns="typeColumns"
              :data-source="types"
              :pagination="false"
              row-key="id"
              size="middle"
            >
              <template #bodyCell="{ column, record }">
                <template v-if="column.key === 'formName'">
                  {{ record.formName ?? record.formCode }}
                </template>
                <template v-else-if="column.key === 'nameRequired'">
                  {{ reportNameRequiredText(record.nameRequired) }}
                </template>
                <template v-else-if="column.key === 'nameCount'">
                  {{ record.nameCount }}
                </template>
                <template v-else-if="column.key === 'status'">
                  <Tag
                    :color="record.status === 'ENABLED' ? 'success' : 'default'"
                  >
                    {{ reportConfigStatusText(record.status) }}
                  </Tag>
                </template>
                <template v-else-if="column.key === 'action'">
                  <Button
                    size="small"
                    type="link"
                    @click="openTypeEdit(record)"
                  >
                    编辑
                  </Button>
                  <Popconfirm
                    :title="deleteTypeConfirmText(record)"
                    ok-text="删除"
                    ok-type="danger"
                    @confirm="removeType(record)"
                  >
                    <Button danger size="small" type="link">删除</Button>
                  </Popconfirm>
                </template>
              </template>
            </Table>
          </Spin>
        </TabPane>

        <TabPane key="names" tab="名称字典">
          <div class="toolbar">
            <Select
              v-model:value="nameTypeFilter"
              :options="typeFilterOptions"
              class="filter-select"
            />
            <Input
              v-model:value="nameKeyword"
              allow-clear
              class="filter-input"
              placeholder="搜索名称或绑定模板"
            />
            <Button type="primary" @click="openNameCreate">+ 新建名称</Button>
          </div>
          <div class="hint block">
            同一类型下名称唯一；同名报告单可多次添加、按报告时间区分（A-ATT-011）。
          </div>
          <Spin :spinning="namesLoading">
            <Table
              :columns="nameColumns"
              :data-source="filteredNames"
              :pagination="{ pageSize: 20, showSizeChanger: false }"
              row-key="id"
              size="middle"
            >
              <template #bodyCell="{ column, record }">
                <template v-if="column.key === 'indicatorTplName'">
                  <template v-if="record.indicatorTplName">
                    {{ record.indicatorTplName }}
                  </template>
                  <template v-else>—</template>
                </template>
                <template v-else-if="column.key === 'status'">
                  <Tag
                    :color="record.status === 'ENABLED' ? 'success' : 'default'"
                  >
                    {{ reportConfigStatusText(record.status) }}
                  </Tag>
                </template>
                <template v-else-if="column.key === 'action'">
                  <Button
                    size="small"
                    type="link"
                    @click="openNameEdit(record)"
                  >
                    编辑
                  </Button>
                  <Popconfirm
                    :title="`删除名称「${record.name}」？已保存报告单不受影响。`"
                    ok-text="删除"
                    ok-type="danger"
                    @confirm="removeName(record)"
                  >
                    <Button danger size="small" type="link">删除</Button>
                  </Popconfirm>
                </template>
              </template>
              <template #emptyText>
                <Empty
                  :image="Empty.PRESENTED_IMAGE_SIMPLE"
                  description="暂无名称"
                />
              </template>
            </Table>
          </Spin>
        </TabPane>

        <TabPane key="templates" tab="指标模板">
          <div class="toolbar">
            <span class="hint">
              项目：名称、代码、参考范围（可按性别区分）、单位、顺序（A-ATT-012）。
            </span>
            <Button type="primary" @click="openTplCreate">+ 新建模板</Button>
          </div>
          <Spin :spinning="tplLoading">
            <Table
              :columns="tplColumns"
              :data-source="templates"
              :pagination="false"
              row-key="id"
              size="middle"
            >
              <template #bodyCell="{ column, record }">
                <template v-if="column.key === 'itemCount'">
                  {{ record.items.length }}
                </template>
                <template v-else-if="column.key === 'boundNameCount'">
                  {{ record.boundNameCount > 0 ? record.boundNameCount : '—' }}
                </template>
                <template v-else-if="column.key === 'status'">
                  <Tag
                    :color="record.status === 'ENABLED' ? 'success' : 'default'"
                  >
                    {{ reportConfigStatusText(record.status) }}
                  </Tag>
                </template>
                <template v-else-if="column.key === 'action'">
                  <Button size="small" type="link" @click="openTplEdit(record)">
                    编辑
                  </Button>
                  <Popconfirm
                    :title="deleteTplConfirmText(record)"
                    ok-text="删除"
                    ok-type="danger"
                    @confirm="removeTpl(record)"
                  >
                    <Button danger size="small" type="link">删除</Button>
                  </Popconfirm>
                </template>
              </template>
            </Table>
          </Spin>
        </TabPane>
      </Tabs>
    </Card>

    <!-- 新建 / 编辑类型 -->
    <Modal
      v-model:open="typeOpen"
      :confirm-loading="typeSaving"
      :title="typeEditing ? `编辑类型：${typeEditing.name}` : '新建资料类型'"
      @ok="submitType"
    >
      <Form layout="vertical">
        <FormItem
          :validate-status="typeErrors.code ? 'error' : undefined"
          :help="typeErrors.code"
          label="类型编码"
        >
          <Input
            v-model:value="typeForm.code"
            :disabled="typeEditing !== null"
            placeholder="如 ECHO（创建后不可改）"
          />
        </FormItem>
        <FormItem
          :validate-status="typeErrors.name ? 'error' : undefined"
          :help="typeErrors.name"
          label="类型名称"
        >
          <Input v-model:value="typeForm.name" placeholder="如 心脏超声" />
        </FormItem>
        <FormItem
          :validate-status="typeErrors.formCode ? 'error' : undefined"
          :help="typeErrors.formCode"
          label="字段集（报告单类型的表单，决定添加报告单时显示的字段）"
        >
          <Select
            v-model:value="typeForm.formCode"
            :options="formOptions"
            placeholder="选择报告单类型的表单"
          />
        </FormItem>
        <FormItem label="名称必填">
          <Switch v-model:checked="typeForm.nameRequired" />
          <span class="hint inline-hint">
            检查、检验类必填，其他类型可选填（C-SCAN-003）
          </span>
        </FormItem>
        <FormItem
          :validate-status="typeErrors.sortNo ? 'error' : undefined"
          :help="typeErrors.sortNo"
          label="排序（小者在前）"
        >
          <InputNumber v-model:value="typeForm.sortNo" :max="9999" :min="0" />
        </FormItem>
        <FormItem v-if="typeEditing" label="状态">
          <Select v-model:value="typeForm.status">
            <SelectOption value="ENABLED">启用</SelectOption>
            <SelectOption value="DISABLED">
              停用（添加报告单不再可选）
            </SelectOption>
          </Select>
        </FormItem>
        <FormItem
          :validate-status="typeErrors.remark ? 'error' : undefined"
          :help="typeErrors.remark"
          label="说明"
        >
          <Textarea
            v-model:value="typeForm.remark"
            :rows="2"
            placeholder="选填"
          />
        </FormItem>
      </Form>
    </Modal>

    <!-- 新建 / 编辑名称 -->
    <Modal
      v-model:open="nameOpen"
      :confirm-loading="nameSaving"
      :title="nameEditing ? `编辑名称：${nameEditing.name}` : '新建名称'"
      @ok="submitName"
    >
      <Form layout="vertical">
        <FormItem label="所属类型">
          <Select
            v-model:value="nameTypeCode"
            :disabled="nameEditing !== null"
            :options="
              types.map((type) => ({ label: type.name, value: type.code }))
            "
          />
        </FormItem>
        <FormItem
          :validate-status="nameErrors.name ? 'error' : undefined"
          :help="nameErrors.name"
          label="名称"
        >
          <Input
            v-model:value="nameForm.name"
            placeholder="如 经胸超声心动图、血常规"
          />
        </FormItem>
        <FormItem
          help="绑定后添加报告单时自动带出项目行，只需填写结果（A-ATT-012）"
          label="绑定指标模板"
        >
          <Select
            v-model:value="nameForm.indicatorTplId"
            :options="tplOptions"
            allow-clear
            placeholder="不绑定"
            show-search
          />
        </FormItem>
        <FormItem
          :validate-status="nameErrors.sortNo ? 'error' : undefined"
          :help="nameErrors.sortNo"
          label="排序"
        >
          <InputNumber v-model:value="nameForm.sortNo" :max="9999" :min="0" />
        </FormItem>
        <FormItem v-if="nameEditing" label="状态">
          <Select v-model:value="nameForm.status">
            <SelectOption value="ENABLED">启用</SelectOption>
            <SelectOption value="DISABLED">停用（下拉不再出现）</SelectOption>
          </Select>
        </FormItem>
      </Form>
    </Modal>

    <!-- 新建 / 编辑指标模板 -->
    <Modal
      v-model:open="tplOpen"
      :confirm-loading="tplSaving"
      :title="tplEditing ? `编辑模板：${tplEditing.name}` : '新建指标模板'"
      width="760"
      @ok="submitTpl"
    >
      <Form layout="vertical">
        <FormItem
          :validate-status="tplErrors.name ? 'error' : undefined"
          :help="tplErrors.name"
          label="模板名称"
        >
          <Input v-model:value="tplForm.name" placeholder="如 血常规" />
        </FormItem>
        <FormItem
          :validate-status="tplErrors.items ? 'error' : undefined"
          :help="tplErrors.items"
          label="项目（名称、代码、参考范围、单位、顺序）"
        >
          <div class="tpl-items">
            <div
              v-for="(item, index) in tplForm.items"
              :key="index"
              class="tpl-item-row"
            >
              <Input
                v-model:value="item.itemName"
                class="tpl-col-name"
                placeholder="项目名称"
              />
              <Input
                v-model:value="item.itemCode"
                class="tpl-col-code"
                placeholder="代码"
              />
              <Input
                v-model:value="item.refRange"
                class="tpl-col-range"
                placeholder="参考范围"
              />
              <Input
                v-model:value="item.refRangeMale"
                class="tpl-col-range"
                placeholder="男性范围"
              />
              <Input
                v-model:value="item.refRangeFemale"
                class="tpl-col-range"
                placeholder="女性范围"
              />
              <Input
                v-model:value="item.unit"
                class="tpl-col-unit"
                placeholder="单位"
              />
              <InputNumber
                v-model:value="item.sortNo"
                :max="9999"
                :min="0"
                class="tpl-col-sort"
              />
              <Button
                danger
                size="small"
                type="link"
                @click="removeTplItem(index)"
              >
                删除
              </Button>
            </div>
          </div>
          <Button size="small" type="dashed" @click="addTplItem">
            + 添加项目
          </Button>
        </FormItem>
        <Alert
          v-if="tplItemErrors.length > 0"
          class="block"
          :message="tplItemErrors.join('；')"
          show-icon
          type="error"
        />
        <FormItem v-if="tplEditing" label="状态">
          <Select v-model:value="tplForm.status">
            <SelectOption value="ENABLED">启用</SelectOption>
            <SelectOption value="DISABLED">
              停用（新报告单不再带出项目）
            </SelectOption>
          </Select>
        </FormItem>
        <FormItem label="说明">
          <Textarea
            v-model:value="tplForm.remark"
            :rows="2"
            placeholder="选填"
          />
        </FormItem>
      </Form>
    </Modal>
  </Page>
</template>

<style scoped>
.toolbar {
  display: flex;
  gap: 8px;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
}

.hint {
  font-size: 13px;
  color: rgb(0 0 0 / 45%);
}

.inline-hint {
  margin-left: 8px;
}

.block {
  margin-bottom: 12px;
}

.filter-select {
  width: 180px;
}

.filter-input {
  flex: 1;
  max-width: 260px;
}

.tpl-items {
  display: flex;
  flex-direction: column;
  gap: 8px;
  width: 100%;
  margin-bottom: 8px;
}

.tpl-item-row {
  display: flex;
  gap: 6px;
  align-items: center;
}

.tpl-col-name {
  flex: 2;
}

.tpl-col-code {
  flex: 1;
}

.tpl-col-range {
  flex: 1.2;
}

.tpl-col-unit {
  width: 90px;
}

.tpl-col-sort {
  width: 84px;
}
</style>
