<script lang="ts" setup>
import type { VbenFormSchema } from '@vben/common-ui';

import { computed } from 'vue';

import { AuthenticationLogin, z } from '@vben/common-ui';
import { $t } from '@vben/locales';

import { useAuthStore } from '@payment/backoffice-runtime/store';

import {
  LOGIN_DEFAULT_CREDENTIAL_FIELD,
  resolveLoginDefaults,
} from './login-defaults';

defineOptions({ name: 'Login' });

const authStore = useAuthStore();
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
    :show-third-party-login="false"
    @submit="authStore.authLogin"
  />
</template>
