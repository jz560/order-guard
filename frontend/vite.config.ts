import { defineConfig } from 'vitest/config'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  server: {
    proxy: {
      '/health/backend': {
        target: process.env.BACKEND_PROXY_URL ?? 'http://127.0.0.1:8080',
        rewrite: () => '/actuator/health',
      },
      '/health/simulator': {
        target: process.env.SIMULATOR_PROXY_URL ?? 'http://127.0.0.1:8081',
        rewrite: () => '/actuator/health',
      },
    },
  },
  test: { environment: 'jsdom', clearMocks: true },
})
