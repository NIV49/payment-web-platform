<script lang="ts" setup>
import type { MerchantLifecycleApi } from '@payment/backoffice-runtime/api/merchant-lifecycle';
import type { MerchantOnboardingApi } from '@payment/backoffice-runtime/api/merchant-onboarding';

import type { MerchantOnboardingFormValues } from '../onboarding-form';

import {
  computed,
  nextTick,
  onBeforeUnmount,
  onMounted,
  reactive,
  ref,
  watch,
} from 'vue';
import { useRoute, useRouter } from 'vue-router';

import { useAccess } from '@vben/access';
import { Page } from '@vben/common-ui';

import { useVbenForm } from '@payment/backoffice-runtime/adapter/form';
import { isOptimisticLockConflict } from '@payment/backoffice-runtime/api/error-contract';
import { getPlatformMerchant } from '@payment/backoffice-runtime/api/merchant-lifecycle';
import {
  createPlatformMerchant,
  getEligibleMerchantTenants,
  getPendingMerchantAmendment,
  submitMerchantAmendment,
} from '@payment/backoffice-runtime/api/merchant-onboarding';
import { PERMISSION_CODES } from '@payment/backoffice-runtime/api/permission-codes';
import { $t } from '@payment/backoffice-runtime/locales';
import { createIdempotencyKeyGeneration } from '@payment/backoffice-runtime/views/merchant/contract';
import { createMerchantRequestGuard } from '@payment/backoffice-runtime/views/merchant/request-guard';
import { Alert, Button, message, Select, Spin, Switch } from 'antdv-next';

import ClassificationDictionaryAlert from '../components/classification-dictionary-alert.vue';
import MerchantDocumentUpload from '../components/merchant-document-upload.vue';
import { useMerchantClassificationDictionary } from '../merchant-classification-dictionary';
import {
  buildMerchantOnboardingSections,
  buildMerchantProfileInput,
  effectiveDetailToForm,
  MERCHANT_ONBOARDING_FIELD_NAMES,
  validateMerchantOnboardingFormValues,
} from '../onboarding-form';

interface UploadControl {
  beginBinding?: () => void;
  finishBinding?: (bound: boolean) => void;
  markBound?: () => void;
}

type DocumentFieldName =
  | 'brandLogoDocumentId'
  | 'businessLicenseDocumentId'
  | 'legalIdBackDocumentId'
  | 'legalIdFrontDocumentId'
  | 'legalIdHoldingDocumentId';

const route = useRoute();
const router = useRouter();
const { hasAccessByCodes } = useAccess();
const classifications = useMerchantClassificationDictionary();
const merchantId = computed(() =>
  typeof route.params.merchantId === 'string'
    ? route.params.merchantId
    : undefined,
);
const mode = computed<'create' | 'edit'>(() =>
  merchantId.value ? 'edit' : 'create',
);
const detail = ref<MerchantLifecycleApi.PlatformMerchantDetail>();
const pending = ref<MerchantOnboardingApi.PendingAmendment | null>();
const eligibleTenants = ref<MerchantOnboardingApi.EligibleTenant[]>([]);
const targetTenantId = ref('');
const loadFailed = ref(false);
const loading = ref(true);
const saveFailed = ref(false);
const stale = ref(false);
const submitting = ref(false);
const replacements = reactive({ legalIdNo: true, registrationNumber: true });
const requiredReplacements = reactive({
  legalIdNo: false,
  registrationNumber: false,
});
const originalProtectedContext = reactive({
  hasLegalId: false,
  hasRegistrationNumber: false,
  legalIdTypeCode: '',
  registrationCountry: '',
});
const formValues = reactive<MerchantOnboardingFormValues>({
  authenticationType: '',
  brandLogoDocumentId: '',
  brandName: '',
  businessLicenseDocumentId: '',
  contactEmail: '',
  contactPhone: '',
  displayName: '',
  industryCode: '',
  legalIdBackDocumentId: '',
  legalIdFrontDocumentId: '',
  legalIdHoldingDocumentId: '',
  legalIdNo: '',
  legalIdTypeCode: '',
  legalIdValidity: ['', ''],
  legalName: '',
  legalPersonName: '',
  marketCodes: [],
  merchantTypeCode: '',
  operatingAddress: '',
  registeredAddress: '',
  registrationCountry: '',
  registrationNumber: '',
  remarks: '',
});
const brandLogoUpload = ref<UploadControl>();
const businessLicenseUpload = ref<UploadControl>();
const legalIdFrontUpload = ref<UploadControl>();
const legalIdBackUpload = ref<UploadControl>();
const legalIdHoldingUpload = ref<UploadControl>();
const loadGuard = createMerchantRequestGuard();
const tenantGuard = createMerchantRequestGuard();
const mutationGuard = createMerchantRequestGuard();
const mutationKey = createIdempotencyKeyGeneration(() => crypto.randomUUID());

