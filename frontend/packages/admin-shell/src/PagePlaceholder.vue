<script setup lang="ts">
defineProps<{
  title: string
  epic: string
  summary: string
}>()
</script>

<template>
  <section class="page">
    <header class="page-head">
      <h1>{{ title }}</h1>
      <a-tag color="default">{{ epic }}</a-tag>
    </header>
    <a-result status="info" :title="`${title}尚未开放`" :sub-title="summary" />
  </section>
</template>

<style scoped>
.page-head {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 8px;
}
.page-head h1 {
  margin: 0;
  font-size: 22px;
  font-weight: 600;
}
</style>
