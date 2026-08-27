import { env } from 'node:process';

import { defineConfig } from '@vben/vite-config';

const DEFAULT_API_TARGET = 'http://localhost:8080/api';

export default defineConfig(async ({ command }) => ({
  application: {},
  vite: {
    build:
      command === 'build'
        ? {
            emptyOutDir: true,
            manifest: true,
            outDir: 'dist',
          }
        : undefined,
    server: {
      allowedHosts: ['platform.localhost'],
      host: '127.0.0.1',
      proxy: {
        '/api': {
          changeOrigin: false,
          rewrite: (path) => path.replace(/^\/api/, ''),
          target:
            env.PAYMENT_PLATFORM_ADMIN_DEV_API_TARGET ?? DEFAULT_API_TARGET,
          ws: true,
        },
      },
    },
  },
}));
