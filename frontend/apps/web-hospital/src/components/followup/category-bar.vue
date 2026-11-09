<script lang="ts" setup>
import type { CategoryProgress } from '#/reference/types';

import { computed, onBeforeUnmount, ref } from 'vue';

import { colors } from '@crdp/tokens';
import { Drawer } from 'ant-design-vue';

import { useBreakpoint } from '#/reference/use-breakpoint';

import { useAnchorSpy } from './anchor-nav';

const props = withDefaults(
  defineProps<{
    activeKey?: string;
    items?: CategoryProgress[];
    mode?: 'auto' | 'bar' | 'select';
    showHighlightCount?: boolean;
    /**
     * scroll-spy（E13-T11，components.md §6）：观察 `#crdp-anchor-<key>` 锚点，
     * 滚动时顶部最近的一个经 update:activeKey 上抛。默认关闭——表单编辑页的
     * activeKey 是「选中分类」语义（baseline-edit 只渲染当前分类），只有
     * 详情页这类全分类连续滚动视图（基线页签、展开的随访记录）开启。
     */
    spy?: boolean;
  }>(),
  {
    activeKey: '',
    items: () => [],
    mode: 'auto',
    showHighlightCount: false,
    spy: false,
  },
);

const emit = defineEmits<{
  jump: [key: string];
  'update:activeKey': [key: string];
}>();

const { isMobile } = useBreakpoint();
const asSelect = computed(
  () => props.mode === 'select' || (props.mode === 'auto' && isMobile.value),
);

/**
 * E24-T02 / pages.md §5.2：手机「跳到分组」下拉 + 底部抽屉（design-system §7：
 * 底部抽屉 100% 宽，只用于手机筛选 / 操作菜单 / 附件导航 / 手机「跳到分组」）。
 */
const drawerOpen = ref(false);

function pick(key: string) {
  emit('update:activeKey', key);
  emit('jump', key);
}

function pickInDrawer(key: string) {
  pick(key);
  drawerOpen.value = false;
}

/** 触发按钮上的当前分组与已填 / 应填（未选过分组只显示「跳到分组」）。 */
const activeItem = computed(() =>
  props.items.find((item) => item.key === props.activeKey),
);

/**
 * E13-T11 scroll-spy：spy 开启时观察各分类锚点，滚动把顶部最近的一个
 * 上抛为 activeKey（components.md §6「v-model:activeKey，滚动时由 scroll-spy 更新」）。
 */
const spy = useAnchorSpy({
  enabled: () => props.spy,
  keys: computed(() => props.items.map((item) => item.key)),
  onActive: (key) => {
    if (key !== props.activeKey) emit('update:activeKey', key);
  },
});

onBeforeUnmount(() => spy.stop());
</script>

<template>
  <div v-if="asSelect" class="bar" data-testid="category-select">
    <button
      class="jump-trigger"
      data-testid="category-jump-trigger"
      type="button"
      @click="drawerOpen = true"
    >
      <span class="jump-label">跳到分组</span>
      <template v-if="activeItem">
        <span class="jump-current">{{ activeItem.label }}</span>
        <span
          class="count"
          :style="{
            color:
              activeItem.filled < activeItem.total ? colors.warning : undefined,
          }"
        >
          {{ activeItem.filled }}/{{ activeItem.total }}
        </span>
      </template>
      <span class="jump-arrow">▾</span>
    </button>
    <Drawer
      root-class-name="crdp-category-drawer"
      :open="drawerOpen"
      placement="bottom"
      title="跳到分组"
      @close="drawerOpen = false"
    >
      <div class="chips drawer-chips">
        <button
          v-for="item in items"
          :key="item.key"
          class="chip"
          :class="{ active: item.key === activeKey }"
          type="button"
          @click="pickInDrawer(item.key)"
        >
          <span>{{ item.label }}</span>
          <span
            class="count"
            :style="{
              color: item.filled < item.total ? colors.warning : undefined,
            }"
          >
            {{ item.filled }}/{{ item.total }}
          </span>
          <span v-if="showHighlightCount && item.highlightCount" class="flag">
            ▲{{ item.highlightCount }}
          </span>
          <span v-if="item.attachmentCount">📎 {{ item.attachmentCount }}</span>
        </button>
      </div>
    </Drawer>
  </div>
  <div v-else class="bar chips" data-testid="category-bar">
    <button
      v-for="item in items"
      :key="item.key"
      class="chip"
      :class="{ active: item.key === activeKey }"
      type="button"
      @click="pick(item.key)"
    >
      <span>{{ item.label }}</span>
      <span
        class="count"
        :style="{
          color: item.filled < item.total ? colors.warning : undefined,
        }"
      >
        {{ item.filled }}/{{ item.total }}
      </span>
      <span v-if="showHighlightCount && item.highlightCount" class="flag">
        ▲{{ item.highlightCount }}
      </span>
      <span v-if="item.attachmentCount">📎 {{ item.attachmentCount }}</span>
    </button>
  </div>
</template>

<style scoped>
.bar {
  position: sticky;
  top: 0;
  z-index: 5;
  background: #fff;
}

.chips {
  display: flex;
  gap: 8px;
  padding: 8px 0;
  overflow-x: auto;
}

.chips::-webkit-scrollbar {
  display: none;
}

/* 手机「跳到分组」触发按钮：占满宽、触控可点区域 ≥ 40px（tokens globalCss） */
.jump-trigger {
  display: flex;
  flex: 1;
  gap: 8px;
  align-items: center;
  justify-content: space-between;
  padding: 8px 12px;
  font-size: 14px;
  color: #262626;
  cursor: pointer;
  background: #fff;
  border: 1px solid #e9e9e9;
  border-radius: 4px;
}

.jump-label {
  flex: none;
  color: #262626;
}

.jump-current {
  overflow: hidden;
  text-overflow: ellipsis;
  color: #3a75ff;
  white-space: nowrap;
}

.jump-arrow {
  flex: none;
  color: rgb(0 0 0 / 45%);
}

/* 底部抽屉内分组纵向铺开（一屏可扫全部分组） */
.drawer-chips {
  flex-direction: column;
  padding: 0;
  overflow: visible;
}

.drawer-chips .chip {
  justify-content: space-between;
  width: 100%;
  min-height: 44px;
}

.chip {
  display: inline-flex;
  flex: none;
  gap: 6px;
  align-items: center;
  padding: 4px 10px;
  font-size: 14px;
  color: #262626;
  cursor: pointer;
  background: #fff;
  border: 1px solid #e9e9e9;
  border-radius: 4px;
}

.chip.active {
  background: #e6f0ff;
  border-color: #3a75ff;
}

.count {
  font-variant-numeric: tabular-nums;
}

.flag {
  color: #ff4d4f;
}
</style>
