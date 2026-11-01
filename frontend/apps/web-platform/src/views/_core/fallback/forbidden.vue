<script lang="ts" setup>
import { Fallback } from '@vben/common-ui';

defineOptions({ name: 'Fallback403' });
</script>

<template>
  <!-- E06-T01 / pages.md §0.2：无菜单权限的统一文案（照抄设计文档） -->
  <Fallback
    description="没有访问权限，请联系本院管理员"
    home-path="/dashboard"
    status="403"
  />
</template>
