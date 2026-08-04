import { readdir, readFile } from 'node:fs/promises';
import { join, relative, resolve, sep } from 'node:path';

const root = resolve(process.argv[2] ?? 'apps/web-antdv-next/dist');
const commonViews = [
  'src/views/_core/fallback/forbidden.vue',
  'src/views/_core/fallback/not-found.vue',
  'src/views/_core/profile/index.vue',
];
const deployments = {
  agent: {
    namespace: 'payment-agent-admin',
    title: 'Payment Agent Admin',
    views: [...commonViews, 'src/views/dashboard/workspace/index.vue'],
  },
  merchant: {
    namespace: 'payment-merchant-admin',
    title: 'Payment Merchant Admin',
    views: [...commonViews, 'src/views/dashboard/workspace/index.vue'],
  },
  platform: {
    namespace: 'payment-platform-admin',
    title: 'Payment Operations',
    views: [
      ...commonViews,
      'src/views/dashboard/analytics/index.vue',
      'src/views/dashboard/workspace/index.vue',
      'src/views/demos/antd/index.vue',
      'src/views/system/dept/list.vue',
      'src/views/system/menu/list.vue',
      'src/views/system/role/list.vue',
      'src/views/system/user/list.vue',
    ],
  },
};

async function files(directory) {
  const result = [];
  for (const entry of await readdir(directory, { withFileTypes: true })) {
    const path = join(directory, entry.name);
    if (entry.isDirectory()) result.push(...(await files(path)));
    else result.push(path);
  }
  return result;
}

function artifactPath(directory, path) {
  return relative(directory, path).split(sep).join('/');
}

function referencedCodeAssets(manifestEntries) {
  const referenced = new Set();
  const add = (path) => {
    if (typeof path === 'string' && /\.(?:css|js)$/.test(path)) {
      referenced.add(path.replace(/^\.\//, ''));
    }
  };
  for (const entry of Object.values(manifestEntries)) {
    if (entry === null || typeof entry !== 'object' || Array.isArray(entry)) {
      throw new Error('Vite manifest contains a malformed entry');
    }
    add(entry.file);
    if (entry.css !== undefined && !Array.isArray(entry.css)) {
      throw new Error('Vite manifest contains a malformed CSS asset list');
    }
    for (const css of entry.css ?? []) add(css);
  }
  return referenced;
}

function referencedHtmlCodeAssets(html) {
  const referenced = new Set();
  const attributePattern =
    /\b(?:href|src)\s*=\s*["']([^"']+\.(?:css|js)(?:[?#][^"']*)?)["']/g;
  for (const [, value] of html.matchAll(attributePattern)) {
    if (/^(?:[a-z][a-z\d+.-]*:|\/\/)/i.test(value)) continue;
    referenced.add(value.split(/[?#]/, 1)[0].replace(/^\.?\//, ''));
  }
  return referenced;
}

const rootEntries = await readdir(root, { withFileTypes: true });
const actual = rootEntries
  .filter((entry) => entry.isDirectory())
  .map((entry) => entry.name)
  .toSorted();
const expected = Object.keys(deployments).toSorted();
if (JSON.stringify(actual) !== JSON.stringify(expected)) {
  throw new Error(
    `Expected exactly ${expected.join(', ')}, received ${actual.join(', ')}`,
  );
}

for (const [variant, policy] of Object.entries(deployments)) {
  const directory = join(root, variant);
  const paths = await files(directory);
  const manifest = paths.find((path) => path.endsWith('/.vite/manifest.json'));
  if (!manifest) throw new Error(`${variant} artifact has no Vite manifest`);
  const manifestEntries = JSON.parse(await readFile(manifest, 'utf8'));
  const actualViews = Object.keys(manifestEntries)
    .filter((path) => path.startsWith('src/views/') && path.endsWith('.vue'))
    .toSorted();
  const expectedViews = policy.views.toSorted();
  if (JSON.stringify(actualViews) !== JSON.stringify(expectedViews)) {
    throw new Error(
      `${variant} artifact view boundary mismatch: expected ${expectedViews.join(', ')}, received ${actualViews.join(', ')}`,
    );
  }

  const index = await readFile(join(directory, 'index.html'), 'utf8');
  const referencedAssets = referencedCodeAssets(manifestEntries);
  for (const asset of referencedHtmlCodeAssets(index)) {
    referencedAssets.add(asset);
  }
  const actualAssets = new Set(
    paths
      .filter((path) => /\.(?:css|js)$/.test(path))
      .map((path) => artifactPath(directory, path)),
  );
  const missingAssets = [...referencedAssets]
    .filter((path) => !actualAssets.has(path))
    .toSorted((left, right) => left.localeCompare(right));
  if (missingAssets.length > 0) {
    throw new Error(
      `${variant} artifact is missing referenced assets: ${missingAssets.join(', ')}`,
    );
  }
  const orphanAssets = [...actualAssets]
    .filter((path) => !referencedAssets.has(path))
    .toSorted((left, right) => left.localeCompare(right));
  if (orphanAssets.length > 0) {
    throw new Error(
      `${variant} artifact contains unreferenced JavaScript or CSS: ${orphanAssets.join(', ')}`,
    );
  }

  if (!index.includes(`<title>${policy.title}</title>`)) {
    throw new Error(`${variant} artifact has the wrong title`);
  }

  const contents = await Promise.all(
    paths
      .filter((path) => /\.(?:html|js|json)$/.test(path))
      .map((path) => readFile(path, 'utf8')),
  );
  const text = contents.join('\n');
  if (!text.includes(policy.namespace) || !text.includes('/api')) {
    throw new Error(
      `${variant} artifact is missing its namespace or same-origin API`,
    );
  }
}

console.log(`Verified independent artifacts: ${expected.join(', ')}`);
