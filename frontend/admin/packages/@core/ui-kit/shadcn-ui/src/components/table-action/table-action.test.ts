import { createApp, h, nextTick } from 'vue';

import { afterEach, describe, expect, it, vi } from 'vitest';

import TableAction from './table-action.vue';

describe('VbenTableAction', () => {
  afterEach(() => {
    document.body.innerHTML = '';
    vi.useRealTimers();
  });

  it('keeps the hover trigger pointer-interactive', async () => {
    const host = document.createElement('div');
    document.body.append(host);
    const app = createApp({
      render: () =>
        h(TableAction, {
          actions: [{ text: 'Detail' }, { text: 'Edit' }, { text: 'Audit' }],
          dropdownActions: [{ text: 'Delete' }],
          dropdownTrigger: 'hover',
        }),
    });
    app.mount(host);
    await nextTick();

    const trigger = host.querySelector('[data-slot="dropdown-menu-trigger"]');
    expect(trigger).not.toBeNull();
    expect(trigger?.hasAttribute('disabled')).toBe(false);

    app.unmount();
    host.remove();
  });

  it('keeps three or fewer combined actions inline without an overflow trigger', async () => {
    for (const count of [0, 1, 2, 3]) {
      const host = document.createElement('div');
      document.body.append(host);
      const actions = Array.from({ length: count }, (_, index) => ({
        text: `Action ${index + 1}`,
      }));
      const app = createApp({
        render: () =>
          h(TableAction, {
            actions: actions.slice(0, 1),
            dropdownActions: actions.slice(1),
          }),
      });
      app.mount(host);
      await nextTick();

      expect(
        host.querySelector('[data-slot="dropdown-menu-trigger"]'),
      ).toBeNull();
      for (const action of actions) {
        expect(host.textContent).toContain(action.text);
      }

      app.unmount();
      host.remove();
    }
  });

  it('opens the accessible default dropdown on click but not hover', async () => {
    const host = document.createElement('div');
    document.body.append(host);
    const app = createApp({
      render: () =>
        h(TableAction, {
          actions: [{ text: 'Detail' }, { text: 'Edit' }, { text: 'Audit' }],
          dropdownActions: [{ text: 'Delete' }, { text: 'Archive' }],
        }),
    });
    app.mount(host);
    await nextTick();

    const trigger = host.querySelector(
      '[data-slot="dropdown-menu-trigger"]',
    ) as HTMLElement;
    expect(trigger.getAttribute('aria-label')).toBe('Delete, Archive');

    trigger.dispatchEvent(new MouseEvent('mouseenter'));
    await nextTick();
    expect(
      document.querySelector('[data-slot="dropdown-menu-content"]'),
    ).toBeNull();

    trigger.click();
    await nextTick();
    expect(
      document.querySelector('[data-slot="dropdown-menu-content"]'),
    ).not.toBeNull();

    app.unmount();
  });

  it('shows up to three visible actions inline and overflows from the fourth', async () => {
    const host = document.createElement('div');
    document.body.append(host);
    const app = createApp({
      render: () =>
        h(TableAction, {
          actions: [
            { text: 'Detail' },
            { text: 'Edit' },
            { text: 'Audit' },
            { text: 'Delete' },
          ],
        }),
    });
    app.mount(host);
    await nextTick();

    expect(host.textContent).toContain('Detail');
    expect(host.textContent).toContain('Edit');
    expect(host.textContent).toContain('Audit');
    expect(host.textContent).not.toContain('Delete');

    const trigger = host.querySelector(
      '[data-slot="dropdown-menu-trigger"]',
    ) as HTMLElement;
    expect(trigger.getAttribute('aria-label')).toBe('Delete');
    trigger.click();
    await nextTick();
    expect(document.body.textContent).toContain('Delete');

    app.unmount();
  });

  it('keeps a hover dropdown mounted while its confirmation is open', async () => {
    vi.useFakeTimers();
    const host = document.createElement('div');
    document.body.append(host);
    const app = createApp({
      render: () =>
        h(TableAction, {
          actions: [{ text: 'Detail' }, { text: 'Edit' }, { text: 'Audit' }],
          dropdownActions: [
            {
              popConfirm: { confirm: vi.fn(), title: 'Delete item?' },
              text: 'Delete',
            },
          ],
          dropdownTrigger: 'hover',
        }),
    });
    app.mount(host);
    await nextTick();

    const trigger = host.querySelector(
      '[data-slot="dropdown-menu-trigger"]',
    ) as HTMLElement;
    trigger.dispatchEvent(new MouseEvent('mouseenter'));
    await nextTick();
    const menuContent = document.querySelector(
      '[data-slot="dropdown-menu-content"]',
    ) as HTMLElement;
    expect(menuContent).not.toBeNull();

    (document.querySelector('[role="menuitem"]') as HTMLElement).click();
    await nextTick();
    expect(
      document.querySelector('[data-slot="popover-content"]'),
    ).not.toBeNull();

    trigger.dispatchEvent(new MouseEvent('mouseleave'));
    menuContent.dispatchEvent(new MouseEvent('mouseleave'));
    await vi.advanceTimersByTimeAsync(350);
    expect(trigger.getAttribute('aria-expanded')).toBe('true');

    app.unmount();
  });
});
