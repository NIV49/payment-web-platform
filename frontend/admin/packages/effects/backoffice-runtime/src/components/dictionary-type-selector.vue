<script lang="ts" setup>
import { onMounted, ref } from 'vue';

import { Alert, Button, Select } from 'antdv-next';

import { getDictionaryTypeOptions } from '../api';
import { $t } from '../locales';
import { dictionaryTypeSelectOptions } from '../views/system/dict/data/navigation';

defineProps<{ modelValue?: string }>();
const emit = defineEmits<{ select: [dictType: string] }>();

const loading = ref(false);
const loadFailed = ref(false);
const options = ref<{ label: string; value: string }[]>([]);

onMounted(loadOptions);

async function loadOptions() {
  loading.value = true;
  loadFailed.value = false;
  try {
    options.value = dictionaryTypeSelectOptions(
      await getDictionaryTypeOptions(),
    );
  } catch {
    options.value = [];
    loadFailed.value = true;
  } finally {
    loading.value = false;
  }
}

function onChange(value: unknown) {
  if (typeof value === 'string') emit('select', value);
}
</script>

<template>
  <div class="flex w-full max-w-md flex-col gap-2">
    <Select
      class="w-full"
      :loading="loading"
      :options="options"
      option-filter-prop="label"
      :placeholder="$t('system.dictData.selectType')"
      show-search
      :value="modelValue"
      @change="onChange"
    />
    <Alert
      v-if="loadFailed"
      show-icon
      :title="$t('system.dictData.typeOptionsLoadFailed')"
      type="error"
    >
      <template #action>
        <Button :loading="loading" size="small" @click="loadOptions">
          {{ $t('system.dictData.retry') }}
        </Button>
      </template>
    </Alert>
  </div>
</template>
