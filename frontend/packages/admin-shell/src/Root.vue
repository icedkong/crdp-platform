<script setup lang="ts">
import zhCN from 'ant-design-vue/es/locale/zh_CN'
</script>

<template>
  <a-config-provider
    :locale="zhCN"
    :theme="{
      token: {
        colorPrimary: '#0e7c73',
        borderRadius: 8,
        fontFamily: 'PingFang SC, Noto Sans SC, Microsoft YaHei, sans-serif',
      },
    }"
  >
    <router-view />
  </a-config-provider>
</template>

<style>
body {
  margin: 0;
  background: #f3f6f5;
}
</style>
