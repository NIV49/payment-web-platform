import type { SystemMenuApi } from '#/api/system/menu';

import { describe, expect, it } from 'vitest';

import {
  canAppendMenuChild,
  filterMenuParentOptions,
} from './permission-contract';

const menu = (
  id: string,
  type: SystemMenuApi.SystemMenu['type'],
  children?: SystemMenuApi.SystemMenu[],
): SystemMenuApi.SystemMenu => ({
  children,
  id,
  name: `menu-${id}`,
  pid: '0',
  rowVersion: 0,
  status: 1,
  type,
});

describe('menu permission presentation contract', () => {
  it('never allows BUTTON nodes to own children', () => {
    expect(canAppendMenuChild(menu('1', 'button'))).toBe(false);
    expect(canAppendMenuChild(menu('2', 'menu'))).toBe(true);
  });

  it('removes BUTTON nodes from parent choices without mutating the tree', () => {
    const source = [
      menu('1', 'catalog', [menu('2', 'button'), menu('3', 'menu')]),
      menu('4', 'button'),
    ];

    expect(filterMenuParentOptions(source)).toEqual([
      menu('1', 'catalog', [menu('3', 'menu')]),
    ]);
    expect(source[0]?.children?.map(({ id }) => id)).toEqual(['2', '3']);
  });

  it('removes the edited menu and its descendants from parent choices', () => {
    const source = [
      menu('1', 'catalog', [menu('2', 'menu', [menu('3', 'menu')])]),
      menu('4', 'menu'),
    ];

    expect(filterMenuParentOptions(source, '2')).toEqual([
      menu('1', 'catalog', []),
      menu('4', 'menu'),
    ]);
  });
});