function hasAllAccess(codes: string[]) {
  return codes.every((code) => hasAccessByCodes([code]));
}

const canCreate = computed(() =>
  hasAllAccess([
    PERMISSION_CODES.merchantCreate,
    PERMISSION_CODES.merchantDocumentUpload,
  ]),
);
const canAmend = computed(
  () =>
    mode.value === 'edit' &&
    (detail.value?.status === 'ACTIVE' ||
      detail.value?.status === 'DISABLED') &&
    hasAllAccess([
      PERMISSION_CODES.merchantView,
      PERMISSION_CODES.merchantAmend,
      PERMISSION_CODES.merchantDocumentUpload,
      PERMISSION_CODES.merchantDocumentView,
    ]),
);
const canSubmit = computed(
  () =>
    pending.value === null &&
    (mode.value === 'create' ? canCreate.value : canAmend.value),
);
const tenantOptions = computed(() =>
  eligibleTenants.value.map((tenant) => ({
    label: `${tenant.tenantName} (${tenant.tenantCode})`,
    value: tenant.tenantId,
  })),
);

function currentDictionaryOptions() {
  return {
    authenticationTypeOptions: classifications.authenticationTypeOptions.value,
    industryOptions: classifications.industryOptions.value,
    legalIdTypeOptions: classifications.legalIdTypeOptions.value,
    merchantTypeOptions: classifications.merchantTypeOptions.value,
  };
}

function currentSections() {
  return buildMerchantOnboardingSections(
    currentDictionaryOptions(),
    mode.value,
    replacements,
  );
}

function onFormEdited() {
  mutationKey.markEdited();
  saveFailed.value = false;
  stale.value = false;
}

const onboardingFieldNames = new Set<string>(MERCHANT_ONBOARDING_FIELD_NAMES);

function onFormValuesChange(
  values: Readonly<Record<string, unknown>>,
  changedFields: string[],
) {
  if (submitting.value) return;
  Object.assign(
    formValues,
    Object.fromEntries(
      changedFields
        .filter((field) => onboardingFieldNames.has(field))
        .map((field) => [field, values[field]]),
    ),
  );
  const previousLegalIdReplacement = replacements.legalIdNo;
  const previousRegistrationReplacement = replacements.registrationNumber;
  syncRequiredReplacements();
  if (
    previousLegalIdReplacement !== replacements.legalIdNo ||
    previousRegistrationReplacement !== replacements.registrationNumber
  ) {
    syncFormState();
  }
  onFormEdited();
}

function syncRequiredReplacements() {
  if (mode.value !== 'edit') return;
  requiredReplacements.legalIdNo =
    !originalProtectedContext.hasLegalId ||
    formValues.legalIdTypeCode !== originalProtectedContext.legalIdTypeCode;
  requiredReplacements.registrationNumber =
    !originalProtectedContext.hasRegistrationNumber ||
    formValues.registrationCountry !==
      originalProtectedContext.registrationCountry;
  if (requiredReplacements.legalIdNo) replacements.legalIdNo = true;
  if (requiredReplacements.registrationNumber) {
    replacements.registrationNumber = true;
  }
}

