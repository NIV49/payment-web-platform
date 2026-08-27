<script lang="ts" setup>
import type { MerchantLifecycleApi } from '@payment/backoffice-runtime/api/merchant-lifecycle';
import type { SubmissionFormValues } from '@payment/backoffice-runtime/views/merchant/contract';

import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue';

import { useAccess } from '@vben/access';
import { Page } from '@vben/common-ui';

import { useVbenForm, z } from '@payment/backoffice-runtime/adapter/form';
import { isOptimisticLockConflict } from '@payment/backoffice-runtime/api/error-contract';
import {
  getMerchantApplication,
  isMerchantStateConflict,
  submitMerchantApplication,
} from '@payment/backoffice-runtime/api/merchant-lifecycle';
import { PERMISSION_CODES } from '@payment/backoffice-runtime/api/permission-codes';
import { $t } from '@payment/backoffice-runtime/locales';
import {
  buildSubmissionRequest,
  createIdempotencyKeyGeneration,
} from '@payment/backoffice-runtime/views/merchant/contract';
import { createMerchantRequestGuard } from '@payment/backoffice-runtime/views/merchant/request-guard';
import {
  Alert,
  Button,
  Card,
  Descriptions,
  DescriptionsItem,
  Empty,
  message,
  Spin,
  Tag,
} from 'antdv-next';

const { hasAccessByCodes } = useAccess();
const application = ref<MerchantLifecycleApi.MerchantDetail | null>();
const loadFailed = ref(false);
const loading = ref(true);
const submitting = ref(false);
const submitFailed = ref(false);
const guard = createMerchantRequestGuard();
const mutationGuard = createMerchantRequestGuard();
const idempotency = createIdempotencyKeyGeneration(() => crypto.randomUUID());
let disposed = false;

const expectedVersion = computed<null | number>(() =>
  application.value?.status === 'REVIEW_REJECTED'
    ? application.value.rowVersion
    : null,
);
const canEdit = computed(
  () =>
    application.value === null ||
    application.value?.status === 'REVIEW_REJECTED',
);
const requiredPermission = computed(() =>
  expectedVersion.value === null
    ? PERMISSION_CODES.merchantSubmit
    : PERMISSION_CODES.merchantResubmit,
);
const canSubmit = computed(
  () => canEdit.value && hasAccessByCodes([requiredPermission.value]),
);

const [ProfileForm, profileFormApi] = useVbenForm<SubmissionFormValues>({
  commonConfig: { componentProps: { class: 'w-full' } },
  handleValuesChange() {
    idempotency.markEdited();
  },
  layout: 'vertical',
  schema: [
    {
      component: 'Input',
      fieldName: 'legalName',
      label: $t('merchant.fields.legalName'),
      rules: z.string().trim().min(1).max(200),
    },
    {
      component: 'Input',
      fieldName: 'displayName',
      label: $t('merchant.fields.displayName'),
      rules: z.string().trim().min(1).max(128),
    },
    {
      component: 'Input',
      componentProps: { maxLength: 2 },
      fieldName: 'registrationCountry',
      label: $t('merchant.fields.registrationCountry'),
      rules: z.string().regex(/^[A-Z]{2}$/),
    },
    {
      component: 'Input',
      componentProps: { autocomplete: 'off', maxLength: 128 },
      fieldName: 'registrationNumber',
      help: $t('merchant.profile.registrationNumberHelp'),
      label: $t('merchant.fields.registrationNumber'),
      rules: z.string().trim().max(128).optional(),
    },
  ],
  showDefaultActions: false,
});

async function loadApplication() {
  if (disposed) return;
  const request = guard.begin('merchant:self');
  loading.value = true;
  loadFailed.value = false;
  idempotency.reset();
  try {
    const response = await getMerchantApplication();
    if (!guard.isCurrent(request, 'merchant:self')) return;
    application.value = response.merchant;
    await nextTick();
    if (!guard.isCurrent(request, 'merchant:self')) return;
    if (response.merchant === null) {
      await profileFormApi.reset();
    }
    if (response.merchant?.status === 'REVIEW_REJECTED') {
      await profileFormApi.reset();
      await profileFormApi.setValues({
        displayName: response.merchant.displayName,
        legalName: response.merchant.legalName,
        registrationCountry: response.merchant.registrationCountry,
        registrationNumber: '',
      });
      idempotency.reset();
    }
  } catch {
    if (guard.isCurrent(request, 'merchant:self')) loadFailed.value = true;
  } finally {
    if (guard.isCurrent(request, 'merchant:self')) loading.value = false;
  }
}

