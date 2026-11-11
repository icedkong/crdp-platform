<script lang="ts" setup>
import type {
  ImplantLeadActionForm,
  ImplantOptions,
  ImplantRecordView,
  ImplantReplaceForm,
} from '@crdp/api';

import { computed, reactive, ref, watch } from 'vue';

import {
  buildReplacePayload,
  emptyReplaceForm,
  IMPLANT_LEAD_ACTION_KEEP,
  IMPLANT_REPLACE_TYPES,
  IMPLANT_TYPE_LEAD_REVISION,
  IMPLANT_TYPE_OTHER,
  leadPositionOptions,
  leadSummary,
  validateReplaceForm,
} from '@crdp/api';
import {
  Button,
  DatePicker,
  FormItem,
  Input,
  message,
  Modal,
  Select,
  Table,
} from 'ant-design-vue';

import { fetchImplantOptions, replacePatientImplant } from '#/api/core/implant';

/**
 * E21-T02 / C-PAT-006 · 更换 / 升级 / 电极修正弹窗（pages.md §4.4「操作：更换 / 升级、
 * 电极修正」；C 规格 §4 器械更换流程）。目标 = 当前（在用）器械：
 * - 类型四选一：更换（电池耗竭）/ 升级 / 电极修正 / 其他（须填说明）——[电极修正] 入口
 *   预选 LEAD_REVISION 并锁定器械字段（脉冲发生器不变）；
 * - 旧电极逐根显式处置：沿用（保留原植入日期）/ 废弃留置 / 已拔除；可另新植电极；
 * - 保存后旧器械自动标记「已更换」（结束日期 = 新植入日期），由后端在同一事务完成。
 */
const props = defineProps<{
  /** 预选植入类型（[电极修正] 入口传 LEAD_REVISION）。 */
  initialType?: string;
  /** 打开状态。 */
  open: boolean;
  /** 目标（当前器械）植入记录。 */
  record: ImplantRecordView | null;
}>();

const emit = defineEmits<{
  (event: 'saved', record: ImplantRecordView): void;
  (event: 'update:open', value: boolean): void;
}>();

const form = reactive<ImplantReplaceForm>(emptyReplaceForm(dummy()));
const options = ref<ImplantOptions | null>(null);
const submitting = ref(false);

function dummy(): ImplantRecordView {
  // 占位（props.record 未就绪时维持 reactive 结构；保存前必经 open 重置）
  return {
    id: '',
    patientId: '',
    current: true,
    implantDate: '',
    implantType: '',
    deviceType: '',
    status: '',
    leads: [],
    implantNo: 0,
    version: 0,
  } as ImplantRecordView;
}

watch(
  () => props.open,
  (open) => {
    if (!open || !props.record) return;
    Object.assign(form, emptyReplaceForm(props.record, props.initialType));
    if (!options.value) {
      fetchImplantOptions()
        .then((data) => {
          options.value = data;
        })
        .catch(() => message.error('植入选项加载失败，请重试'));
    }
  },
  { immediate: true },
);

const isLeadRevision = computed(() => form.type === IMPLANT_TYPE_LEAD_REVISION);
const requiresNote = computed(() => form.type === IMPLANT_TYPE_OTHER);

const typeSelectOptions = computed(() =>
  (options.value?.implantTypes ?? [])
    .filter((item) => IMPLANT_REPLACE_TYPES.includes(item.code as never))
    .map((item) => ({ label: item.label, value: item.code })),
);
const deviceTypeOptions = computed(() =>
  (options.value?.deviceTypes ?? []).map((item) => ({
    label: item.label,
    value: item.code,
  })),
);
const brandOptions = computed(() =>
  (options.value?.brands ?? []).map((item) => ({
    label: item.label,
    value: item.code,
  })),
);
const pacingModeOptions = computed(() =>
  (options.value?.pacingModes ?? []).map((item) => ({
    label: item.label,
    value: item.code,
  })),
);
const indicationOptions = computed(() =>
  (options.value?.indications ?? []).map((item) => ({
    label: item.label,
    value: item.code,
  })),
);
const chamberOptions = computed(() =>
  (options.value?.leadPositions ?? []).map((group) => ({
    label: group.chamber,
    value: group.chamber,
  })),
);

/** 电极处置三选一（系统枚举；沿用保留原植入日期，废弃留置 / 已拔除保留在旧记录下）。 */
const leadActionOptions = [
  { label: '沿用（保留原植入日期）', value: IMPLANT_LEAD_ACTION_KEEP },
  { label: '废弃留置', value: 'ABANDON' },
  { label: '已拔除', value: 'EXTRACT' },
];

const leadColumns = [
  { title: '电极', key: 'lead', width: 200 },
  { title: '植入日期', key: 'implantDate', width: 110 },
  { title: '处置', key: 'action', width: 200 },
];

