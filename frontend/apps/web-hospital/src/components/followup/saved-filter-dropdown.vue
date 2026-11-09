<script lang="ts" setup>
import type { SavedFilter } from '@crdp/api';

import { ref } from 'vue';

import {
  Button,
  Dropdown,
  Input,
  Menu,
  MenuItem,
  Popconfirm,
} from 'ant-design-vue';

/**
 * 「我的筛选」下拉（A-PAT-015 / components.md §11.1；E13-T06 随患者列表引入）：
 * 点击应用、行内「更新 / 重命名 / 删除」。E24-T03 抽成独立组件——桌面行内
 * 筛选栏与平板 / 手机筛选抽屉共用同一份标记；交互逻辑不变。
 */
const props = defineProps<{
  savedFilters: SavedFilter[];
}>();

const emit = defineEmits<{
  (e: 'apply', item: SavedFilter): void;
  (e: 'remove', item: SavedFilter): void;
  (e: 'rename', item: SavedFilter, name: string): void;
  (e: 'overwrite', item: SavedFilter): void;
}>();

const renamingId = ref('');
const renamingName = ref('');

function startRename(item: SavedFilter) {
  renamingId.value = item.id;
  renamingName.value = item.name;
}

/** 行内重命名确定：改名才上抛（空名 / 同名视为取消）。 */
function confirmRename(item: SavedFilter) {
  const name = renamingName.value.trim();
  renamingId.value = '';
  if (!name || name === item.name) return;
  emit('rename', item, name);
}
</script>

<template>
  <!-- trigger=click：触控设备可点开（design-system §0.5 悬停在触控改为点击；桌面同样可点） -->
  <Dropdown trigger="click">
    <Button data-testid="patient-saved-filters">我的筛选 ▾</Button>
    <template #overlay>
      <Menu data-testid="saved-filter-menu">
        <MenuItem v-if="props.savedFilters.length === 0" disabled>
          暂无保存的筛选，用「高级检索」保存
        </MenuItem>
        <MenuItem
          v-for="item in props.savedFilters"
          :key="item.id"
          :title="item.name"
        >
          <div class="saved-filter-row">
            <template v-if="renamingId === item.id">
              <Input
                v-model:value="renamingName"
                :maxlength="32"
                size="small"
                :style="{ width: '140px' }"
                @keyup.enter="confirmRename(item)"
              />
              <Button
                size="small"
                type="link"
                @click.stop="confirmRename(item)"
              >
                确定
              </Button>
            </template>
            <template v-else>
              <span class="saved-filter-name" @click.stop="emit('apply', item)">
                {{ item.name }}
              </span>
              <span @click.stop>
                <Button
                  size="small"
                  type="link"
                  title="用当前检索条件覆盖该筛选"
                  @click="emit('overwrite', item)"
                >
                  更新
                </Button>
                <Button size="small" type="link" @click="startRename(item)">
                  重命名
                </Button>
                <Popconfirm
                  title="删除该筛选？"
                  ok-text="删除"
                  cancel-text="取消"
                  @confirm="emit('remove', item)"
                >
                  <Button danger size="small" type="link">删除</Button>
                </Popconfirm>
              </span>
            </template>
          </div>
        </MenuItem>
      </Menu>
    </template>
  </Dropdown>
</template>

<style scoped>
.saved-filter-row {
  display: flex;
  gap: 4px;
  align-items: center;
  justify-content: space-between;
  min-width: 280px;
}

.saved-filter-name {
  max-width: 150px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  cursor: pointer;
}
</style>
