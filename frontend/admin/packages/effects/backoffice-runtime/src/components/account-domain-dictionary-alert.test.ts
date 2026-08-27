import { createApp } from 'vue';

import { describe, expect, it, vi } from 'vitest';

import AccountDomainDictionaryAlert from './account-domain-dictionary-alert.vue';

vi.mock('antdv-next', () => ({
  Alert: { template: '<section><slot name="action" /></section>' },
  Button: {
    emits: ['click'],
    template:
      '<button type="button" @click="$emit(\'click\')"><slot /></button>',
  },
}));

vi.mock('../locales', () => ({ $t: (key: string) => key }));

describe('account domain dictionary alert', () => {
  it('stays hidden without an error', () => {
    const root = document.createElement('div');
    const app = createApp(AccountDomainDictionaryAlert, {
      reload: vi.fn(),
    });
    app.mount(root);

    expect(root.querySelector('section')).toBeNull();
    app.unmount();
  });

  it('offers an explicit retry after a dictionary failure', () => {
    const reload = vi.fn().mockResolvedValue(true);
    const root = document.createElement('div');
    const app = createApp(AccountDomainDictionaryAlert, {
      error: new Error('dictionary unavailable'),
      reload,
    });
    app.mount(root);

    const retry = root.querySelector('button');
    expect(retry?.textContent).toBe('system.retry');
    retry?.click();
    expect(reload).toHaveBeenCalledOnce();
    app.unmount();
  });
});