const newLeadColumns = [
  { title: '腔室', key: 'chamber', width: 110 },
  { title: '植入部位', key: 'position', width: 150 },
  { title: '型号', key: 'model' },
  { title: '植入日期', key: 'implantDate', width: 150 },
  { title: '操作', key: 'action', width: 70 },
];

const errors = ref<Record<string, string>>({});

function fieldError(field: string) {
  return errors.value[field];
}

function setLeadAction(index: number, value: unknown) {
  const actions = [...form.leadActions];
  const current = actions.at(index);
  if (!current) return;
  actions[index] = { ...current, action: value as string };
  form.leadActions = actions;
}

function addNewLead() {
  form.newLeads = [
    ...form.newLeads,
    {
      chamber: undefined,
      position: null,
      model: null,
      implantDate: null,
      status: null,
    },
  ];
}

function removeNewLead(index: number) {
  form.newLeads = form.newLeads.filter((_, i) => i !== index);
}

function setNewLeadField(
  index: number,
  field: 'chamber' | 'implantDate' | 'model' | 'position',
  value: unknown,
) {
  const leads = [...form.newLeads];
  const current = leads.at(index);
  if (!current) return;
  leads[index] = { ...current, [field]: value } as typeof current;
  form.newLeads = leads;
}

function positionsOf(chamber: null | string | undefined) {
  return leadPositionOptions(options.value, chamber).map((item) => ({
    label: item.label,
    value: item.code,
  }));
}

/** 处置行 → 对应电极视图（目标记录下的电极）。 */
function leadOf(index: number | undefined) {
  if (index === undefined || !props.record) return undefined;
  const action: ImplantLeadActionForm | undefined = form.leadActions[index];
  return props.record.leads.find((lead) => lead.id === action?.leadId);
}

async function handleSave() {
  if (!props.record) return;
  errors.value = validateReplaceForm(form, options.value);
  if (Object.keys(errors.value).length > 0) {
    message.warning('请先补全必填项并处置每根在用电极');
    return;
  }
  submitting.value = true;
  try {
    const saved = await replacePatientImplant(
      props.record.id,
      buildReplacePayload(form),
    );
    message.success('已保存，原器械自动标记为已更换');
    emit('saved', saved);
    emit('update:open', false);
  } catch (error) {
    const body = error as { code?: number; message?: string };
    if (body?.code === 40_900) {
      message.error('该植入记录已被他人处理，请刷新后重试');
    } else {
      message.error(body?.message || '保存失败，请稍后重试');
    }
  } finally {
    submitting.value = false;
  }
}
</script>

