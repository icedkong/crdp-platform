<script lang="ts" setup>
import type { ContactLogRow, ContactMethod, ContactResult } from '@crdp/api';
import type { UploadProps } from 'ant-design-vue';
import type { Dayjs } from 'dayjs';

import { computed, ref, watch } from 'vue';

import {
  CONTACT_METHOD_OPTIONS,
  CONTACT_RESULT_OPTIONS,
  contactFollowupHint,
} from '@crdp/api';
import {
  Alert,
  Button,
  DatePicker,
  Form,
  FormItem,
  message,
  Modal,
  Select,
  Textarea,
  Upload,
} from 'ant-design-vue';
import dayjs from 'dayjs';

import { createContactLog, updateContactLog } from '#/api/core/contact';
import { uploadFile } from '#/api/core/file';

/**
 * E15-T04 / A-FU-006、A-FU-025 · ContactLogModal（components.md §13「记录沟通」：
 * 520 宽；字段：方式 / 结果 / 约定下次联系时间 / 备注 / MP3 录音（可选）；结果 = 未接通时
 * 提示「系统次日自动生成跟进」，结果 = 告知已故时提示死亡终止口径）。
 *
 * - 录音（A-FU-025）：先传文件服务（真实类型 / 50 MB 由后端按内容校验，前端只做扩展名与
 *   大小预检），创建沟通记录时一并提交 audioFileIds；修改时录音清单不可改（删记录重记）。
 * - 提交成功提示：常规「已记录沟通」；告知已故「已记录；患者标记为死亡，未完成任务已终止」。
 */
const props = defineProps<{
  /** 患者 id（创建时必传；编辑按行 id 定位）。 */
  patientId?: string;
  /** 传入即编辑模式（PUT，带 baseVersion 乐观锁）。 */
  row?: ContactLogRow | null;
  /** 关联任务（随访工作台入口带；患者页签不传）。 */
  taskId?: null | string;
}>();

const emit = defineEmits<{ success: [row: ContactLogRow] }>();

const open = defineModel<boolean>('open', { default: false });

const method = ref<ContactMethod>('PHONE');
const result = ref<ContactResult>('REACHED');
const nextContactAt = ref<Dayjs | null>(null);
const note = ref('');
const submitting = ref(false);
/** 已上传待关联的录音（上传即校验；提交时一并挂到沟通记录）。 */
const audios = ref<Array<{ fileId: string; name: string }>>([]);
const uploading = ref(false);

const isEdit = computed(() => props.row !== null && props.row !== undefined);
const hint = computed(() => contactFollowupHint(result.value));

watch(
  open,
  (value) => {
    if (!value) return;
    if (props.row) {
      method.value = props.row.method as ContactMethod;
      result.value = props.row.result as ContactResult;
      nextContactAt.value = props.row.nextContactTime
        ? dayjs(props.row.nextContactTime)
        : null;
      note.value = props.row.note ?? '';
    } else {
      method.value = 'PHONE';
      result.value = 'REACHED';
      nextContactAt.value = null;
      note.value = '';
    }
    audios.value = [];
  },
  { immediate: true },
);

/** 录音预检（pages.md §6.1：前端预检大小与扩展名，真实类型以后端 Tika 为准）。 */
const beforeUpload: UploadProps['beforeUpload'] = (file) => {
  const ext = file.name.split('.').pop()?.toLowerCase() ?? '';
  if (!['amr', 'm4a', 'mp3', 'wav'].includes(ext)) {
    message.error('录音只接受 MP3 / M4A / WAV / AMR 格式');
    return Upload.LIST_IGNORE;
  }
  if (file.size > 50 * 1024 * 1024) {
    message.error('录音单个不能超过 50 MB');
    return Upload.LIST_IGNORE;
  }
  return true;
};

const customRequest: UploadProps['customRequest'] = async (options) => {
  const { file, onError, onSuccess } = options;
  uploading.value = true;
  try {
    const uploaded = await uploadFile(file as File);
    audios.value.push({ fileId: uploaded.fileId, name: uploaded.originalName });
    onSuccess?.(uploaded);
  } catch (error) {
    onError?.(error as Error);
  } finally {
    uploading.value = false;
  }
};

