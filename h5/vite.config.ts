import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import { VitePWA } from 'vite-plugin-pwa';

// 智汇于庄 H5 · C 端单页
//
// - base: '/h5/'：产物静态资源前缀与网关路由 /h5/ 对齐（网关将 /h5/ 反代到 h5 容器）。
// - server.proxy：本地 dev 直接转发到宿主网关（localhost:80），与线上同源路径一致
//   （/api/v1/* -> backend，/ai/v1/* -> ai-service）。
export default defineConfig({
  base: '/h5/',
  plugins: [
    react(),
    VitePWA({
      registerType: 'autoUpdate',
      includeAssets: ['favicon.svg', 'pwa-icon.svg'],
      manifest: {
        name: '智汇于庄 · 助农优选', short_name: '智汇于庄',
        description: '于庄合作社农产品直供与农技问答',
        theme_color: '#17603a', background_color: '#f4f6ef', display: 'standalone',
        start_url: '/h5/', scope: '/h5/', lang: 'zh-CN',
        icons: [{ src: '/h5/pwa-icon.svg', sizes: 'any', type: 'image/svg+xml', purpose: 'any maskable' }],
      },
      workbox: {
        navigateFallback: '/h5/index.html',
        globPatterns: ['**/*.{js,css,html,svg,woff2}'],
        runtimeCaching: [{
          urlPattern: /\/api\/v1\/products(?:\/.*)?$/,
          handler: 'NetworkFirst',
          options: { cacheName: 'yuzhuang-products', networkTimeoutSeconds: 4, expiration: { maxEntries: 20, maxAgeSeconds: 86400 } },
        }],
      },
    }),
  ],
  build: {
    outDir: 'dist',
    sourcemap: false,
    chunkSizeWarningLimit: 900,
    rollupOptions: { output: { manualChunks: { react: ['react', 'react-dom'], data: ['axios', '@tanstack/react-query'] } } },
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
