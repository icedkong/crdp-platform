<script lang="ts" setup>
import type { ImplantForm, ImplantFormErrors, ImplantOptions } from '@crdp/api';

import { computed } from 'vue';

import { leadPositionOptions, leadsAllowedForDevice } from '@crdp/api';
import {
  Button,
  DatePicker,
  FormItem,
  Input,
  Select,
  Table,
} from 'ant-design-vue';

/**
 * E21-T01 / C-PAT-004、C-PAT-005 · 植入记录字段组（新建 / 编辑弹窗与登记页 C 模式
 * 「下半部分为第一条植入记录与电极」共用，pages.md §3 页面 2 / §4.4）。字段与选项全部
 * 来自 options 接口（页面不硬编码业务文案）；电极逐行编辑：腔室 → 部位按腔室过滤
 * （Q15），无导线起搏器不录电极（C-PAT-005，提示并隐藏电极区）。
 */
const props = defineProps<{
  errors?: ImplantFormErrors;
  options?: ImplantOptions | null;
}>();

/** 表单模型（v-model:form；父级持有 reactive 对象，本组件只改字段值）。 */
const form = defineModel<ImplantForm>('form', { required: true });

const leadsEnabled = computed(() =>
  leadsAllowedForDevice(form.value.deviceType),
);

const typeOptions = computed(() => toSelect(props.options?.implantTypes));
const deviceTypeOptions = computed(() => toSelect(props.options?.deviceTypes));
const brandOptions = computed(() => toSelect(props.options?.brands));
const pacingModeOptions = computed(() => toSelect(props.options?.pacingModes));
const indicationOptions = computed(() => toSelect(props.options?.indications));
const chamberOptions = computed(() =>
  (props.options?.leadPositions ?? []).map((group) => ({
    label: group.chamber,
    value: group.chamber,
  })),
);
const leadStatusOptions = computed(() => toSelect(props.options?.leadStatuses));

function toSelect(items?: Array<{ code: string; label: string }>) {
  return (items ?? []).map((item) => ({ label: item.label, value: item.code }));
}

function positionsOf(chamber: null | string | undefined) {
  return toSelect(leadPositionOptions(props.options, chamber));
}

function fieldError(field: string) {
  return props.errors?.[field];
}

/** 器械类型切换：改为无导线时清空电极（后端同样拒绝，C-PAT-005）。 */
function handleDeviceTypeChange(value: unknown) {
  form.value.deviceType = value as null | string;
  if (!leadsAllowedForDevice(form.value.deviceType)) form.value.leads = [];
}

function addLead() {
  form.value.leads = [
    ...(form.value.leads ?? []),
    {
      chamber: undefined,
      position: null,
      model: null,
      implantDate: null,
      status: null,
    },
  ];
}

/** 腔室切换后原部位可能不属于新腔室（Q15 按腔室过滤），清掉待重选。 */
function handleChamberChange(index: number | undefined, value: unknown) {
  if (index === undefined) return;
  const leads = [...(form.value.leads ?? [])];
  const current = leads.at(index);
  if (!current) return;
  leads[index] = { ...current, chamber: value as string, position: null };
  form.value.leads = leads;
}

/** 行内字段受控更新（电极行内的输入控件）。 */
function setLeadField(
  index: number | undefined,
  field: 'implantDate' | 'model' | 'position' | 'status',
  value: unknown,
) {
  if (index === undefined) return;
  const leads = [...(form.value.leads ?? [])];
  const current = leads.at(index);
  if (!current) return;
  leads[index] = { ...current, [field]: value } as typeof current;
  form.value.leads = leads;
}

function removeLead(index: number) {
  form.value.leads = (form.value.leads ?? []).filter((_, i) => i !== index);
}

const leadColumns = [
  { title: '腔室', dataIndex: 'chamber', width: 110 },
  { title: '植入部位', dataIndex: 'position', width: 150 },
  { title: '型号', dataIndex: 'model' },
  { title: '植入日期', dataIndex: 'implantDate', width: 160 },
  { title: '状态', dataIndex: 'status', width: 130 },
  { title: '操作', key: 'action', width: 70 },
];
</script>

