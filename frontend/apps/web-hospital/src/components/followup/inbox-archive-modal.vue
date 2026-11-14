<script lang="ts" setup>
import type {
  ReportCatalogName,
  ReportCatalogType,
  ReportCreatedView,
  ReportLocationInput,
  ReportLocationNode,
} from '@crdp/api';

import { computed, ref, watch } from 'vue';

import { reportInboxArchiveError } from '@crdp/api';
import {
  Button,
  DatePicker,
  Form,
  FormItem,
  Input,
  message,
  Modal,
  Select,
} from 'ant-design-vue';
import dayjs from 'dayjs';

import { searchPatients } from '#/api/core/patient';
import {
  archiveReportInboxFile,
  fetchCatalogReportNames,
  fetchCatalogReportTypes,
  fetchReportLocations,
} from '#/api/core/report';
import LocationPicker from '#/components/followup/location-picker.vue';

/**
 * E21-T16 / C-SCAN-009：待归档区「归档」对话框（后标注）——逐个指定患者、类型与位置
 * （至少一个）后生成报告单。患者按姓名 / 病案号检索（盲索引精确，与临时任务同口径）；
 * 选定患者后加载位置树（必填可多选，LocationPicker）；名称按类型 nameRequired；
 * 报告时间默认当天（先扫描后标注的纸质资料常无报告日期）。校验前端先提示、
 * 后端同口径 40001 / 40400 兜底（AGENTS §6）。
 */
const props = defineProps<{
  file?: null | {
    fileName: string;
    id: string;
  };
  open: boolean;
}>();

const emit = defineEmits<{
  archived: [view: ReportCreatedView];
  'update:open': [open: boolean];
}>();

const keyword = ref('');
const searching = ref(false);
const patientOptions = ref<Array<{ label: string; value: string }>>([]);
const patientId = ref<null | string>(null);
const types = ref<ReportCatalogType[]>([]);
const typesLoading = ref(false);
const names = ref<ReportCatalogName[]>([]);
const namesLoading = ref(false);
const treeOptions = ref<ReportLocationNode[]>([]);
const treeLoading = ref(false);
const typeCode = ref<null | string>(null);
const nameId = ref<null | string>(null);
const reportDate = ref<dayjs.Dayjs | null>(null);
const locations = ref<ReportLocationInput[]>([]);
const submitting = ref(false);
const showErrors = ref(false);

const currentType = computed(
  () => types.value.find((item) => item.code === typeCode.value) ?? null,
);

const errorText = computed(() =>
  reportInboxArchiveError(
    {
      nameId: nameId.value,
      nameRequired: currentType.value?.nameRequired ?? true,
      patientId: patientId.value,
      reportDate: reportDate.value ? 'set' : null,
      typeCode: typeCode.value,
    },
    locations.value.length,
  ),
);

watch(
  () => props.open,
  (open) => {
    if (!open) return;
    resetForm();
    void loadTypes();
  },
  { immediate: true },
);

function resetForm() {
  keyword.value = '';
  patientOptions.value = [];
  patientId.value = null;
  nameId.value = null;
  typeCode.value = null;
  reportDate.value = dayjs();
  locations.value = [];
  names.value = [];
  treeOptions.value = [];
  showErrors.value = false;
}

async function loadTypes() {
  typesLoading.value = true;
  try {
    types.value = await fetchCatalogReportTypes();
  } catch {
    types.value = [];
  } finally {
    typesLoading.value = false;
  }
}

/** 患者检索（姓名 / 病案号盲索引精确；数据范围由后端承担，越范围患者不出现在候选）。 */
async function handlePatientSearch() {
  const trimmed = keyword.value.trim();
  if (trimmed.length === 0) {
    message.warning('请输入患者姓名或病案号');
    return;
  }
  searching.value = true;
  try {
    const page = await searchPatients({
      filters: {},
      keyword: trimmed,
      pageNo: 1,
      pageSize: 20,
    });
    patientOptions.value = page.items.map((item) => ({
      label: `${item.name}（${item.patientNo}）`,
      value: item.id,
    }));
    if (page.items.length === 0) {
      message.info('未找到患者，请核对姓名或病案号（须完整输入）');
    }
  } catch {
    patientOptions.value = [];
  } finally {
    searching.value = false;
  }
}

/** 选定患者 → 加载该患者的位置树；换患者清空已选位置（位置锚点随患者）。 */
async function onPatientChange(value: unknown) {
  patientId.value = (value as string) ?? null;
  locations.value = [];
  treeOptions.value = [];
  if (!patientId.value) return;
  treeLoading.value = true;
  try {
    treeOptions.value = await fetchReportLocations(patientId.value);
  } catch {
    treeOptions.value = [];
  } finally {
    treeLoading.value = false;
  }
}

