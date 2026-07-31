<script lang="ts" setup>
import type { RoleRequestIdentity } from '../role-request-guard';

import type { SystemMenuApi } from '#/api/system/menu';
import type { SystemRoleApi } from '#/api/system/role';

import type { RoleConfigurationTree } from '../menu-tree';

import { computed, nextTick, ref } from 'vue';

import { Tree, useVbenDrawer } from '@vben/common-ui';
import { IconifyIcon } from '@vben/icons';

import { Alert, Button, Input, Spin } from 'antdv-next';

import { useVbenForm } from '#/adapter/form';
import { isOptimisticLockConflict } from '#/api/error-contract';
import { getMenuList } from '#/api/system/menu';
import {
  getGrantablePermissions,
  getRoleGrants,
  replaceRoleConfiguration,
} from '#/api/system/role-grant';
import { createRole } from '#/api/system/role';
import { $t } from '#/locales';

import { useFormSchema } from '../data';
import {
  buildTenantRoleGrants,
  findMissingPermissionDependencies,
} from '../grant-contract';
import {
  buildRoleConfigurationTree,
  filterAvailableNavigationMenuIds,
  filterNavigableMenuTree,
  normalizeRoleConfigurationSelection,
} from '../menu-tree';
import { createRoleRequestGuard } from '../role-request-guard';

const emits = defineEmits(['success']);

const NEW_ROLE_SCOPE = 'new-role';

const formData = ref<SystemRoleApi.SystemRole>();
const menuOptions = ref<SystemMenuApi.SystemMenu[]>([]);
const roleConfigurationTree = ref<RoleConfigurationTree>();
const configurationReason = ref('');
const configurationReadOnly = ref(false);
const reasonMissing = ref(false);
const loadingMenuOptions = ref(false);
const menuOptionsLoadFailed = ref(false);
const formReady = ref(false);
const requestGuard = createRoleRequestGuard();
const appliedRequestIdentity = ref<RoleRequestIdentity>();

const [Form, formApi] = useVbenForm({
  schema: useFormSchema(),
  showDefaultActions: false,
});