<template>
  <div class="implant-fields">
    <div>
      <div class="form-grid">
        <FormItem
          label="植入类型"
          required
          :validate-status="fieldError('implantType') ? 'error' : undefined"
          :help="fieldError('implantType')"
        >
          <Select
            v-model:value="form.implantType"
            :options="typeOptions"
            placeholder="请选择"
          />
        </FormItem>
        <FormItem
          label="植入日期"
          required
          :validate-status="fieldError('implantDate') ? 'error' : undefined"
          :help="fieldError('implantDate')"
        >
          <DatePicker
            v-model:value="form.implantDate"
            value-format="YYYY-MM-DD"
            style="width: 100%"
            placeholder="历史建档可填过去日期"
          />
        </FormItem>
        <FormItem
          label="起搏器类型"
          required
          :validate-status="fieldError('deviceType') ? 'error' : undefined"
          :help="fieldError('deviceType')"
        >
          <Select
            v-model:value="form.deviceType"
            :options="deviceTypeOptions"
            placeholder="请选择"
            @change="handleDeviceTypeChange"
          />
        </FormItem>
        <FormItem label="品牌">
          <Select
            v-model:value="form.brand"
            :options="brandOptions"
            allow-clear
            placeholder="请选择"
          />
        </FormItem>
        <FormItem label="型号">
          <Input
            v-model:value="form.model"
            :maxlength="128"
            placeholder="如 示例型号 S-01"
          />
        </FormItem>
        <FormItem label="设备序列号">
          <Input
            v-model:value="form.serialNo"
            :maxlength="64"
            placeholder="选填（Q16）"
          />
        </FormItem>
        <FormItem label="起搏模式">
          <Select
            v-model:value="form.pacingMode"
            :options="pacingModeOptions"
            allow-clear
            placeholder="统一字典随 E21-T04"
          />
        </FormItem>
        <FormItem label="植入医院">
          <Input
            v-model:value="form.hospital"
            :maxlength="128"
            placeholder="默认本院"
          />
        </FormItem>
        <FormItem label="植入医生">
          <Input
            v-model:value="form.doctor"
            :maxlength="64"
            placeholder="本院医生或外院手填"
          />
        </FormItem>
        <FormItem label="植入指征">
          <Select
            v-model:value="form.indication"
            :options="indicationOptions"
            allow-clear
            placeholder="请选择"
          />
        </FormItem>
        <FormItem
          v-if="form.indication === 'OTHER'"
          label="指征补充说明"
          required
          :validate-status="fieldError('indicationNote') ? 'error' : undefined"
          :help="fieldError('indicationNote')"
        >
          <Input
            v-model:value="form.indicationNote"
            :maxlength="255"
            placeholder="选「其他」须填写说明（Q16）"
          />
        </FormItem>
      </div>
    </div>

    <div v-if="leadsEnabled" class="lead-block">
      <div class="lead-title">电极（RA / RV / LV，可多根）</div>
      <div v-if="fieldError('leads')" class="lead-error">
        {{ fieldError('leads') }}
      </div>
      <Table
        :columns="leadColumns"
        :data-source="form.leads ?? []"
        :pagination="false"
        size="small"
      >
        <template #bodyCell="{ column, index }">
          <template v-if="column.dataIndex === 'chamber'">
            <Select
              :value="(form.leads ?? [])[index]?.chamber"
              :options="chamberOptions"
              size="small"
              style="width: 90px"
              placeholder="腔室"
              @change="(value: any) => handleChamberChange(index, value)"
            />
          </template>
          <template v-else-if="column.dataIndex === 'position'">
            <Select
              :value="(form.leads ?? [])[index]?.position"
              :options="positionsOf((form.leads ?? [])[index]?.chamber)"
              size="small"
              style="width: 130px"
              allow-clear
              placeholder="按腔室"
              @update:value="
                (value: any) => setLeadField(index, 'position', value)
              "
            />
          </template>
          <template v-else-if="column.dataIndex === 'model'">
            <Input
              :value="(form.leads ?? [])[index]?.model"
              size="small"
              :maxlength="128"
              placeholder="选填"
              @update:value="
                (value: any) => setLeadField(index, 'model', value)
              "
            />
          </template>
          <template v-else-if="column.dataIndex === 'implantDate'">
            <DatePicker
              :value="(form.leads ?? [])[index]?.implantDate"
              value-format="YYYY-MM-DD"
              size="small"
              style="width: 130px"
              placeholder="默认同植入日期"
              @update:value="
                (value: any) => setLeadField(index, 'implantDate', value)
              "
            />
          </template>
          <template v-else-if="column.dataIndex === 'status'">
            <Select
              :value="(form.leads ?? [])[index]?.status"
              :options="leadStatusOptions"
              size="small"
              style="width: 110px"
              placeholder="在用"
              @update:value="
                (value: any) => setLeadField(index, 'status', value)
              "
            />
          </template>
          <template v-else-if="column.key === 'action'">
            <Button
              danger
              size="small"
              type="link"
              @click="index !== undefined && removeLead(index)"
            >
              移除
            </Button>
          </template>
        </template>
      </Table>
      <Button
        class="lead-add"
        size="small"
        type="dashed"
        block
        data-testid="lead-add"
        @click="addLead"
      >
        + 添加电极
      </Button>
    </div>
    <div v-else class="lead-block lead-hint">
      无导线起搏器不录电极（C-PAT-005）
    </div>
  </div>
</template>

<style scoped>
.form-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 0 16px;
}

.lead-block {
  margin-top: 8px;
}

.lead-title {
  margin-bottom: 8px;
  font-size: 14px;
  font-weight: 600;
}

.lead-error {
  margin-bottom: 8px;
  color: #ff4d4f;
}

.lead-add {
  margin-top: 8px;
}

.lead-hint {
  color: rgb(0 0 0 / 45%);
}
</style>
