<script lang="ts" setup>
import type { NotifyRule, NotifySmsTemplate } from '@crdp/api';

import { onMounted, reactive, ref } from 'vue';

import { Page } from '@vben/common-ui';

import {
  Alert,
  Button,
  CheckboxGroup,
  Checkbox as CheckboxOption,
  Form,
  FormItem,
  InputNumber,
  message,
  Modal,
  Result,
  Select,
  SelectOption,
  Spin,
  Switch,
  Table,
  Tag,
  TimePicker,
} from 'ant-design-vue';

import {
  fetchNotifyRules,
  fetchNotifySmsTemplates,
  updateNotifyRule,
} from '#/api/core/notify';
import { useBreakpoint } from '#/reference/use-breakpoint';

import {
  CHANNEL_OPTIONS,
  channelText,
  editModelOf,
  RECIPIENT_OPTIONS,
  recipientText,
  supportsAdvanceDays,
  supportsSendTime,
  templateOptions,
  timingText,
  validateRuleModel,
} from './rules';

defineOptions({ name: 'FollowupNotice' });

/**
 * E09-T04 / A-NTF-001、C-NTF-001 · 通知设置（/system/notice，pages.md §10）：
 * 逐事件配置开关、提前天数、发送时间、渠道、接收人规则；短信模板只能选平台
 * 审核过的（PLT-CFG-006）。A / C 的事件差异（C 首期无窗口预告）由本院停用对应
 * 事件实现，不写租户分支。保存写 NOTICE_RULE_UPDATE 审计。仅桌面 / 平板。
 */
const { isMobile } = useBreakpoint();

const loading = ref(false);
const loadError = ref('');
const rules = ref<NotifyRule[]>([]);
const templates = ref<NotifySmsTemplate[]>([]);

const saving = ref(false);
const editing = ref(false);
const editingRule = ref<NotifyRule | null>(null);
const editErrors = ref<string[]>([]);
const formRef = ref();

const form = reactive({
  advanceDays: null as null | number,
  channels: [] as string[],
  enabled: true,
  recipients: [] as string[],
  sendTime: '',
  smsTemplateCode: '',
});

const columns = [
  { title: '事件', dataIndex: 'label', key: 'label' },
  { title: '状态', dataIndex: 'enabled', key: 'enabled', width: 80 },
  { title: '触发时机', key: 'timing', width: 170 },
  { title: '渠道', key: 'channels', width: 120 },
  { title: '接收人', key: 'recipients' },
  {
    title: '短信模板',
    dataIndex: 'smsTemplateCode',
    key: 'smsTemplateCode',
    width: 170,
  },
  { title: '操作', key: 'action', width: 90 },
];

async function load() {
  loading.value = true;
  loadError.value = '';
  try {
    const [ruleList, templateList] = await Promise.all([
      fetchNotifyRules(),
      fetchNotifySmsTemplates(),
    ]);
    rules.value = ruleList;
    templates.value = templateList;
  } catch (error) {
    loadError.value = error instanceof Error ? error.message : '加载失败';
  } finally {
    loading.value = false;
  }
}

function openEdit(rule: NotifyRule) {
  editingRule.value = rule;
  const model = editModelOf(rule);
  form.enabled = model.enabled;
  form.advanceDays = model.advanceDays;
  form.sendTime = model.sendTime;
  form.channels = [...model.channels];
  form.recipients = [...model.recipients];
  form.smsTemplateCode = model.smsTemplateCode;
  editErrors.value = [];
  editing.value = true;
}

async function handleSave() {
  const rule = editingRule.value;
  if (!rule) {
    return;
  }
  const model = {
    advanceDays: form.advanceDays,
    channels: form.channels as NotifyRule['channels'],
    enabled: form.enabled,
    recipients: form.recipients as NotifyRule['recipients'],
    sendTime: form.sendTime,
    smsTemplateCode: form.smsTemplateCode,
  };
  editErrors.value = validateRuleModel(model, rule);
  if (editErrors.value.length > 0) {
    return;
  }
  saving.value = true;
  try {
    const saved = await updateNotifyRule(rule.eventType, {
      advanceDays: model.advanceDays,
      channels: model.channels,
      enabled: model.enabled,
      recipients: model.recipients,
      sendTime: model.sendTime.trim() === '' ? null : model.sendTime.trim(),
      smsTemplateCode:
        model.smsTemplateCode.trim() === ''
          ? null
          : model.smsTemplateCode.trim(),
    });
    rules.value = rules.value.map((item) =>
      item.eventType === saved.eventType ? saved : item,
    );
    editing.value = false;
    message.success(`已保存「${saved.label}」通知规则，并记录操作日志`);
  } finally {
    saving.value = false;
  }
}

