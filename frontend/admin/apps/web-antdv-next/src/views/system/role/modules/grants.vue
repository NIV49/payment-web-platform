<script lang="ts" setup>
import type { SystemRoleApi } from '#/api/system/role';
import type { IamRoleGrantApi } from '#/api/system/role-grant';

import { computed, ref } from 'vue';

import { useVbenDrawer } from '@vben/common-ui';

import {
  Alert,
  Button,
  Checkbox,
  Empty,
  Input,
  message,
  Modal,
  Spin,
  Tag,
} from 'antdv-next';

import { isOptimisticLockConflict } from '#/api/error-contract';
import {
  getGrantablePermissions,
  getRoleGrants,
  replaceRoleGrants,
} from '#/api/system/role-grant';
import { $t } from '#/locales';

import { buildTenantRoleGrants } from '../grant-contract';

const emits = defineEmits<{
  success: [];
}>();

const role = ref<SystemRoleApi.SystemRole>();
const grantDetail = ref<IamRoleGrantApi.RoleGrantDetail>();
const grantablePermissions = ref<IamRoleGrantApi.GrantablePermission[]>([]);
const selectedPermissionCodes = ref<string[]>([]);
const reason = ref('');
const loading = ref(false);
const loadFailed = ref(false);

const editable = computed(() => grantDetail.value?.editable === true);
const groupedPermissions = computed(() => {
  const groups = new Map<string, IamRoleGrantApi.GrantablePermission[]>();
  for (const permission of grantablePermissions.value) {
    const permissions = groups.get(permission.resourceCode) ?? [];
    permissions.push(permission);
    groups.set(permission.resourceCode, permissions);
  }
  return [...groups.entries()].map(([resourceCode, permissions]) => ({
    permissions,
    resourceCode,
  }));
});

const [Drawer, drawerApi] = useVbenDrawer({
  async onConfirm() {
    if (!editable.value || !role.value || !grantDetail.value) return;
    const normalizedReason = reason.value.trim();
    if (!normalizedReason) {
      message.warning($t('system.role.grantReasonRequired'));
      return;
    }
    drawerApi.lock();
    try {
      await replaceRoleGrants(role.value.id, {
        expectedVersion: grantDetail.value.roleVersion,
        grants: buildTenantRoleGrants(selectedPermissionCodes.value),
        reason: normalizedReason,
      });
      message.success($t('system.role.grantSaveSuccess'));
      emits('success');
      drawerApi.close();
    } catch (error) {
      if (isOptimisticLockConflict(error)) {
        emits('success');
        drawerApi.close();
      }
    } finally {
      drawerApi.unlock();
    }
  },
  async onOpenChange(isOpen) {
    if (!isOpen) return;
    role.value = drawerApi.getData<SystemRoleApi.SystemRole>();
    reason.value = '';
    selectedPermissionCodes.value = [];
    await loadGrants();
  },
});

async function loadGrants() {
  if (!role.value) return;
  loading.value = true;
  loadFailed.value = false;
  try {
    const [permissions, detail] = await Promise.all([
      getGrantablePermissions(),
      getRoleGrants(role.value.id),
    ]);
    grantablePermissions.value = permissions;
    grantDetail.value = detail;
    selectedPermissionCodes.value = detail.grants.map(
      ({ permissionCode }) => permissionCode,
    );
  } catch {
    loadFailed.value = true;
    grantablePermissions.value = [];
    grantDetail.value = undefined;
  } finally {
    loading.value = false;
  }
}

function togglePermission(permissionCode: string, checked: boolean) {
  const next = new Set(selectedPermissionCodes.value);
  if (checked) next.add(permissionCode);
  else next.delete(permissionCode);
  selectedPermissionCodes.value = [...next];
}

function clearAll() {
  if (!editable.value || selectedPermissionCodes.value.length === 0) return;
  Modal.confirm({
    content: $t('system.role.clearGrantsConfirm'),
    onOk() {
      selectedPermissionCodes.value = [];
    },
    title: $t('system.role.clearGrants'),
  });
}

const drawerTitle = computed(() =>
  $t('system.role.grantDrawerTitle', [role.value?.name ?? '']),
);
</script>

<template>
  <Drawer class="w-full max-w-180" :title="drawerTitle">
    <Spin :spinning="loading">
      <div class="flex min-h-80 flex-col gap-4 px-2">
        <Alert
          v-if="loadFailed"
          show-icon
          :title="$t('system.role.grantLoadFailed')"
          type="error"
        >
          <template #action>
            <Button size="small" @click="loadGrants">
              {{ $t('system.role.retry') }}
            </Button>
          </template>
        </Alert>

        <Alert
          v-else-if="grantDetail && !editable"
          show-icon
          :title="$t('system.role.grantReadOnly')"
          type="warning"
        />

        <template v-if="!loadFailed">
          <div class="flex items-center justify-between">
            <span class="text-sm text-muted-foreground">
              {{
                $t('system.role.selectedGrantCount', [
                  selectedPermissionCodes.length,
                ])
              }}
            </span>
            <Button
              danger
              size="small"
              :disabled="!editable || selectedPermissionCodes.length === 0"
              @click="clearAll"
            >
              {{ $t('system.role.clearGrants') }}
            </Button>
          </div>

          <Empty v-if="groupedPermissions.length === 0 && !loading" />
          <section
            v-for="group in groupedPermissions"
            :key="group.resourceCode"
            class="border-b pb-3 last:border-b-0"
          >
            <h3 class="mb-2 text-sm font-medium">
              {{ group.resourceCode }}
            </h3>
            <div class="grid grid-cols-1 gap-2 sm:grid-cols-2">
              <Checkbox
                v-for="permission in group.permissions"
                :key="permission.permissionCode"
                :checked="
                  selectedPermissionCodes.includes(permission.permissionCode)
                "
                :disabled="!editable"
                @change="
                  togglePermission(
                    permission.permissionCode,
                    $event.target.checked,
                  )
                "
              >
                <span>{{ permission.permissionCode }}</span>
                <Tag class="ml-2" color="blue">
                  {{ permission.riskLevel }}
                </Tag>
              </Checkbox>
            </div>
          </section>

          <div>
            <label class="mb-1 block text-sm font-medium">
              {{ $t('system.role.grantReason') }}
            </label>
            <Input.TextArea
              v-model:value="reason"
              :disabled="!editable"
              :maxlength="500"
              :placeholder="$t('system.role.grantReasonPlaceholder')"
              :rows="3"
              show-count
            />
          </div>
        </template>
      </div>
    </Spin>
  </Drawer>
</template>
