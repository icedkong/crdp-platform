import vue from '@vitejs/plugin-vue'
import { defineConfig } from 'vite'

export default defineConfig({
  plugins: [vue()],
  server: {
    host: '0.0.0.0',
    port: 14174,
    proxy: {
      '/api': 'http://127.0.0.1:18080',
    },
  },
  resolve: {
    dedupe: ['vue', 'vue-router', 'pinia', 'ant-design-vue'],
  },
})
