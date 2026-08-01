<script lang="ts" setup>
import type { SystemDeptApi } from '#/api/system/dept';

import { computed, ref } from 'vue';

import { useVbenModal } from '@vben/common-ui';

import { Button } from 'antdv-next';

import { useVbenForm } from '#/adapter/form';
import { isOptimisticLockConflict } from '#/api/error-contract';
import { createDept, updateDept } from '#/api/system/dept';
import { $t } from '#/locales';

import { useSchema } from '../data';
import { canManageDepartment } from '../selection-contract';

const emit = defineEmits(['success']);
const formData = ref<SystemDeptApi.SystemDept>();
const currentDepartmentId = computed(() => formData.value?.id);
const currentParentId = computed(() => {
  const parentId = formData.value?.pid;
  return parentId === undefined || parentId === 0
    ? undefined
    : String(parentId);
});
const getTitle = computed(() => {
  return formData.value?.id
    ? $t('ui.actionTitle.edit', [$t('system.dept.name')])
    : $t('ui.actionTitle.create', [$t('system.dept.name')]);
});

const [Form, formApi] = useVbenForm({
  layout: 'vertical',
  schema: useSchema(currentDepartmentId, currentParentId),
  showDefaultActions: false,
});

function resetForm() {
  formApi.reset();
  formApi.setValues(formData.value || {});
}

const [Modal, modalApi] = useVbenModal({
  async onConfirm() {
    if (formData.value?.id && !canManageDepartment(formData.value)) return;
    const { valid } = await formApi.validate();
    if (valid) {
      modalApi.lock();
      const data = await formApi.getValues<SystemDeptApi.DeptSaveParams>();
      try {
        await (formData.value?.id
          ? updateDept(formData.value.id, {
              ...data,
              expectedVersion: formData.value.rowVersion,
            })
          : createDept(data));
        modalApi.close();
        emit('success');
      } catch (error) {
        if (!isOptimisticLockConflict(error)) throw error;
        modalApi.close();
        emit('success');
      } finally {
        modalApi.lock(false);
      }
    }
  },
  onOpenChange(isOpen) {
    if (isOpen) {
      const source = modalApi.getData<SystemDeptApi.SystemDept>();
      const data = source ? { ...source } : undefined;
      if (data) {
        if (data.pid === 0) {
          data.pid = undefined;
        }
        formData.value = data;
        formApi.setValues(formData.value);
      }
    }
  },
});
</script>

<template>
  <Modal :title="getTitle">
    <Form class="mx-4" />
    <template #prepend-footer>
      <div class="flex-auto">
        <Button type="primary" danger @click="resetForm">
          {{ $t('common.reset') }}
        </Button>
      </div>
    </template>
  </Modal>
</template>
