<script lang="ts" setup>
import type { SystemUserApi } from '@payment/backoffice-runtime/api/system/user';

import { computed, ref } from 'vue';

import { useVbenDrawer, VbenDescriptions } from '@vben/common-ui';
import { useUserStore } from '@vben/stores';

import AccountDomainDictionaryAlert from '@payment/backoffice-runtime/components/account-domain-dictionary-alert';
import CommonStatusDictionaryAlert from '@payment/backoffice-runtime/components/common-status-dictionary-alert';
import {
  useAccountDomainDictionary,
  useCommonStatusDictionary,
} from '@payment/backoffice-runtime/composables';
import { getInstalledBackofficeDeployment } from '@payment/backoffice-runtime/deployment-internal';
import { $t } from '@payment/backoffice-runtime/locales';

import { useDescriptionItems } from '../data';

const detailData = ref<SystemUserApi.SystemUser>();
const userStore = useUserStore();
const canUsePlatformControlPlane =
  getInstalledBackofficeDeployment()?.accountDomain === 'PLATFORM' &&
  userStore.userInfo?.systemAdministrator === true;
const accountDomains = useAccountDomainDictionary({
  enabled: canUsePlatformControlPlane,
});
const accountDomainError = accountDomains.error;
const getAccountDomainOptions = () => accountDomains.options.value;
const commonStatus = useCommonStatusDictionary();
const commonStatusError = commonStatus.error;
const getStatusOptions = () => commonStatus.options.value;

const items = computed(() =>
  useDescriptionItems(
    detailData.value,
    getStatusOptions,
    getAccountDomainOptions,
  ),
);

const [Drawer, drawerApi] = useVbenDrawer({
  onOpenChange(isOpen) {
    if (isOpen) {
      detailData.value = drawerApi.getData<SystemUserApi.SystemUser>();
    }
  },
});
</script>
<template>
  <Drawer :footer="false" :title="$t('common.detail')">
    <CommonStatusDictionaryAlert
      :error="commonStatusError"
      :reload="commonStatus.reload"
    />
    <AccountDomainDictionaryAlert
      :error="accountDomainError"
      :reload="accountDomains.reload"
    />
    <VbenDescriptions bordered :column="1" :items="items" />
  </Drawer>
</template>
