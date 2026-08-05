import { execFile } from 'node:child_process';
import { mkdir, mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { promisify } from 'node:util';

import { describe, expect, it } from 'vitest';

const execFileAsync = promisify(execFile);

const artifactPolicies = {
  agent: {
    directory: 'agent-admin',
    namespace: 'payment-agent-admin',
    title: 'Payment Agent Admin',
    views: ['src/views/dashboard/workspace/index.vue'],
  },
  merchant: {
    directory: 'merchant-admin',
    namespace: 'payment-merchant-admin',
    title: 'Payment Merchant Admin',
    views: ['src/views/dashboard/workspace/index.vue'],
  },
  platform: {
    directory: 'platform-admin',
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

function artifactDirectory(root: string, directory: string) {
  return join(root, 'apps', directory, 'dist');
}

async function createArtifactFixture(root: string) {
  for (const policy of Object.values(artifactPolicies)) {
    const directory = artifactDirectory(root, policy.directory);
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

describe('independent backoffice production safety', () => {
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

  it.each(Object.values(artifactPolicies))(
    'uses an isolated namespace and same-origin API for $directory',
    async (policy) => {
      const environment = await readWorkspaceFile(
        `apps/${policy.directory}/.env.production`,
      );

      expect(environment).toMatch(/^VITE_GLOB_API_URL=\/api$/m);
      expect(environment).toMatch(/^VITE_ARCHIVER=false$/m);
      expect(environment).not.toContain('mock-napi.vben.pro');
    },
  );

  it('rejects an unreferenced stale chunk in one application artifact', async () => {
    const root = await mkdtemp(join(tmpdir(), 'iam002-artifacts-'));
    try {
      await createArtifactFixture(root);
      const verifier = resolve(
        process.cwd(),
        'scripts/deploy/verify-three-artifacts.mjs',
      );
      await execFileAsync(process.execPath, [verifier, root]);

      const agentMarker = join(
        artifactDirectory(root, 'agent-admin'),
        'assets/sibling-marker.txt',
      );
      await writeFile(agentMarker, 'preserve sibling\n');
      await writeFile(
        join(
          artifactDirectory(root, 'merchant-admin'),
          'assets/platform-only-stale.js',
        ),
        'export const forbidden = true;\n',
      );

      await expect(
        execFileAsync(process.execPath, [verifier, root]),
      ).rejects.toThrow(/unreferenced JavaScript or CSS/i);
      await expect(readFile(agentMarker, 'utf8')).resolves.toBe(
        'preserve sibling\n',
      );
    } finally {
      await rm(root, { force: true, recursive: true });
    }
  });

  it('cleans only the selected application output during its build', async () => {
    const root = await mkdtemp(join(tmpdir(), 'iam002-vite-build-'));
    try {
      const merchantRoot = join(root, 'apps/merchant-admin');
      const selectedOutput = join(merchantRoot, 'dist');
      const selectedStaleAsset = join(selectedOutput, 'stale-merchant.js');
      const siblingMarker = join(root, 'apps/agent-admin/dist/sibling.txt');
      await mkdir(selectedOutput, { recursive: true });
      await mkdir(join(root, 'apps/agent-admin/dist'), { recursive: true });
      await writeFile(
        join(merchantRoot, 'index.html'),
        '<script type="module" src="/main.js"></script>',
      );
      await writeFile(
        join(merchantRoot, 'main.js'),
        'export const ready = true;\n',
      );
      await writeFile(selectedStaleAsset, 'export const stale = true;\n');
      await writeFile(siblingMarker, 'preserve sibling\n');

      const vite = resolve(process.cwd(), 'node_modules/vite/bin/vite.js');
      await execFileAsync(
        process.execPath,
        [vite, 'build', '--outDir', 'dist', '--emptyOutDir'],
        { cwd: merchantRoot },
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

  it('does not load the Baidu analytics script in any application', async () => {
    for (const policy of Object.values(artifactPolicies)) {
      const productEntry = await readWorkspaceFile(
        `apps/${policy.directory}/index.html`,
      );
      expect(productEntry).not.toContain('hm.baidu.com');
    }
  });

  it('keeps local login credentials behind a compile-time development branch', async () => {
    const loginView = await readWorkspaceFile(
      'packages/effects/backoffice-runtime/src/views/_core/authentication/login.vue',
    );
    const loginDefaults = await readWorkspaceFile(
      'packages/effects/backoffice-runtime/src/views/_core/authentication/login-defaults.ts',
    );
    const requestClient = await readWorkspaceFile(
      'packages/effects/backoffice-runtime/src/api/request.ts',
    );
    const coreRoutes = await readWorkspaceFile(
      'packages/effects/backoffice-runtime/src/router/routes/core.ts',
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

  it('builds only one allowlisted application into each image', async () => {
    const dockerfile = await readWorkspaceFile('scripts/deploy/Dockerfile');
    const applicationVariable = `\${APP_NAME}`;

    expect(dockerfile).toContain(
      'FROM node:24.16.0-bookworm-slim@sha256:2c87ef9bd3c6a3bd4b472b4bec2ce9d16354b0c574f736c476489d09f560a203 AS builder',
    );
    expect(dockerfile).toContain('RUN corepack enable');
    expect(dockerfile).not.toContain('npm i -g corepack');
    expect(dockerfile).toContain('ARG APP_NAME=platform-admin');
    expect(dockerfile).toContain('platform-admin|merchant-admin|agent-admin');
    expect(dockerfile).toContain(
      'FROM nginx:1.30.4-alpine@sha256:97d490c12ba55b4946b01546d1c3ed324e8d41ab1c9fcb2a616aa470620e5b46 AS production',
    );
    expect(dockerfile).toContain(
      `pnpm --filter "@payment/${applicationVariable}" run build`,
    );
    expect(dockerfile).toContain(
      `COPY --from=builder /app/apps/${applicationVariable}/dist /usr/share/nginx/html`,
    );
    expect(dockerfile).not.toContain('playground/dist');
  });
});