async function onTypeChange(code: unknown) {
  typeCode.value = (code as string) ?? null;
  nameId.value = null;
  names.value = [];
  const next = typeCode.value;
  if (!next) return;
  namesLoading.value = true;
  try {
    names.value = await fetchCatalogReportNames(next);
  } catch {
    names.value = [];
  } finally {
    namesLoading.value = false;
  }
}

async function handleOk() {
  showErrors.value = true;
  if (errorText.value || !props.file) return;
  submitting.value = true;
  try {
    const created = await archiveReportInboxFile(props.file.id, {
      locations: locations.value,
      nameId: nameId.value,
      patientId: patientId.value as string,
      reportDate: reportDate.value?.format('YYYY-MM-DD') ?? null,
      typeCode: typeCode.value ?? '',
    });
    message.success(
      `已归档：${created.typeName}${created.name ? ` · ${created.name}` : ''}，位置 ${created.locationCount} 个`,
    );
    emit('archived', created);
    emit('update:open', false);
  } catch {
    // 错误提示由统一拦截器弹出（40001 校验 / 40400 越范围在后端兜底）
  } finally {
    submitting.value = false;
  }
}

// 说明文案（与上传报告单对话框同口径的压缩提示在进区时已完成，这里只提示当前文件）
const fileHint = `待归档文件：${props.file?.fileName ?? '—'}`;
</script>

<template>
  <Modal
    :confirm-loading="submitting"
    :open="open"
    ok-text="归档"
    title="归档扫描件（指定患者、类型与位置）"
    :width="880"
    @cancel="emit('update:open', false)"
    @ok="handleOk"
  >
    <Form layout="vertical" @submit.prevent>
      <FormItem label="患者" required>
        <div class="patient-row">
          <Select
            :options="patientOptions"
            placeholder="输入姓名或病案号检索后选择"
            show-search
            :value="patientId"
            @update:value="onPatientChange"
          />
          <Input
            v-model:value="keyword"
            allow-clear
            data-testid="inbox-archive-patient-keyword"
            placeholder="姓名 / 病案号（完整输入）"
            style="width: 220px"
            @keyup.enter="handlePatientSearch"
          />
          <Button
            :loading="searching"
            data-testid="inbox-archive-patient-search"
            @click="handlePatientSearch"
          >
            检索
          </Button>
        </div>
        <p v-if="showErrors && !patientId" class="error">请选择患者</p>
      </FormItem>

      <FormItem label="类型" required>
        <Select
          :loading="typesLoading"
          :options="
            types.map((item) => ({ label: item.name, value: item.code }))
          "
          placeholder="请选择类型"
          :value="typeCode"
          @update:value="onTypeChange"
        />
        <p v-if="showErrors && !typeCode" class="error">请选择资料类型</p>
      </FormItem>

      <FormItem label="名称" :required="currentType?.nameRequired ?? true">
        <Select
          :disabled="!typeCode"
          :loading="namesLoading"
          :options="names.map((item) => ({ label: item.name, value: item.id }))"
          :placeholder="typeCode ? '请选择名称' : '请先选择类型'"
          show-search
          :value="nameId"
          @update:value="nameId = $event"
        />
      </FormItem>

      <FormItem label="报告时间" required>
        <DatePicker
          v-model:value="reportDate"
          style="width: 100%"
          value-format="YYYY-MM-DD"
        />
      </FormItem>

      <FormItem label="位置" required>
        <LocationPicker
          :options="treeOptions"
          :patient-id="patientId ?? ''"
          :show-error="showErrors && locations.length === 0"
          :value="locations"
          @update:value="locations = $event"
        />
        <p v-if="treeLoading" class="hint">正在加载位置树…</p>
        <p v-else-if="patientId && treeOptions.length === 0" class="hint">
          该患者暂无可选位置（基线分类与随访记录随模板与记录生成）。
        </p>
        <p v-else class="hint">归档须至少指定 1 个位置（C-SCAN-009）。</p>
      </FormItem>

      <p class="hint">{{ fileHint }}</p>
    </Form>
  </Modal>
</template>

<style scoped>
.patient-row {
  display: flex;
  gap: 8px;
  align-items: center;
}

.patient-row :deep(.ant-select) {
  flex: 1;
}

.error {
  margin: 4px 0 0;
  font-size: 12px;
  color: #ff4d4f;
}

.hint {
  margin: 4px 0 0;
  font-size: 12px;
  color: #8c8c8c;
}
</style>