const initialSections = currentSections();
const formOptions = (schema: (typeof initialSections)[number]['schema']) => ({
  commonConfig: {
    colon: true,
    disabled: true,
    formItemClass: 'col-span-2 md:col-span-1',
  },
  handleValuesChange: onFormValuesChange,
  layout: 'vertical' as const,
  schema,
  showDefaultActions: false,
  wrapperClass: 'grid-cols-2 gap-x-4',
});

const [MerchantBasicForm, merchantBasicFormApi] = useVbenForm(
  formOptions(initialSections[0]?.schema ?? []),
);
const [SubjectForm, subjectFormApi] = useVbenForm(
  formOptions(initialSections[1]?.schema ?? []),
);
const [LegalRepresentativeForm, legalRepresentativeFormApi] = useVbenForm(
  formOptions(initialSections[2]?.schema ?? []),
);

function syncFormState() {
  const sections = currentSections();
  const disabled = loading.value || submitting.value || !canSubmit.value;
  const commonConfig = {
    colon: true,
    disabled,
    formItemClass: 'col-span-2 md:col-span-1',
  };
  merchantBasicFormApi.setState({
    commonConfig,
    schema: sections[0]?.schema ?? [],
  });
  subjectFormApi.setState({
    commonConfig,
    schema: sections[1]?.schema ?? [],
  });
  legalRepresentativeFormApi.setState({
    commonConfig,
    schema: sections[2]?.schema ?? [],
  });
}

watch(
  [
    () => classifications.authenticationTypeOptions.value,
    () => classifications.industryOptions.value,
    () => classifications.legalIdTypeOptions.value,
    () => classifications.merchantTypeOptions.value,
  ],
  () => {
    if (!loading.value) syncFormState();
  },
);

async function loadEligibleTenants(search = '') {
  if (submitting.value) return;
  const scope = `eligible:${search}`;
  const request = tenantGuard.begin(scope);
  try {
    const response = await getEligibleMerchantTenants({
      page: 1,
      pageSize: 100,
      ...(search ? { tenantName: search } : {}),
    });
    if (tenantGuard.isCurrent(request, scope))
      eligibleTenants.value = response.items;
  } catch {
    if (tenantGuard.isCurrent(request, scope)) loadFailed.value = true;
  }
}

async function applyValues(values: MerchantOnboardingFormValues) {
  Object.assign(formValues, values);
  await Promise.all([
    merchantBasicFormApi.setValues(values),
    subjectFormApi.setValues(values),
    legalRepresentativeFormApi.setValues(values),
  ]);
}

async function loadPage() {
  loadGuard.invalidate();
  loadFailed.value = false;
  stale.value = false;
  loading.value = true;
  if (mode.value === 'create') {
    if (
      !hasAllAccess([
        PERMISSION_CODES.merchantCreate,
        PERMISSION_CODES.merchantDocumentUpload,
      ])
    ) {
      loadFailed.value = true;
      loading.value = false;
      return;
    }
    await loadEligibleTenants();
    pending.value = null;
    loading.value = false;
    syncFormState();
    return;
  }

  const currentMerchantId = merchantId.value;
  if (
    !currentMerchantId ||
    !hasAllAccess([
      PERMISSION_CODES.merchantView,
      PERMISSION_CODES.merchantAmend,
      PERMISSION_CODES.merchantDocumentUpload,
      PERMISSION_CODES.merchantDocumentView,
    ])
  ) {
    loadFailed.value = true;
    loading.value = false;
    return;
  }
  const scope = `merchant:${currentMerchantId}`;
  const request = loadGuard.begin(scope);
  try {
    const [effective, pendingAmendment] = await Promise.all([
      getPlatformMerchant(currentMerchantId),
      getPendingMerchantAmendment(currentMerchantId),
    ]);
    if (!loadGuard.isCurrent(request, scope)) return;
    detail.value = effective;
    pending.value = pendingAmendment;
    targetTenantId.value = effective.tenantId;
    const mapped = effectiveDetailToForm(effective);
    originalProtectedContext.hasLegalId = effective.legalIdNoMasked !== null;
    originalProtectedContext.hasRegistrationNumber =
      effective.registrationNumberMasked.length > 0;
    originalProtectedContext.legalIdTypeCode = effective.legalIdTypeCode ?? '';
    originalProtectedContext.registrationCountry =
      effective.registrationCountry;
    replacements.legalIdNo = mapped.replacements.legalIdNo;
    replacements.registrationNumber = mapped.replacements.registrationNumber;
    requiredReplacements.legalIdNo = mapped.replacements.legalIdNo;
    requiredReplacements.registrationNumber =
      mapped.replacements.registrationNumber;
    loading.value = false;
    syncFormState();
    await nextTick();
    if (!loadGuard.isCurrent(request, scope)) return;
    await applyValues(mapped.values);
    mutationKey.reset();
  } catch {
    if (loadGuard.isCurrent(request, scope)) {
      loadFailed.value = true;
      loading.value = false;
    }
  }
}

