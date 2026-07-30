import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

import { describe, expect, it } from 'vitest';

const source = readFileSync(
  resolve(
    'packages/effects/layouts/src/widgets/user-dropdown/user-dropdown.vue',
  ),
  'utf8',
);

describe('user dropdown action ownership', () => {
  it('binds each menu action only to its owning menu item', () => {
    const iconButtons = source.match(/<VbenIconButton\b[^>]*>/g) ?? [];

    expect(iconButtons).not.toHaveLength(0);
    expect(iconButtons.filter((tag) => tag.includes('@click'))).toEqual([]);
    expect(source).toContain('@click="menu.handler"');
    expect(source).toContain('@click="handleLogout"');
    expect(source).toContain('@click="handleRefresh"');
    expect(source).toContain('@click="handleOpenSettings"');
    expect(source).toContain('<ThemeToggle class="mr-2" @click.stop />');
  });
});
