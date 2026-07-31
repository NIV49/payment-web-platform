<script lang="ts" setup>
import type { RoleRequestIdentity } from '../role-request-guard';

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

import {
  buildTenantRoleGrants,
  findMissingPermissionDependencies,
  permissionDependencies,
  reconcilePermissionSelection,
} from '../grant-contract';
import { createRoleRequestGuard } from '../role-request-guard';

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
const loadGuard = createRoleRequestGuard();
const appliedLoadIdentity = ref<RoleRequestIdentity>();

const editable = computed(() => grantDetail.value?.editable === true);
const dependencyViolations = computed(() =>
  findMissingPermissionDependencies(selectedPermissionCodes.value),
);
const dependencyViolationSummary = computed(() =>
  dependencyViolations.value
    .map(
      ({ missing, permissionCode }) =>
        `${permissionCode} -> ${missing.join(', ')}`,
    )
    .join('; '),
);
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
    const currentRole = role.value;
    const currentDetail = grantDetail.value;
    const currentLoadIdentity = appliedLoadIdentity.value;
    if (
      !editable.value ||
      !currentRole ||
      !currentDetail ||
      currentDetail.roleId !== currentRole.id ||
      !currentLoadIdentity ||
      !loadGuard.isCurrent(currentLoadIdentity, currentRole.id)
    ) {
      return;
    }
    const normalizedReason = reason.value.trim();
    if (!normalizedReason) {
      message.warning($t('system.role.grantReasonRequired'));
      return;
    }
    if (dependencyViolations.value.length > 0) {
      message.warning(
        $t('system.role.grantDependencyMissing', [
          dependencyViolationSummary.value,
        ]),
      );
      return;
    }
    drawerApi.lock();
    try {
      await replaceRoleGrants(currentRole.id, {
        expectedVersion: currentDetail.roleVersion,
        grants: buildTenantRoleGrants(selectedPermissionCodes.value),
        reason: normalizedReason,
      });
      if (!loadGuard.isCurrent(currentLoadIdentity, role.value?.id)) return;
      message.success($t('system.role.grantSaveSuccess'));
      emits('success');
      drawerApi.close();
    } catch (error) {
      if (!loadGuard.isCurrent(currentLoadIdentity, role.value?.id)) return;
      if (isOptimisticLockConflict(error)) {
        emits('success');
        drawerApi.close();
      }
    } finally {
      if (loadGuard.isCurrent(currentLoadIdentity, role.value?.id)) {
        drawerApi.unlock();
      }
    }
  },
  onOpenChange(isOpen) {
    loadGuard.invalidate();
    appliedLoadIdentity.value = undefined;
    grantablePermissions.value = [];
    grantDetail.value = undefined;
    selectedPermissionCodes.value = [];
    reason.value = '';
    loading.value = false;
    loadFailed.value = false;
    drawerApi.unlock();
    drawerApi.setState({ showConfirmButton: false });
    if (!isOpen) {
      role.value = undefined;
      return;
    }
    role.value = drawerApi.getData<SystemRoleApi.SystemRole>();
    void loadGrants();
  },
});

async function loadGrants() {
  const currentRole = role.value;
  if (!currentRole) return;
  const loadIdentity = loadGuard.begin(currentRole.id);
  loading.value = true;
  loadFailed.value = false;
  appliedLoadIdentity.value = undefined;
  drawerApi.setState({ showConfirmButton: false });
  try {
    const [permissions, detail] = await Promise.all([
      getGrantablePermissions(),
      getRoleGrants(currentRole.id),
    ]);
    if (!loadGuard.isCurrent(loadIdentity, role.value?.id)) return;
    if (detail.roleId !== currentRole.id) {
      throw new Error('Role grant response does not match the active role');
    }
    grantablePermissions.value = permissions;
    grantDetail.value = detail;
    selectedPermissionCodes.value = detail.grants.map(
      ({ permissionCode }) => permissionCode,
    );
    appliedLoadIdentity.value = loadIdentity;
    drawerApi.setState({ showConfirmButton: detail.editable });
  } catch {
    if (!loadGuard.isCurrent(loadIdentity, role.value?.id)) return;
    loadFailed.value = true;
    grantablePermissions.value = [];
    grantDetail.value = undefined;
    drawerApi.setState({ showConfirmButton: false });
  } finally {
    if (loadGuard.isCurrent(loadIdentity, role.value?.id)) {
      loading.value = false;
    }
  }
}

function togglePermission(permissionCode: string, checked: boolean) {
  selectedPermissionCodes.value = reconcilePermissionSelection(
    selectedPermissionCodes.value,
    permissionCode,
    checked,
  );
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

        <Alert
          v-else-if="dependencyViolations.length > 0"
          show-icon
          :title="
            $t('system.role.grantDependencyMissing', [
              dependencyViolationSummary,
            ])
          "
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
                <Tag
                  v-if="
                    permissionDependencies(permission.permissionCode).length > 0
                  "
                  class="ml-2"
                >
                  {{
                    $t('system.role.grantRequires', [
                      permissionDependencies(permission.permissionCode).join(
                        ', ',
                      ),
                    ])
                  }}
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
