import { createApp, defineComponent, h, nextTick, ref } from 'vue';

import { afterEach, describe, expect, it, vi } from 'vitest';

import { useHoverToggle } from '../use-hover-toggle';

describe('useHoverToggle', () => {
  afterEach(() => {
    document.body.innerHTML = '';
    vi.useRealTimers();
  });

  it('keeps content open while the pointer moves from trigger to content', async () => {
    vi.useFakeTimers();
    const host = document.createElement('div');
    document.body.append(host);
    let open: ReturnType<typeof ref<boolean>> | undefined;

    const app = createApp(
      defineComponent({
        setup() {
          const trigger = ref<HTMLElement>();
          const content = ref<HTMLElement>();
          [open] = useHoverToggle([trigger, content], {
            enterDelay: 0,
            leaveDelay: 200,
          });
          return () =>
            h('div', [
              h('button', { ref: trigger, type: 'button' }, 'more'),
              h('div', { ref: content }, 'content'),
            ]);
        },
      }),
    );
    app.mount(host);
    await nextTick();

    const trigger = host.querySelector('button') as HTMLButtonElement;
    const content = host.querySelector('button + div') as HTMLDivElement;
    trigger.dispatchEvent(new MouseEvent('mouseenter'));
    await nextTick();
    expect(open?.value).toBe(true);

    trigger.dispatchEvent(new MouseEvent('mouseleave'));
    content.dispatchEvent(new MouseEvent('mouseenter'));
    await vi.advanceTimersByTimeAsync(250);
    expect(open?.value).toBe(true);

    content.dispatchEvent(new MouseEvent('mouseleave'));
    await vi.advanceTimersByTimeAsync(199);
    expect(open?.value).toBe(true);
    await vi.advanceTimersByTimeAsync(1);
    expect(open?.value).toBe(false);
    app.unmount();
  });

  it('can reopen after hovered content is unmounted', async () => {
    vi.useFakeTimers();
    const host = document.createElement('div');
    document.body.append(host);
    const contentMounted = ref(true);
    let open: ReturnType<typeof ref<boolean>> | undefined;

    const app = createApp(
      defineComponent({
        setup() {
          const trigger = ref<HTMLElement>();
          const content = ref<HTMLElement>();
          [open] = useHoverToggle([trigger, content], {
            enterDelay: 0,
            leaveDelay: 200,
          });
          return () =>
            h('div', [
              h('button', { ref: trigger, type: 'button' }, 'more'),
              contentMounted.value
                ? h('div', { ref: content }, 'content')
                : undefined,
            ]);
        },
      }),
    );
    app.mount(host);
    await nextTick();

    const trigger = host.querySelector('button') as HTMLButtonElement;
    const content = host.querySelector('button + div') as HTMLDivElement;
    trigger.dispatchEvent(new MouseEvent('mouseenter'));
    content.dispatchEvent(new MouseEvent('mouseenter'));
    await nextTick();
    expect(open?.value).toBe(true);

    contentMounted.value = false;
    await nextTick();
    await vi.advanceTimersByTimeAsync(200);
    expect(open?.value).toBe(false);

    contentMounted.value = true;
    await nextTick();
    trigger.dispatchEvent(new MouseEvent('mouseenter'));
    await nextTick();
    expect(open?.value).toBe(true);

    app.unmount();
  });
});
