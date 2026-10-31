<script lang="ts" setup>
import { onMounted, onUnmounted, ref } from 'vue';
import { useRouter } from 'vue-router';

import { breakpoints } from '@crdp/tokens';
import { Button, Result } from 'ant-design-vue';

const router = useRouter();
const isMobile = ref(false);
const responsive = false;
const title = '租户管理';

function read() {
  isMobile.value = window.matchMedia(
    `(max-width: ${breakpoints.tablet - 1}px)`,
  ).matches;
}
onMounted(() => {
  read();
  window.addEventListener('resize', read);
});
onUnmounted(() => window.removeEventListener('resize', read));
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
