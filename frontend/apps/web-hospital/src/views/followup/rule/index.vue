<script lang="ts" setup>
import type { CompareRule } from '@crdp/api';

import { computed, onMounted, ref } from 'vue';

import { Page } from '@vben/common-ui';

import {
  compareTargetText,
  conditionText,
  thresholdBounds,
  thresholdKeyOf,
  toCompareRuleFormModel,
  toCompareRuleUpdate,
  validateCompareRuleModel,
} from '@crdp/api';
import {
  Alert,
  Button,
  Form,
  FormItem,
  InputNumber,
  message,
  Modal,
  Result,
  Spin,
  Switch,
  Table,
  Tag,
} from 'ant-design-vue';

import { fetchCompareRules, updateCompareRule } from '#/api/core/compareRule';
import { useBreakpoint } from '#/reference/use-breakpoint';

import { applyUpdate, savedMessage, thresholdControl } from './logic';

defineOptions({ name: 'FollowupAbnormalRule' });

/**
 * E17-T01 / A-FU-027（Q41）、C-FU-011（随 E21）· 对比规则（/system/abnormal-rule，pages.md §10）：
 * 录入随访记录时与上一次（或基线）对比，满足规则的字段在表单内标红提示——不发通知、
 * 不生成待办、不影响提交。每条规则可开关、调阈值（A 的 Q41 全量规则随 E17-T05 预置、
 * C 的默认随 E21-T20）；修改记录操作日志。仅桌面 / 平板。
 */
const { isMobile } = useBreakpoint();

const loading = ref(false);
const loadError = ref('');
const rules = ref<CompareRule[]>([]);

const saving = ref(false);
const editing = ref(false);
const editingRule = ref<CompareRule | null>(null);
const editErrors = ref<string[]>([]);
const editEnabled = ref(true);
const editThreshold = ref<null | number>(null);

/** 弹窗阈值控件：标签 / 提示 / 输入范围（无阈值类型为 null，只显示开关）。 */
const thresholdUi = computed(() => {
  const rule = editingRule.value;
  if (!rule) {
    return null;
  }
  const control = thresholdControl(rule.ruleType);
  const key = thresholdKeyOf(rule.ruleType);
  if (!control || !key) {
    return null;
  }
  const bounds = thresholdBounds(key);
  return { ...control, ...bounds };
});

const columns = [
  { title: '规则', dataIndex: 'name', key: 'name' },
  { title: '表单', dataIndex: 'formName', key: 'formName', width: 130 },
  { title: '字段', key: 'field', width: 180 },
  { title: '对比对象', key: 'target', width: 220 },
  { title: '条件', key: 'condition', width: 190 },
  {
    title: '类型',
    dataIndex: 'ruleTypeLabel',
    key: 'ruleTypeLabel',
    width: 120,
  },
  { title: '状态', dataIndex: 'enabled', key: 'enabled', width: 80 },
  { title: '操作', key: 'action', width: 90 },
];

async function load() {
  loading.value = true;
  loadError.value = '';
  try {
    rules.value = await fetchCompareRules();
  } catch (error) {
    loadError.value = error instanceof Error ? error.message : '加载失败';
  } finally {
    loading.value = false;
  }
}

function openEdit(rule: CompareRule) {
  editingRule.value = rule;
  const model = toCompareRuleFormModel(rule);
  editEnabled.value = model.enabled;
  editThreshold.value = model.threshold;
  editErrors.value = [];
  editing.value = true;
}

async function handleSave() {
  const rule = editingRule.value;
  if (!rule) {
    return;
  }
  const model = { enabled: editEnabled.value, threshold: editThreshold.value };
  editErrors.value = validateCompareRuleModel(rule, model);
  if (editErrors.value.length > 0) {
    return;
  }
  saving.value = true;
  try {
    const saved = await updateCompareRule(
      rule.id,
      toCompareRuleUpdate(rule, model),
    );
    rules.value = applyUpdate(rules.value, saved);
    editing.value = false;
    message.success(savedMessage(saved.name));
  } finally {
    saving.value = false;
  }
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
    title="对比规则"
    description="录入随访记录时与上一次（或基线出院前）对比，满足规则的字段在表单内标红并显示对比值与变化量；只是提示，不发通知、不影响提交。每条规则可开关、调阈值，修改后记录操作日志。"
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
        row-key="id"
        size="middle"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'name'">
            {{ record.name }}
            <p class="rule-message">{{ record.message }}</p>
          </template>
          <template v-else-if="column.key === 'field'">
            {{ record.fieldLabel ?? record.targetField }}
            <span v-if="record.fieldUnit">（{{ record.fieldUnit }}）</span>
          </template>
          <template v-else-if="column.key === 'target'">
            {{ compareTargetText(record) }}
          </template>
          <template v-else-if="column.key === 'condition'">
            {{ conditionText(record) }}
          </template>
          <template v-else-if="column.key === 'enabled'">
            <Tag v-if="record.enabled" color="success">启用</Tag>
            <Tag v-else>停用</Tag>
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
      :title="editingRule ? `对比规则：${editingRule.name}` : '对比规则'"
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
      <Form :label-col="{ span: 6 }" @keyup.enter.prevent>
        <FormItem label="规则开关">
          <Switch
            v-model:checked="editEnabled"
            checked-children="启用"
            un-checked-children="停用"
          />
          <p class="hint">停用后该规则不再参与录入时标红</p>
        </FormItem>
        <FormItem v-if="thresholdUi" :label="thresholdUi.label">
          <InputNumber
            v-model:value="editThreshold"
            :min="thresholdUi.min"
            :max="thresholdUi.max"
            :precision="thresholdUi.precision"
            style="width: 160px"
          />
          <p class="hint">{{ thresholdUi.hint }}</p>
        </FormItem>
        <FormItem v-else label="阈值">
          <p class="mb-0 text-xs text-gray-500">该规则无阈值参数，只可开关</p>
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

.rule-message {
  margin: 2px 0 0;
  font-size: 12px;
  color: gray;
}
</style>
