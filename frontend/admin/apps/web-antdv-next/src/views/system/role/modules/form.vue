<script lang="ts" setup>
import type { DataNode } from 'antdv-next/dist/tree';

import type { SystemRoleApi } from '#/api/system/role';

import { computed, nextTick, ref } from 'vue';

import { Tree, useVbenDrawer } from '@vben/common-ui';
import { IconifyIcon } from '@vben/icons';

import { Alert, Spin } from 'antdv-next';

import { useVbenForm } from '#/adapter/form';
import { isOptimisticLockConflict } from '#/api/error-contract';
import { getMenuList } from '#/api/system/menu';
import { createRole, updateRole } from '#/api/system/role';
import { $t } from '#/locales';

import { useFormSchema } from '../data';
import { mergeRoleNavigationMenuIds } from '../grant-contract';
import { filterNavigableMenuTree } from '../menu-tree';

const emits = defineEmits(['success']);

const formData = ref<SystemRoleApi.SystemRole>();

const [Form, formApi] = useVbenForm({
  schema: useFormSchema(),
  showDefaultActions: false,
});

const menuOptions = ref<DataNode[]>([]);
const buttonMenuIds = ref<Set<string>>(new Set());
const preservedButtonMenuIds = ref<string[]>([]);
const loadingMenuOptions = ref(false);

const id = ref();
const [Drawer, drawerApi] = useVbenDrawer({
  async onConfirm() {
    const { valid } = await formApi.validate();
    if (!valid) return;
    const values = await formApi.getValues<SystemRoleApi.RoleSaveParams>();
    values.menuIds = mergeRoleNavigationMenuIds(
      values.menuIds,
      preservedButtonMenuIds.value,
    );
    drawerApi.lock();
    const currentRole = formData.value;
    let request;
    if (id.value) {
      if (!currentRole) {
        drawerApi.unlock();
        return;
      }
      request = updateRole(id.value, {
        ...values,
        expectedVersion: currentRole.rowVersion,
      });
    } else {
      request = createRole(values);
    }
    request
      .then(() => {
        emits('success');
        drawerApi.close();
      })
      .catch((error) => {
        drawerApi.unlock();
        if (isOptimisticLockConflict(error)) {
          emits('success');
          drawerApi.close();
        }
      });
  },

  async onOpenChange(isOpen) {
    if (isOpen) {
      const data = drawerApi.getData<SystemRoleApi.SystemRole>();
      const existingRole = data?.id ? data : undefined;
      formApi.reset();

      if (existingRole) {
        formData.value = existingRole;
        id.value = existingRole.id;
      } else {
        formData.value = undefined;
        id.value = undefined;
      }

      if (menuOptions.value.length === 0) {
        await loadMenuOptions();
      }
      // Wait for Vue to flush DOM updates (form fields mounted)
      await nextTick();
      if (existingRole) {
        const currentMenuIds = existingRole.menuIds ?? [];
        preservedButtonMenuIds.value = currentMenuIds.filter((menuId) =>
          buttonMenuIds.value.has(menuId),
        );
        formApi.setValues({ ...existingRole, menuIds: currentMenuIds });
      } else {
        preservedButtonMenuIds.value = [];
        formApi.setValues({ menuIds: [], status: 1 });
      }
    }
  },
});

async function loadMenuOptions() {
  loadingMenuOptions.value = true;
  try {
    const res = await getMenuList();
    buttonMenuIds.value = collectButtonMenuIds(res);
    menuOptions.value = filterNavigableMenuTree(res) as unknown as DataNode[];
  } finally {
    loadingMenuOptions.value = false;
  }
}

function collectButtonMenuIds(
  menus: readonly import('#/api/system/menu').SystemMenuApi.SystemMenu[],
) {
  const ids = new Set<string>();
  for (const menu of menus) {
    if (menu.type === 'button') ids.add(menu.id);
    if (menu.children) {
      for (const id of collectButtonMenuIds(menu.children)) ids.add(id);
    }
  }
  return ids;
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
