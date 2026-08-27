import type { VNode } from 'vue';

import { defineComponent } from 'vue';

import { beforeEach, describe, expect, it, vi } from 'vitest';

const mocks = vi.hoisted(() => ({
  hasAccessByCodes: vi.fn<(codes: string[]) => boolean>(),
  renderers: new Map<string, Record<string, any>>(),
}));

vi.mock('@vben/access', () => ({
  useAccess: () => ({ hasAccessByCodes: mocks.hasAccessByCodes }),
}));

vi.mock('@vben/plugins/vxe-table', () => ({
  setupVbenVxeTable: ({ configVxeTable }: Record<string, any>) => {
    configVxeTable({
      renderer: {
        add: (name: string, renderer: Record<string, any>) =>
          mocks.renderers.set(name, renderer),
        delete: vi.fn(),
        forEach: vi.fn(),
      },
      setConfig: vi.fn(),
    });
  },
  useVbenVxeGrid: vi.fn(),
}));

vi.mock('antdv-next', () => {
  const Stub = defineComponent({ render: () => null });
  return {
    Button: Stub,
    Image: Stub,
    Popconfirm: Stub,
    Switch: Stub,
    Tag: Stub,
  };
});

vi.mock('./form', () => ({ useVbenForm: vi.fn() }));

await import('./vxe-table');

describe('cellOperation renderer', () => {
  beforeEach(() => {
    mocks.hasAccessByCodes.mockReset();
    mocks.hasAccessByCodes.mockImplementation((codes) =>
      codes.includes('allowed'),
    );
  });

  it('keeps the first three visible permitted actions inline', () => {
    const onClick = vi.fn();
    const row = { id: 'row-1', locked: false };
    const renderer = mocks.renderers.get('CellOperation');

    const vnode = renderer?.renderTableDefault(
      {
        attrs: { nameField: 'id', onClick },
        options: [
          { code: 'hidden', ifShow: false },
          { auth: 'denied', code: 'denied' },
          { code: 'edit' },
          { code: 'detail', show: (record: typeof row) => !record.locked },
          { code: 'delete' },
          { code: 'audit', text: 'Audit' },
        ],
        props: {},
      },
      { column: { align: 'center' }, row },
    ) as VNode;

    expect(vnode.props?.align).toBe('center');
    expect(vnode.props?.dropdownTrigger).toBe('click');
    expect(vnode.props?.actions.map((action: any) => action.key)).toEqual([
      'edit',
      'detail',
      'delete',
    ]);
    expect(
      vnode.props?.dropdownActions.map((action: any) => action.key),
    ).toEqual(['audit']);

    vnode.props?.dropdownActions[0].onClick();
    expect(onClick).toHaveBeenCalledWith({ code: 'audit', row });
    expect(vnode.props?.actions[2].popConfirm).toBeDefined();
  });
});
