import { DEPLOYMENT_MENU_PAGE_COMPONENTS } from '#/deployment-policy';

export const MENU_PAGE_COMPONENTS = DEPLOYMENT_MENU_PAGE_COMPONENTS;

export const VBEN_IFRAME_COMPONENT = 'IFrameView';

type VbenMenuType = 'button' | 'catalog' | 'embedded' | 'link' | 'menu';

const VBEN_LOCALE_KEY_PATTERN = /^[a-z][A-Za-z0-9_-]*(?:\.[A-Za-z0-9_-]+)+$/;
const VBEN_ROUTE_NAME_PATTERN = /^[A-Za-z][A-Za-z0-9_]{1,127}$/;

export function isVbenLocaleKey(value: unknown): value is string {
  return typeof value === 'string' && VBEN_LOCALE_KEY_PATTERN.test(value);
}

export function isVbenRouteName(value: unknown): value is string {
  return typeof value === 'string' && VBEN_ROUTE_NAME_PATTERN.test(value);
}

export function isRegisteredMenuComponent(
  value: unknown,
  registeredComponents: readonly string[] = MENU_PAGE_COMPONENTS,
): value is string {
  return typeof value === 'string' && registeredComponents.includes(value);
}

export function menuTypeRequiresRoutePath(type: VbenMenuType): boolean {
  return type !== 'button';
}

export function resolveVbenMenuComponent(
  type: VbenMenuType,
  pageComponent?: string,
): string | undefined {
  if (type === 'menu') return pageComponent;
  if (type === 'embedded' || type === 'link') return VBEN_IFRAME_COMPONENT;
  return undefined;
}
