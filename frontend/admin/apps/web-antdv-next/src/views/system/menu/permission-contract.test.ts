import type { SystemMenuApi } from '#/api/system/menu';

import { describe, expect, it, vi } from 'vitest';

import { PERMISSION_CODES } from '#/api/permission-codes';
import { filterDeletedMenuTree } from '#/api/system/menu';

import {
  canAppendMenuChild,
  canManageMenu,
  canPerformMenuAction,
  filterMenuParentOptions,
  getMenuActionPresentation,
} from './permission-contract';

vi.mock('#/api/request', () => ({ requestClient: {} }));

const menu = (
  id: string,
  type: SystemMenuApi.SystemMenu['type'],
  children?: SystemMenuApi.SystemMenu[],
  overrides: Partial<SystemMenuApi.SystemMenu & { disabled?: boolean }> = {},
): SystemMenuApi.SystemMenu & { disabled?: boolean } => ({
  children,
  id,
  name: `menu-${id}`,
  pid: '0',
  rowVersion: 0,
  status: 1,
  type,
  ...overrides,
});

describe('menu permission presentation contract', () => {
  it('hides deleted menus while retaining disabled management rows', () => {
    const result = filterDeletedMenuTree([
      menu('1', 'menu', undefined, { status: 0 }),
      menu('2', 'menu', undefined, {
        deletedAt: '2026-08-01T00:00:00Z',
      }),
    ]);

    expect(result).toEqual([menu('1', 'menu', undefined, { status: 0 })]);
  });

  it('never allows BUTTON nodes to own children', () => {
    expect(canAppendMenuChild(menu('1', 'button'))).toBe(false);
    expect(canAppendMenuChild(menu('2', 'menu'))).toBe(true);
  });

  it('allows active system-managed menus to own children', () => {
    expect(
      canAppendMenuChild(menu('1', 'menu', undefined, { systemManaged: true })),
    ).toBe(true);
    expect(
      canAppendMenuChild(menu('2', 'menu', undefined, { status: 0 })),
    ).toBe(false);
    expect(
      canAppendMenuChild(
        menu('3', 'menu', undefined, { deletedAt: '2026-08-01T00:00:00Z' }),
      ),
    ).toBe(false);
  });

  it('allows disabled and system-managed menus to be managed', () => {
    expect(canManageMenu(menu('1', 'menu', undefined, { status: 0 }))).toBe(
      true,
    );
    expect(
      canManageMenu(menu('2', 'menu', undefined, { systemManaged: true })),
    ).toBe(true);
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

  it('enables active system-managed non-BUTTON parent choices', () => {
    const result = filterMenuParentOptions([
      menu('1', 'catalog'),
      menu('2', 'menu', undefined, { status: 0 }),
      menu('3', 'button'),
      menu('4', 'menu', undefined, { systemManaged: true }),
      menu('5', 'menu', undefined, { deletedAt: '2026-08-01T00:00:00Z' }),
    ]);

    expect(result).toEqual([
      menu('1', 'catalog'),
      menu('4', 'menu', undefined, { systemManaged: true }),
    ]);
  });

  it('pins the current non-selectable parent and its ancestors as read-only', () => {
    const result = filterMenuParentOptions(
      [
        menu(
          '1',
          'catalog',
          [
            menu('2', 'menu', [menu('3', 'menu')], {
              status: 0,
            }),
          ],
          { systemManaged: true },
        ),
      ],
      '3',
      '2',
    );

    expect(result).toEqual([
      menu(
        '1',
        'catalog',
        [menu('2', 'menu', [], { disabled: true, status: 0 })],
        { disabled: true, systemManaged: true },
      ),
    ]);
  });

  it('requires menu view together with the action permission', () => {
    const granted = new Set<string>([PERMISSION_CODES.menuCreate]);
    const hasAccess = (codes: string[]) =>
      codes.some((code) => granted.has(code));
    expect(
      canPerformMenuAction(
        menu('1', 'menu'),
        PERMISSION_CODES.menuCreate,
        hasAccess,
      ),
    ).toBe(false);
    granted.add(PERMISSION_CODES.menuView);
    expect(
      canPerformMenuAction(
        menu('1', 'menu'),
        PERMISSION_CODES.menuCreate,
        hasAccess,
      ),
    ).toBe(true);
  });

  it('shows system-managed menu actions as enabled', () => {
    const hasAccess = () => true;
    const protectedMenu = menu('1', 'menu', undefined, {
      systemManaged: true,
    });

    expect(
      getMenuActionPresentation(
        protectedMenu,
        PERMISSION_CODES.menuUpdate,
        hasAccess,
      ),
    ).toEqual({ disabled: false, visible: true });
    expect(
      getMenuActionPresentation(
        protectedMenu,
        PERMISSION_CODES.menuDelete,
        hasAccess,
      ),
    ).toEqual({ disabled: false, visible: true });
  });

  it('keeps ordinary menu actions visible and enabled when dependencies exist', () => {
    const granted = new Set<string>([
      PERMISSION_CODES.menuDelete,
      PERMISSION_CODES.menuUpdate,
      PERMISSION_CODES.menuView,
    ]);
    const hasAccess = (codes: string[]) =>
      codes.some((code) => granted.has(code));

    expect(
      getMenuActionPresentation(
        menu('1', 'menu'),
        PERMISSION_CODES.menuUpdate,
        hasAccess,
      ),
    ).toEqual({ disabled: false, visible: true });
    expect(
      getMenuActionPresentation(
        menu('1', 'menu'),
        PERMISSION_CODES.menuDelete,
        hasAccess,
      ),
    ).toEqual({ disabled: false, visible: true });
  });

  it('still hides menu actions when permission dependencies are missing', () => {
    expect(
      getMenuActionPresentation(
        menu('1', 'menu'),
        PERMISSION_CODES.menuUpdate,
        () => false,
      ),
    ).toEqual({ disabled: false, visible: false });
  });
});
