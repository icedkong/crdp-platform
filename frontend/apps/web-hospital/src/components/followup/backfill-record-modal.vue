<script lang="ts" setup>
import type { RecordTimepointSuggestionView } from '@crdp/api';
import type { Dayjs } from 'dayjs';

import { computed, ref, watch } from 'vue';

import { message, Modal } from 'ant-design-vue';
import dayjs from 'dayjs';

import {
  checkRecordDuplicate,
  createBackfillRecord,
  fetchTimepointSuggestion,
} from '#/api/core/task';
import {
  backfillDateError,
  deviceContextText,
  isBackfillDateDisabled,
  resolvePresetVisitDate,
  timepointSuggestionText,
} from '#/views/followup/patient/backfill-logic';

/**
 * E15-T07 / A-FU-014、C-FU-013 · 历史补录弹窗（患者详情「随访记录」页签入口）：
 * 填写实际随访日期（过去日期，今天及以后不可选）→ POST /patients/{id}/records
 * （source=BACKFILL）→ 新标签页打开随访表单（recordId 路由，pages.md §5）。
 * E21-T11 起创建前查重复记录提示（C-FU-024）：同一患者同一随访日期已有记录时先确认
 * 「确定继续？」再创建。E21-T14 / C-FU-014 起随日期联动建议随访时间点（如 25–45 天
 * 「术后1个月」，可改选）并匹配当时器械；有植入记录时禁选早于最早植入日期的日期
 * （「不早于植入日期、不晚于今天」，后端 40001 兜底）。
 *
 * E21-T14 / C-FU-015 连续补录：`continuous` 形态（提交成功后的「继续补录下一次」打开，
 * 标题与提示不同）；created 事件由宿主决定打开方式——患者详情新标签页、表单页同页切换
 * （不需反复打开表单）。
 */
const props = defineProps<{
  /** 连续补录形态（C-FU-015：表单页提交成功后的「继续补录下一次」，同页切换不新开标签页）。 */
  continuous?: boolean;
  /** 建议接口开关：连续补录 / 患者详情补录都建议；A / B 租户接口无器械与建议时行自动省略。 */
  open: boolean;
  patientId: string;
  /** 预填随访日期（E21-T17 转录：取报告单的报告时间；无效 / 当天及以后回落默认值）。 */
  presetVisitDate?: null | string;
  /** 转录模式（E21-T17 / C-SCAN-012：位置中没有随访记录 → 新建补录记录并加入位置）。 */
  transcribe?: boolean;
}>();

const emit = defineEmits<{
  (event: 'created', recordId: string): void;
  (event: 'update:open', value: boolean): void;
}>();

const visitDate = ref<Dayjs | null>(null);
const submitting = ref(false);
/** E21-T14 / C-FU-014：建议随访时间点（随日期联动；接口失败不拦截创建）。 */
const suggestion = ref<null | RecordTimepointSuggestionView>(null);

watch(
  () => props.open,
  (open) => {
    if (!open) return;
    // 转录模式优先预填报告单的报告时间（E21-T17；非法 / 当天及以后回落默认补录日期）
    visitDate.value = dayjs(
      resolvePresetVisitDate(
        props.transcribe ? (props.presetVisitDate ?? null) : null,
      ),
    );
    suggestion.value = null;
  },
);

/** 随日期取建议（C-FU-014「先填随访日期，系统建议随访时间点并匹配器械」）；无效日期清空。 */
watch(
  () => visitDate.value?.format('YYYY-MM-DD') ?? null,
  (date) => {
    if (!date || backfillDateError(date)) {
      suggestion.value = null;
      return;
    }
    fetchTimepointSuggestion(props.patientId, date)
      .then((view) => {
        suggestion.value = view;
      })
      .catch(() => {
        // 建议接口失败不拦截补录（时间点可手选，后端创建兜底）
        suggestion.value = null;
      });
  },
);

/** 最早植入日期（建议接口下发；补录日期下界，A / B 与未录植入为 null）。 */
const earliestImplantDate = computed(
  () => suggestion.value?.earliestImplantDate ?? null,
);

