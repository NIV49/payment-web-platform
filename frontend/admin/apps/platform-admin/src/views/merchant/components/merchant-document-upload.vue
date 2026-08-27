<script lang="ts" setup>
import type { MerchantOnboardingApi } from '@payment/backoffice-runtime/api/merchant-onboarding';

import { onBeforeUnmount, ref, watch } from 'vue';

import { Eye, ImagePlus, X } from '@vben/icons';

import {
  deleteTemporaryMerchantDocument,
  getAttachedMerchantDocumentContent,
  getTemporaryMerchantDocumentContent,
  uploadTemporaryMerchantDocument,
} from '@payment/backoffice-runtime/api/merchant-onboarding';
import { $t } from '@payment/backoffice-runtime/locales';
import { Alert, Button, Image, Spin } from 'antdv-next';

const props = defineProps<{
  amendmentId?: string;
  disabled?: boolean;
  kind: MerchantOnboardingApi.DocumentKind;
  merchantId?: string;
  modelValue: string;
  targetTenantId?: string;
}>();

const emit = defineEmits<{
  edited: [];
  'update:modelValue': [value: string];
}>();

const MAX_DOCUMENT_BYTES = 2 * 1024 * 1024;

const documentId = ref(props.modelValue);
const errorKey = ref<string>();
const fileInput = ref<HTMLInputElement>();
const loading = ref(false);
const ownedTemporaryId = ref<string>();
const previewUrl = ref<string>();
let disposed = false;
let bindingInProgress = false;
let requestVersion = 0;

function revokePreview() {
  if (!previewUrl.value) return;
  URL.revokeObjectURL(previewUrl.value);
  previewUrl.value = undefined;
}

async function deleteOwnedTemporary(documentIdToDelete: string) {
  try {
    await deleteTemporaryMerchantDocument(documentIdToDelete);
  } catch {
    // The upload expires server-side; keep cleanup failures out of form state.
  }
}

function setDocumentId(value: string) {
  documentId.value = value;
  emit('update:modelValue', value);
  emit('edited');
}

function openPicker() {
  if (!props.disabled && !bindingInProgress) fileInput.value?.click();
}

function isValidFile(file: File) {
  return (
    (file.type === 'image/png' || file.type === 'image/jpeg') &&
    file.size > 0 &&
    file.size <= MAX_DOCUMENT_BYTES
  );
}

async function hasMatchingImageSignature(file: File) {
  try {
    const bytes = new Uint8Array(await file.slice(0, 8).arrayBuffer());
    if (file.type === 'image/jpeg') {
      return bytes[0] === 255 && bytes[1] === 216 && bytes[2] === 255;
    }
    return (
      bytes[0] === 137 &&
      bytes[1] === 80 &&
      bytes[2] === 78 &&
      bytes[3] === 71 &&
      bytes[4] === 13 &&
      bytes[5] === 10 &&
      bytes[6] === 26 &&
      bytes[7] === 10
    );
  } catch {
    return false;
  }
}

async function uploadFile(file: File) {
  if (props.disabled || bindingInProgress) return false;
  errorKey.value = undefined;
  if (!isValidFile(file) || !(await hasMatchingImageSignature(file))) {
    errorKey.value = 'merchant.onboarding.documents.invalid';
    return false;
  }
  if (!props.targetTenantId) {
    errorKey.value = 'merchant.onboarding.documents.tenantRequired';
    return false;
  }

  const currentRequest = ++requestVersion;
  loading.value = true;
  try {
    const uploaded = await uploadTemporaryMerchantDocument({
      file,
      kind: props.kind,
      targetTenantId: props.targetTenantId,
    });
    if (disposed || currentRequest !== requestVersion) {
      void deleteOwnedTemporary(uploaded.documentId);
      return false;
    }

    const previousTemporaryId = ownedTemporaryId.value;
    ownedTemporaryId.value = uploaded.documentId;
    setDocumentId(uploaded.documentId);
    if (previousTemporaryId && previousTemporaryId !== uploaded.documentId) {
      void deleteOwnedTemporary(previousTemporaryId);
    }
    await previewDocument();
    return true;
  } catch {
    if (!disposed && currentRequest === requestVersion) {
      errorKey.value = 'merchant.onboarding.documents.uploadFailed';
    }
    return false;
  } finally {
    if (!disposed && currentRequest === requestVersion) loading.value = false;
  }
}

