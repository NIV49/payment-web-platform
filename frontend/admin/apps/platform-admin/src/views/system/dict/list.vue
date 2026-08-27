<script lang="ts" setup>
import type {
  OnActionClickParams,
  VxeTableGridOptions,
} from '#/adapter/vxe-table';
import type { SystemDictionaryApi } from '#/api/system/dictionary';

import { computed } from 'vue';
import { useRouter } from 'vue-router';

import { useAccess } from '@vben/access';
import { Page, useVbenModal } from '@vben/common-ui';
import { Plus } from '@vben/icons';

import { dictionaryDataRouteLocation } from '@payment/backoffice-runtime/views/system/dictionary-data';
import { Button, message } from 'antdv-next';

import { useVbenVxeGrid } from '#/adapter/vxe-table';
import { PERMISSION_CODES } from '#/api';
import { isOptimisticLockConflict } from '#/api/error-contract';
import { deleteDictionary, getDictionaries } from '#/api/system/dictionary';
import { $t } from '#/locales';

import { useColumns, useGridFormSchema } from './data';
import Form from './modules/form.vue';

const router = useRouter();
const { hasAccessByCodes } = useAccess();
const canCreate = computed(() =>
  hasAccessByCodes([PERMISSION_CODES.dictionaryCreate]),
);

const [FormModal, formModalApi] = useVbenModal({
  connectedComponent: Form,
  destroyOnClose: true,
});

const [Grid, gridApi] = useVbenVxeGrid({
  formOptions: {
    schema: useGridFormSchema(),
    submitOnChange: false,
  },
  gridOptions: {
    columns: useColumns(onActionClick),
    height: 'auto',
    keepSource: true,
    proxyConfig: {
      ajax: {
        query: async ({ page }, formValues) =>
          await getDictionaries({
            page: page.currentPage,
            pageSize: page.pageSize,
            ...formValues,
          }),
      },
    },
    rowConfig: { keyField: 'dictId' },
    toolbarConfig: {
      custom: true,
      export: false,
      refresh: true,
      search: true,
      zoom: true,
    },
  } as VxeTableGridOptions<SystemDictionaryApi.SystemDictionary>,
});

function onActionClick({
  code,
  row,
}: OnActionClickParams<SystemDictionaryApi.SystemDictionary>) {
  if (code === 'data') {
    if (!hasAccessByCodes([PERMISSION_CODES.dictionaryView])) return;
    const location = dictionaryDataRouteLocation(row.dictType);
    if (location) router.push(location);
  } else if (code === 'edit') {
    if (!hasAccessByCodes([PERMISSION_CODES.dictionaryUpdate])) return;
    formModalApi.setData(row).open();
  } else if (code === 'delete') {
    onDelete(row);
  }
}

function onDelete(row: SystemDictionaryApi.SystemDictionary) {
  if (!hasAccessByCodes([PERMISSION_CODES.dictionaryDelete])) return;
  const hideLoading = message.loading({
    content: $t('ui.actionMessage.deleting', [row.dictName]),
    duration: 0,
    key: 'action_process_msg',
  });
  deleteDictionary(row.dictId, row.rowVersion)
    .then(() => {
      message.success({
        content: $t('ui.actionMessage.deleteSuccess', [row.dictName]),
        key: 'action_process_msg',
      });
      gridApi.query();
    })
    .catch((error) => {
      hideLoading();
      if (isOptimisticLockConflict(error)) gridApi.query();
    });
}

function onCreate() {
  if (!canCreate.value) return;
  formModalApi.setData(undefined).open();
}
</script>

<template>
  <Page auto-content-height>
    <FormModal @success="gridApi.query" />
    <Grid :table-title="$t('system.dict.list')">
      <template #toolbar-tools>
        <Button v-if="canCreate" type="primary" @click="onCreate">
          <Plus class="size-5" />
          {{ $t('ui.actionTitle.create', [$t('system.dict.name')]) }}
        </Button>
      </template>
    </Grid>
  </Page>
</template>