function setTargetTenant(value?: string) {
  if (submitting.value) return;
  const normalized = value ?? '';
  if (targetTenantId.value === normalized) return;
  targetTenantId.value = normalized;
  mutationKey.markEdited();
  saveFailed.value = false;
}

function onTargetTenantChange(value: unknown) {
  setTargetTenant(typeof value === 'string' ? value : undefined);
}

function setReplacement(
  field: 'legalIdNo' | 'registrationNumber',
  value: boolean,
) {
  if (submitting.value || !canSubmit.value) return;
  if (requiredReplacements[field] && !value) return;
  replacements[field] = value;
  syncFormState();
  mutationKey.markEdited();
}

function onReplacementChange(
  field: 'legalIdNo' | 'registrationNumber',
  value: unknown,
) {
  setReplacement(field, value === true);
}

function setDocument(field: DocumentFieldName, value: string) {
  if (submitting.value || !canSubmit.value) return;
  formValues[field] = value;
  let api = legalRepresentativeFormApi;
  if (field === 'brandLogoDocumentId') api = merchantBasicFormApi;
  if (field === 'businessLicenseDocumentId') api = subjectFormApi;
  void api.setValues({ [field]: value });
  onFormEdited();
}

function collectValues() {
  const values = {
    ...formValues,
    legalIdValidity: [...formValues.legalIdValidity],
    marketCodes: [...formValues.marketCodes],
  } as MerchantOnboardingFormValues;
  const validation = validateMerchantOnboardingFormValues(
    values,
    mode.value,
    replacements,
  );
  if (!validation.valid) {
    saveFailed.value = true;
    void Promise.all([
      merchantBasicFormApi.validate(),
      subjectFormApi.validate(),
      legalRepresentativeFormApi.validate(),
    ]).catch(() => undefined);
    return;
  }
  return values;
}

function uploadControls() {
  return [
    brandLogoUpload.value,
    businessLicenseUpload.value,
    legalIdFrontUpload.value,
    legalIdBackUpload.value,
    legalIdHoldingUpload.value,
  ];
}

function beginUploadsBinding() {
  uploadControls().forEach((control) => control?.beginBinding?.());
}

function finishUploadsBinding(bound: boolean) {
  uploadControls().forEach((control) => {
    if (control?.finishBinding) {
      control.finishBinding(bound);
    } else if (bound) {
      control?.markBound?.();
    }
  });
}

