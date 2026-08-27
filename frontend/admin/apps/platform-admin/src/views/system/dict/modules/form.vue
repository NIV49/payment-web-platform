<script lang="ts" setup>
import type { SystemDictionaryApi } from '#/api/system/dictionary';

import { computed, ref } from 'vue';

import { useVbenModal } from '@vben/common-ui';

import { useVbenForm } from '#/adapter/form';
import { isOptimisticLockConflict } from '#/api/error-contract';
import { createDictionary, updateDictionary } from '#/api/system/dictionary';
import { $t } from '#/locales';

import { useFormSchema } from '../data';

const emit = defineEmits(['success']);
const formData = ref<SystemDictionaryApi.SystemDictionary>();

const [Form, formApi] = useVbenForm({
  layout: 'vertical',
  schema: useFormSchema(),
  showDefaultActions: false,
});

const title = computed(() =>
  formData.value
    ? $t('ui.actionTitle.edit', [$t('system.dict.name')])
    : $t('ui.actionTitle.create', [$t('system.dict.name')]),
);

const [Modal, modalApi] = useVbenModal({
  async onConfirm() {
    const { valid } = await formApi.validate();
    if (!valid) return;
    modalApi.lock();
    try {
      const values =
        await formApi.getValues<SystemDictionaryApi.DictionarySaveParams>();
      const current = formData.value;
      await (current
        ? updateDictionary(current.dictId, {
            ...values,
            expectedVersion: current.rowVersion,
          })
        : createDictionary(values));
      modalApi.close();
      emit('success');
    } catch (error) {
      if (!isOptimisticLockConflict(error)) throw error;
      modalApi.close();
      emit('success');
    } finally {
      modalApi.lock(false);
    }
  },
  onOpenChange(isOpen) {
    if (!isOpen) {
      formData.value = undefined;
      return;
    }
    const current = modalApi.getData<SystemDictionaryApi.SystemDictionary>();
    formData.value = current?.dictId ? current : undefined;
    formApi.reset();
    formApi.setValues(formData.value ?? { sort: 0 });
  },
});
</script>

<template>
  <Modal :title="title">
    <Form class="mx-4" />
  </Modal>
</template>
