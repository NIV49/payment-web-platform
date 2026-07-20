import { readFile } from 'node:fs/promises';
import { resolve } from 'node:path';

import { describe, expect, it } from 'vitest';

async function readWorkspaceFile(relativePath: string) {
  return readFile(resolve(process.cwd(), relativePath), 'utf8');
}

describe('web-antdv-next production safety', () => {
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

    expect(dockerfile).toContain('RUN pnpm run build:antdv-next');
    expect(dockerfile).toContain(
      'COPY --from=builder /app/apps/web-antdv-next/dist /usr/share/nginx/html',
    );
    expect(dockerfile).not.toContain('playground/dist');
  });
});
