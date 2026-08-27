<script lang="ts" setup>
import { $t } from '@payment/backoffice-runtime/locales';
import { Alert, Button } from 'antdv-next';

const props = defineProps<{
  error?: unknown;
  reload: () => Promise<boolean>;
}>();

function retry() {
  void props.reload();
}
</script>

<template>
  <Alert
    v-if="error"
    class="mb-4"
    show-icon
    :title="$t('merchant.classification.loadFailed')"
    type="warning"
  >
    <template #action>
      <Button size="small" @click="retry">
        {{ $t('merchant.classification.retry') }}
      </Button>
    </template>
  </Alert>
</template>
