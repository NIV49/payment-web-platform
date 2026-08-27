<script lang="ts" setup>
import type { MerchantLifecycleApi } from '@payment/backoffice-runtime/api/merchant-lifecycle';
import type { MerchantOnboardingApi } from '@payment/backoffice-runtime/api/merchant-onboarding';

import { computed } from 'vue';

import { Descriptions, DescriptionsItem, Tag } from 'antdv-next';

import { $t } from '#/locales';

import { normalizeMerchantTypeCode } from '../merchant-presentation';
import MerchantDocumentPreview from './merchant-document-preview.vue';

type Profile =
  | MerchantLifecycleApi.PlatformMerchantDetail
  | MerchantOnboardingApi.PendingProfile;

const props = defineProps<{
  amendmentId?: string;
  canPreviewDocuments: boolean;
  merchantId: string;
  profile: Profile;
}>();

const marketNames = computed(() =>
  props.profile.marketCodes.map((code) => $t(`merchant.markets.${code}`)),
);

function display(value: null | string | undefined) {
  return value && value.length > 0 ? value : '-';
}

function merchantTypeLabel() {
  const value = normalizeMerchantTypeCode(props.profile.merchantTypeCode);
  return value ? $t(`merchant.merchantTypes.${value}`) : '-';
}

function authenticationTypeLabel() {
  return props.profile.authenticationType
    ? $t(`merchant.authenticationTypes.${props.profile.authenticationType}`)
    : '-';
}

function industryLabel() {
  return props.profile.industryCode
    ? $t(`merchant.industries.${props.profile.industryCode}`)
    : '-';
}

function legalIdTypeLabel() {
  return props.profile.legalIdTypeCode
    ? $t(`merchant.legalIdTypes.${props.profile.legalIdTypeCode}`)
    : '-';
}

function validityLabel() {
  return props.profile.legalIdValidity
    ? `${props.profile.legalIdValidity.validFrom} - ${props.profile.legalIdValidity.validTo}`
    : '-';
}
</script>

