import { execFile } from 'node:child_process';
import { mkdir, mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { promisify } from 'node:util';

import { describe, expect, it } from 'vitest';

import { resolveDeployment } from '../../apps/web-antdv-next/deployment-config';

const execFileAsync = promisify(execFile);

const artifactPolicies = {
  agent: {
    namespace: 'payment-agent-admin',
    title: 'Payment Agent Admin',
    views: ['src/views/dashboard/workspace/index.vue'],
  },
  merchant: {
    namespace: 'payment-merchant-admin',
    title: 'Payment Merchant Admin',
    views: ['src/views/dashboard/workspace/index.vue'],
  },
  platform: {
    namespace: 'payment-platform-admin',
    title: 'Payment Operations',
    views: [
      'src/views/dashboard/analytics/index.vue',
      'src/views/dashboard/workspace/index.vue',
      'src/views/demos/antd/index.vue',
      'src/views/system/dept/list.vue',
      'src/views/system/menu/list.vue',
      'src/views/system/role/list.vue',
      'src/views/system/user/list.vue',
    ],
  },
} as const;

const commonArtifactViews = [
  'src/views/_core/fallback/forbidden.vue',
  'src/views/_core/fallback/not-found.vue',
  'src/views/_core/profile/index.vue',
];

async function readWorkspaceFile(relativePath: string) {
  return readFile(resolve(process.cwd(), relativePath), 'utf8');
}

async function createArtifactFixture(root: string) {
  for (const [variant, policy] of Object.entries(artifactPolicies)) {
    const directory = join(root, variant);
    const assetDirectory = join(directory, 'assets');
    const manifestDirectory = join(directory, '.vite');
    await mkdir(assetDirectory, { recursive: true });
    await mkdir(manifestDirectory, { recursive: true });
    const manifest: Record<string, unknown> = {
      'src/main.ts': {
        css: ['assets/main.css'],
        file: 'assets/main.js',
        isEntry: true,
      },
    };
    for (const [index, view] of [
      ...commonArtifactViews,
      ...policy.views,
    ].entries()) {
      const file = `assets/view-${index}.js`;
      manifest[view] = { file };
      await writeFile(join(directory, file), 'export {};\n');
    }
    await writeFile(
      join(manifestDirectory, 'manifest.json'),
      `${JSON.stringify(manifest)}\n`,
    );
    await writeFile(
      join(directory, 'index.html'),
      `<title>${policy.title}</title><script src="/_app-config.js"></script><script src="/assets/main.js"></script>`,
    );
    await writeFile(
      join(directory, '_app-config.js'),
      'globalThis.appConfig={};\n',
    );
    await writeFile(
      join(assetDirectory, 'main.js'),
      `const namespace='${policy.namespace}'; const api='/api';\n`,
    );
    await writeFile(join(assetDirectory, 'main.css'), 'body {}\n');
  }
}

describe('web-antdv-next production safety', () => {
  it('does not execute remote package runners from lifecycle scripts', async () => {
    const packageManifest = JSON.parse(
      await readWorkspaceFile('package.json'),
    ) as { scripts?: Record<string, string> };
    const lifecycleScriptNames = new Set([
      'dependencies',
      'install',
      'pack',
      'postinstall',
      'postpack',
      'postprepare',
      'postpublish',
      'postrestart',
      'poststart',
      'poststop',
      'posttest',
      'postversion',
      'preinstall',
      'prepack',
      'prepare',
      'preprepare',
      'prepublish',
      'prepublishOnly',
      'prerestart',
      'prestart',
      'prestop',
      'pretest',
      'preversion',
      'publish',
      'restart',
      'start',
      'stop',
      'test',
      'version',
    ]);
    const lifecycleScripts = Object.entries(
      packageManifest.scripts ?? {},
    ).filter(([name]) => lifecycleScriptNames.has(name));

    expect(lifecycleScripts.length).toBeGreaterThan(0);
    for (const [name, command] of lifecycleScripts) {
      expect(command, `${name} must use lockfile-governed tooling`).not.toMatch(
        /(?:^|\s)(?:npx|pnpm\s+dlx)(?:\s|$)/,
      );
    }
  });

  it.each(['agent', 'merchant', 'platform'])(
    'uses an isolated namespace and same-origin API for %s',
    async (variant) => {
      const environment = await readWorkspaceFile(
        `apps/web-antdv-next/.env.${variant}`,
      );

      expect(environment).toMatch(/^VITE_GLOB_API_URL=\/api$/m);
      expect(environment).toMatch(/^VITE_ARCHIVER=false$/m);
      expect(environment).toContain(
        `VITE_ACCOUNT_DOMAIN=${variant.toUpperCase()}`,
      );
      expect(environment).not.toContain('mock-napi.vben.pro');
    },
  );

  it('keeps local backoffice variants on their matching API roots', async () => {
    const viteConfig = await readWorkspaceFile(
      'apps/web-antdv-next/vite.config.ts',
    );

    expect(viteConfig).toContain("agent: 'http://localhost:8083/api'");
    expect(viteConfig).toContain("merchant: 'http://localhost:8082/api'");
    expect(viteConfig).toContain("platform: 'http://localhost:8080/api'");
    expect(viteConfig).toContain('env.PAYMENT_BACKOFFICE_DEV_API_TARGET');
    expect(viteConfig).toContain('target: developmentApiTarget');
    expect(viteConfig).toContain('emptyOutDir: true');
    expect(viteConfig).toMatch(/outDir: `dist\/\$\{mode\}`/);
    expect(viteConfig).toContain('const deployment = resolveDeployment(mode);');
    expect(viteConfig).toMatch(
      /new URL\(`src\/deployments\/\$\{deployment\}\.ts`, import\.meta\.url\)/,
    );
  });

  it('rejects an unreferenced stale chunk without deleting sibling variants', async () => {
    const root = await mkdtemp(join(tmpdir(), 'iam001-artifacts-'));
    try {
      await createArtifactFixture(root);
      const verifier = resolve(
        process.cwd(),
        'scripts/deploy/verify-three-artifacts.mjs',
      );
      await execFileAsync(process.execPath, [verifier, root]);

      const siblingMarker = join(root, 'agent/assets/sibling-marker.txt');
      await writeFile(siblingMarker, 'preserve sibling\n');
      await writeFile(
        join(root, 'merchant/assets/platform-only-stale.js'),
        'export const forbidden = true;\n',
      );

      await expect(
        execFileAsync(process.execPath, [verifier, root]),
      ).rejects.toThrow(/unreferenced JavaScript or CSS/i);
      await expect(readFile(siblingMarker, 'utf8')).resolves.toBe(
        'preserve sibling\n',
      );
    } finally {
      await rm(root, { force: true, recursive: true });
    }
  });

  it('cleans only the selected variant output during a single-variant build', async () => {
    const root = await mkdtemp(join(tmpdir(), 'iam001-vite-build-'));
    try {
      const selectedOutput = join(root, 'dist/merchant');
      const selectedStaleAsset = join(selectedOutput, 'stale-merchant.js');
      const siblingMarker = join(root, 'dist/agent/sibling-marker.txt');
      await mkdir(selectedOutput, { recursive: true });
      await mkdir(join(root, 'dist/agent'), { recursive: true });
      await writeFile(
        join(root, 'index.html'),
        '<script type="module" src="/main.js"></script>',
      );
      await writeFile(join(root, 'main.js'), 'export const ready = true;\n');
      await writeFile(selectedStaleAsset, 'export const stale = true;\n');
      await writeFile(siblingMarker, 'preserve sibling\n');

      const vite = resolve(process.cwd(), 'node_modules/vite/bin/vite.js');
      await execFileAsync(
        process.execPath,
        [vite, 'build', '--outDir', 'dist/merchant', '--emptyOutDir'],
        { cwd: root },
      );

      await expect(readFile(selectedStaleAsset, 'utf8')).rejects.toThrow(
        /ENOENT/,
      );
      await expect(readFile(siblingMarker, 'utf8')).resolves.toBe(
        'preserve sibling\n',
      );
    } finally {
      await rm(root, { force: true, recursive: true });
    }
  });

  it.each([
    ['agent', 'agent'],
    ['merchant', 'merchant'],
    ['platform', 'platform'],
    ['development', 'platform'],
    ['production', 'platform'],
  ] as const)(
    'maps Vite mode %s to the %s deployment policy',
    (mode, expected) => {
      expect(resolveDeployment(mode)).toBe(expected);
    },
  );

  it('uses the approved local frontend ports for all three backoffices', async () => {
    const appManifest = JSON.parse(
      await readWorkspaceFile('apps/web-antdv-next/package.json'),
    ) as { scripts: Record<string, string> };

    expect(appManifest.scripts['dev:platform']).toContain('--port 5999');
    expect(appManifest.scripts['dev:merchant']).toContain('--port 6002');
    expect(appManifest.scripts['dev:agent']).toContain('--port 6001');
    expect(appManifest.scripts['dev:platform']).toContain('--strictPort');
    expect(appManifest.scripts['dev:merchant']).toContain('--strictPort');
    expect(appManifest.scripts['dev:agent']).toContain('--strictPort');
    expect(appManifest.scripts['preview:platform']).toBe(
      'vite preview --mode platform --outDir dist/platform --port 5999 --strictPort',
    );
    expect(appManifest.scripts['preview:merchant']).toBe(
      'vite preview --mode merchant --outDir dist/merchant --port 6002 --strictPort',
    );
    expect(appManifest.scripts['preview:agent']).toBe(
      'vite preview --mode agent --outDir dist/agent --port 6001 --strictPort',
    );
  });

  it('does not load the Baidu analytics script', async () => {
    const productEntry = await readWorkspaceFile(
      'apps/web-antdv-next/index.html',
    );

    expect(productEntry).not.toContain('hm.baidu.com');
  });

  it('keeps local login credentials behind a compile-time development branch', async () => {
    const loginView = await readWorkspaceFile(
      'apps/web-antdv-next/src/views/_core/authentication/login.vue',
    );
    const loginDefaults = await readWorkspaceFile(
      'apps/web-antdv-next/src/views/_core/authentication/login-defaults.ts',
    );
    const requestClient = await readWorkspaceFile(
      'apps/web-antdv-next/src/api/request.ts',
    );
    const thirdPartyLogin = await readWorkspaceFile(
      'packages/effects/common-ui/src/ui/authentication/third-party-login.vue',
    );
    const coreRoutes = await readWorkspaceFile(
      'apps/web-antdv-next/src/router/routes/core.ts',
    );
    const commonLogin = await readWorkspaceFile(
      'packages/effects/common-ui/src/ui/authentication/login.vue',
    );

    expect(loginView).toContain('if (import.meta.env.DEV)');
    expect(loginView).not.toContain('resolveLoginDefaults();');
    expect(loginDefaults).not.toContain('import.meta.env');
    expect(requestClient).not.toContain(
      'useAppConfig(import.meta.env, import.meta.env.PROD)',
    );
    expect(thirdPartyLogin).not.toContain(
      'useAppConfig(import.meta.env, import.meta.env.PROD)',
    );
    expect(coreRoutes).not.toMatch(
      /path: '(?:code-login|forget-password|qrcode-login|register)'/,
    );
    expect(loginView).toContain(':show-code-login="false"');
    expect(loginView).toContain([':show-forget-pass', 'word="false"'].join(''));
    expect(loginView).toContain(':show-qrcode-login="false"');
    expect(loginView).toContain(':show-register="false"');
    expect(loginView).toContain(':show-third-party-login="false"');
    expect(commonLogin).toContain('props.rememberMeNamespace');
    expect(commonLogin).toContain('location.host');
  });

  it('builds and deploys the product application', async () => {
    const dockerfile = await readWorkspaceFile('scripts/deploy/Dockerfile');
    const artifactVariable = `\${APP_VARIANT}`;

    expect(dockerfile).toContain(
      'FROM node:24.16.0-bookworm-slim@sha256:2c87ef9bd3c6a3bd4b472b4bec2ce9d16354b0c574f736c476489d09f560a203 AS builder',
    );
    expect(dockerfile).toContain('RUN corepack enable');
    expect(dockerfile).not.toContain('npm i -g corepack');
    expect(dockerfile).toContain('ARG APP_VARIANT=platform');
    expect(dockerfile).toContain('platform|merchant|agent');
    expect(dockerfile).toContain(
      'FROM nginx:1.30.4-alpine@sha256:97d490c12ba55b4946b01546d1c3ed324e8d41ab1c9fcb2a616aa470620e5b46 AS production',
    );
    expect(dockerfile).toContain(
      `COPY --from=builder /app/apps/web-antdv-next/dist/${artifactVariable} /usr/share/nginx/html`,
    );
    expect(dockerfile).not.toContain('playground/dist');
  });
});
