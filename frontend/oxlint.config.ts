import { defineConfig } from '@vben/oxlint-config';

export default defineConfig({
  rules: {
    // 上游菜单组件名就是 Menu，和 HTML 保留名冲突。不改上游组件名。
    'vue/no-reserved-component-names': 'off',
  },
});
