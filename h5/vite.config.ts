import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// 智汇于庄 H5 · C 端单页
//
// - base: '/h5/'：产物静态资源前缀与网关路由 /h5/ 对齐（网关将 /h5/ 反代到 h5 容器）。
// - server.proxy：本地 dev 直接转发到宿主网关（localhost:80），与线上同源路径一致
//   （/api/v1/* -> backend，/ai/v1/* -> ai-service）。
export default defineConfig({
  base: '/h5/',
  plugins: [react()],
  build: {
    outDir: 'dist',
    sourcemap: false,
    chunkSizeWarningLimit: 900,
  },
  server: {
    host: true,
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://localhost',
        changeOrigin: true,
      },
      '/ai': {
        target: 'http://localhost',
        changeOrigin: true,
      },
    },
  },
});