function templateLabel(code: null | string | undefined) {
  if (!code) {
    return '—';
  }
  const found = templates.value.find((template) => template.code === code);
  return found ? found.name : code;
}

onMounted(load);
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
    title="通知设置"
    description="逐个随访事件配置开关、提前天数、发送时间、通知渠道与接收人规则；短信模板只能选择平台审核过的模板。修改后记录操作日志。"
  >
    <Spin :spinning="loading">
      <Alert
        v-if="loadError"
        class="mb-4"
        :message="loadError"
        show-icon
        type="error"
      />
      <Table
        v-else
        :columns="columns"
        :data-source="rules"
        :pagination="false"
        row-key="eventType"
        size="middle"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'enabled'">
            <Tag v-if="record.enabled" color="success">启用</Tag>
            <Tag v-else>停用</Tag>
          </template>
          <template v-else-if="column.key === 'timing'">
            {{ timingText(record) }}
          </template>
          <template v-else-if="column.key === 'channels'">
            {{ channelText(record.channels) }}
          </template>
          <template v-else-if="column.key === 'recipients'">
            {{ recipientText(record.recipients) }}
          </template>
          <template v-else-if="column.key === 'smsTemplateCode'">
            {{ templateLabel(record.smsTemplateCode) }}
          </template>
          <template v-else-if="column.key === 'action'">
            <Button size="small" type="link" @click="openEdit(record)">
              设置
            </Button>
          </template>
        </template>
      </Table>
    </Spin>

    <Modal
      v-model:open="editing"
      :confirm-loading="saving"
      :title="editingRule ? `通知规则：${editingRule.label}` : '通知规则'"
      :width="560"
      :mask-closable="false"
      ok-text="保存"
      cancel-text="取消"
      @ok="handleSave"
    >
      <Alert
        v-if="editErrors.length > 0"
        class="mb-4"
        :message="editErrors.join('；')"
        show-icon
        type="error"
      />
      <Form
        ref="formRef"
        :label-col="{ span: 6 }"
        :model="form"
        @keyup.enter.prevent
      >
        <FormItem label="事件开关">
          <Switch
            v-model:checked="form.enabled"
            checked-children="启用"
            un-checked-children="停用"
          />
          <p class="hint">停用后该事件不产生任何通知</p>
        </FormItem>
        <FormItem
          v-if="editingRule && supportsAdvanceDays(editingRule)"
          label="提前天数"
        >
          <InputNumber
            v-model:value="form.advanceDays"
            :min="0"
            :max="365"
            :precision="0"
            placeholder="如 7"
            style="width: 160px"
          />
          <p class="hint">任务开启前 / 窗口结束前的提醒天数</p>
        </FormItem>
        <FormItem
          v-if="editingRule && supportsSendTime(editingRule)"
          label="发送时间"
        >
          <TimePicker
            v-model:value="form.sendTime"
            format="HH:mm"
            :allow-empty="true"
            placeholder="08:30"
            style="width: 160px"
            value-format="HH:mm"
          />
        </FormItem>
        <FormItem label="通知渠道">
          <CheckboxGroup v-model:value="form.channels">
            <CheckboxOption
              v-for="item in CHANNEL_OPTIONS"
              :key="item.value"
              :value="item.value"
            >
              {{ item.label }}
            </CheckboxOption>
          </CheckboxGroup>
          <p class="mb-0 text-xs text-gray-500">
            短信按人并入每日汇总发送（免打扰时段与每日上限随后续版本生效）
          </p>
        </FormItem>
        <FormItem label="接收人规则">
          <CheckboxGroup
            v-model:value="form.recipients"
            class="recipient-group"
          >
            <CheckboxOption
              v-for="item in RECIPIENT_OPTIONS"
              :key="item.value"
              :value="item.value"
            >
              {{ item.label }}
            </CheckboxOption>
          </CheckboxGroup>
        </FormItem>
        <FormItem v-if="form.channels.includes('SMS')" label="短信模板">
          <Select
            v-model:value="form.smsTemplateCode"
            placeholder="选择平台审核过的模板"
            style="width: 320px"
          >
            <SelectOption
              v-for="item in templateOptions(templates)"
              :key="item.value"
              :value="item.value"
            >
              {{ item.label }}
            </SelectOption>
          </Select>
        </FormItem>
      </Form>
    </Modal>
  </Page>
</template>

<style scoped>
.hint {
  margin: 4px 0 0;
  font-size: 12px;
  color: gray;
}

.recipient-group {
  display: flex;
  flex-direction: column;
  gap: 4px;
  align-items: flex-start;
}
</style>