<template>
  <div class="space-y-6" data-test="merchant-profile-details">
    <section>
      <h2 class="mb-3 text-base font-semibold">
        {{ $t('merchant.onboarding.sections.merchantBasic') }}
      </h2>
      <Descriptions bordered :column="{ xs: 1, md: 2, xl: 3 }" size="small">
        <DescriptionsItem :label="$t('merchant.onboarding.fields.displayName')">
          {{ display(profile.displayName) }}
        </DescriptionsItem>
        <DescriptionsItem :label="$t('merchant.onboarding.fields.brandName')">
          {{ display(profile.brandName) }}
        </DescriptionsItem>
        <DescriptionsItem
          :label="$t('merchant.onboarding.fields.authenticationType')"
        >
          {{ authenticationTypeLabel() }}
        </DescriptionsItem>
        <DescriptionsItem
          :label="$t('merchant.onboarding.fields.merchantTypeCode')"
        >
          {{ merchantTypeLabel() }}
        </DescriptionsItem>
        <DescriptionsItem
          :label="$t('merchant.onboarding.fields.industryCode')"
        >
          {{ industryLabel() }}
        </DescriptionsItem>
        <DescriptionsItem
          :label="$t('merchant.onboarding.fields.brandLogoDocumentId')"
        >
          <MerchantDocumentPreview
            :amendment-id="amendmentId"
            :can-preview="canPreviewDocuments"
            :document="profile.brandLogoDocument"
            kind="BRAND_LOGO"
            :merchant-id="merchantId"
          />
        </DescriptionsItem>
      </Descriptions>
    </section>

    <section>
      <h2 class="mb-3 text-base font-semibold">
        {{ $t('merchant.onboarding.sections.subject') }}
      </h2>
      <Descriptions bordered :column="{ xs: 1, md: 2, xl: 3 }" size="small">
        <DescriptionsItem :label="$t('merchant.onboarding.fields.legalName')">
          {{ display(profile.legalName) }}
        </DescriptionsItem>
        <DescriptionsItem
          :label="$t('merchant.onboarding.fields.registrationCountry')"
        >
          {{ display(profile.registrationCountry) }}
        </DescriptionsItem>
        <DescriptionsItem
          :label="$t('merchant.onboarding.fields.registrationNumber')"
        >
          {{ display(profile.registrationNumberMasked) }}
        </DescriptionsItem>
        <DescriptionsItem :label="$t('merchant.onboarding.fields.marketCodes')">
          <div class="flex flex-wrap gap-1">
            <Tag v-for="name in marketNames" :key="name">{{ name }}</Tag>
            <span v-if="marketNames.length === 0">-</span>
          </div>
        </DescriptionsItem>
        <DescriptionsItem
          :label="$t('merchant.onboarding.fields.registeredAddress')"
        >
          {{ display(profile.registeredAddress) }}
        </DescriptionsItem>
        <DescriptionsItem
          :label="$t('merchant.onboarding.fields.operatingAddress')"
        >
          {{ display(profile.operatingAddress) }}
        </DescriptionsItem>
        <DescriptionsItem
          :label="$t('merchant.onboarding.fields.businessLicenseDocumentId')"
        >
          <MerchantDocumentPreview
            :amendment-id="amendmentId"
            :can-preview="canPreviewDocuments"
            :document="profile.businessLicenseDocument"
            kind="BUSINESS_LICENSE"
            :merchant-id="merchantId"
          />
        </DescriptionsItem>
      </Descriptions>
    </section>

    <section>
      <h2 class="mb-3 text-base font-semibold">
        {{ $t('merchant.onboarding.sections.legalRepresentative') }}
      </h2>
      <Descriptions bordered :column="{ xs: 1, md: 2, xl: 3 }" size="small">
        <DescriptionsItem
          :label="$t('merchant.onboarding.fields.legalPersonName')"
        >
          {{ display(profile.legalPersonName) }}
        </DescriptionsItem>
        <DescriptionsItem
          :label="$t('merchant.onboarding.fields.contactEmail')"
        >
          {{ display(profile.contactEmail) }}
        </DescriptionsItem>
        <DescriptionsItem
          :label="$t('merchant.onboarding.fields.contactPhone')"
        >
          {{ display(profile.contactPhone) }}
        </DescriptionsItem>
        <DescriptionsItem
          :label="$t('merchant.onboarding.fields.legalIdTypeCode')"
        >
          {{ legalIdTypeLabel() }}
        </DescriptionsItem>
        <DescriptionsItem :label="$t('merchant.onboarding.fields.legalIdNo')">
          {{ display(profile.legalIdNoMasked) }}
        </DescriptionsItem>
        <DescriptionsItem
          :label="$t('merchant.onboarding.fields.legalIdValidity')"
        >
          {{ validityLabel() }}
        </DescriptionsItem>
        <DescriptionsItem
          :label="$t('merchant.onboarding.fields.legalIdFrontDocumentId')"
        >
          <MerchantDocumentPreview
            :amendment-id="amendmentId"
            :can-preview="canPreviewDocuments"
            :document="profile.legalIdFrontDocument"
            kind="LEGAL_ID_FRONT"
            :merchant-id="merchantId"
          />
        </DescriptionsItem>
        <DescriptionsItem
          :label="$t('merchant.onboarding.fields.legalIdBackDocumentId')"
        >
          <MerchantDocumentPreview
            :amendment-id="amendmentId"
            :can-preview="canPreviewDocuments"
            :document="profile.legalIdBackDocument"
            kind="LEGAL_ID_BACK"
            :merchant-id="merchantId"
          />
        </DescriptionsItem>
        <DescriptionsItem
          :label="$t('merchant.onboarding.fields.legalIdHoldingDocumentId')"
        >
          <MerchantDocumentPreview
            :amendment-id="amendmentId"
            :can-preview="canPreviewDocuments"
            :document="profile.legalIdHoldingDocument"
            kind="LEGAL_ID_HOLDING"
            :merchant-id="merchantId"
          />
        </DescriptionsItem>
        <DescriptionsItem :label="$t('merchant.onboarding.fields.remarks')">
          {{ display(profile.remarks) }}
        </DescriptionsItem>
      </Descriptions>
    </section>
  </div>
</template>