const id = ref<string>();
const [Drawer, drawerApi] = useVbenDrawer({
  async onConfirm() {
    const currentRole = formData.value;
    const currentScope = currentRole?.id ?? NEW_ROLE_SCOPE;
    const currentRequestIdentity = appliedRequestIdentity.value;
    if (
      !formReady.value ||
      configurationReadOnly.value ||
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

    reasonMissing.value = Boolean(
      currentRole && !configurationReason.value.trim(),
    );
    if (reasonMissing.value) return;

    drawerApi.lock();
    try {
      if (currentRole) {
        const configuration = roleConfigurationTree.value;
        if (!configuration) return;
        const normalized = normalizeRoleConfigurationSelection(
          values.menuIds ?? [],
          configuration,
        );
        await replaceRoleConfiguration(currentRole.id, {
          expectedVersion: currentRole.rowVersion,
          grants: buildTenantRoleGrants(normalized.permissionCodes),
          menuIds: normalized.menuIds,
          name: values.name,
          reason: configurationReason.value.trim(),
          remark: values.remark,
          status: values.status,
        });
      } else {
        await createRole({
          ...values,
          menuIds: filterAvailableNavigationMenuIds(
            values.menuIds ?? [],
            menuOptions.value,
          ),
        });
      }
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
    configurationReadOnly.value = false;
    configurationReason.value = '';
    reasonMissing.value = false;
    roleConfigurationTree.value = undefined;
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
    if (existingRole) {
      const [rawMenus, grantablePermissions, grantDetail] = await Promise.all([
        getMenuList(),
        getGrantablePermissions(),
        getRoleGrants(existingRole.id),
      ]);
      if (!requestGuard.isCurrent(requestIdentity, currentScope())) return;

      const configuration = buildRoleConfigurationTree(
        rawMenus,
        grantablePermissions.map(({ permissionCode }) => permissionCode),
      );
      const permissionCodes = grantDetail.grants.map(
        ({ permissionCode }) => permissionCode,
      );
      const unsupportedPermission = permissionCodes.some(
        (permissionCode) => !configuration.buttonIdByPermission[permissionCode],
      );
      const missingDependencies =
        findMissingPermissionDependencies(permissionCodes).length > 0;
      const versionChanged = grantDetail.roleVersion !== existingRole.rowVersion;
      configurationReadOnly.value =
        !grantDetail.editable ||
        unsupportedPermission ||
        missingDependencies ||
        versionChanged;
      roleConfigurationTree.value = configuration;
      menuOptions.value = configuration.tree;

      const selectedIds = [
        ...filterAvailableNavigationMenuIds(
          existingRole.menuIds ?? [],
          configuration.tree,
        ),
        ...permissionCodes.flatMap((permissionCode) => {
          const buttonId = configuration.buttonIdByPermission[permissionCode];
          return buttonId ? [buttonId] : [];
        }),
      ];
      const normalized = normalizeRoleConfigurationSelection(
        selectedIds,
        configuration,
      );
      await nextTick();
      if (!requestGuard.isCurrent(requestIdentity, currentScope())) return;
      await formApi.setValues({
        ...existingRole,
        menuIds: configurationReadOnly.value
          ? selectedIds
          : normalized.selectedIds,
      });
    } else {
      const navigationMenus = filterNavigableMenuTree(await getMenuList());
      if (!requestGuard.isCurrent(requestIdentity, currentScope())) return;
      menuOptions.value = navigationMenus;
      await nextTick();
      if (!requestGuard.isCurrent(requestIdentity, currentScope())) return;
      await formApi.setValues({ menuIds: [], status: 1 });
    }
    if (!requestGuard.isCurrent(requestIdentity, currentScope())) return;
    appliedRequestIdentity.value = requestIdentity;
    formReady.value = true;
    drawerApi.setState({
      showConfirmButton: !configurationReadOnly.value,
    });
  } catch {
    if (!requestGuard.isCurrent(requestIdentity, currentScope())) return;
    menuOptionsLoadFailed.value = true;
    drawerApi.setState({ showConfirmButton: false });
  } finally {
    if (requestGuard.isCurrent(requestIdentity, currentScope())) {
      loadingMenuOptions.value = false;
    }
  }
}

async function onRoleTreeSelect() {
  const configuration = roleConfigurationTree.value;
  if (!configuration || configurationReadOnly.value) return;
  await nextTick();
  const values = await formApi.getValues<SystemRoleApi.RoleSaveParams>();
  const normalized = normalizeRoleConfigurationSelection(
    values.menuIds ?? [],
    configuration,
  );
  await formApi.setValues({ menuIds: normalized.selectedIds });
}

function currentScope() {
  return formData.value?.id ?? NEW_ROLE_SCOPE;
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
      :title="$t('system.role.configurationLoadFailed')"
      type="error"
    >
      <template #action>
        <Button size="small" @click="retryInitializeForm">
          {{ $t('system.role.retry') }}
        </Button>
      </template>
    </Alert>
    <Alert
      v-if="configurationReadOnly"
      class="mb-4"
      show-icon
      :title="$t('system.role.grantReadOnly')"
      type="warning"
    />
    <Form>
      <template #menuIds="slotProps">
        <Spin :spinning="loadingMenuOptions" :classes="{ root: 'w-full' }">
          <Tree
            :tree-data="menuOptions"
            multiple
            bordered
            check-strictly
            auto-check-parent
            :disabled="configurationReadOnly"
            :default-expanded-level="2"
            v-bind="slotProps"
            value-field="id"
            label-field="meta.title"
            icon-field="meta.icon"
            @select="onRoleTreeSelect"
          >
            <template #node="{ value }">
              <IconifyIcon v-if="value.meta?.icon" :icon="value.meta.icon" />
              {{ $t(value.meta?.title ?? value.name) }}
            </template>
          </Tree>
        </Spin>
      </template>
    </Form>
    <div v-if="formData?.id" class="mt-4">
      <div class="mb-1 text-sm font-medium">
        {{ $t('system.role.grantReason') }}
      </div>
      <Input.TextArea
        v-model:value="configurationReason"
        :disabled="configurationReadOnly"
        :maxlength="500"
        :placeholder="$t('system.role.grantReasonPlaceholder')"
        :rows="3"
        @update:value="reasonMissing = false"
      />
      <div v-if="reasonMissing" class="mt-1 text-sm text-red-500">
        {{ $t('system.role.grantReasonRequired') }}
      </div>
    </div>
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
