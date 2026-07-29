import { readFile } from 'node:fs/promises';
import { resolve } from 'node:path';

import { describe, expect, it } from 'vitest';

async function readWorkspaceFile(relativePath: string) {
  return readFile(resolve(process.cwd(), relativePath), 'utf8');
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

  it('uses the same-origin API path in production', async () => {
    const productionEnvironment = await readWorkspaceFile(
      'apps/web-antdv-next/.env.production',
    );

    expect(productionEnvironment).toMatch(/^VITE_GLOB_API_URL=\/api$/m);
    expect(productionEnvironment).not.toContain('mock-napi.vben.pro');
  });

  it('does not load the Baidu analytics script', async () => {
    const productEntry = await readWorkspaceFile(
      'apps/web-antdv-next/index.html',
    );

    expect(productEntry).not.toContain('hm.baidu.com');
  });

  it('builds and deploys the product application', async () => {
    const dockerfile = await readWorkspaceFile('scripts/deploy/Dockerfile');

    expect(dockerfile).toContain(
      'FROM node:24.16.0-bookworm-slim@sha256:2c87ef9bd3c6a3bd4b472b4bec2ce9d16354b0c574f736c476489d09f560a203 AS builder',
    );
    expect(dockerfile).toContain('RUN corepack enable');
    expect(dockerfile).not.toContain('npm i -g corepack');
    expect(dockerfile).toContain('RUN pnpm run build:antdv-next');
    expect(dockerfile).toContain(
      'FROM nginx:1.30.4-alpine@sha256:97d490c12ba55b4946b01546d1c3ed324e8d41ab1c9fcb2a616aa470620e5b46 AS production',
    );
    expect(dockerfile).toContain(
      'COPY --from=builder /app/apps/web-antdv-next/dist /usr/share/nginx/html',
    );
    expect(dockerfile).not.toContain('playground/dist');
  });
});
