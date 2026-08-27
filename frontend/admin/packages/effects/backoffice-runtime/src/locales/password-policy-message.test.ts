import { i18n } from '@vben/locales';

import { afterEach, describe, expect, it } from 'vitest';

import enUSSystem from './langs/en-US/system.json';
import zhCNSystem from './langs/zh-CN/system.json';

const TEST_LOCALE = 'password-policy-syntax-test';
const originalLocale = i18n.global.locale.value;

afterEach(() => {
  i18n.global.locale.value = originalLocale;
  i18n.global.setLocaleMessage(TEST_LOCALE, {});
});

describe('password policy locale messages', () => {
  it.each([
    ['en-US', enUSSystem.user.passwordPolicy],
    ['zh-CN', zhCNSystem.user.passwordPolicy],
  ])('compiles the %s message with Vue I18n', (_locale, passwordPolicy) => {
    i18n.global.setLocaleMessage(TEST_LOCALE, {
      passwordPolicy,
    });
    i18n.global.locale.value = TEST_LOCALE;

    expect(() => i18n.global.t('passwordPolicy')).not.toThrow();
    expect(i18n.global.t('passwordPolicy')).toContain('@');
  });
});
