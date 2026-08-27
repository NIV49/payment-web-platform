<script lang="ts" setup>
import type { SystemRoleApi } from '@payment/backoffice-runtime/api/system/role';
import type { SystemUserApi } from '@payment/backoffice-runtime/api/system/user';

import { computed, ref } from 'vue';

import { useVbenDrawer } from '@vben/common-ui';
import { useUserStore } from '@vben/stores';

import {
  getRoleMembers,
  updateRoleMembers,
} from '@payment/backoffice-runtime/api';
import { isOptimisticLockConflict } from '@payment/backoffice-runtime/api/error-contract';
import { $t } from '@payment/backoffice-runtime/locales';
import { Alert, Button, message, Spin, Transfer } from 'antdv-next';

import { mergeRoleMemberCatalog } from './role-member-assignment';

const PAGE_SIZE = 200;
const MAX_MEMBER_CHANGES = 200;

interface RoleMemberTransferItem {
  description: string;
  key: string;
  title: string;
}

const userStore = useUserStore();
const target = ref<SystemRoleApi.SystemRole>();
const users = ref<SystemUserApi.SystemUser[]>([]);
const initialAssignedIds = ref<string[]>([]);
const selectedAssignedIds = ref<string[]>([]);
const loadFailed = ref(false);
const ready = ref(false);
let loadSequence = 0;

const title = computed(() =>
  $t('system.role.assignUserTitle', [target.value?.name ?? '']),
);
const transferItems = computed(() =>
  users.value.map((user) => ({
    description: user.username,
    key: user.id,
    title: user.name,
  })),
);

function renderTransferItem(item: RoleMemberTransferItem) {
  return `${item.title} (${item.description})`;
}

async function loadAllMembers(roleId: string, assigned: boolean) {
  const items: SystemUserApi.SystemUser[] = [];
  let page = 1;
  while (true) {
    const result = await getRoleMembers(roleId, {
      assigned,
      page,
      pageSize: PAGE_SIZE,
    });
    items.push(...result.items);
    if (items.length >= result.total || result.items.length < PAGE_SIZE) {
      return items;
    }
    page += 1;
  }
}

async function loadMembers() {
  const currentRole = target.value;
  if (!currentRole) return;
  const sequence = ++loadSequence;
  ready.value = false;
  loadFailed.value = false;
  drawerApi.setState({ loading: true, showConfirmButton: false });
  try {
    const [assigned, unassigned] = await Promise.all([
      loadAllMembers(currentRole.id, true),
      loadAllMembers(currentRole.id, false),
    ]);
    if (sequence !== loadSequence || target.value?.id !== currentRole.id)
      return;
    users.value = mergeRoleMemberCatalog(
      assigned,
      unassigned,
      userStore.userInfo?.userId,
    );
    initialAssignedIds.value = assigned.map((user) => user.id);
    selectedAssignedIds.value = [...initialAssignedIds.value];
    ready.value = true;
    drawerApi.setState({ loading: false, showConfirmButton: true });
  } catch {
    if (sequence !== loadSequence || target.value?.id !== currentRole.id)
      return;
    loadFailed.value = true;
    drawerApi.setState({ loading: false, showConfirmButton: false });
  }
}

const [Drawer, drawerApi] = useVbenDrawer({
  async onConfirm() {
    const currentRole = target.value;
    if (!currentRole || !ready.value) return;
    const before = new Set(initialAssignedIds.value);
    const after = new Set(selectedAssignedIds.value);
    const members: SystemRoleApi.RoleMemberChange[] = users.value.flatMap(
      (user) => {
        const wasAssigned = before.has(user.id);
        const assigned = after.has(user.id);
        return wasAssigned === assigned
          ? []
          : [
              {
                assigned,
                userId: user.id,
                userVersion: user.userVersion,
              },
            ];
      },
    );
    if (members.length > MAX_MEMBER_CHANGES) {
      message.error($t('system.role.assignUserLimit'));
      return;
    }
    if (members.length === 0) {
      drawerApi.close();
      return;
    }
    drawerApi.lock();
    try {
      await updateRoleMembers(currentRole.id, { members });
      message.success($t('system.role.assignUserSuccess', [currentRole.name]));
      drawerApi.close();
    } catch (error) {
      if (isOptimisticLockConflict(error)) {
        await loadMembers();
        return;
      }
      await loadMembers();
      throw error;
    } finally {
      drawerApi.unlock();
    }
  },
  onOpenChange(open) {
    loadSequence += 1;
    ready.value = false;
    loadFailed.value = false;
    users.value = [];
    initialAssignedIds.value = [];
    selectedAssignedIds.value = [];
    if (!open) {
      target.value = undefined;
      return;
    }
    target.value = drawerApi.getData<SystemRoleApi.SystemRole>();
    void loadMembers();
  },
});
</script>

<template>
  <Drawer :title="title">
    <Alert
      v-if="loadFailed"
      show-icon
      :title="$t('system.role.assignUserLoadFailed')"
      type="error"
    >
      <template #action>
        <Button size="small" @click="loadMembers">
          {{ $t('common.retry') }}
        </Button>
      </template>
    </Alert>
    <Spin v-else :spinning="!ready">
      <Transfer
        v-if="ready"
        v-model:target-keys="selectedAssignedIds"
        class="role-member-transfer"
        :data-source="transferItems"
        :render="renderTransferItem"
        show-search
        :styles="{
          section: { height: '420px', width: 'calc(50% - 24px)' },
        }"
        :titles="[
          $t('system.role.unassignedUsers'),
          $t('system.role.assignedUsers'),
        ]"
      />
    </Spin>
  </Drawer>
</template>

<style scoped>
:deep(.role-member-transfer .ant-transfer-list) {
  flex: 1;
  min-width: 0;
}
</style>
