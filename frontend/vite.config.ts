/// <reference types="vitest/config" />
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// 建置結果由 Maven 複製到 JAR 的 static/；所有資源都打包在本地，執行時不連外部網址
export default defineConfig({
  plugins: [vue()],
  base: '/',
  define: {
    __VUE_I18N_FULL_INSTALL__: true,
    __VUE_I18N_LEGACY_API__: false,
    __INTLIFY_PROD_DEVTOOLS__: false,
  },
  build: {
    outDir: 'dist',
    emptyOutDir: true,
    assetsDir: 'assets',
    chunkSizeWarningLimit: 2000,
  },
  server: {
    port: 5173,
    // 開發時把 API 轉給本機的 Jetty（java -jar target/simplesnmp-1.0.0.jar）
    proxy: { '/api': 'http://localhost:8080' },
  },
  test: {
    environment: 'jsdom',
    include: ['src/**/*.test.ts', 'scripts/**/*.test.ts'],
  },
})
