<script lang="ts" setup>
import { useRouter } from 'vue-router';

import { preferences } from '@vben/preferences';

import { Button, Result } from 'ant-design-vue';

/**
 * E24-T01 / NFR-RESP-002、OQ-24：非响应式页面在手机（<768）打开时的整页替换
 * （components.md §22，文案照抄）。路由 meta `responsive !== true`（默认）时
 * 由响应式守卫重定向到 /mobile 渲染本组件——整页只做提示，侧栏 / 抽屉菜单
 * 仍可用，便于从菜单进入已适配的手机页面。
 */
defineOptions({ name: 'MobileBlocker' });

const props = defineProps<{
  /** 「返回首页」目标；缺省用 preferences.app.defaultHomePath */
  homePath?: string;
}>();

const router = useRouter();

function backHome() {
  router.push(props.homePath ?? preferences.app.defaultHomePath);
}
</script>

<template>
  <div class="mobile-blocker">
    <Result status="info" title="请在电脑或平板上操作">
      <template #extra>
        <Button type="primary" @click="backHome">返回首页</Button>
      </template>
    </Result>
  </div>
</template>

<style scoped>
.mobile-blocker {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 100%;
  min-height: 320px;
  padding: 16px;
}
</style>
