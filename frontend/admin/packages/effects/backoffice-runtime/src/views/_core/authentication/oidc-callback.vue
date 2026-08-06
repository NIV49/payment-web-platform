<script lang="ts" setup>
import { onMounted } from 'vue';
import { useRoute, useRouter } from 'vue-router';

import { LOGIN_PATH } from '@vben/constants';
import { preferences } from '@vben/preferences';

import { parseOidcCallbackQuery } from '@payment/backoffice-runtime/api/core/oidc-navigation';
import { useAuthStore } from '@payment/backoffice-runtime/store';
import { Spin } from 'antdv-next';

defineOptions({ name: 'OidcCallback' });

const authStore = useAuthStore();
const route = useRoute();
const router = useRouter();

onMounted(async () => {
  let callback;
  try {
    callback = parseOidcCallbackQuery(route.query.handoff, route.query.stepup);
  } catch {
    await router.replace(LOGIN_PATH);
    return;
  }
  if (callback.kind === 'step-up') {
    try {
      await authStore.redeemOidcStepUp(callback.value);
      await router.replace(preferences.app.defaultHomePath);
    } catch {
      await router.replace(LOGIN_PATH);
    }
    return;
  }
  try {
    const result = await authStore.redeemOidcHandoff(
      callback.value,
      () => undefined,
    );
    await router.replace(
      result.userInfo?.homePath || preferences.app.defaultHomePath,
    );
  } catch {
    await router.replace(LOGIN_PATH);
  }
});
</script>

<template>
  <div class="flex min-h-32 items-center justify-center">
    <Spin size="large" />
  </div>
</template>