const dateError = computed(() =>
  visitDate.value
    ? backfillDateError(
        visitDate.value.format('YYYY-MM-DD'),
        new Date(),
        earliestImplantDate.value,
      )
    : '',
);

/** 禁选日期：今天及以后、早于最早植入日期（下界随建议接口返回填充）。 */
function disabledDate(date: Dayjs) {
  return isBackfillDateDisabled(date, new Date(), earliestImplantDate.value);
}

/** C-FU-014 建议行与器械行（无器械 / 无建议整行省略；连续补录照常显示）。 */
const suggestionText = computed(() =>
  timepointSuggestionText(suggestion.value),
);
const deviceText = computed(() => deviceContextText(suggestion.value));

async function handleOk() {
  if (!visitDate.value || dateError.value) {
    message.warning(dateError.value || '请填写实际随访日期');
    return;
  }
  // C-FU-024 重复记录提示：同一患者同一随访日期已有记录（含草稿）先确认
  const date = visitDate.value.format('YYYY-MM-DD');
  try {
    const view = await checkRecordDuplicate(props.patientId, date);
    if (view.duplicate) {
      Modal.confirm({
        title: '重复记录提示',
        content: `该患者在 ${date} 已有一条随访记录，确定继续？`,
        okText: '继续创建',
        cancelText: '取消',
        onOk: () => createRecord(date),
      });
      return;
    }
  } catch {
    // 提示接口失败不拦截创建（后端保存复算与提示各自兜底）
  }
  await createRecord(date);
}

async function createRecord(date: string) {
  submitting.value = true;
  try {
    // 创建即种入建议时间点（后端按同口径建议；表单里可改选，C-FU-014）
    const created = await createBackfillRecord(props.patientId, {
      source: 'BACKFILL',
      visitDate: date,
    });
    message.success(
      props.continuous
        ? '已创建下一条补录记录，正在切换'
        : '已创建补录记录，正在打开随访表单',
    );
    emit('created', created.recordId);
    emit('update:open', false);
  } catch (error) {
    message.error('创建补录记录失败，请稍后重试');
    console.error(error);
  } finally {
    submitting.value = false;
  }
}

function handleCancel() {
  emit('update:open', false);
}
</script>

<template>
  <a-modal
    :confirm-loading="submitting"
    :open="open"
    ok-text="创建并录入"
    :title="
      continuous
        ? '继续补录下一次（C-FU-015）'
        : transcribe
          ? '新建补录记录（转录）'
          : '历史补录（A-FU-014）'
    "
    :width="480"
    @cancel="handleCancel"
    @ok="handleOk"
  >
    <p class="hint">
      {{
        continuous
          ? '保留患者与器械、测量值清空：填下一份资料的实际随访日期，创建后在本页切换到新记录继续录入（不新开标签页）。'
          : transcribe
            ? '位置中没有随访记录：先新建一条补录记录，创建后在新标签页分屏对照扫描件录入；提交后记录自动加入报告单位置，报告单置为「已电子化」。'
            : '为过去日期补录随访记录：不需要任务、不受窗口限制，记录标记为补录。创建后在新标签页打开随访表单填写并提交。'
      }}
    </p>
    <a-form-item
      :validate-status="dateError ? 'error' : ''"
      :help="dateError"
      label="实际随访日期"
      required
    >
      <a-date-picker
        v-model:value="visitDate"
        :disabled-date="disabledDate"
        placeholder="选择过去的随访日期"
        style="width: 100%"
      />
    </a-form-item>
    <!-- C-FU-014 建议随访时间点与当时器械（无建议 / 无器械整行省略；Tab 逐项录入、Enter 确认） -->
    <p
      v-if="suggestionText"
      class="suggestion"
      data-testid="backfill-suggestion"
    >
      {{ suggestionText }}
    </p>
    <p v-if="deviceText" class="hint">{{ deviceText }}</p>
  </a-modal>
</template>

<style scoped>
.hint {
  margin: 0 0 12px;
  font-size: 12px;
  color: #8c8c8c;
}

.suggestion {
  padding: 8px 12px;
  margin: 0 0 12px;
  font-size: 12px;
  color: #3a75ff;
  background: rgb(58 117 255 / 6%);
  border-radius: 6px;
}
</style>
