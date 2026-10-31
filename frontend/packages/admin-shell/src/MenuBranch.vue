<script setup lang="ts">
import type { MenuNode } from '@crdp/api'
import { iconOf } from './icons'

defineOptions({ name: 'MenuBranch' })
defineProps<{ nodes: MenuNode[] }>()
</script>

<template>
  <template v-for="node in nodes" :key="node.id">
    <a-sub-menu v-if="node.type === 'DIR'" :key="`dir-${node.id}`">
      <template #icon>
        <component :is="iconOf(node.meta.icon)" />
      </template>
      <template #title>{{ node.meta.title }}</template>
      <MenuBranch :nodes="node.children" />
    </a-sub-menu>
    <a-menu-item v-else-if="node.type === 'MENU' && !node.meta.hideInMenu" :key="node.path">
      <template #icon>
        <component :is="iconOf(node.meta.icon)" />
      </template>
      {{ node.meta.title }}
    </a-menu-item>
  </template>
</template>
