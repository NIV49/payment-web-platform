<script lang="ts" setup>
import type { VbenFormSchema } from '@vben/common-ui';

import { computed } from 'vue';

import { AuthenticationLogin, z } from '@vben/common-ui';
import { $t } from '@vben/locales';

import { useAuthStore } from '@payment/backoffice-runtime/store';

import {
  LOGIN_DEFAULT_CREDENTIAL_FIELD,
  resolveLoginCopy,
  resolveLoginDefaults,
  resolveOidcLoginMode,
} from './login-defaults';

defineOptions({ name: 'Login' });

const authStore = useAuthStore();
const loginCopy = resolveLoginCopy(import.meta.env.VITE_ACCOUNT_DOMAIN);
const productionOidc = import.meta.env.PROD;
const oidcLogin = resolveOidcLoginMode({
  explicitMode: import.meta.env.VITE_AUTH_MODE,
  prod: productionOidc,
});
const rememberMeNamespace = import.meta.env.VITE_APP_NAMESPACE;
let loginDefaults = resolveLoginDefaults({ dev: false });
if (import.meta.env.DEV) {
  loginDefaults = resolveLoginDefaults({
    dev: true,
    [LOGIN_DEFAULT_CREDENTIAL_FIELD]: import.meta.env.VITE_LOCAL_ADMIN_PASSWORD,
    username: import.meta.env.VITE_LOCAL_ADMIN_USERNAME,
  });
}

const formSchema = computed((): VbenFormSchema[] => {
  if (import.meta.env.PROD) return [];
  if (oidcLogin) return [];
  return [
    {
      component: 'VbenInput',
      componentProps: {
        autocomplete: 'username',
        placeholder: $t('authentication.usernameTip'),
      },
      defaultValue: loginDefaults.username,
      fieldName: 'username',
      label: $t('authentication.username'),
      rules: z.string().min(1, { message: $t('authentication.usernameTip') }),
    },
    {
      component: 'VbenInputPassword',
      componentProps: {
        autocomplete: 'current-password',
        placeholder: $t('authentication.password'),
      },
      defaultValue: loginDefaults.password,
      fieldName: 'password',
      label: $t('authentication.password'),
      rules: z.string().min(1, { message: $t('authentication.passwordTip') }),
    },
  ];
});

function submit(values: Record<string, unknown>) {
  if (import.meta.env.PROD) {
    authStore.startOidcLogin();
    return;
  }
  if (oidcLogin) {
    authStore.startOidcLogin();
    return;
  }
  return authStore.authLogin(values);
}
</script>

<template>
  <AuthenticationLogin
    :form-schema="formSchema"
    :loading="authStore.loginLoading"
    :remember-me-namespace="rememberMeNamespace"
    :show-code-login="false"
    :show-forget-password="false"
    :show-qrcode-login="false"
    :show-register="false"
    :show-remember-me="!oidcLogin"
    :show-third-party-login="false"
    :submit-button-text="oidcLogin ? $t('page.auth.continue') : undefined"
    :sub-title="$t(loginCopy.subTitleKey)"
    :title="$t(loginCopy.titleKey)"
    @submit="submit"
  />
</template>
