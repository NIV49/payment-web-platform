<script lang="ts" setup>
import type { MerchantLifecycleApi } from '@payment/backoffice-runtime/api/merchant-lifecycle';
import type { MerchantOnboardingApi } from '@payment/backoffice-runtime/api/merchant-onboarding';

import { computed, onBeforeUnmount, onMounted, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';

import { useAccess } from '@vben/access';
import { Page } from '@vben/common-ui';
import { ArrowLeft } from '@vben/icons';

import { isOptimisticLockConflict } from '@payment/backoffice-runtime/api/error-contract';
import {
  getPlatformMerchant,
  reviewMerchant,
} from '@payment/backoffice-runtime/api/merchant-lifecycle';
import {
  getPendingMerchantAmendment,
  reviewMerchantAmendment,
} from '@payment/backoffice-runtime/api/merchant-onboarding';
import { PERMISSION_CODES } from '@payment/backoffice-runtime/api/permission-codes';
import { $t } from '@payment/backoffice-runtime/locales';
import { createIdempotencyKeyGeneration } from '@payment/backoffice-runtime/views/merchant/contract';
import { createMerchantRequestGuard } from '@payment/backoffice-runtime/views/merchant/request-guard';
import {
  Alert,
  Button,
  Descriptions,
  DescriptionsItem,
  message,
  Modal,
  Radio,
  RadioGroup,
  Select,
  Spin,
  Tag,
} from 'antdv-next';

import MerchantProfileDetails from '../components/merchant-profile-details.vue';

type ReviewReasonCode =
  | MerchantLifecycleApi.ReasonCode
  | MerchantOnboardingApi.AmendmentReviewRequest['reasonCode'];

const route = useRoute();
const router = useRouter();
const { hasAccessByCodes } = useAccess();
const merchantId = computed(() =>
  typeof route.params.merchantId === 'string' ? route.params.merchantId : '',
);
const reviewMode = computed(() => route.name === 'MerchantReview');
const detail = ref<MerchantLifecycleApi.PlatformMerchantDetail>();
const pending = ref<MerchantOnboardingApi.PendingAmendment | null>();
const loading = ref(true);
const loadFailed = ref(false);
const stale = ref(false);
const reviewOpen = ref(false);
const reviewFailed = ref(false);
const reviewSubmitting = ref(false);
const reviewDecision = ref<'APPROVE' | 'REJECT'>('APPROVE');
const reviewReasonCode = ref<ReviewReasonCode>();
const loadGuard = createMerchantRequestGuard();
const reviewGuard = createMerchantRequestGuard();
const reviewKey = createIdempotencyKeyGeneration(() => crypto.randomUUID());

function hasAllAccess(codes: string[]) {
  return codes.every((code) => hasAccessByCodes([code]));
}

const canPreviewDocuments = computed(() =>
  hasAccessByCodes([PERMISSION_CODES.merchantDocumentView]),
);
const creationReview = computed(
  () => pending.value === null && detail.value?.status === 'PENDING_REVIEW',
);
const reviewAvailable = computed(
  () => pending.value !== null || creationReview.value,
);
const canReview = computed(
  () =>
    reviewMode.value &&
    reviewAvailable.value &&
    hasAllAccess([
      PERMISSION_CODES.merchantView,
      PERMISSION_CODES.merchantDocumentView,
      PERMISSION_CODES.merchantReview,
    ]) &&
    (pending.value === null || pending.value?.canCurrentActorReview === true),
);
const displayedProfile = computed(() => pending.value?.profile ?? detail.value);
const pageTitle = computed(() =>
  reviewMode.value
    ? $t('merchant.detail.reviewTitle')
    : $t('merchant.detail.title'),
);

function reviewReasonOptions(): ReviewReasonCode[] {
  if (creationReview.value) {
    return reviewDecision.value === 'APPROVE'
      ? ['PROFILE_VERIFIED']
      : ['PROFILE_MISMATCH', 'REGISTRATION_UNVERIFIED', 'COMPLIANCE_REJECTED'];
  }
  return reviewDecision.value === 'APPROVE'
    ? ['PROFILE_AMENDMENT_VERIFIED']
    : [
        'PROFILE_AMENDMENT_MISMATCH',
        'DOCUMENT_UNVERIFIED',
        'COMPLIANCE_REJECTED',
      ];
}

function reviewReasonLabel(value: ReviewReasonCode) {
  return creationReview.value
    ? $t(`merchant.reasons.${value}`)
    : $t(`merchant.onboarding.review.reasons.${value}`);
}

function onReviewDecisionChange(value: unknown) {
  if (value !== 'APPROVE' && value !== 'REJECT') return;
  reviewDecision.value = value;
  reviewReasonCode.value = reviewReasonOptions()[0];
  reviewFailed.value = false;
  reviewKey.markEdited();
}

function onReviewReasonChange(value: unknown) {
  const allowed = reviewReasonOptions();
  reviewReasonCode.value = allowed.includes(value as ReviewReasonCode)
    ? (value as ReviewReasonCode)
    : undefined;
  reviewFailed.value = false;
  reviewKey.markEdited();
}

function openReview() {
  if (!canReview.value) return;
  reviewDecision.value = 'APPROVE';
  reviewReasonCode.value = reviewReasonOptions()[0];
  reviewFailed.value = false;
  reviewKey.reset();
  reviewOpen.value = true;
}

async function loadPage() {
  loadGuard.invalidate();
  loading.value = true;
  loadFailed.value = false;
  stale.value = false;
  const currentMerchantId = merchantId.value;
  if (
    !currentMerchantId ||
    !hasAccessByCodes([PERMISSION_CODES.merchantView])
  ) {
    loading.value = false;
    loadFailed.value = true;
    return false;
  }
  const scope = `${reviewMode.value ? 'review' : 'detail'}:${currentMerchantId}`;
  const request = loadGuard.begin(scope);
  try {
    const [effective, pendingAmendment] = await Promise.all([
      getPlatformMerchant(currentMerchantId),
      reviewMode.value
        ? getPendingMerchantAmendment(currentMerchantId)
        : Promise.resolve(undefined),
    ]);
    if (!loadGuard.isCurrent(request, scope)) return false;
    detail.value = effective;
    pending.value = pendingAmendment;
    return true;
  } catch {
    if (loadGuard.isCurrent(request, scope)) loadFailed.value = true;
    return false;
  } finally {
    if (loadGuard.isCurrent(request, scope)) loading.value = false;
  }
}

async function submitReview(
  decision = reviewDecision.value,
  reasonCode = reviewReasonCode.value,
) {
  const currentDetail = detail.value;
  const currentMerchantId = merchantId.value;
  const currentPending = pending.value;
  if (
    (decision !== 'APPROVE' && decision !== 'REJECT') ||
    !canReview.value ||
    !currentDetail ||
    !currentMerchantId ||
    !reasonCode ||
    reviewSubmitting.value
  ) {
    return false;
  }
  reviewDecision.value = decision;
  if (!reviewReasonOptions().includes(reasonCode)) return false;
  reviewReasonCode.value = reasonCode;
  reviewSubmitting.value = true;
  reviewFailed.value = false;
  const idempotencyKey = reviewKey.current();
  const scope = `${currentMerchantId}:${currentPending?.amendmentId ?? 'creation'}:${idempotencyKey}`;
  const request = reviewGuard.begin(scope);
  try {
    if (currentPending) {
      await reviewMerchantAmendment(
        currentMerchantId,
        currentPending.amendmentId,
        {
          decision,
          expectedVersion: currentPending.rowVersion,
          idempotencyKey,
          reasonCode:
            reasonCode as MerchantOnboardingApi.AmendmentReviewRequest['reasonCode'],
        },
      );
    } else if (creationReview.value) {
      await reviewMerchant(currentMerchantId, {
        decision,
        expectedVersion: currentDetail.rowVersion,
        idempotencyKey,
        reasonCode: reasonCode as MerchantLifecycleApi.ReasonCode,
      });
    } else {
      return false;
    }
    if (!reviewGuard.isCurrent(request, scope)) return false;
    message.success($t('merchant.detail.reviewSuccess'));
    reviewOpen.value = false;
    await router.push({ name: 'MerchantList' });
    return true;
  } catch (error) {
    if (!reviewGuard.isCurrent(request, scope)) return false;
    if (isOptimisticLockConflict(error)) {
      reviewOpen.value = false;
      await loadPage();
      stale.value = true;
    } else {
      reviewFailed.value = true;
    }
    return false;
  } finally {
    if (reviewGuard.isCurrent(request, scope)) reviewSubmitting.value = false;
  }
}

function returnToList() {
  void router.push({ name: 'MerchantList' });
}

onMounted(loadPage);
onBeforeUnmount(() => {
  loadGuard.invalidate();
  reviewGuard.invalidate();
});

defineExpose({ loadPage, openReview, submitReview });
</script>

<template>
  <Page>
    <template #title>
      <div class="flex items-center gap-2 text-lg font-semibold">
        <Button
          :aria-label="$t('common.back')"
          data-test="merchant-return"
          shape="circle"
          type="text"
          @click="returnToList"
        >
          <ArrowLeft class="size-4" />
        </Button>
        <span>{{ pageTitle }}</span>
      </div>
    </template>
    <template #extra>
      <Button
        v-if="canReview"
        data-test="merchant-review"
        type="primary"
        @click="openReview"
      >
        {{ $t('merchant.detail.reviewAction') }}
      </Button>
    </template>

    <Alert
      v-if="loadFailed"
      class="mb-4"
      show-icon
      type="error"
      :message="$t('merchant.detail.loadFailed')"
    />
    <Alert
      v-if="stale"
      class="mb-4"
      show-icon
      type="warning"
      :message="$t('merchant.onboarding.stale')"
    />
    <Alert
      v-if="reviewMode && !loading && !loadFailed && !reviewAvailable"
      class="mb-4"
      show-icon
      type="info"
      :message="$t('merchant.detail.reviewUnavailable')"
    />

    <Spin :spinning="loading">
      <div v-if="detail && displayedProfile" class="space-y-6">
        <Descriptions bordered :column="{ xs: 1, md: 2, xl: 3 }" size="small">
          <DescriptionsItem :label="$t('merchant.fields.merchantCode')">
            {{ detail.merchantCode }}
          </DescriptionsItem>
          <DescriptionsItem :label="$t('merchant.fields.merchantStatus')">
            <Tag>{{ $t(`merchant.status.${detail.status}`) }}</Tag>
          </DescriptionsItem>
          <DescriptionsItem :label="$t('merchant.fields.rowVersion')">
            {{ detail.rowVersion }}
          </DescriptionsItem>
        </Descriptions>
        <MerchantProfileDetails
          :amendment-id="pending?.amendmentId"
          :can-preview-documents="canPreviewDocuments"
          :merchant-id="merchantId"
          :profile="displayedProfile"
        />
      </div>
    </Spin>

    <Modal
      v-model:open="reviewOpen"
      :confirm-loading="reviewSubmitting"
      :ok-button-props="{ disabled: !reviewReasonCode }"
      :title="$t('merchant.detail.reviewTitle')"
      @ok="submitReview()"
    >
      <Alert
        v-if="reviewFailed"
        class="mb-4"
        show-icon
        type="error"
        :message="$t('merchant.onboarding.review.failed')"
      />
      <div class="space-y-4">
        <div>
          <div class="mb-2 text-sm font-medium">
            {{ $t('merchant.detail.reviewDecision') }}
          </div>
          <RadioGroup
            :value="reviewDecision"
            @update:value="onReviewDecisionChange"
          >
            <Radio value="APPROVE">
              {{ $t('merchant.onboarding.review.approve') }}
            </Radio>
            <Radio value="REJECT">
              {{ $t('merchant.onboarding.review.reject') }}
            </Radio>
          </RadioGroup>
        </div>
        <div>
          <div class="mb-2 text-sm font-medium">
            {{ $t('merchant.actions.reasonCode') }}
          </div>
          <Select
            allow-clear
            class="w-full"
            :options="
              reviewReasonOptions().map((value) => ({
                label: reviewReasonLabel(value),
                value,
              }))
            "
            :value="reviewReasonCode"
            @update:value="onReviewReasonChange"
          />
        </div>
      </div>
    </Modal>
  </Page>
</template>
