<script setup lang="ts">
import { useSessionStore } from '@fu/admin-shell'

const session = useSessionStore()
</script>

<template>
  <section>
    <h1>平台工作台</h1>
    <p>当前是 {{ session.me?.displayName }}。租户、模块和菜单仍是占位页，数据来自后端菜单。</p>
    <a-card title="权限码">{{ session.codes.join('、') }}</a-card>
  </section>
</template>

<style scoped>
h1 {
  margin: 0 0 8px;
  font-size: 22px;
}
p {
  color: #5c706e;
}
</style>
