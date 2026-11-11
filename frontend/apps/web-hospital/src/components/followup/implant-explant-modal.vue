<script lang="ts" setup>
import type { ImplantExplantForm, ImplantRecordView } from '@crdp/api';

import { reactive, ref, watch } from 'vue';

import {
  buildExplantPayload,
  emptyExplantForm,
  leadSummary,
  validateExplantForm,
} from '@crdp/api';
import {
  DatePicker,
  FormItem,
  Input,
  message,
  Modal,
  Select,
  Table,
} from 'ant-design-vue';

import { explantPatientImplant } from '#/api/core/implant';

/**
 * E21-T02 · 取出弹窗（pages.md §4.4 植入记录区块操作；api implant.md §1 /explant）。
 * 在用（当前）器械取出：结束日期 = 取出日期；在用电极逐根显式标「废弃留置 / 已拔除」
 * （取出不产生沿用电极）；取出后无在用器械，再植入走登记 / 新建。
 */
const props = defineProps<{
  /** 打开状态。 */
  open: boolean;
  /** 目标（当前器械）植入记录。 */
  record: ImplantRecordView | null;
}>();

const emit = defineEmits<{
  (event: 'saved', record: ImplantRecordView): void;
  (event: 'update:open', value: boolean): void;
}>();

const form = reactive<ImplantExplantForm>(
  emptyExplantForm({ leads: [] } as unknown as ImplantRecordView),
);
const submitting = ref(false);
const errors = ref<Record<string, string>>({});

watch(
  () => props.open,
  (open) => {
    if (!open || !props.record) return;
    Object.assign(form, emptyExplantForm(props.record));
    errors.value = {};
  },
  { immediate: true },
);

const leadActionOptions = [
  { label: '废弃留置', value: 'ABANDON' },
  { label: '已拔除', value: 'EXTRACT' },
];

const leadColumns = [
  { title: '电极', key: 'lead' },
  { title: '植入日期', key: 'implantDate', width: 110 },
  { title: '处置', key: 'action', width: 170 },
];

function fieldError(field: string) {
  return errors.value[field];
}

function leadOf(index: number | undefined) {
  if (index === undefined || !props.record) return undefined;
  const action = form.leadActions[index];
  return props.record.leads.find((lead) => lead.id === action?.leadId);
}

function setLeadAction(index: number, value: unknown) {
  const actions = [...form.leadActions];
  const current = actions.at(index);
  if (!current) return;
  actions[index] = { ...current, action: value as string };
  form.leadActions = actions;
}

async function handleSave() {
  if (!props.record) return;
  errors.value = validateExplantForm(form, props.record);
  if (Object.keys(errors.value).length > 0) {
    message.warning('请填写取出日期并处置每根在用电极');
    return;
  }
  submitting.value = true;
  try {
    const saved = await explantPatientImplant(
      props.record.id,
      buildExplantPayload(form, props.record),
    );
    message.success('已标记取出，该患者当前无在用器械');
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
    :title="`取出器械（第 ${record?.implantNo} 次 · ${record?.deviceTypeLabel || record?.deviceType}）`"
    :width="640"
    :confirm-loading="submitting"
    ok-text="确认取出"
    cancel-text="取消"
    data-testid="implant-explant-modal"
    @ok="handleSave"
    @cancel="emit('update:open', false)"
  >
    <div class="explant-form">
      <div class="form-grid">
        <FormItem
          label="取出日期"
          required
          :validate-status="fieldError('date') ? 'error' : undefined"
          :help="fieldError('date')"
        >
          <DatePicker
            v-model:value="form.date"
            value-format="YYYY-MM-DD"
            style="width: 100%"
            placeholder="不能早于植入日期"
          />
        </FormItem>
        <FormItem label="取出原因">
          <Input
            v-model:value="form.reason"
            :maxlength="255"
            placeholder="选填，缺省记「器械取出」"
          />
        </FormItem>
      </div>
      <div v-if="form.leadActions.length > 0" class="lead-block">
        <div class="lead-title">在用电极处置（取出不产生沿用电极）</div>
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
                style="width: 150px"
                placeholder="请选择处置"
                @change="(value: any) => setLeadAction(index!, value)"
              />
            </template>
          </template>
        </Table>
      </div>
      <div class="hint">
        取出后该患者无在用器械；再次植入请在「新建植入记录」中登记。
      </div>
    </div>
  </Modal>
</template>

<style scoped>
.form-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
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

.hint {
  margin-top: 12px;
  font-size: 12px;
  color: rgb(0 0 0 / 45%);
}
</style>