<template>
  <Modal
    :open="open && !!record"
    :title="
      isLeadRevision
        ? `电极修正（第 ${record?.implantNo} 次 · ${record?.deviceTypeLabel || record?.deviceType}）`
        : `更换 / 升级（第 ${record?.implantNo} 次 · ${record?.deviceTypeLabel || record?.deviceType}）`
    "
    :width="860"
    :confirm-loading="submitting"
    ok-text="保存"
    cancel-text="取消"
    data-testid="implant-replace-modal"
    @ok="handleSave"
    @cancel="emit('update:open', false)"
  >
    <div class="replace-form">
      <div class="form-grid">
        <FormItem
          label="植入类型"
          required
          :validate-status="fieldError('type') ? 'error' : undefined"
          :help="fieldError('type')"
        >
          <Select
            v-model:value="form.type"
            :options="typeSelectOptions"
            placeholder="更换（电池耗竭）/ 升级 / 电极修正 / 其他"
          />
        </FormItem>
        <FormItem
          label="手术日期"
          required
          :validate-status="fieldError('date') ? 'error' : undefined"
          :help="fieldError('date')"
        >
          <DatePicker
            v-model:value="form.date"
            value-format="YYYY-MM-DD"
            style="width: 100%"
            placeholder="不能早于原植入日期"
          />
        </FormItem>
        <FormItem
          v-if="requiresNote"
          label="植入说明"
          required
          :validate-status="fieldError('note') ? 'error' : undefined"
          :help="fieldError('note')"
        >
          <Input
            v-model:value="form.note"
            :maxlength="255"
            placeholder="类型选「其他」须填写说明"
          />
        </FormItem>
        <FormItem
          label="起搏器类型"
          required
          :validate-status="fieldError('deviceType') ? 'error' : undefined"
          :help="fieldError('deviceType')"
        >
          <Select
            v-model:value="form.device.deviceType"
            :options="deviceTypeOptions"
            :disabled="isLeadRevision"
            placeholder="请选择"
          />
        </FormItem>
        <FormItem label="品牌">
          <Select
            v-model:value="form.device.brand"
            :options="brandOptions"
            :disabled="isLeadRevision"
            allow-clear
            placeholder="请选择"
          />
        </FormItem>
        <FormItem label="型号">
          <Input
            v-model:value="form.device.model"
            :disabled="isLeadRevision"
            :maxlength="128"
            placeholder="如 示例型号 S-02"
          />
        </FormItem>
        <FormItem label="设备序列号">
          <Input
            v-model:value="form.device.serialNo"
            :disabled="isLeadRevision"
            :maxlength="64"
            placeholder="选填（Q16）"
          />
        </FormItem>
        <FormItem label="起搏模式">
          <Select
            v-model:value="form.device.pacingMode"
            :options="pacingModeOptions"
            allow-clear
            placeholder="统一字典（C-FLD-003）"
          />
        </FormItem>
        <FormItem label="植入医院">
          <Input
            v-model:value="form.device.hospital"
            :maxlength="128"
            placeholder="默认本院"
          />
        </FormItem>
        <FormItem label="植入医生">
          <Input
            v-model:value="form.device.doctor"
            :maxlength="64"
            placeholder="本院医生或外院手填"
          />
        </FormItem>
        <FormItem label="植入指征">
          <Select
            v-model:value="form.device.indication"
            :options="indicationOptions"
            allow-clear
            placeholder="请选择"
          />
        </FormItem>
        <FormItem
          v-if="form.device.indication === 'OTHER'"
          label="指征补充说明"
          required
          :validate-status="fieldError('indicationNote') ? 'error' : undefined"
          :help="fieldError('indicationNote')"
        >
          <Input
            v-model:value="form.device.indicationNote"
            :maxlength="255"
            placeholder="选「其他」须填写说明（Q16）"
          />
        </FormItem>
      </div>

      <div v-if="isLeadRevision" class="hint">
        电极修正不改变脉冲发生器：类型 / 品牌 / 型号 /
        序列号沿用原记录，只调整电极。
      </div>

      <div
        v-if="form.leadActions.length > 0"
        class="lead-block"
        data-testid="replace-lead-actions"
      >
        <div class="lead-title">原电极处置（逐根选择；沿用保留原植入日期）</div>
        <div v-if="fieldError('leadActions')" class="lead-error">
          {{ fieldError('leadActions') }}
        </div>
        <Table
          :columns="leadColumns"
          :data-source="form.leadActions"
          :pagination="false"
          size="small"
        >
          <template #bodyCell="{ column, index }">
            <template v-if="column.key === 'lead'">
              <span>{{ leadSummary(leadOf(index)) }}</span>
            </template>
            <template v-else-if="column.key === 'implantDate'">
              <span>{{ leadOf(index)?.implantDate || '—' }}</span>
            </template>
            <template v-else-if="column.key === 'action'">
              <Select
                :value="form.leadActions[index]?.action ?? undefined"
                :options="leadActionOptions"
                size="small"
                style="width: 190px"
                placeholder="请选择处置"
                @change="(value: any) => setLeadAction(index!, value)"
              />
            </template>
          </template>
        </Table>
      </div>

      <div class="lead-block">
        <div class="lead-title">新植电极（植入日期缺省 = 手术日期）</div>
        <Table
          :columns="newLeadColumns"
          :data-source="form.newLeads"
          :pagination="false"
          size="small"
        >
          <template #bodyCell="{ column, index }">
            <template v-if="column.key === 'chamber'">
              <Select
                :value="form.newLeads[index]?.chamber"
                :options="chamberOptions"
                size="small"
                style="width: 90px"
                placeholder="腔室"
                @change="
                  (value: any) => setNewLeadField(index!, 'chamber', value)
                "
              />
            </template>
            <template v-else-if="column.key === 'position'">
              <Select
                :value="form.newLeads[index]?.position"
                :options="positionsOf(form.newLeads[index]?.chamber)"
                size="small"
                style="width: 130px"
                allow-clear
                placeholder="按腔室"
                @update:value="
                  (value: any) => setNewLeadField(index!, 'position', value)
                "
              />
            </template>
            <template v-else-if="column.key === 'model'">
              <Input
                :value="form.newLeads[index]?.model"
                size="small"
                :maxlength="128"
                placeholder="选填"
                @update:value="
                  (value: any) => setNewLeadField(index!, 'model', value)
                "
              />
            </template>
            <template v-else-if="column.key === 'implantDate'">
              <DatePicker
                :value="form.newLeads[index]?.implantDate"
                value-format="YYYY-MM-DD"
                size="small"
                style="width: 130px"
                placeholder="默认手术日期"
                @update:value="
                  (value: any) => setNewLeadField(index!, 'implantDate', value)
                "
              />
            </template>
            <template v-else-if="column.key === 'action'">
              <Button
                danger
                size="small"
                type="link"
                @click="index !== undefined && removeNewLead(index)"
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
          data-testid="replace-new-lead-add"
          @click="addNewLead"
        >
          + 添加电极
        </Button>
      </div>

      <div class="hint">
        保存后原器械自动标记「已更换」（结束日期 = 手术日期）；废弃留置 /
        已拔除的电极保留在原记录下。
      </div>
    </div>
  </Modal>
</template>

<style scoped>
.replace-form {
  display: flex;
  flex-direction: column;
}

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

.hint {
  margin-top: 12px;
  font-size: 12px;
  color: rgb(0 0 0 / 45%);
}
</style>
