<script lang="ts" setup>
import type { NotifyPrefView } from '@crdp/api';

import { onMounted, ref } from 'vue';

import { colors } from '@crdp/tokens';
import {
  Alert,
  Button,
  CheckboxGroup,
  Checkbox as CheckboxOption,
  Empty,
  message,
  Spin,
} from 'ant-design-vue';

import { fetchNotifyPref, updateNotifyPref } from '#/api/core/notify';

import {
  hasBlockedChoice,
  optionHint,
  toPrefFormModel,
  validatePrefModel,
} from './notify-pref';

defineOptions({ name: 'ProfileNotifyPref' });

/**
 * E09-T07 / A-NTF-007、C-NTF-007 · 个人中心「通知偏好」页签（pages.md §10）：
 * 按事件关闭短信（非逾期类；逾期类能否关闭由租户设置决定），站内信不受影响。
 * 保存写 NOTICE_PREF_UPDATE 审计；关闭即时对规则引擎生效（短信记 SKIPPED）。
 */
const loading = ref(false);
const loadError = ref('');
const saving = ref(false);
const errorText = ref('');
const view = ref<NotifyPrefView | null>(null);
const checked = ref<string[]>([]);

const textSecondary = colors.textSecondary;

async function load() {
  loading.value = true;
  loadError.value = '';
  try {
    const prefView = await fetchNotifyPref();
    view.value = prefView;
    checked.value = toPrefFormModel(prefView);
  } catch (error) {
    loadError.value = error instanceof Error ? error.message : '加载失败';
  } finally {
    loading.value = false;
  }
}

async function handleSave() {
  if (!view.value) {
    return;
  }
  errorText.value = validatePrefModel(checked.value, view.value) ?? '';
  if (errorText.value !== '') {
    return;
  }
  saving.value = true;
  try {
    const saved = await updateNotifyPref({ smsOff: [...checked.value] });
    view.value = saved;
    checked.value = toPrefFormModel(saved);
    message.success('通知偏好已保存');
  } finally {
    saving.value = false;
  }
}

onMounted(load);
</script>

<template>
  <div class="tab-body" data-testid="profile-notify-pref">
    <Spin :spinning="loading">
      <Alert
        v-if="loadError"
        class="mb-4"
        :message="loadError"
        show-icon
        type="error"
      />
      <template v-else-if="view">
        <p class="intro" :style="{ color: textSecondary }">
          关闭后对应事件不再向您发送短信（站内信不受影响）；已逾期是必须触达的提醒，
          除非本院设置允许，否则不可关闭。
        </p>
        <Empty
          v-if="view.options.length === 0"
          description="本院暂无含短信渠道的通知事件"
        />
        <CheckboxGroup v-else v-model:value="checked" class="pref-group">
          <CheckboxOption
            v-for="option in view.options"
            :key="option.eventType"
            class="pref-item"
            :class="{ blocked: !option.closeable }"
            :disabled="!option.closeable"
            :value="option.eventType"
          >
            <span class="pref-label">{{ option.label }}</span>
            <span class="pref-hint" :style="{ color: textSecondary }">
              {{ optionHint(option, view.overdueSmsOffAllowed) }}
            </span>
          </CheckboxOption>
        </CheckboxGroup>
        <Alert
          v-if="errorText"
          class="mt-3"
          :message="errorText"
          show-icon
          type="error"
        />
        <Button
          class="mt-4"
          type="primary"
          :disabled="hasBlockedChoice(checked, view)"
          :loading="saving"
          data-testid="notify-pref-save"
          @click="handleSave"
        >
          保存
        </Button>
      </template>
    </Spin>
  </div>
</template>

<style scoped>
.intro {
  margin: 0 0 12px;
  font-size: 12px;
}

.pref-group {
  display: flex;
  flex-direction: column;
  gap: 8px;
  align-items: stretch;
}

.pref-item.blocked :deep(.ant-checkbox-input) {
  cursor: not-allowed;
}

.pref-item.blocked :deep(.ant-checkbox) {
  cursor: not-allowed;
}

.pref-label {
  margin-right: 8px;
}

.pref-hint {
  font-size: 12px;
}
</style>
