<script lang="ts" setup>
import type { DashboardLayoutView } from '@crdp/api';

import { computed, ref, watch } from 'vue';

import { userHiddenCodes } from '@crdp/api';
import {
  Button,
  Checkbox,
  message,
  Popover,
  Space,
  Spin,
} from 'ant-design-vue';

import { saveDashboardUserPref } from '#/api/core/dashboard';

defineOptions({ name: 'DashboardCardSettings' });

/**
 * E19-T06 / A-DASH-014（P1）· 首页右上角「卡片设置」（pages.md §2 线框「病区 [全部▾] [卡片设置]」）：
 * 用户在本人角色允许的卡片范围内隐藏或重新显示卡片——不能加入角色未允许的卡片（选项只来自
 * 布局响应），也不能调整顺序（无拖拽）；「恢复默认」清空隐藏清单回到角色配置的卡片与顺序。
 * allowUserHide=false 的卡片（角色配置「允许用户隐藏」为否）只展示、不可取消。
 */
const props = defineProps<{
  /** 当前布局（GET /dashboard/layout）：角色允许的卡片清单与其 userHidden 状态。 */
  layout: DashboardLayoutView;
}>();

const emit = defineEmits<{ saved: [view: DashboardLayoutView] }>();

const open = ref(false);
const saving = ref(false);
const checked = ref<string[]>([]);

const cards = computed(() =>
  [...props.layout.cards].toSorted((a, b) => a.order - b.order),
);

watch(open, (value) => {
  if (value) {
    // 打开时按当前布局初始化勾选：用户隐藏的卡片不勾，其余（含不可隐藏的）勾上
    const hidden = new Set(userHiddenCodes(props.layout));
    checked.value = cards.value
      .filter((card) => !hidden.has(card.code))
      .map((card) => card.code);
  }
});

async function handleSave() {
  saving.value = true;
  try {
    const hidden = cards.value
      .filter(
        (card) => card.allowUserHide && !checked.value.includes(card.code),
      )
      .map((card) => card.code);
    const view = await saveDashboardUserPref(hidden);
    emit('saved', view);
    open.value = false;
    message.success(
      hidden.length === 0 ? '已恢复角色默认卡片' : '卡片设置已保存',
    );
  } catch {
    message.error('卡片设置保存失败，请稍后重试');
  } finally {
    saving.value = false;
  }
}

async function handleReset() {
  saving.value = true;
  try {
    const view = await saveDashboardUserPref([]);
    emit('saved', view);
    open.value = false;
    message.success('已恢复角色默认布局');
  } catch {
    message.error('恢复默认失败，请稍后重试');
  } finally {
    saving.value = false;
  }
}
</script>

<template>
  <Popover v-model:open="open" placement="bottomRight" trigger="click">
    <Button data-testid="dashboard-card-settings">卡片设置</Button>
    <template #content>
      <div class="card-settings" data-testid="dashboard-card-settings-panel">
        <p class="title">显示的首页卡片</p>
        <p class="hint">
          只能在本人角色允许的卡片内隐藏 / 显示，顺序由角色配置决定
        </p>
        <Spin :spinning="saving">
          <Space direction="vertical" size="small">
            <Checkbox
              v-for="card in cards"
              :key="card.code"
              :checked="checked.includes(card.code)"
              :data-testid="`card-setting-${card.code}`"
              :disabled="!card.allowUserHide"
              @change="
                (event: { target: { checked: boolean } }) => {
                  const next = new Set(checked);
                  if (event.target.checked) {
                    next.add(card.code);
                  } else {
                    next.delete(card.code);
                  }
                  checked = [...next];
                }
              "
            >
              {{ card.title }}
              <span v-if="!card.allowUserHide" class="lock-hint">
                （不可隐藏）
              </span>
            </Checkbox>
          </Space>
        </Spin>
        <div class="actions">
          <Button
            size="small"
            type="primary"
            :loading="saving"
            @click="handleSave"
          >
            保存
          </Button>
          <Button size="small" :disabled="saving" @click="handleReset">
            恢复默认
          </Button>
        </div>
      </div>
    </template>
  </Popover>
</template>

<style scoped>
.card-settings {
  width: 280px;
}

.title {
  margin: 0;
  font-weight: 600;
}

.hint {
  margin: 4px 0 12px;
  font-size: 12px;
  color: gray;
}

.lock-hint {
  font-size: 12px;
  color: gray;
}

.actions {
  display: flex;
  gap: 8px;
  margin-top: 12px;
}
</style>
