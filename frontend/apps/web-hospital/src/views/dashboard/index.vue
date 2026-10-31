<script lang="ts" setup>
import { useRouter } from 'vue-router';

import { Button, Result } from 'ant-design-vue';

import { useBreakpoint } from '#/reference/use-breakpoint';

const router = useRouter();
const { isMobile } = useBreakpoint();
const responsive = false;
const title = '工作台';
</script>

<template>
  <Result
    v-if="isMobile && !responsive"
    status="info"
    title="请在电脑或平板上操作"
  >
    <template #extra>
      <Button type="primary" @click="router.push('/dashboard')">
        返回首页
      </Button>
    </template>
  </Result>
  <div v-else class="page">
    <h1 class="crdp-page-title">{{ title }}</h1>
    <p>占位页面。业务尚未实现。没有真实患者数据。</p>
  </div>
</template>

<style scoped>
.page {
  padding: 16px;
}
</style>
