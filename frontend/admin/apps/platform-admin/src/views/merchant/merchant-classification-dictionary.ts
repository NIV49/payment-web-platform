import type { MerchantLifecycleApi } from '@payment/backoffice-runtime/api/merchant-lifecycle';
import type { MerchantOnboardingApi } from '@payment/backoffice-runtime/api/merchant-onboarding';
import type { SystemDictionaryDataApi } from '@payment/backoffice-runtime/api/system/dictionary-data';

import type { ComputedRef } from 'vue';

import { computed, onScopeDispose, readonly, shallowRef } from 'vue';

import { MERCHANT_AUTHENTICATION_TYPES } from '@payment/backoffice-runtime/api/merchant-lifecycle';
import {
  INDUSTRY_CODES,
  LEGAL_ID_TYPE_CODES,
  MCH003_MERCHANT_TYPES,
} from '@payment/backoffice-runtime/api/merchant-onboarding';
import { queryDictionaryDataBatch } from '@payment/backoffice-runtime/api/system/dictionary-data';

import { $t } from '#/locales';

const MERCHANT_TYPE_DICTIONARY = 'MERCHANT_TYPE_CODE';
const AUTHENTICATION_TYPE_DICTIONARY = 'MERCHANT_AUTH_TYPE';
const INDUSTRY_DICTIONARY = 'MERCHANT_INDUSTRY_CODE';
const LEGAL_ID_TYPE_DICTIONARY = 'MERCHANT_LEGAL_ID_TYPE';

interface ClassificationOption<T extends string> {
  color: SystemDictionaryDataApi.DictionaryColor;
  label: string;
  value: T;
}

type MerchantTypeOptions = readonly Readonly<
  ClassificationOption<MerchantLifecycleApi.MerchantTypeCode>
>[];
type AuthenticationTypeOptions = readonly Readonly<
  ClassificationOption<MerchantLifecycleApi.AuthenticationType>
>[];
type IndustryOptions = readonly Readonly<
  ClassificationOption<MerchantOnboardingApi.IndustryCode>
>[];
type LegalIdTypeOptions = readonly Readonly<
  ClassificationOption<MerchantOnboardingApi.LegalIdTypeCode>
>[];

const MERCHANT_TYPE_FALLBACK_COLORS = [
  'success',
  'warning',
  'default',
  'processing',
  'purple',
] as const;
const AUTHENTICATION_TYPE_FALLBACK_COLORS = [
  'processing',
  'success',
  'purple',
  'default',
  'warning',
] as const;
const INDUSTRY_FALLBACK_COLORS = [
  'processing',
  'success',
  'purple',
  'warning',
  'default',
  'error',
] as const;
const LEGAL_ID_TYPE_FALLBACK_COLORS = [
  'processing',
  'success',
  'purple',
] as const;

function buildMerchantTypeOptions(
  items: readonly SystemDictionaryDataApi.DictionaryBatchItem[],
): MerchantTypeOptions {
  return buildOptions(
    MCH003_MERCHANT_TYPES,
    MERCHANT_TYPE_FALLBACK_COLORS,
    'merchant.merchantTypes',
    items,
  );
}

function buildAuthenticationTypeOptions(
  items: readonly SystemDictionaryDataApi.DictionaryBatchItem[],
): AuthenticationTypeOptions {
  return buildOptions(
    MERCHANT_AUTHENTICATION_TYPES,
    AUTHENTICATION_TYPE_FALLBACK_COLORS,
    'merchant.authenticationTypes',
    items,
  );
}

function buildIndustryOptions(
  items: readonly SystemDictionaryDataApi.DictionaryBatchItem[],
): IndustryOptions {
  return buildOptions(
    INDUSTRY_CODES,
    INDUSTRY_FALLBACK_COLORS,
    'merchant.industries',
    items,
  );
}

function buildLegalIdTypeOptions(
  items: readonly SystemDictionaryDataApi.DictionaryBatchItem[],
): LegalIdTypeOptions {
  return buildOptions(
    LEGAL_ID_TYPE_CODES,
    LEGAL_ID_TYPE_FALLBACK_COLORS,
    'merchant.legalIdTypes',
    items,
  );
}

