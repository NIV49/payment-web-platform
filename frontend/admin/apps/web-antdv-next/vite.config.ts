import type { BuildVariant } from './deployment-config';

import { env } from 'node:process';
import { fileURLToPath } from 'node:url';

import { defineConfig } from '@vben/vite-config';

import { isBuildVariant, resolveDeployment } from './deployment-config';

const API_TARGETS: Record<BuildVariant, string> = {
  agent: 'http://localhost:8083/api',
  merchant: 'http://localhost:8082/api',
  platform: 'http://localhost:8080/api',
};

export default defineConfig(async ({ command, mode }) => {
  if (command === 'build' && !isBuildVariant(mode)) {
    throw new Error(`Unsupported backoffice build mode: ${mode}`);
  }

  const deployment = resolveDeployment(mode);
  const developmentApiTarget =
    env.PAYMENT_BACKOFFICE_DEV_API_TARGET ?? API_TARGETS[deployment];

  return {
    application: {},
    vite: {
      build:
        command === 'build'
          ? {
              emptyOutDir: true,
              manifest: true,
              outDir: `dist/${mode}`,
            }
          : undefined,
      resolve: {
        alias: {
          '#/deployment-policy': fileURLToPath(
            new URL(`src/deployments/${deployment}.ts`, import.meta.url),
          ),
        },
      },
      server: {
        host: '127.0.0.1',
        proxy: {
          '/api': {
            changeOrigin: true,
            rewrite: (path) => path.replace(/^\/api/, ''),
            // 本地 Spring Boot 后端地址
            target: developmentApiTarget,
            ws: true,
          },
        },
      },
    },
  };
});
