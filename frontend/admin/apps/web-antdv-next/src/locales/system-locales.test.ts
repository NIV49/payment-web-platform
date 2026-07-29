import { describe, expect, it } from 'vitest';

import enDemos from './langs/en-US/demos.json';
import enPage from './langs/en-US/page.json';
import enUS from './langs/en-US/system.json';
import zhDemos from './langs/zh-CN/demos.json';
import zhPage from './langs/zh-CN/page.json';
import zhCN from './langs/zh-CN/system.json';

function scalarPaths(value: Record<string, unknown>, prefix = ''): string[] {
  return Object.entries(value).flatMap(([key, child]) => {
    const path = prefix ? `${prefix}.${key}` : key;
    if (child !== null && typeof child === 'object') {
      return scalarPaths(child as Record<string, unknown>, path);
    }
    return [path];
  });
}

describe('system locale contracts', () => {
  it.each([
    ['demos', enDemos, zhDemos],
    ['page', enPage, zhPage],
    ['system', enUS, zhCN],
  ])('keeps %s English and Chinese translation keys in sync', (_, en, zh) => {
    expect(scalarPaths(en).toSorted()).toEqual(scalarPaths(zh).toSorted());
  });
});