async function onFileChange(event: Event) {
  const input = event.target as HTMLInputElement;
  const file = input.files?.[0];
  input.value = '';
  if (file) await uploadFile(file);
}

function loadDocumentContent(currentDocumentId: string) {
  if (ownedTemporaryId.value === currentDocumentId || !props.merchantId) {
    return getTemporaryMerchantDocumentContent(currentDocumentId);
  }
  if (props.amendmentId) {
    return getAttachedMerchantDocumentContent(
      props.merchantId,
      props.kind,
      props.amendmentId,
    );
  }
  return getAttachedMerchantDocumentContent(props.merchantId, props.kind);
}

async function previewDocument() {
  const currentDocumentId = documentId.value;
  if (!currentDocumentId) return false;

  const currentRequest = ++requestVersion;
  loading.value = true;
  errorKey.value = undefined;
  try {
    const blob = await loadDocumentContent(currentDocumentId);
    if (disposed || currentRequest !== requestVersion) return false;
    revokePreview();
    previewUrl.value = URL.createObjectURL(blob);
    return true;
  } catch {
    if (!disposed && currentRequest === requestVersion) {
      errorKey.value = 'merchant.onboarding.documents.previewFailed';
    }
    return false;
  } finally {
    if (!disposed && currentRequest === requestVersion) loading.value = false;
  }
}

async function removeDocument() {
  if (props.disabled || bindingInProgress) return;
  requestVersion += 1;
  const temporaryId = ownedTemporaryId.value;
  ownedTemporaryId.value = undefined;
  revokePreview();
  setDocumentId('');
  if (temporaryId) await deleteOwnedTemporary(temporaryId);
}

function markBound() {
  finishBinding(true);
}

function beginBinding() {
  requestVersion += 1;
  loading.value = false;
  bindingInProgress = true;
}

function finishBinding(bound: boolean) {
  bindingInProgress = false;
  if (!bound) return;
  ownedTemporaryId.value = undefined;
}

watch(
  () => props.modelValue,
  (value) => {
    if (value === documentId.value) return;
    documentId.value = value;
    revokePreview();
  },
);

watch(
  () => props.targetTenantId,
  (value, previous) => {
    if (value === previous || bindingInProgress) return;
    requestVersion += 1;
    loading.value = false;
    errorKey.value = undefined;
    revokePreview();
    const temporaryId = ownedTemporaryId.value;
    if (!temporaryId) return;
    ownedTemporaryId.value = undefined;
    setDocumentId('');
    void deleteOwnedTemporary(temporaryId);
  },
);

onBeforeUnmount(() => {
  disposed = true;
  requestVersion += 1;
  revokePreview();
  const temporaryId = ownedTemporaryId.value;
  ownedTemporaryId.value = undefined;
  if (temporaryId && !bindingInProgress) {
    void deleteOwnedTemporary(temporaryId);
  }
});

defineExpose({
  beginBinding,
  finishBinding,
  markBound,
  previewDocument,
  removeDocument,
  uploadFile,
});
</script>

<template>
  <div class="merchant-document-upload">
    <input
      ref="fileInput"
      accept="image/png,image/jpeg"
      class="hidden"
      type="file"
      @change="onFileChange"
    />
    <Spin :spinning="loading">
      <div class="flex min-h-20 items-center gap-3">
        <Image
          v-if="previewUrl"
          :preview="false"
          :src="previewUrl"
          class="h-20 w-20 rounded border object-contain"
        />
        <div class="flex flex-wrap gap-2">
          <Button :disabled="disabled" @click="openPicker">
            <ImagePlus class="mr-1 size-4" />
            {{
              documentId
                ? $t('merchant.onboarding.documents.replace')
                : $t('merchant.onboarding.documents.upload')
            }}
          </Button>
          <Button v-if="documentId" @click="previewDocument">
            <Eye class="mr-1 size-4" />
            {{ $t('merchant.onboarding.documents.preview') }}
          </Button>
          <Button
            v-if="documentId && !disabled"
            danger
            type="text"
            @click="removeDocument"
          >
            <X class="mr-1 size-4" />
            {{ $t('merchant.onboarding.documents.remove') }}
          </Button>
        </div>
      </div>
    </Spin>
    <Alert
      v-if="errorKey"
      class="mt-2"
      show-icon
      type="error"
      :message="$t(errorKey)"
    />
  </div>
</template>
