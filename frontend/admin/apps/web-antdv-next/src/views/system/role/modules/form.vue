<script lang="ts" setup>
import type { RoleRequestIdentity } from '../role-request-guard';

import type { SystemMenuApi } from '#/api/system/menu';
import type { SystemRoleApi } from '#/api/system/role';

import { computed, nextTick, ref } from 'vue';

import { Tree, useVbenDrawer } from '@vben/common-ui';
import { IconifyIcon } from '@vben/icons';

import { Alert, Button, Spin } from 'antdv-next';

import { useVbenForm } from '#/adapter/form';
import { isOptimisticLockConflict } from '#/api/error-contract';
import { getMenuList } from '#/api/system/menu';
import { createRole, updateRole } from '#/api/system/role';
import { $t } from '#/locales';

import { useFormSchema } from '../data';
import {
  filterAvailableNavigationMenuIds,
  filterNavigableMenuTree,
} from '../menu-tree';
import { createRoleRequestGuard } from '../role-request-guard';

const emits = defineEmits(['success']);

const NEW_ROLE_SCOPE = 'new-role';

const formData = ref<SystemRoleApi.SystemRole>();

const [Form, formApi] = useVbenForm({
  schema: useFormSchema(),
  showDefaultActions: false,
});

const menuOptions = ref<SystemMenuApi.SystemMenu[]>([]);
const loadingMenuOptions = ref(false);
const menuOptionsLoadFailed = ref(false);
const formReady = ref(false);
const requestGuard = createRoleRequestGuard();
const appliedRequestIdentity = ref<RoleRequestIdentity>();

const id = ref();
const [Drawer, drawerApi] = useVbenDrawer({
  async onConfirm() {
    const currentRole = formData.value;
    const currentScope = currentRole?.id ?? NEW_ROLE_SCOPE;
    const currentRequestIdentity = appliedRequestIdentity.value;
    if (
      !formReady.value ||
      !currentRequestIdentity ||
      !requestGuard.isCurrent(currentRequestIdentity, currentScope) ||
      (currentRole ? id.value !== currentRole.id : id.value !== undefined)
    ) {
      return;
    }
    const { valid } = await formApi.validate();
    if (
      !valid ||
      !requestGuard.isCurrent(currentRequestIdentity, currentScope)
    ) {
      return;
    }
    const values = await formApi.getValues<SystemRoleApi.RoleSaveParams>();
    if (!requestGuard.isCurrent(currentRequestIdentity, currentScope)) return;
    drawerApi.lock();
    try {
      await (currentRole
        ? updateRole(currentRole.id, {
            ...values,
            expectedVersion: currentRole.rowVersion,
          })
        : createRole(values));
      if (!requestGuard.isCurrent(currentRequestIdentity, currentScope)) {
        return;
      }
      emits('success');
      drawerApi.close();
    } catch (error) {
      if (!requestGuard.isCurrent(currentRequestIdentity, currentScope)) {
        return;
      }
      if (isOptimisticLockConflict(error)) {
        emits('success');
        drawerApi.close();
      }
    } finally {
      if (requestGuard.isCurrent(currentRequestIdentity, currentScope)) {
        drawerApi.unlock();
      }
    }
  },

  onOpenChange(isOpen) {
    requestGuard.invalidate();
    appliedRequestIdentity.value = undefined;
    formReady.value = false;
    loadingMenuOptions.value = false;
    menuOptionsLoadFailed.value = false;
    drawerApi.unlock();
    drawerApi.setState({ showConfirmButton: false });
    if (!isOpen) {
      formData.value = undefined;
      id.value = undefined;
      return;
    }
    const data = drawerApi.getData<SystemRoleApi.SystemRole>();
    const existingRole = data?.id ? data : undefined;
    formApi.reset();
    formData.value = existingRole;
    id.value = existingRole?.id;
    void initializeForm(existingRole);
  },
});

