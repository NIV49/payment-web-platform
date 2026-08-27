<script lang="ts" setup>
import type { MerchantLifecycleApi } from '@payment/backoffice-runtime/api/merchant-lifecycle';
import type { MerchantOnboardingApi } from '@payment/backoffice-runtime/api/merchant-onboarding';

import { onBeforeUnmount, ref } from 'vue';

import { Eye } from '@vben/icons';

import { getAttachedMerchantDocumentContent } from '@payment/backoffice-runtime/api/merchant-onboarding';
import { Alert, Button, Image } from 'antdv-next';

import { $t } from '#/locales';

const props = defineProps<{
  amendmentId?: string;
  canPreview: boolean;
  document:
    | MerchantLifecycleApi.MerchantDocumentMetadata
    | MerchantOnboardingApi.DocumentMetadata
    | null;
  kind: MerchantOnboardingApi.DocumentKind;
  merchantId: string;
}>();

const loading = ref(false);
const previewFailed = ref(false);
const previewUrl = ref<string>();
let disposed = false;
let requestVersion = 0;

function revokePreview() {
  if (!previewUrl.value) return;
  URL.revokeObjectURL(previewUrl.value);
  previewUrl.value = undefined;
}

async function previewDocument() {
  if (!props.canPreview || !props.document || loading.value) return false;
  const currentRequest = ++requestVersion;
  loading.value = true;
  previewFailed.value = false;
  try {
    const blob = await getAttachedMerchantDocumentContent(
      props.merchantId,
      props.kind,
      props.amendmentId,
    );
    if (disposed || currentRequest !== requestVersion) return false;
    revokePreview();
    previewUrl.value = URL.createObjectURL(blob);
    return true;
  } catch {
    if (!disposed && currentRequest === requestVersion) {
      previewFailed.value = true;
    }
    return false;
  } finally {
    if (!disposed && currentRequest === requestVersion) loading.value = false;
  }
}

onBeforeUnmount(() => {
  disposed = true;
  requestVersion += 1;
  revokePreview();
});

defineExpose({ previewDocument });
</script>

<template>
  <span v-if="!document">-</span>
  <div v-else class="flex min-h-9 flex-wrap items-center gap-3">
    <Image
      v-if="previewUrl"
      :alt="$t('merchant.onboarding.documents.preview')"
      class="max-h-24 max-w-32 object-contain"
      :src="previewUrl"
    />
    <span class="text-muted-foreground text-xs">
      {{ document.width }} x {{ document.height }}
    </span>
    <Button
      v-if="canPreview"
      :loading="loading"
      size="small"
      type="link"
      @click="previewDocument"
    >
      <Eye class="mr-1 size-4" />
      {{ $t('merchant.onboarding.documents.preview') }}
    </Button>
    <span v-else class="text-muted-foreground text-xs">
      {{ $t('merchant.detail.documentPreviewUnavailable') }}
    </span>
    <Alert
      v-if="previewFailed"
      show-icon
      type="error"
      :message="$t('merchant.onboarding.documents.previewFailed')"
    />
  </div>
</template>
