import { defineConfig } from '@vben/eslint-config';

export default defineConfig([
  {
    files: ['packages/@core/ui-kit/layout-ui/**/*.vue'],
    rules: {
      // 上游用 update:sidebar-width 配合 v-model:sidebar-width，不能改成 camelCase。
      'vue/custom-event-name-casing': 'off',
    },
  },
]);