async function initializeForm(existingRole?: SystemRoleApi.SystemRole) {
  const scope = existingRole?.id ?? NEW_ROLE_SCOPE;
  const requestIdentity = requestGuard.begin(scope);
  loadingMenuOptions.value = true;
  menuOptionsLoadFailed.value = false;
  appliedRequestIdentity.value = undefined;
  drawerApi.setState({ showConfirmButton: false });
  try {
    let nextMenuOptions = menuOptions.value;
    if (nextMenuOptions.length === 0) {
      nextMenuOptions = filterNavigableMenuTree(await getMenuList());
    }
    if (
      !requestGuard.isCurrent(
        requestIdentity,
        formData.value?.id ?? NEW_ROLE_SCOPE,
      )
    ) {
      return;
    }
    menuOptions.value = nextMenuOptions;
    await nextTick();
    if (
      !requestGuard.isCurrent(
        requestIdentity,
        formData.value?.id ?? NEW_ROLE_SCOPE,
      )
    ) {
      return;
    }
    await formApi.setValues(
      existingRole
        ? {
            ...existingRole,
            menuIds: filterAvailableNavigationMenuIds(
              existingRole.menuIds ?? [],
              nextMenuOptions,
            ),
          }
        : { menuIds: [], status: 1 },
    );
    if (
      !requestGuard.isCurrent(
        requestIdentity,
        formData.value?.id ?? NEW_ROLE_SCOPE,
      )
    ) {
      return;
    }
    appliedRequestIdentity.value = requestIdentity;
    formReady.value = true;
    drawerApi.setState({ showConfirmButton: true });
  } catch {
    if (
      !requestGuard.isCurrent(
        requestIdentity,
        formData.value?.id ?? NEW_ROLE_SCOPE,
      )
    ) {
      return;
    }
    menuOptionsLoadFailed.value = true;
    drawerApi.setState({ showConfirmButton: false });
  } finally {
    if (
      requestGuard.isCurrent(
        requestIdentity,
        formData.value?.id ?? NEW_ROLE_SCOPE,
      )
    ) {
      loadingMenuOptions.value = false;
    }
  }
}

function retryInitializeForm() {
  void initializeForm(formData.value);
}

const getDrawerTitle = computed(() => {
  return formData.value?.id
    ? $t('common.edit', $t('system.role.name'))
    : $t('common.create', $t('system.role.name'));
});
</script>
<template>
  <Drawer :title="getDrawerTitle">
    <Alert
      v-if="menuOptionsLoadFailed"
      class="mb-4"
      show-icon
      :title="$t('system.role.navigationMenuLoadFailed')"
      type="error"
    >
      <template #action>
        <Button size="small" @click="retryInitializeForm">
          {{ $t('system.role.retry') }}
        </Button>
      </template>
    </Alert>
    <Alert
      class="mb-4"
      show-icon
      :title="$t('system.role.navigationOnlyWarningTitle')"
      type="info"
    >
      <template #description>
        {{ $t('system.role.navigationOnlyWarningDescription') }}
      </template>
    </Alert>
    <Form>
      <template #menuIds="slotProps">
        <Spin :spinning="loadingMenuOptions" :classes="{ root: 'w-full' }">
          <Tree
            :tree-data="menuOptions"
            multiple
            bordered
            :default-expanded-level="2"
            v-bind="slotProps"
            value-field="id"
            label-field="meta.title"
            icon-field="meta.icon"
          >
            <template #node="{ value }">
              <IconifyIcon v-if="value.meta.icon" :icon="value.meta.icon" />
              {{ $t(value.meta.title) }}
            </template>
          </Tree>
        </Spin>
      </template>
    </Form>
  </Drawer>
</template>
<style lang="css" scoped>
:deep(.ant-tree-title) {
  .tree-actions {
    @apply ml-5 hidden;
  }
}

:deep(.ant-tree-title:hover) {
  .tree-actions {
    @apply ml-5 flex flex-auto justify-end;
  }
}
</style>
