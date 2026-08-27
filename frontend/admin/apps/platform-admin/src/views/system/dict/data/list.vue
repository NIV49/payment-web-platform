<script lang="ts" setup>
import type { SystemDictionaryDataApi } from '@payment/backoffice-runtime/api';

import type {
  OnActionClickParams,
  VxeTableGridOptions,
} from '#/adapter/vxe-table';

import { computed, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';

import { useAccess } from '@vben/access';
import { Page, useVbenModal } from '@vben/common-ui';
import { Plus } from '@vben/icons';

import DictionaryTypeSelector from '@payment/backoffice-runtime/components/dictionary-type-selector';
import {
  dictionaryDataRouteLocation,
  resolveDictionaryTypeQuery,
  useColumns,
  useGridFormSchema,
} from '@payment/backoffice-runtime/views/system/dictionary-data';
import { Button, message } from 'antdv-next';

import { useVbenVxeGrid } from '#/adapter/vxe-table';
import { getDictionaryData, PERMISSION_CODES } from '#/api';
import { isOptimisticLockConflict } from '#/api/error-contract';
import { deleteDictionaryData } from '#/api/system/dictionary';
import { $t } from '#/locales';

import Form from './modules/form.vue';
import {
  createDictionaryDataScopeGuard,
  hasExactDictionaryDataScope,
} from './scope-guard';

const route = useRoute();
const router = useRouter();
const { hasAccessByCodes } = useAccess();
const dictionaryDataScopeGuard = createDictionaryDataScopeGuard();
let directoryRequestCount = 0;
let refreshAfterPendingRequest = false;

const dictType = computed(() =>
  resolveDictionaryTypeQuery(route.query.dictType),
);
const canCreate = computed(
  () =>
    Boolean(dictType.value) &&
    hasAccessByCodes([PERMISSION_CODES.dictionaryUpdate]),
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
    columns: useColumns(onActionClick, true),
    height: 'auto',
    keepSource: true,
    proxyConfig: {
      ajax: {
        query: async ({ page }, formValues) => {
          const currentDictType = dictType.value;
          if (!currentDictType) return { items: [], total: 0 };
          const request = dictionaryDataScopeGuard.begin(currentDictType);
          directoryRequestCount += 1;
          try {
            const response = await getDictionaryData({
              dictType: currentDictType,
              page: page.currentPage,
              pageSize: page.pageSize,
              ...formValues,
            });
            return dictionaryDataScopeGuard.isCurrent(request) &&
              hasExactDictionaryDataScope(response, currentDictType)
              ? response
              : { items: [], total: 0 };
          } finally {
            directoryRequestCount -= 1;
            if (directoryRequestCount === 0 && refreshAfterPendingRequest) {
              refreshAfterPendingRequest = false;
              queueMicrotask(() => gridApi.query());
            }
          }
        },
      },
    },
    rowConfig: { keyField: 'dictCode' },
    toolbarConfig: {
      custom: true,
      export: false,
      refresh: true,
      search: true,
      zoom: true,
    },
  } as VxeTableGridOptions<SystemDictionaryDataApi.SystemDictionaryData>,
});

watch(dictType, () => {
  dictionaryDataScopeGuard.invalidate();
  void gridApi.grid.reloadData([]);
  if (directoryRequestCount > 0) {
    refreshAfterPendingRequest = true;
  } else {
    void gridApi.query();
  }
});

function onActionClick({
  code,
  row,
}: OnActionClickParams<SystemDictionaryDataApi.SystemDictionaryData>) {
  if (code === 'edit') {
    if (!hasAccessByCodes([PERMISSION_CODES.dictionaryUpdate])) return;
    const currentDictType = dictType.value;
    if (!currentDictType || row.dictType !== currentDictType) return;
    formModalApi.setData({ dictType: currentDictType, row }).open();
  } else if (code === 'delete') {
    onDelete(row);
  }
}

function onDelete(row: SystemDictionaryDataApi.SystemDictionaryData) {
  if (!hasAccessByCodes([PERMISSION_CODES.dictionaryUpdate])) return;
  if (!dictType.value || row.dictType !== dictType.value) return;
  const hideLoading = message.loading({
    content: $t('ui.actionMessage.deleting', [row.label]),
    duration: 0,
    key: 'action_process_msg',
  });
  deleteDictionaryData(row.dictCode, row.rowVersion)
    .then(() => {
      message.success({
        content: $t('ui.actionMessage.deleteSuccess', [row.label]),
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
  const currentDictType = dictType.value;
  if (!canCreate.value || !currentDictType) return;
  formModalApi.setData({ dictType: currentDictType }).open();
}

function onSelectType(selectedDictType: string) {
  const location = dictionaryDataRouteLocation(selectedDictType);
  if (location) router.push(location);
}
</script>

<template>
  <Page auto-content-height>
    <FormModal @success="gridApi.query" />
    <DictionaryTypeSelector
      class="mb-4"
      :model-value="dictType"
      @select="onSelectType"
    />
    <Grid :table-title="$t('system.dictData.list')">
      <template #toolbar-tools>
        <Button v-if="canCreate" type="primary" @click="onCreate">
          <Plus class="size-5" />
          {{ $t('ui.actionTitle.create', [$t('system.dictData.name')]) }}
        </Button>
      </template>
    </Grid>
  </Page>
</template>
