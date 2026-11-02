<script lang="ts" setup>
import type { NotifySettings } from '@crdp/api';

import { onMounted, reactive, ref } from 'vue';

import {
  Alert,
  Button,
  Form,
  FormItem,
  InputNumber,
  message,
  Spin,
  Switch,
  TimePicker,
} from 'ant-design-vue';

import { fetchNotifySettings, updateNotifySettings } from '#/api/core/notify';

import {
  SMS_DAILY_LIMIT_MAX,
  SMS_DAILY_LIMIT_MIN,
  toSettingsUpdate,
  validateSettingsModel,
} from './send-settings';

/**
 * E09-T07 / A-NTF-005、A-NTF-007 · 通知设置「发送设置」页签：
 * 免打扰时段（默认 21:00–08:00，医院已确认）、每人每日短信上限（默认 3，建议值）、
 * 逾期类短信是否允许个人关闭（A-NTF-007，默认不允许保证逾期触达）。
 * 免打扰与上限的发送侧执行随 E09-T05 汇总作业；保存写 NOTICE_SETTINGS_UPDATE 审计。
 */
const emit = defineEmits<{ saved: [settings: NotifySettings] }>();

const loading = ref(false);
const loadError = ref('');
const saving = ref(false);
const errors = ref<string[]>([]);

const form = reactive({
  dndEnd: '08:00',
  dndStart: '21:00',
  overdueSmsOffAllowed: false,
  smsDailyLimit: 3 as null | number,
});

async function load() {
  loading.value = true;
  loadError.value = '';
  try {
    const view = await fetchNotifySettings();
    form.dndStart = view.dndStart;
    form.dndEnd = view.dndEnd;
    form.smsDailyLimit = view.smsDailyLimit;
    form.overdueSmsOffAllowed = view.overdueSmsOffAllowed;
  } catch (error) {
    loadError.value = error instanceof Error ? error.message : '加载失败';
  } finally {
    loading.value = false;
  }
}

async function handleSave() {
  errors.value = validateSettingsModel(form);
  if (errors.value.length > 0) {
    return;
  }
  saving.value = true;
  try {
    const saved = await updateNotifySettings(toSettingsUpdate(form));
    form.dndStart = saved.dndStart;
    form.dndEnd = saved.dndEnd;
    form.smsDailyLimit = saved.smsDailyLimit;
    form.overdueSmsOffAllowed = saved.overdueSmsOffAllowed;
    emit('saved', saved);
    message.success('已保存发送设置，并记录操作日志');
  } finally {
    saving.value = false;
  }
}

onMounted(load);
</script>

<template>
  <Spin :spinning="loading">
    <Alert
      v-if="loadError"
      class="mb-4"
      :message="loadError"
      show-icon
      type="error"
    />
    <Form
      v-else
      :label-col="{ span: 8 }"
      :wrapper-col="{ span: 14 }"
      :style="{ maxWidth: '560px' }"
      @keyup.enter.prevent
    >
      <FormItem label="免打扰时段">
        <div class="dnd-row">
          <TimePicker
            v-model:value="form.dndStart"
            format="HH:mm"
            :allow-empty="false"
            placeholder="21:00"
            style="width: 110px"
            value-format="HH:mm"
          />
          <span>至次日</span>
          <TimePicker
            v-model:value="form.dndEnd"
            format="HH:mm"
            :allow-empty="false"
            placeholder="08:00"
            style="width: 110px"
            value-format="HH:mm"
          />
        </div>
        <p class="hint">
          时段内产生的短信推迟到时段结束后随汇总发送（A-NTF-005）
        </p>
      </FormItem>
      <FormItem label="每人每日短信上限">
        <InputNumber
          v-model:value="form.smsDailyLimit"
          :min="SMS_DAILY_LIMIT_MIN"
          :max="SMS_DAILY_LIMIT_MAX"
          :precision="0"
          style="width: 160px"
        />
        <p class="hint">
          超出上限的短信并入次日汇总（建议值 3，可按本院情况调整）
        </p>
      </FormItem>
      <FormItem label="逾期短信可否个人关闭">
        <Switch
          v-model:checked="form.overdueSmsOffAllowed"
          checked-children="允许"
          un-checked-children="不允许"
        />
        <p class="hint">
          「已逾期」是必须触达的提醒；不允许时个人无法在通知偏好中关闭逾期类短信
        </p>
      </FormItem>
      <Alert
        v-if="errors.length > 0"
        class="mb-3"
        :message="errors.join('；')"
        show-icon
        type="error"
      />
      <FormItem :wrapper-col="{ offset: 8, span: 14 }">
        <Button
          type="primary"
          :loading="saving"
          data-testid="notify-settings-save"
          @click="handleSave"
        >
          保存
        </Button>
      </FormItem>
    </Form>
  </Spin>
</template>

<style scoped>
.hint {
  margin: 4px 0 0;
  font-size: 12px;
  color: gray;
}

.dnd-row {
  display: flex;
  gap: 8px;
  align-items: center;
}
</style>
