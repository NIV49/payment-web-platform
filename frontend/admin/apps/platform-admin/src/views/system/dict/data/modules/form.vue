<script lang="ts" setup>
import type { SystemDictionaryDataApi } from '@payment/backoffice-runtime/api';

import type { SystemDictionaryApi } from '#/api/system/dictionary';

import { computed, ref } from 'vue';

import { useVbenModal } from '@vben/common-ui';

import { useFormSchema } from '@payment/backoffice-runtime/views/system/dictionary-data';

import { useVbenForm } from '#/adapter/form';
import { isOptimisticLockConflict } from '#/api/error-contract';
import {
  createDictionaryData,
  updateDictionaryData,
} from '#/api/system/dictionary';
import { $t } from '#/locales';

type FormContext = {
  dictType: string;
  row?: SystemDictionaryDataApi.SystemDictionaryData;
};

const emit = defineEmits(['success']);
const context = ref<FormContext>();

const [Form, formApi] = useVbenForm({
  layout: 'vertical',
  schema: useFormSchema(),
  showDefaultActions: false,
});

const title = computed(() =>
  context.value?.row
    ? $t('ui.actionTitle.edit', [$t('system.dictData.name')])
    : $t('ui.actionTitle.create', [$t('system.dictData.name')]),
);

const [Modal, modalApi] = useVbenModal({
  async onConfirm() {
    const currentContext = context.value;
    if (!currentContext) return;
    const { valid } = await formApi.validate();
    if (!valid) return;
    modalApi.lock();
    try {
      const values =
        await formApi.getValues<
          Omit<SystemDictionaryApi.DictionaryDataSaveParams, 'dictType'>
        >();
      const data: SystemDictionaryApi.DictionaryDataSaveParams = {
        ...values,
        dictType: currentContext.dictType,
      };
      await (currentContext.row
        ? updateDictionaryData(currentContext.row.dictCode, {
            ...data,
            expectedVersion: currentContext.row.rowVersion,
          })
        : createDictionaryData(data));
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
      context.value = undefined;
      return;
    }
    context.value = modalApi.getData<FormContext>();
    formApi.reset();
    formApi.setValues(context.value?.row ?? { color: 'default', sort: 0 });
  },
});
</script>

<template>
  <Modal :title="title">
    <Form class="mx-4" />
  </Modal>
</template>
