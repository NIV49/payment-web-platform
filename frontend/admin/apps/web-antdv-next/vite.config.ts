import { defineConfig } from '@vben/vite-config';

export default defineConfig(async () => {
  return {
    application: {},
    vite: {
      server: {
        host: '127.0.0.1',
        proxy: {
          '/api': {
            changeOrigin: true,
            rewrite: (path) => path.replace(/^\/api/, ''),
            // 本地 Spring Boot 后端地址
            target: 'http://localhost:8080/api',
            ws: true,
          },
        },
      },
    },
  };
});