function buildOptions<T extends string>(
  allowlist: readonly T[],
  fallbackColors: readonly SystemDictionaryDataApi.DictionaryColor[],
  labelPrefix: string,
  items: readonly SystemDictionaryDataApi.DictionaryBatchItem[],
) {
  const legalValues = new Set<string>(allowlist);
  const accepted = new Map<T, ClassificationOption<T>>();
  for (const item of items) {
    const value = item.value as T;
    if (!legalValues.has(value) || accepted.has(value)) continue;
    accepted.set(value, {
      color: item.color,
      label: $t(`${labelPrefix}.${value}`),
      value,
    });
  }
  allowlist.forEach((value, index) => {
    if (accepted.has(value)) return;
    accepted.set(value, {
      color: fallbackColors[index] ?? 'default',
      label: $t(`${labelPrefix}.${value}`),
      value,
    });
  });
  return Object.freeze(
    [...accepted.values()].map((option) => Object.freeze(option)),
  );
}

function useMerchantClassificationDictionary(): {
  authenticationTypeOptions: ComputedRef<AuthenticationTypeOptions>;
  error: Readonly<{ value: unknown }>;
  industryOptions: ComputedRef<IndustryOptions>;
  legalIdTypeOptions: ComputedRef<LegalIdTypeOptions>;
  merchantTypeOptions: ComputedRef<MerchantTypeOptions>;
  reload: () => Promise<boolean>;
} {
  const items = shallowRef<SystemDictionaryDataApi.DictionaryBatchResult>({
    [AUTHENTICATION_TYPE_DICTIONARY]: [],
    [INDUSTRY_DICTIONARY]: [],
    [LEGAL_ID_TYPE_DICTIONARY]: [],
    [MERCHANT_TYPE_DICTIONARY]: [],
  });
  const error = shallowRef<unknown>();
  let disposed = false;
  let requestVersion = 0;

  async function reload() {
    if (disposed) return false;
    const currentRequest = ++requestVersion;
    error.value = undefined;
    try {
      const response = await queryDictionaryDataBatch([
        MERCHANT_TYPE_DICTIONARY,
        AUTHENTICATION_TYPE_DICTIONARY,
        INDUSTRY_DICTIONARY,
        LEGAL_ID_TYPE_DICTIONARY,
      ]);
      if (disposed || currentRequest !== requestVersion) return false;
      items.value = response;
      return true;
    } catch (loadError) {
      if (!disposed && currentRequest === requestVersion) {
        error.value = loadError;
      }
      return false;
    }
  }

  void reload();
  onScopeDispose(() => {
    disposed = true;
    requestVersion += 1;
  });

  return {
    authenticationTypeOptions: computed(() =>
      buildAuthenticationTypeOptions(
        items.value[AUTHENTICATION_TYPE_DICTIONARY] ?? [],
      ),
    ),
    error: readonly(error),
    industryOptions: computed(() =>
      buildIndustryOptions(items.value[INDUSTRY_DICTIONARY] ?? []),
    ),
    legalIdTypeOptions: computed(() =>
      buildLegalIdTypeOptions(items.value[LEGAL_ID_TYPE_DICTIONARY] ?? []),
    ),
    merchantTypeOptions: computed(() =>
      buildMerchantTypeOptions(items.value[MERCHANT_TYPE_DICTIONARY] ?? []),
    ),
    reload,
  };
}

export {
  AUTHENTICATION_TYPE_DICTIONARY,
  buildAuthenticationTypeOptions,
  buildIndustryOptions,
  buildLegalIdTypeOptions,
  buildMerchantTypeOptions,
  INDUSTRY_DICTIONARY,
  LEGAL_ID_TYPE_DICTIONARY,
  MERCHANT_TYPE_DICTIONARY,
  useMerchantClassificationDictionary,
};
export type {
  AuthenticationTypeOptions,
  IndustryOptions,
  LegalIdTypeOptions,
  MerchantTypeOptions,
};
