import type { SystemMenuApi } from '#/api/system/menu';

import { describe, expect, it } from 'vitest';

import {
  buildRoleConfigurationTree,
  filterAvailableNavigationMenuIds,
  filterNavigableMenuTree,
  normalizeRoleConfigurationSelection,
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

const makeButton = (
  id: string,
  authCode: string,
  status: SystemMenuApi.SystemMenu['status'] = 1,
): SystemMenuApi.SystemMenu => ({
  ...makeMenu(id, 'button', undefined, status),
  authCode,
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

  it('builds one stable-id tree and filters duplicate or unsafe button bindings', () => {
    const source = [
      makeMenu('10', 'catalog', [
        makeMenu('11', 'menu', [
          makeButton('12', 'user:view'),
          makeButton('13', 'user:view'),
          makeButton('14', 'role:grant-update'),
          makeButton('15', 'unknown:view'),
          makeButton('16', 'user:create', 0),
          makeButton('17', 'user:create'),
          makeButton('18', 'department:view'),
          makeButton('19', 'role:view'),
        ]),
      ]),
    ];

    const configuration = buildRoleConfigurationTree(source, [
      'user:create',
      'user:view',
      'department:view',
      'role:view',
    ]);

    expect(configuration.tree[0]?.id).toBe('10');
    expect(
      configuration.tree[0]?.children?.[0]?.children?.map(({ id }) => id),
    ).toEqual(['12', '17', '18', '19']);
    expect(configuration.permissionByButtonId).toEqual({
      '12': 'user:view',
      '17': 'user:create',
      '18': 'department:view',
      '19': 'role:view',
    });
  });

  it('hides actions whose permission dependencies are not represented by buttons', () => {
    const configuration = buildRoleConfigurationTree(
      [
        makeMenu('10', 'menu', [
          makeButton('11', 'user:view'),
          makeButton('12', 'user:create'),
        ]),
      ],
      ['user:create', 'user:view'],
    );

    expect(configuration.tree[0]?.children?.map(({ id }) => id)).toEqual([
      '11',
    ]);
    expect(configuration.permissionByButtonId).toEqual({ '11': 'user:view' });
  });

  it('selecting a button adds navigation ancestors and the permission dependency closure', () => {
    const source = [
      makeMenu('10', 'catalog', [
        makeMenu('11', 'menu', [
          makeButton('12', 'user:view'),
          makeButton('13', 'department:view'),
          makeButton('14', 'role:view'),
          makeButton('15', 'user:create'),
        ]),
      ]),
    ];
    const configuration = buildRoleConfigurationTree(source, [
      'department:view',
      'role:view',
      'user:create',
      'user:view',
    ]);

    expect(normalizeRoleConfigurationSelection(['15'], configuration)).toEqual({
      menuIds: ['10', '11'],
      permissionCodes: [
        'department:view',
        'role:view',
        'user:create',
        'user:view',
      ],
      selectedIds: ['10', '11', '12', '13', '14', '15'],
    });
  });

  it('selecting navigation never grants descendant buttons', () => {
    const configuration = buildRoleConfigurationTree(
      [
        makeMenu('10', 'catalog', [
          makeMenu('11', 'menu', [makeButton('12', 'user:view')]),
        ]),
      ],
      ['user:view'],
    );

    expect(
      normalizeRoleConfigurationSelection(['10', '11'], configuration),
    ).toEqual({
      menuIds: ['10', '11'],
      permissionCodes: [],
      selectedIds: ['10', '11'],
    });
  });

  it('removing a dependency also removes actions that can no longer be granted', () => {
    const configuration = buildRoleConfigurationTree(
      [
        makeMenu('10', 'catalog', [
          makeMenu('11', 'menu', [
            makeButton('12', 'role:view'),
            makeButton('13', 'menu:view'),
            makeButton('14', 'role:create'),
          ]),
        ]),
      ],
      ['menu:view', 'role:create', 'role:view'],
    );
    const selected = normalizeRoleConfigurationSelection(['14'], configuration);

    expect(
      normalizeRoleConfigurationSelection(
        selected.selectedIds.filter((id) => id !== '12'),
        configuration,
        { checked: false, id: '12' },
      ),
    ).toEqual({
      menuIds: ['10', '11'],
      permissionCodes: ['menu:view'],
      selectedIds: ['10', '11', '13'],
    });
  });
});