async function submit() {
  if (!canSubmit.value || submitting.value) return;
  const scope = 'merchant:self-submit';
  const mutation = mutationGuard.begin(scope);
  submitting.value = true;
  submitFailed.value = false;
  try {
    const { valid } = await profileFormApi.validate();
    if (!mutationGuard.isCurrent(mutation, scope) || !valid) return;
    const values = await profileFormApi.getValues<SubmissionFormValues>();
    if (!mutationGuard.isCurrent(mutation, scope)) return;
    let request: MerchantLifecycleApi.SubmissionRequest;
    try {
      request = buildSubmissionRequest(
        values,
        expectedVersion.value,
        idempotency.current(),
        application.value?.registrationCountry,
      );
    } catch {
      if (mutationGuard.isCurrent(mutation, scope)) {
        message.error($t('merchant.profile.registrationNumberRequired'));
      }
      return;
    }
    await submitMerchantApplication(request);
    if (!mutationGuard.isCurrent(mutation, scope)) return;
    message.success($t('merchant.profile.submitSuccess'));
    await profileFormApi.reset();
    if (!mutationGuard.isCurrent(mutation, scope)) return;
    idempotency.reset();
    await loadApplication();
  } catch (error) {
    if (!mutationGuard.isCurrent(mutation, scope)) return;
    if (isOptimisticLockConflict(error) || isMerchantStateConflict(error)) {
      await profileFormApi.reset();
      if (!mutationGuard.isCurrent(mutation, scope)) return;
      idempotency.reset();
      await loadApplication();
    } else {
      submitFailed.value = true;
    }
  } finally {
    if (mutationGuard.isCurrent(mutation, scope)) submitting.value = false;
  }
}

onMounted(loadApplication);
onBeforeUnmount(() => {
  disposed = true;
  guard.invalidate();
  mutationGuard.invalidate();
  idempotency.reset();
  void profileFormApi.reset();
});
</script>

<template>
  <Page auto-content-height>
    <Spin :spinning="loading">
      <Alert
        v-if="loadFailed"
        show-icon
        type="error"
        :message="$t('merchant.profile.loadFailed')"
      >
        <template #action>
          <Button size="small" @click="loadApplication">
            {{ $t('common.retry') }}
          </Button>
        </template>
      </Alert>

      <Card v-else-if="canEdit" :title="$t('merchant.profile.title')">
        <Alert
          v-if="application === null"
          class="mb-4"
          show-icon
          type="info"
          :message="$t('merchant.profile.notSubmitted')"
        />
        <Alert
          v-else-if="application?.lastDecision"
          class="mb-4"
          show-icon
          type="warning"
          :message="
            $t(`merchant.reasons.${application.lastDecision.reasonCode}`)
          "
        />
        <ProfileForm />
        <Alert
          v-if="submitFailed"
          class="mt-4"
          show-icon
          type="error"
          :message="$t('merchant.profile.submitFailed')"
        />
        <div class="mt-4 flex justify-end">
          <Button
            type="primary"
            :disabled="!canSubmit"
            :loading="submitting"
            @click="submit"
          >
            {{
              $t(
                expectedVersion === null
                  ? 'merchant.profile.submit'
                  : 'merchant.profile.resubmit',
              )
            }}
          </Button>
        </div>
      </Card>

      <Card v-else-if="application" :title="$t('merchant.profile.title')">
        <Alert
          class="mb-4"
          show-icon
          type="info"
          :message="$t('merchant.profile.readOnly')"
        />
        <Descriptions bordered :column="1" size="small">
          <DescriptionsItem :label="$t('merchant.fields.merchantCode')">
            {{ application.merchantCode }}
          </DescriptionsItem>
          <DescriptionsItem :label="$t('merchant.fields.legalName')">
            {{ application.legalName }}
          </DescriptionsItem>
          <DescriptionsItem :label="$t('merchant.fields.displayName')">
            {{ application.displayName }}
          </DescriptionsItem>
          <DescriptionsItem :label="$t('merchant.fields.registrationCountry')">
            {{ application.registrationCountry }}
          </DescriptionsItem>
          <DescriptionsItem :label="$t('merchant.fields.registrationNumber')">
            {{ application.registrationNumberMasked }}
          </DescriptionsItem>
          <DescriptionsItem :label="$t('merchant.fields.status')">
            <Tag>
              {{ $t(`merchant.status.${application.status}`) }}
            </Tag>
          </DescriptionsItem>
          <DescriptionsItem
            v-if="application.lastDecision"
            :label="$t('merchant.fields.lastDecision')"
          >
            {{ $t(`merchant.reasons.${application.lastDecision.reasonCode}`) }}
          </DescriptionsItem>
        </Descriptions>
      </Card>

      <Empty
        v-else-if="!loading"
        :description="$t('merchant.profile.notSubmitted')"
      />
    </Spin>
  </Page>
</template>
