import { access, readdir, readFile } from 'node:fs/promises';
import { extname, relative, resolve } from 'node:path';

import { describe, expect, it } from 'vitest';

const applications = [
  {
    accountDomain: 'PLATFORM',
    apiTarget: 'http://localhost:8080/api',
    directory: 'platform-admin',
    namespace: 'payment-platform-admin',
    packageName: '@payment/platform-admin',
    port: '5999',
    trustedLocalHosts: ['platform.localhost'],
  },
  {
    accountDomain: 'MERCHANT',
    apiTarget: 'http://localhost:8082/api',
    directory: 'merchant-admin',
    namespace: 'payment-merchant-admin',
    packageName: '@payment/merchant-admin',
    port: '6002',
    trustedLocalHosts: ['merchant.localhost', 'merchant-e2e.localhost'],
  },
  {
    accountDomain: 'AGENT',
    apiTarget: 'http://localhost:8083/api',
    directory: 'agent-admin',
    namespace: 'payment-agent-admin',
    packageName: '@payment/agent-admin',
    port: '6001',
    trustedLocalHosts: ['agent.localhost'],
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

async function sourceFilesUnder(relativeRoot: string) {
  const root = resolve(process.cwd(), relativeRoot);
  const sourceFiles: string[] = [];

  async function visit(directory: string) {
    for (const entry of await readdir(directory, { withFileTypes: true })) {
      const absolutePath = resolve(directory, entry.name);
      if (entry.isDirectory()) {
        await visit(absolutePath);
      } else if (['.ts', '.tsx', '.vue'].includes(extname(entry.name))) {
        sourceFiles.push(absolutePath);
      }
    }
  }

  await visit(root);
  return sourceFiles.toSorted();
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
      for (const host of application.trustedLocalHosts) {
        expect(viteConfig).toContain(`'${host}'`);
      }
      expect(viteConfig).toContain('changeOrigin: false');
      expect(viteConfig).not.toContain('resolveDeployment');
      expect(main).toContain("from '@payment/backoffice-runtime'");
      expect(main).toContain("from './deployment'");
    }

    expect(await pathExists('apps/web-antdv-next')).toBe(false);
  });

  it('warms the current application entrypoint', async () => {
    const applicationConfig = await workspaceFile(
      'internal/vite-config/src/config/application.ts',
    );

    expect(applicationConfig).toContain("'./src/main.ts'");
    expect(applicationConfig).not.toContain("'./src/bootstrap.ts'");
  });

  it('indexes every active locale root from the repository editor config', async () => {
    const settings = await workspaceFile('../../.vscode/settings.json');

    for (const localeRoot of [
      'frontend/admin/packages/locales/src/langs',
      'frontend/admin/packages/effects/backoffice-runtime/src/locales/langs',
      'frontend/admin/playground/src/locales/langs',
    ]) {
      expect(settings).toContain(`"${localeRoot}"`);
    }
    expect(settings).toContain(
      '"i18n-ally.pathMatcher": "{locale}/{namespace}.{ext}"',
    );
    expect(settings).toContain('"i18n-ally.sourceLanguage": "en-US"');
  });

  it('keeps shared user and role pages plus dictionary lookup infrastructure in the reusable runtime', async () => {
    const manifest = JSON.parse(
      await workspaceFile('packages/effects/backoffice-runtime/package.json'),
    ) as { name: string };

    expect(manifest.name).toBe('@payment/backoffice-runtime');
    expect(
      await pathExists('packages/effects/backoffice-runtime/src/views/system'),
    ).toBe(true);
    expect(
      await pathExists(
        'packages/effects/backoffice-runtime/src/views/system/user/list.vue',
      ),
    ).toBe(true);
    expect(
      await pathExists(
        'packages/effects/backoffice-runtime/src/views/system/role/list.vue',
      ),
    ).toBe(true);
    expect(
      await pathExists(
        'packages/effects/backoffice-runtime/src/views/system/dict/data/list.vue',
      ),
    ).toBe(false);
    expect(
      await pathExists(
        'packages/effects/backoffice-runtime/src/api/system/dictionary-data.ts',
      ),
    ).toBe(true);
    expect(
      await pathExists(
        'packages/effects/backoffice-runtime/src/composables/use-common-status-dictionary.ts',
      ),
    ).toBe(true);
    const commonPages = await workspaceFile(
      'packages/effects/backoffice-runtime/src/common-pages.ts',
    );
    expect(commonPages).not.toContain('READ_ONLY_DICTIONARY_DATA_PAGE_MAP');
    expect(commonPages).not.toContain('./views/system/dict/data/list.vue');
    expect(
      await pathExists(
        'packages/effects/backoffice-runtime/src/views/dashboard/analytics',
      ),
    ).toBe(false);
    expect(
      await pathExists('packages/effects/backoffice-runtime/src/views/demos'),
    ).toBe(false);
  });

  it('uses package self-references for cross-module imports in shared system views', async () => {
    const runtimeRoot = 'packages/effects/backoffice-runtime/src/views/system';
    const deepParentImport = /(?:from\s+|import\s*\()\s*['"](?:\.\.\/){3,}/;
    const violations: string[] = [];

    for (const sourceFile of await sourceFilesUnder(runtimeRoot)) {
      const source = await readFile(sourceFile, 'utf8');
      for (const [index, line] of source.split('\n').entries()) {
        if (deepParentImport.test(line)) {
          violations.push(
            `${relative(resolve(process.cwd(), runtimeRoot), sourceFile)}:${index + 1}`,
          );
        }
      }
    }

    expect(violations).toEqual([]);
  });

  it('uses package self-references instead of application-scoped aliases inside the shared runtime', async () => {
    const runtimeRoot = 'packages/effects/backoffice-runtime/src';
    const runtimeManifest = JSON.parse(
      await workspaceFile('packages/effects/backoffice-runtime/package.json'),
    ) as { exports: Record<string, unknown>; imports?: unknown };
    const runtimeTypeScript = await workspaceFile(
      'packages/effects/backoffice-runtime/tsconfig.json',
    );
    const applicationScopedAlias =
      /(?:from\s+|import\s*\(|vi\.mock\()\s*['"]#\//;
    const unscopedRuntimeReference =
      /(?:from\s+|import\s*\(|vi\.mock\()\s*['"]\/backoffice-runtime/;
    const duplicatedPackageScope = /@payment@payment\/backoffice-runtime/;
    const violations: string[] = [];

    for (const sourceFile of await sourceFilesUnder(runtimeRoot)) {
      const source = await readFile(sourceFile, 'utf8');
      for (const [index, line] of source.split('\n').entries()) {
        if (
          applicationScopedAlias.test(line) ||
          unscopedRuntimeReference.test(line) ||
          duplicatedPackageScope.test(line)
        ) {
          violations.push(
            `${relative(resolve(process.cwd(), runtimeRoot), sourceFile)}:${index + 1}`,
          );
        }
      }
    }

    expect(violations).toEqual([]);
    expect(runtimeManifest).not.toHaveProperty('imports');
    for (const systemApiExport of [
      './api/system/dept',
      './api/system/dictionary-data',
      './api/system/dictionary-type',
      './api/system/menu',
      './api/system/role',
      './api/system/role-grant',
      './api/system/types',
      './api/system/user',
    ]) {
      expect(runtimeManifest.exports).toHaveProperty(systemApiExport);
    }
    expect(runtimeManifest.exports).not.toHaveProperty('./api/system/*');
    expect(runtimeTypeScript).not.toContain('"#/*"');
  });

  it('keeps only platform-specific administration views in platform-admin', async () => {
    for (const path of [
      'src/views/dashboard/analytics/index.vue',
      'src/views/demos/antd/index.vue',
      'src/views/system/dept/list.vue',
      'src/views/system/menu/list.vue',
      'src/views/system/dict/list.vue',
      'src/views/system/dict/data/list.vue',
    ]) {
      expect(await pathExists(`apps/platform-admin/${path}`)).toBe(true);
      expect(await pathExists(`apps/merchant-admin/${path}`)).toBe(false);
      expect(await pathExists(`apps/agent-admin/${path}`)).toBe(false);
    }

    for (const path of [
      'src/views/system/role/list.vue',
      'src/views/system/user/list.vue',
    ]) {
      for (const application of applications) {
        expect(await pathExists(`apps/${application.directory}/${path}`)).toBe(
          false,
        );
      }
    }

    for (const application of applications.filter(
      ({ accountDomain }) => accountDomain !== 'PLATFORM',
    )) {
      expect(
        await pathExists(
          `apps/${application.directory}/src/views/system/dict/data/list.vue`,
        ),
      ).toBe(false);
    }
  });

  it('removes legacy identity navigation and exposes dictionary pages only to PLATFORM', async () => {
    for (const application of applications) {
      expect(
        await pathExists(
          `apps/${application.directory}/src/views/identity/members/index.vue`,
        ),
      ).toBe(false);
      const deployment = await workspaceFile(
        `apps/${application.directory}/src/deployment.ts`,
      );
      expect(deployment).not.toContain('/identity/members');
      expect(deployment).not.toContain('/identity/tenant-bootstrap');
      expect(deployment).toContain("'/system/user/list'");
      expect(deployment).toContain("'/system/role/list'");
      expect(deployment).not.toContain("'SystemDictionaryData'");
      expect(deployment).not.toContain('/system/dict/data/type/:dictType');

      const isPlatform = application.accountDomain === 'PLATFORM';
      for (const platformOnlyFragment of [
        "'/system/dict/list'",
        "'/system/dict/data/list'",
        "'SystemDictionaryDataIndex'",
        "'/system/dict/data'",
      ]) {
        expect(deployment.includes(platformOnlyFragment)).toBe(isPlatform);
      }
      expect(
        isPlatform ||
          !deployment.includes('READ_ONLY_DICTIONARY_DATA_PAGE_MAP'),
      ).toBe(true);
    }

    for (const application of applications) {
      expect(
        await pathExists(
          `apps/${application.directory}/src/views/identity/tenant-bootstrap/index.vue`,
        ),
      ).toBe(false);
    }
  });
});
