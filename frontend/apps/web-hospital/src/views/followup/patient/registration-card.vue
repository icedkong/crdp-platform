<script setup lang="ts">
import type { PatientCorrectionView } from '@crdp/api';

import type { RegistrationHistory } from '#/api/core/patient-correction';

import { computed, onBeforeUnmount, ref, watch } from 'vue';

import { identityCorrectionChanges } from '@crdp/api';
import { FormRenderer } from '@crdp/form-engine';
import { copyFormValue } from '@crdp/rules';
import { sizes } from '@crdp/tokens';
import {
  Alert,
  Button,
  Card,
  Checkbox,
  Input,
  Modal,
  Space,
  Spin,
  Table,
  Textarea,
} from 'ant-design-vue';

import {
  correctRegistration,
  fetchRegistration,
  fetchRegistrationHistory,
} from '#/api/core/patient-correction';
import { usePermission } from '#/reference/use-permission';
import { useTenantViewStore } from '#/store/view';

const props = defineProps<{ locked?: boolean; patientId: string }>();
const emit = defineEmits<{ (e: 'saved'): void }>();
const { hasPermission } = usePermission();
const tenantView = useTenantViewStore();
const view = ref<null | PatientCorrectionView>(null);
const loading = ref(false);
const error = ref('');
const opened = ref(false);
const sensitive = ref(false);
const saving = ref(false);
const draft = ref<Record<string, unknown>>({});
const entered = ref<Record<string, string>>({});
const reason = ref('');
const cleared = ref<Record<string, boolean>>({});
const confirmed = ref(false);
const conflict = ref(false);
const expired = ref(false);
const historyOpen = ref(false);
const history = ref<RegistrationHistory[]>([]);
const historyLoading = ref(false);
let sequence = 0;
let clearTimer: ReturnType<typeof setTimeout> | undefined;
const identityFields = computed(
  () =>
    view.value?.identitySchema.categories
      .flatMap((c) => c.fields)
      .filter((f) => Object.hasOwn(view.value?.identity ?? {}, f.key)) ?? [],
);
const blocked = computed(() => props.locked || view.value?.locked);

async function load() {
  const current = ++sequence;
  view.value = null;
  if (!props.patientId || tenantView.active) return;
  loading.value = true;
  error.value = '';
  try {
    const result = await fetchRegistration(props.patientId);
    if (current === sequence) view.value = result;
  } catch (error_) {
    if (current === sequence)
      error.value =
        error_ instanceof Error ? error_.message : '登记信息加载失败';
  } finally {
    if (current === sequence) loading.value = false;
  }
}
function clearSensitive() {
  if (clearTimer) clearTimeout(clearTimer);
  clearTimer = undefined;
  entered.value = {};
  cleared.value = {};
  confirmed.value = false;
}
function close() {
  opened.value = false;
  clearSensitive();
  draft.value = {};
  reason.value = '';
  error.value = '';
  conflict.value = false;
}
function start(identity: boolean) {
  if (!view.value || blocked.value) return;
  close();
  sensitive.value = identity;
  draft.value = copyFormValue(view.value.data);
  expired.value = false;
  opened.value = true;
  if (identity) armSensitiveTimer();
}
function armSensitiveTimer() {
  clearTimer = setTimeout(() => {
    clearSensitive();
    expired.value = true;
  }, 60_000);
}
async function save() {
  if (!view.value || saving.value || conflict.value) return;
  if (!reason.value.trim()) {
    error.value = '请填写更正原因，不要包含患者敏感值';
    return;
  }
  if (sensitive.value && (!confirmed.value || expired.value)) {
    error.value = '请确认更正内容；输入超时后请重新打开';
    return;
  }
  const changes = sensitive.value
    ? identityCorrectionChanges(
        entered.value,
        Object.keys(cleared.value).filter((key) => cleared.value[key]),
      )
    : copyFormValue(draft.value);
  if (Object.keys(changes).length === 0) {
    error.value = '请填写需要更正的新值';
    return;
  }
  const current = sequence;
  saving.value = true;
  error.value = '';
  try {
    const updated = await correctRegistration(
      props.patientId,
      {
        baseVersion: view.value.version,
        changes,
        confirmed: confirmed.value,
        reason: reason.value.trim(),
      },
      sensitive.value,
    );
    if (current !== sequence) return;
    view.value = updated;
    close();
    emit('saved');
  } catch (error_) {
    if (current !== sequence) return;
    error.value = error_ instanceof Error ? error_.message : '更正失败';
    const payload = error_ as {
      code?: number;
      response?: { data?: { code?: number }; status?: number };
    };
    conflict.value =
      payload.code === 40_900 || payload.response?.data?.code === 40_900;
  } finally {
    saving.value = false;
  }
}
async function resolveConflict(keepMine: boolean) {
  const expected = sequence;
  try {
    const current = await fetchRegistration(props.patientId);
    if (expected !== sequence) return;
    view.value = current;
    if (!keepMine) {
      draft.value = copyFormValue(current.data);
      clearSensitive();
      if (sensitive.value) {
        expired.value = false;
        armSensitiveTimer();
      }
    }
    confirmed.value = false;
    conflict.value = false;
    error.value = '已加载当前版本，请重新核对并确认保存';
  } catch {
    error.value = '加载服务器版本失败，请重试；本地输入仍保留';
  }
}
async function showHistory() {
  historyOpen.value = true;
  historyLoading.value = true;
  const current = sequence;
  try {
    const rows = await fetchRegistrationHistory(props.patientId);
    if (current === sequence) history.value = rows;
  } catch (error_) {
    error.value = error_ instanceof Error ? error_.message : '字段历史加载失败';
  } finally {
    historyLoading.value = false;
  }
}
function historyValue(value: unknown) {
  if (value && typeof value === 'object' && 'digest' in value)
    return '已记录密文摘要';
  return value === null || value === undefined ? '—' : String(value);
}
watch(
  () => [props.patientId, tenantView.active],
  () => {
    close();
    history.value = [];
    historyOpen.value = false;
    void load();
  },
  { immediate: true, flush: 'sync' },
);
onBeforeUnmount(() => {
  ++sequence;
  clearSensitive();
});
</script>