async function handleSubmit() {
  const patientId = props.patientId;
  if (!patientId && !isEdit.value) return;
  submitting.value = true;
  try {
    const nextContactTime = nextContactAt.value
      ? nextContactAt.value.format('YYYY-MM-DDTHH:mm:ss')
      : null;
    if (props.row) {
      const updated = await updateContactLog(props.row.id, {
        baseVersion: props.row.version,
        method: method.value,
        nextContactTime,
        note: note.value.trim() || null,
        result: result.value,
      });
      message.success('沟通记录已修改');
      emit('success', updated);
    } else {
      const created = await createContactLog(patientId, {
        audioFileIds: audios.value.map((item) => item.fileId),
        method: method.value,
        nextContactTime,
        note: note.value.trim() || null,
        result: result.value,
        taskId: props.taskId ?? null,
      });
      message.success(
        result.value === 'DECEASED'
          ? '已记录；患者标记为死亡，未完成任务已终止'
          : '已记录沟通',
      );
      // E16-T08 / A-ATT-017：带录音时后端同事务生成「通话记录」报告单，提示两处同一数据
      if (created.callReportId) {
        message.info('录音已同步为一份「通话记录」报告单（见报告单页签）');
      }
      emit('success', created.log);
    }
    open.value = false;
  } catch {
    // 错误提示由统一拦截器弹出（含 40001 完整字典提示与 40900 冲突文案）
  } finally {
    submitting.value = false;
  }
}
</script>

<template>
  <Modal
    :confirm-loading="submitting"
    :open="open"
    :title="isEdit ? '修改沟通记录' : '记录沟通'"
    :width="520"
    @cancel="open = false"
    @ok="handleSubmit"
  >
    <Form layout="vertical" @submit.prevent>
      <FormItem label="沟通方式" required>
        <Select v-model:value="method" :options="CONTACT_METHOD_OPTIONS" />
      </FormItem>
      <FormItem label="联系结果" required>
        <Select v-model:value="result" :options="CONTACT_RESULT_OPTIONS" />
        <Alert
          v-if="hint"
          class="hint-alert"
          :message="hint"
          :type="result === 'DECEASED' ? 'warning' : 'info'"
          show-icon
        />
      </FormItem>
      <FormItem label="约定下次联系时间">
        <DatePicker
          v-model:value="nextContactAt"
          placeholder="可选；到时提醒负责人"
          show-time
          style="width: 100%"
        />
      </FormItem>
      <FormItem label="情况说明">
        <Textarea
          v-model:value="note"
          :maxlength="2000"
          placeholder="如：患者诉近期胸闷，建议门诊复查（示例）"
          :rows="3"
        />
      </FormItem>
      <FormItem
        v-if="!isEdit"
        help="MP3 可在线播放；AMR 只保存与下载；单个不超过 50 MB"
      >
        <Upload
          accept=".mp3,.m4a,.wav,.amr"
          :before-upload="beforeUpload"
          :custom-request="customRequest"
          :disabled="uploading"
          :file-list="[]"
          multiple
        >
          <Button :loading="uploading" size="small" type="link">
            上传录音（可选）
          </Button>
        </Upload>
        <span v-for="item in audios" :key="item.fileId" class="audio-name">
          {{ item.name }}
        </span>
        <p class="audio-hint">
          保存后录音同时显示为一份「通话记录」报告单（A-ATT-017，见报告单页签），两处是同一文件。
        </p>
      </FormItem>
      <FormItem
        v-else-if="row && row.audios.length > 0"
        label="录音（不可修改）"
      >
        <span v-for="item in row.audios" :key="item.fileId" class="audio-name">
          {{ item.originalName }}
        </span>
      </FormItem>
    </Form>
  </Modal>
</template>

<style scoped>
.hint-alert {
  margin-top: 8px;
}

.audio-name {
  display: block;
  margin-top: 4px;
  font-size: 12px;
  color: #8c8c8c;
}

.audio-hint {
  margin: 4px 0 0;
  font-size: 12px;
  color: #8c8c8c;
}
</style>
