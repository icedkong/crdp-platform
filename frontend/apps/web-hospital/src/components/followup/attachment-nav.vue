<script lang="ts" setup>
import type { AttachmentNavItem, ReportLocation } from '#/reference/types';

import { computed, ref } from 'vue';

import { sizes } from '@crdp/tokens';
import { Button, Drawer } from 'ant-design-vue';

import { groupBaseline, groupFollowup, itemsForScope } from '#/reference/group';
import { useBreakpoint } from '#/reference/use-breakpoint';

import AttachmentNavList from './attachment-nav-list.vue';

const props = defineProps<{
  canUpload?: boolean;
  items: AttachmentNavItem[];
  patientId: string;
  scope: 'baseline' | 'followup';
}>();

const emit = defineEmits<{
  locate: [location: ReportLocation];
  open: [reportId: string];
  upload: [];
}>();

const { isDesktop, isMobile } = useBreakpoint();
const drawerOpen = ref(false);
const collapsed = ref(false);

const scopedItems = computed(() => itemsForScope(props.items, props.scope));
const groups = computed(() =>
  props.scope === 'baseline'
    ? groupBaseline(scopedItems.value)
    : groupFollowup(scopedItems.value),
);
const emptyText = computed(() =>
  props.scope === 'baseline' ? '暂无基线附件 · 上传' : '暂无随访附件 · 上传',
);

function locate(location: ReportLocation) {
  drawerOpen.value = false;
  emit('locate', location);
}
</script>

<template>
  <aside
    v-if="isDesktop"
    class="nav"
    :data-patient-id="patientId"
    data-testid="attachment-nav"
    :style="{ width: collapsed ? '24px' : `${sizes.attachmentNavWidth}px` }"
  >
    <div class="head">
      <span v-if="!collapsed">附件（{{ scopedItems.length }}）</span>
      <Button size="small" type="text" @click="collapsed = !collapsed">
        {{ collapsed ? '»' : '«' }}
      </Button>
    </div>
    <AttachmentNavList
      v-if="!collapsed"
      :can-upload="canUpload"
      :empty-text="emptyText"
      :groups="groups"
      @locate="locate"
      @open="emit('open', $event)"
      @upload="emit('upload')"
    />
  </aside>
  <div v-else class="trigger">
    <Button data-testid="attachment-open" @click="drawerOpen = true">
      附件（{{ scopedItems.length }}）
    </Button>
    <Drawer
      v-model:open="drawerOpen"
      :height="isMobile ? '70vh' : undefined"
      :placement="isMobile ? 'bottom' : 'left'"
      :title="`附件（${scopedItems.length}）`"
      :width="320"
    >
      <AttachmentNavList
        :can-upload="canUpload"
        :empty-text="emptyText"
        :groups="groups"
        @locate="locate"
        @open="emit('open', $event)"
        @upload="emit('upload')"
      />
    </Drawer>
  </div>
</template>

<style scoped>
.nav {
  flex: none;
  align-self: stretch;
  padding: 8px;
  overflow: auto;
  background: #fff;
  border-right: 1px solid #e9e9e9;
}

.head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8px;
  font-weight: 600;
}

.trigger {
  margin-bottom: 8px;
}
</style>
