<script lang="ts" setup>
import { Alert, Button } from 'antdv-next';

import { $t } from '../locales';

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
    :title="$t('system.dictData.commonStatusLoadFailed')"
    type="warning"
  >
    <template #action>
      <Button size="small" @click="retry">
        {{ $t('system.dictData.retry') }}
      </Button>
    </template>
  </Alert>
</template>
