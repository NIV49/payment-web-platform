import { createApp, defineComponent, h } from 'vue';

import { describe, expect, it, vi } from 'vitest';

import ClassificationDictionaryAlert from './classification-dictionary-alert.vue';

vi.mock('@payment/backoffice-runtime/locales', () => ({
  $t: (key: string) => key,
}));
vi.mock('antdv-next', () => ({
  Alert: { template: '<div><slot name="action" /></div>' },
  Button: defineComponent({
    emits: ['click'],
    setup(_, { emit, slots }) {
      return () =>
        h('button', { onClick: () => emit('click') }, slots.default?.());
    },
  }),
}));

describe('merchant classification dictionary alert', () => {
  it('shows a retry action when batch dictionary loading fails', () => {
    const reload = vi.fn();
    const root = document.createElement('div');
    const app = createApp(ClassificationDictionaryAlert, {
      error: new Error('failed'),
      reload,
    });
    app.mount(root);

    (root.querySelector('button') as HTMLButtonElement).click();

    expect(root.textContent).toContain('merchant.classification.retry');
    expect(reload).toHaveBeenCalledOnce();
    app.unmount();
  });
});
