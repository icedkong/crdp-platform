<script lang="ts" setup>
import { computed } from 'vue';

import { useAntdDesignTokens } from '@vben/hooks';
import { preferences, usePreferences } from '@vben/preferences';

import { antdTheme, mergeShellTokens } from '@crdp/tokens';
import { App, ConfigProvider, theme } from 'ant-design-vue';

import { antdLocale } from '#/locales';

defineOptions({ name: 'App' });

const { isDark } = usePreferences();
const { tokens } = useAntdDesignTokens();

const tokenTheme = computed(() => {
  const algorithm = isDark.value
    ? [theme.darkAlgorithm]
    : [theme.defaultAlgorithm];

  // antd 紧凑模式算法
  if (preferences.app.compact) {
    algorithm.push(theme.compactAlgorithm);
  }

  // G4（M3 审查）：外壳 CSS 变量值经 mergeShellTokens 过滤——业务四色（主色 / 成功 /
  // 警告 / 错误，+ info 别名）锁定 tokens.ts 的设计值，不被色板 500 档
  // （hsl(var(--primary)) 等）回写；背景 / 边框 / 圆角仍随外壳可主题化。
  // 单测见 frontend/packages/tokens/src/tokens.spec.ts（锁定四色实际生效）。
  const live = mergeShellTokens(tokens as unknown as Record<string, unknown>);

  return {
    algorithm,
    components: antdTheme.components,
    token: {
      ...antdTheme.token,
      ...live,
    },
  };
});
</script>

<template>
  <ConfigProvider :locale="antdLocale" :theme="tokenTheme">
    <App>
      <RouterView />
    </App>
  </ConfigProvider>
</template>