async function submitForm() {
  if (!canSubmit.value || submitting.value) return false;
  const currentMode = mode.value;
  const currentMerchantId = merchantId.value;
  const currentTargetTenantId = targetTenantId.value;
  const effective = detail.value;
  const expectedMerchantVersion = effective?.rowVersion;
  if (currentMode === 'create' && !currentTargetTenantId) {
    saveFailed.value = true;
    return false;
  }
  if (
    currentMode === 'edit' &&
    (expectedMerchantVersion === undefined || !currentMerchantId)
  ) {
    saveFailed.value = true;
    return false;
  }
  let values: MerchantOnboardingFormValues | undefined;
  try {
    values = collectValues();
  } catch {
    saveFailed.value = true;
    return false;
  }
  if (!values) return false;
  const idempotencyKey = mutationKey.current();
  const scope = `${currentMode}:${currentMerchantId ?? currentTargetTenantId}:${idempotencyKey}`;
  const request = mutationGuard.begin(scope);
  saveFailed.value = false;
  submitting.value = true;
  syncFormState();
  beginUploadsBinding();
  try {
    const profile = buildMerchantProfileInput(
      values,
      currentMode,
      replacements,
    );
    if (currentMode === 'create') {
      await createPlatformMerchant({
        idempotencyKey,
        profile,
        targetTenantId: currentTargetTenantId,
      });
    }
    if (
      currentMode === 'edit' &&
      currentMerchantId &&
      expectedMerchantVersion !== undefined
    ) {
      await submitMerchantAmendment(currentMerchantId, {
        expectedMerchantVersion,
        idempotencyKey,
        profile,
      });
    }
    if (!mutationGuard.isCurrent(request, scope)) return false;
    finishUploadsBinding(true);
    message.success($t('merchant.onboarding.submitSuccess'));
    await router.push({ name: 'MerchantList' });
    return true;
  } catch (error) {
    if (!mutationGuard.isCurrent(request, scope)) return false;
    finishUploadsBinding(false);
    if (currentMode === 'edit' && isOptimisticLockConflict(error)) {
      await loadPage();
      stale.value = true;
    } else {
      saveFailed.value = true;
    }
    return false;
  } finally {
    if (mutationGuard.isCurrent(request, scope)) {
      submitting.value = false;
      syncFormState();
    }
  }
}

function returnToList() {
  void router.push({ name: 'MerchantList' });
}

onMounted(loadPage);
onBeforeUnmount(() => {
  loadGuard.invalidate();
  tenantGuard.invalidate();
  mutationGuard.invalidate();
});

defineExpose({
  loadPage,
  setReplacement,
  setTargetTenant,
  submitForm,
});
</script>

