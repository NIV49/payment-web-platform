<script lang="ts" setup>
import type { VbenFormSchema } from '@vben/common-ui';

import { computed, onMounted, useTemplateRef } from 'vue';

import { AuthenticationLogin, z } from '@vben/common-ui';
import { $t } from '@vben/locales';

import { useAuthStore } from '#/store';

import { resolveLoginDefaults } from './login-defaults';

defineOptions({ name: 'Login' });

const authStore = useAuthStore();
const loginRef =
  useTemplateRef<InstanceType<typeof AuthenticationLogin>>('loginRef');
const loginDefaults = resolveLoginDefaults(
  import.meta.env.DEV,
  import.meta.env.VITE_DEV_LOGIN_USERNAME,
  import.meta.env.VITE_DEV_LOGIN_PASSWORD,
);

onMounted(() => {
  if (!import.meta.env.DEV) return;
  const formApi = loginRef.value?.getFormApi();
  formApi?.setFieldValue('username', loginDefaults.username, false);
  formApi?.setFieldValue('password', loginDefaults.password, false);
});

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
    ref="loginRef"
    :form-schema="formSchema"
    :loading="authStore.loginLoading"
    @submit="authStore.authLogin"
  />
</template>
