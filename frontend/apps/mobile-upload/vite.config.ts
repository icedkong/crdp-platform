import vue from '@vitejs/plugin-vue';
import { defineConfig } from 'vite';

// E16-T10 / A-ATT-015、C-SCAN-008：手机扫码上传 H5。开发期同源代理到后端
// （生产与后端同域部署，/api 走相对路径；qrUrl 由后端拼本应用基地址 + /#/u/{令牌}）。
export default defineConfig({
  plugins: [vue()],
  server: {
    host: '0.0.0.0',
    port: 14_175,
    proxy: {
      '/api': { target: 'http://127.0.0.1:18080', changeOrigin: true },
    },
  },
});