<template>
  <Page
    :title="
      mode === 'create'
        ? $t('merchant.onboarding.createTitle')
        : $t('merchant.onboarding.editTitle')
    "
  >
    <Spin :spinning="loading">
      <Alert
        v-if="loadFailed"
        class="mb-4"
        show-icon
        type="error"
        :message="$t('merchant.onboarding.loadFailed')"
      />
      <Alert
        v-if="stale"
        class="mb-4"
        show-icon
        type="warning"
        :message="$t('merchant.onboarding.stale')"
      />
      <Alert
        v-if="saveFailed"
        class="mb-4"
        show-icon
        type="error"
        :message="$t('merchant.onboarding.submitFailed')"
      />
      <ClassificationDictionaryAlert
        :error="classifications.error.value"
        :reload="classifications.reload"
      />

      <div v-if="!loadFailed && !loading" class="bg-card border rounded-sm">
        <section v-if="mode === 'create'" class="border-b p-5">
          <h2 class="mb-4 text-base font-semibold">
            {{ $t('merchant.onboarding.targetTenant') }}
          </h2>
          <Select
            allow-clear
            class="w-full max-w-xl"
            :disabled="submitting || !canSubmit"
            show-search
            :filter-option="false"
            :options="tenantOptions"
            :value="targetTenantId || undefined"
            @search="loadEligibleTenants"
            @update:value="onTargetTenantChange"
          />
        </section>

        <section class="border-b p-5">
          <h2 class="mb-4 text-base font-semibold">
            {{ $t('merchant.onboarding.sections.merchantBasic') }}
          </h2>
          <MerchantBasicForm />
          <div class="mt-2">
            <div class="mb-2 text-sm font-medium">
              {{ $t('merchant.onboarding.fields.brandLogoDocumentId') }}
            </div>
            <MerchantDocumentUpload
              ref="brandLogoUpload"
              :disabled="submitting || !canSubmit"
              kind="BRAND_LOGO"
              :merchant-id="merchantId"
              :model-value="formValues.brandLogoDocumentId"
              :target-tenant-id="targetTenantId"
              @update:model-value="setDocument('brandLogoDocumentId', $event)"
            />
          </div>
        </section>

        <section class="border-b p-5">
          <h2 class="mb-4 text-base font-semibold">
            {{ $t('merchant.onboarding.sections.subject') }}
          </h2>
          <SubjectForm />
          <div class="mt-2">
            <div class="mb-2 text-sm font-medium">
              {{ $t('merchant.onboarding.fields.businessLicenseDocumentId') }}
            </div>
            <MerchantDocumentUpload
              ref="businessLicenseUpload"
              :disabled="submitting || !canSubmit"
              kind="BUSINESS_LICENSE"
              :merchant-id="merchantId"
              :model-value="formValues.businessLicenseDocumentId"
              :target-tenant-id="targetTenantId"
              @update:model-value="
                setDocument('businessLicenseDocumentId', $event)
              "
            />
          </div>
        </section>

        <section class="p-5">
          <h2 class="mb-4 text-base font-semibold">
            {{ $t('merchant.onboarding.sections.legalRepresentative') }}
          </h2>
          <div v-if="mode === 'edit'" class="mb-4 flex flex-wrap gap-6">
            <label class="flex items-center gap-2">
              <Switch
                :checked="replacements.legalIdNo"
                :disabled="
                  submitting || requiredReplacements.legalIdNo || !canSubmit
                "
                @update:checked="onReplacementChange('legalIdNo', $event)"
              />
              {{ $t('merchant.onboarding.sensitive.replaceLegalId') }}
            </label>
            <label class="flex items-center gap-2">
              <Switch
                :checked="replacements.registrationNumber"
                :disabled="
                  submitting ||
                  requiredReplacements.registrationNumber ||
                  !canSubmit
                "
                @update:checked="
                  onReplacementChange('registrationNumber', $event)
                "
              />
              {{ $t('merchant.onboarding.sensitive.replaceRegistration') }}
            </label>
          </div>
          <LegalRepresentativeForm />
          <div class="grid grid-cols-1 gap-5 lg:grid-cols-3">
            <div>
              <div class="mb-2 text-sm font-medium">
                {{ $t('merchant.onboarding.fields.legalIdFrontDocumentId') }}
              </div>
              <MerchantDocumentUpload
                ref="legalIdFrontUpload"
                :disabled="submitting || !canSubmit"
                kind="LEGAL_ID_FRONT"
                :merchant-id="merchantId"
                :model-value="formValues.legalIdFrontDocumentId"
                :target-tenant-id="targetTenantId"
                @update:model-value="
                  setDocument('legalIdFrontDocumentId', $event)
                "
              />
            </div>
            <div>
              <div class="mb-2 text-sm font-medium">
                {{ $t('merchant.onboarding.fields.legalIdBackDocumentId') }}
              </div>
              <MerchantDocumentUpload
                ref="legalIdBackUpload"
                :disabled="submitting || !canSubmit"
                kind="LEGAL_ID_BACK"
                :merchant-id="merchantId"
                :model-value="formValues.legalIdBackDocumentId"
                :target-tenant-id="targetTenantId"
                @update:model-value="
                  setDocument('legalIdBackDocumentId', $event)
                "
              />
            </div>
            <div>
              <div class="mb-2 text-sm font-medium">
                {{ $t('merchant.onboarding.fields.legalIdHoldingDocumentId') }}
              </div>
              <MerchantDocumentUpload
                ref="legalIdHoldingUpload"
                :disabled="submitting || !canSubmit"
                kind="LEGAL_ID_HOLDING"
                :merchant-id="merchantId"
                :model-value="formValues.legalIdHoldingDocumentId"
                :target-tenant-id="targetTenantId"
                @update:model-value="
                  setDocument('legalIdHoldingDocumentId', $event)
                "
              />
            </div>
          </div>
        </section>
      </div>

      <Alert
        v-if="pending"
        class="mt-4"
        show-icon
        type="warning"
        :message="$t('merchant.onboarding.pending.title')"
      />

      <div class="mt-4 flex justify-end gap-2">
        <Button @click="returnToList">
          {{ $t('merchant.onboarding.cancel') }}
        </Button>
        <Button
          v-if="canSubmit"
          type="primary"
          :loading="submitting"
          @click="submitForm"
        >
          {{ $t('merchant.onboarding.submit') }}
        </Button>
      </div>
    </Spin>
  </Page>
</template>
