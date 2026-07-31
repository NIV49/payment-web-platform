import type { SystemMenuApi } from '#/api/system/menu';

import { describe, expect, it } from 'vitest';

import {
  filterAvailableNavigationMenuIds,
  filterNavigableMenuTree,
} from './menu-tree';

const makeMenu = (
  id: string,
  type: SystemMenuApi.SystemMenu['type'],
  children?: SystemMenuApi.SystemMenu[],
  status: SystemMenuApi.SystemMenu['status'] = 1,
): SystemMenuApi.SystemMenu => ({
  children,
  id,
  name: `menu-${id}`,
  pid: '0',
  rowVersion: 0,
  status,
  type,
});

describe('role navigable menu tree', () => {
  it('removes button nodes recursively while preserving other nodes and children', () => {
    const source = [
      makeMenu('1', 'catalog', [
        makeMenu('2', 'menu'),
        makeMenu('3', 'button'),
        makeMenu('4', 'catalog', [
          makeMenu('5', 'button'),
          makeMenu('6', 'link'),
        ]),
      ]),
      makeMenu('7', 'button'),
      makeMenu('8', 'embedded'),
    ];

    expect(filterNavigableMenuTree(source)).toEqual([
      makeMenu('1', 'catalog', [
        makeMenu('2', 'menu'),
        makeMenu('4', 'catalog', [makeMenu('6', 'link')]),
      ]),
      makeMenu('8', 'embedded'),
    ]);
  });

  it('does not mutate the API response tree', () => {
    const source = [
      makeMenu('1', 'catalog', [
        makeMenu('2', 'button'),
        makeMenu('3', 'menu'),
      ]),
    ];
    const originalChildren = source[0]?.children;

    const result = filterNavigableMenuTree(source);

    expect(source[0]?.children).toBe(originalChildren);
    expect(source[0]?.children?.map(({ id }) => id)).toEqual(['2', '3']);
    expect(result[0]).not.toBe(source[0]);
  });

  it('removes disabled navigation branches before editing a role', () => {
    const source = [
      makeMenu('1', 'catalog', [
        makeMenu('2', 'menu'),
        makeMenu('3', 'catalog', [makeMenu('4', 'menu')], 0),
      ]),
      makeMenu('5', 'menu', undefined, 0),
    ];

    expect(filterNavigableMenuTree(source)).toEqual([
      makeMenu('1', 'catalog', [makeMenu('2', 'menu')]),
    ]);
  });

  it('drops stale role relationships that are absent from the editable tree', () => {
    const tree = [makeMenu('1', 'catalog', [makeMenu('2', 'menu')])];

    expect(filterAvailableNavigationMenuIds(['2', '3', '4'], tree)).toEqual([
      '2',
    ]);
  });
});