<template>
  <Card
    v-if="!tenantView.active"
    title="登记信息"
    :style="{ marginBottom: `${sizes.gutter}px` }"
  >
    <Spin :spinning="loading">
      <Alert v-if="error && !opened" :message="error" type="error" show-icon />
      <Button v-if="!view && !loading" @click="load">重新加载</Button>
      <template v-if="view">
        <FormRenderer
          :schema="view.schema"
          :model-value="view.data"
          mode="readonly"
        />
        <FormRenderer
          :schema="view.statusSchema"
          :model-value="view.status"
          mode="readonly"
        />
        <Space :size="sizes.gutter">
          <Button
            v-if="hasPermission('followup:patient:edit')"
            :disabled="blocked"
            @click="start(false)"
          >
            更正登记信息
          </Button>
          <Button
            v-if="hasPermission('followup:patient:correct')"
            :disabled="blocked"
            @click="start(true)"
          >
            更正敏感身份
          </Button>
          <Button @click="showHistory">字段历史</Button>
        </Space>
        <Alert
          v-if="blocked"
          message="基线已锁定，请先解锁后更正登记信息"
          type="warning"
          show-icon
        />
      </template>
    </Spin>
  </Card>
  <Modal
    :open="opened"
    :title="sensitive ? '更正敏感身份信息' : '更正登记信息'"
    :width="sizes.modal.multiColMin"
    :confirm-loading="saving"
    :ok-button-props="{ disabled: conflict || expired }"
    @ok="save"
    @cancel="close"
  >
    <Alert v-if="error" :message="error" type="error" show-icon />
    <Alert
      v-if="expired"
      message="敏感新值已在 60 秒后清除，请关闭后重新打开。"
      type="warning"
      show-icon
    />
    <Space v-if="conflict" :size="sizes.gutter">
      <Button @click="resolveConflict(false)">加载服务器版本</Button>
      <Button @click="resolveConflict(true)">保留我的并覆盖</Button>
    </Space>
    <template v-if="view">
      <template v-if="sensitive">
        <Alert
          message="旧值仅脱敏展示；填写要更正的新值，其余留空。新值只在内存保留，不写浏览器草稿。"
          type="info"
          show-icon
        />
        <div
          v-for="field in identityFields"
          :key="field.key"
          :style="{ marginTop: `${sizes.gutter}px` }"
        >
          <label
            :for="`correction-${field.key}`"
            v-text="
              `${field.label} · 当前：${view.identity[field.key] || '未填写'}`
            "
          ></label>
          <Textarea
            v-if="field.type === 'textarea'"
            v-model:value="entered[field.key]"
            :id="`correction-${field.key}`"
            :disabled="expired || saving || cleared[field.key]"
            :maxlength="255"
          />
          <Input
            v-else
            v-model:value="entered[field.key]"
            :id="`correction-${field.key}`"
            :disabled="expired || saving || cleared[field.key]"
            :maxlength="64"
            autocomplete="off"
          />
          <Checkbox
            v-if="!field.required"
            v-model:checked="cleared[field.key]"
            :disabled="expired || saving"
          >
            清除现有值
          </Checkbox>
        </div>
      </template>
      <FormRenderer
        v-else
        v-model="draft"
        :schema="view.schema"
        :mode="saving ? 'readonly' : 'edit'"
      />
      <label>更正原因</label>
      <Textarea
        v-model:value="reason"
        :maxlength="255"
        placeholder="请说明更正原因，不要填写患者敏感值"
      />
      <Checkbox
        v-if="sensitive"
        v-model:checked="confirmed"
        :disabled="expired"
      >
        已核对新值，确认更正并记录审计
      </Checkbox>
    </template>
  </Modal>
  <Modal
    :open="historyOpen"
    title="登记字段历史"
    :width="sizes.modal.multiColMax"
    :footer="null"
    @cancel="historyOpen = false"
  >
    <Table
      :data-source="history"
      row-key="id"
      :loading="historyLoading"
      :pagination="{ pageSize: 20 }"
      :columns="[
        { title: '字段', dataIndex: 'fieldKey' },
        { title: '旧值', dataIndex: 'oldValue' },
        { title: '新值', dataIndex: 'newValue' },
        { title: '修改人', dataIndex: 'operatorName' },
        { title: '原因', dataIndex: 'reason' },
        { title: '时间', dataIndex: 'changedAt' },
      ]"
    >
      <template #bodyCell="{ column, record }">
        <span
          v-if="
            column.dataIndex === 'oldValue' || column.dataIndex === 'newValue'
          "
          v-text="historyValue(record[column.dataIndex])"
        ></span>
      </template>
    </Table>
  </Modal>
</template>
