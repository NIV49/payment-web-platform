<script setup lang="ts">
import { computed, ref } from 'vue';

import { Profile, VbenDescriptions } from '@vben/common-ui';
import { useUserStore } from '@vben/stores';

import { $t } from '#/locales';

import { createProfileDescriptionItems } from './profile-data';

const userStore = useUserStore();

const tabsValue = ref<string>('account');

const tabs = computed(() => [
  {
    label: $t('page.profile.account'),
    value: 'account',
  },
]);

const items = computed(() =>
  createProfileDescriptionItems(userStore.userInfo, {
    name: $t('system.user.name'),
    roles: $t('system.user.roles'),
    userId: $t('system.user.id'),
    username: $t('system.user.username'),
  }),
);
</script>
<template>
  <Profile
    v-model:model-value="tabsValue"
    :title="$t('page.auth.profile')"
    :user-info="userStore.userInfo"
    :tabs="tabs"
  >
    <template #content>
      <VbenDescriptions bordered :column="1" :items="items" />
    </template>
  </Profile>
</template>
