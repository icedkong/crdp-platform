<script lang="ts" setup>
/**
 * E13-T06 / A-PAT-014、A-NFR-009、C-PAT-013 · 高级检索弹窗（components.md §11.2）：
 * 条件行与条件组构造、底部只读「逻辑表达式预览」、保存为个人筛选（A-PAT-015）。
 * 字段只能选目录（字段字典经 search-fields 下发）中的字段；输出结构化 JSON，
 * 后端转换为参数化查询并自动追加病区条件——不允许原始 SQL（A-NFR-009）。
 *
 * E24-T03 / design-system §5、pages.md §3：手机（mobileCommonOnly）改底部抽屉
 * （placement="bottom"，高 70vh）且只留常用条件（common=true 的字段）；
 * 桌面 / 平板保持 900 宽弹窗。手机输入框高 36 / 字号 16 由 .crdp-form 媒体查询承担。
 */
import type { AdvancedSearchField, ConditionGroup } from '@crdp/api';

import { computed, ref, toRaw, watch } from 'vue';

import {
  conditionPreview,
  deepCloneJson,
  emptyConditionGroup,
  isConditionGroupComplete,
  pruneConditionGroup,
} from '@crdp/api';
import { colors, sizes } from '@crdp/tokens';
import { Button, Drawer, Input, Modal } from 'ant-design-vue';

import AdvancedSearchGroup from './advanced-search-group.vue';

const props = defineProps<{
  /** 字段目录（GET /patients/search-fields）。 */
  fieldCatalog: AdvancedSearchField[];
  /** 手机只留常用条件（common=true 的字段），并改用底部抽屉形态（E24-T03）。 */
  mobileCommonOnly?: boolean;
  open: boolean;
  /** 打开时的条件树（续编；缺省从空组开始）。 */
  value?: ConditionGroup | null;
}>();

const emit = defineEmits<{
  (e: 'apply', group: ConditionGroup): void;
  (e: 'saveAs', name: string, group: ConditionGroup): void;
  (e: 'update:open', open: boolean): void;
}>();

const draft = ref<ConditionGroup>(emptyConditionGroup());
const filterName = ref('');
const naming = ref(false);

const fields = computed(() =>
  props.mobileCommonOnly
    ? props.fieldCatalog.filter((item) => item.common)
    : props.fieldCatalog,
);

/** 手机底部抽屉形态（E24-T03）；主体 / 底栏标记共用，只换容器与容器属性。 */
const mobileForm = computed(() => props.mobileCommonOnly === true);
const drawerProps = {
  className: 'crdp-form',
  height: '70vh',
  placement: 'bottom' as const,
};
const modalProps = { width: sizes.modal.advancedSearch };

function close() {
  emit('update:open', false);
}

watch(
  () => props.open,
  (open) => {
    if (open) {
      // 条件树是纯 JSON；props.value 可能是 reactive 代理，先 toRaw 取原始对象再
      // 深拷贝（续编不回写父状态）。E24-T07：deepCloneJson 替代 structuredClone——
      // 微信 XWeb 等旧内核（Chromium < 98）没有该 API，真机上打开即 TypeError
      const cloned = props.value
        ? (deepCloneJson(toRaw(props.value)) as ConditionGroup)
        : null;
      draft.value = cloned ?? emptyConditionGroup();
      if (draft.value.items.length === 0 && fields.value.length > 0) {
        draft.value = {
          ...draft.value,
          items: [
            {
              field: fields.value[0]?.field ?? '',
              op: fields.value[0]?.ops[0] ?? 'EQ',
              value: null,
            },
          ],
        };
      }
      naming.value = false;
      filterName.value = '';
    }
  },
  { immediate: true },
);

/** 颜色全部取自 tokens（不写魔法数）；stylelint 不认 v-bind()，走 CSS 变量注入。 */
const cssVars = {
  '--adv-primary-bg': colors.primaryBg,
  '--adv-text': colors.text,
  '--adv-text-secondary': colors.textSecondary,
};

const preview = computed(() => conditionPreview(draft.value, fields.value));
const complete = computed(() =>
  isConditionGroupComplete(draft.value, fields.value),
);

function onApply() {
  const pruned = pruneConditionGroup(draft.value, fields.value);
  if (pruned) {
    emit('apply', pruned);
  }
  emit('update:open', false);
}

function onClear() {
  draft.value = emptyConditionGroup();
  naming.value = false;
  filterName.value = '';
}

function onSaveAs() {
  const name = filterName.value.trim();
  const pruned = pruneConditionGroup(draft.value, fields.value);
  if (!name || !pruned) return;
  emit('saveAs', name, pruned);
  naming.value = false;
  filterName.value = '';
}
</script>

<template>
  <!-- 手机 = 底部抽屉（E24-T03，design-system §5）；桌面 / 平板 = 900 宽弹窗；主体与底栏共用一份标记 -->
  <component
    :is="mobileForm ? Drawer : Modal"
    v-bind="mobileForm ? drawerProps : modalProps"
    :open="open"
    title="高级检索"
    @cancel="close"
    @close="close"
  >
    <div :style="cssVars" class="body" data-testid="advanced-search-body">
      <AdvancedSearchGroup v-model:group="draft" :fields="fields" />
      <p v-if="fields.length === 0" class="empty-catalog">
        暂无可用字段（字段字典未配置）
      </p>

      <!-- 逻辑表达式预览（只读，A-PAT-014） -->
      <div class="preview" data-testid="advanced-search-preview">
        <span class="preview-label">逻辑表达式预览</span>
        <span class="preview-text">{{ preview || '—' }}</span>
      </div>

      <!-- 保存为个人筛选（A-PAT-015；仅本人可见） -->
      <div v-if="naming" class="save-row">
        <Input
          v-model:value="filterName"
          :maxlength="32"
          placeholder="筛选名称（如：上半年出院）"
          :style="{ width: '240px' }"
          @keyup.enter="onSaveAs"
        />
        <Button
          :disabled="filterName.trim().length === 0 || !preview"
          size="small"
          type="primary"
          @click="onSaveAs"
        >
          保存
        </Button>
      </div>
    </div>

    <template #footer>
      <div class="footer">
        <Button @click="naming = !naming">保存为个人筛选</Button>
        <Button @click="onClear">清空</Button>
        <Button @click="close">取消</Button>
        <Button
          :disabled="!complete || fields.length === 0"
          type="primary"
          @click="onApply"
        >
          查询
        </Button>
      </div>
    </template>
  </component>
</template>

<style scoped>
.body {
  display: flex;
  flex-direction: column;
  gap: 12px;
  max-height: 60vh;
  overflow-y: auto;
}

.preview {
  display: flex;
  gap: 8px;
  padding: 8px 12px;
  background: var(--adv-primary-bg);
  border-radius: 4px;
}

.preview-label {
  flex-shrink: 0;
  font-size: 12px;
  color: var(--adv-text-secondary);
}

.preview-text {
  color: var(--adv-text);
  word-break: break-all;
}

.save-row {
  display: flex;
  gap: 8px;
  align-items: center;
}

.empty-catalog {
  color: var(--adv-text-secondary);
}

.footer {
  display: flex;
  gap: 8px;
  justify-content: flex-end;
}
</style>
