import { access, readFile } from 'node:fs/promises';
import { resolve } from 'node:path';

import { describe, expect, it } from 'vitest';

const applications = [
  {
    accountDomain: 'PLATFORM',
    apiTarget: 'http://localhost:8080/api',
    directory: 'platform-admin',
    namespace: 'payment-platform-admin',
    packageName: '@payment/platform-admin',
    port: '5999',
  },
  {
    accountDomain: 'MERCHANT',
    apiTarget: 'http://localhost:8082/api',
    directory: 'merchant-admin',
    namespace: 'payment-merchant-admin',
    packageName: '@payment/merchant-admin',
    port: '6002',
  },
  {
    accountDomain: 'AGENT',
    apiTarget: 'http://localhost:8083/api',
    directory: 'agent-admin',
    namespace: 'payment-agent-admin',
    packageName: '@payment/agent-admin',
    port: '6001',
  },
] as const;

async function workspaceFile(relativePath: string) {
  return readFile(resolve(process.cwd(), relativePath), 'utf8');
}

async function pathExists(relativePath: string) {
  try {
    await access(resolve(process.cwd(), relativePath));
    return true;
  } catch {
    return false;
  }
}

describe('independent backoffice application topology', () => {
  it('uses three independently addressable application packages', async () => {
    for (const application of applications) {
      const root = `apps/${application.directory}`;
      const manifest = JSON.parse(
        await workspaceFile(`${root}/package.json`),
      ) as { name: string; scripts: Record<string, string> };
      const environment = await workspaceFile(`${root}/.env.production`);
      const viteConfig = await workspaceFile(`${root}/vite.config.ts`);
      const main = await workspaceFile(`${root}/src/main.ts`);

      expect(manifest.name).toBe(application.packageName);
      expect(manifest.scripts.build).toBe('vite build --mode production');
      expect(manifest.scripts.dev).toContain(`--port ${application.port}`);
      expect(manifest.scripts.dev).toContain('--strictPort');
      expect(manifest.scripts).not.toHaveProperty('build:all');
      expect(environment).toContain(
        `VITE_ACCOUNT_DOMAIN=${application.accountDomain}`,
      );
      expect(environment).toContain(
        `VITE_APP_NAMESPACE=${application.namespace}`,
      );
      expect(environment).toMatch(/^VITE_GLOB_API_URL=\/api$/m);
      expect(environment).toMatch(/^VITE_ROUTER_HISTORY=history$/m);
      expect(viteConfig).toContain(`'${application.apiTarget}'`);
      expect(viteConfig).not.toContain('resolveDeployment');
      expect(main).toContain("from '@payment/backoffice-runtime'");
      expect(main).toContain("from './deployment'");
    }

    expect(await pathExists('apps/web-antdv-next')).toBe(false);
  });

  it('keeps reusable runtime code outside deployable applications', async () => {
    const manifest = JSON.parse(
      await workspaceFile('packages/effects/backoffice-runtime/package.json'),
    ) as { name: string };

    expect(manifest.name).toBe('@payment/backoffice-runtime');
    expect(
      await pathExists('packages/effects/backoffice-runtime/src/views/system'),
    ).toBe(false);
    expect(
      await pathExists(
        'packages/effects/backoffice-runtime/src/views/dashboard/analytics',
      ),
    ).toBe(false);
    expect(
      await pathExists('packages/effects/backoffice-runtime/src/views/demos'),
    ).toBe(false);
  });

  it('keeps platform-only administration views in platform-admin', async () => {
    for (const path of [
      'src/views/dashboard/analytics/index.vue',
      'src/views/demos/antd/index.vue',
      'src/views/system/dept/list.vue',
      'src/views/system/menu/list.vue',
      'src/views/system/role/list.vue',
      'src/views/system/user/list.vue',
    ]) {
      expect(await pathExists(`apps/platform-admin/${path}`)).toBe(true);
      expect(await pathExists(`apps/merchant-admin/${path}`)).toBe(false);
      expect(await pathExists(`apps/agent-admin/${path}`)).toBe(false);
    }
  });
});
