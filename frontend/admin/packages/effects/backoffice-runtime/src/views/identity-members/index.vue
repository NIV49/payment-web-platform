<script lang="ts" setup>
import type { VbenFormSchema } from '../../adapter/form';
import type { VxeTableGridOptions } from '../../adapter/vxe-table';
import type { IdentityGovernanceApi } from '../../api';

import { ref } from 'vue';

import { Page, useVbenModal } from '@vben/common-ui';
import { IconifyIcon } from '@vben/icons';

import { Button, message, Modal, Tag, Tooltip } from 'antdv-next';

import { useVbenForm, z } from '../../adapter/form';
import { useVbenVxeGrid, VbenTableAction } from '../../adapter/vxe-table';
import {
  createIdentityInvitation,
  getIdentityInvitationRoles,
  getIdentityMembers,
  requestIdentityMfaRecovery,
} from '../../api';
import { $t } from '../../locales';
import { useAuthStore } from '../../store';

defineOptions({ name: 'IdentityMembersView' });

type InvitationValues = {
  displayName: string;
  email: string;
  roleIds: string[];
};

const authStore = useAuthStore();
const invitationRoles = ref<IdentityGovernanceApi.InvitationRole[]>([]);

const invitationSchema: VbenFormSchema[] = [
  {
    component: 'Input',
    fieldName: 'email',
    label: $t('identity.members.email'),
    rules: z.string().trim().email().max(254),
  },
  {
    component: 'Input',
    fieldName: 'displayName',
    label: $t('identity.members.displayName'),
    rules: z.string().trim().min(1).max(128),
  },
  {
    component: 'Select',
    componentProps: () => ({
      class: 'w-full',
      mode: 'multiple',
      options: invitationRoles.value.map((role) => ({
        label: role.roleName,
        value: role.roleId,
      })),
    }),
    fieldName: 'roleIds',
    label: $t('identity.members.roles'),
    rules: 'required',
  },
];

const [InvitationForm, invitationFormApi] = useVbenForm({
  layout: 'vertical',
  schema: invitationSchema,
  showDefaultActions: false,
});

const [InvitationModal, invitationModalApi] = useVbenModal({
  async onConfirm() {
    const { valid } = await invitationFormApi.validate();
    if (!valid) return;
    const values = await invitationFormApi.getValues<InvitationValues>();
    invitationModalApi.lock();
    try {
      await createIdentityInvitation({
        ...values,
        idempotencyKey: crypto.randomUUID(),
      });
      message.success($t('identity.members.inviteSuccess'));
      invitationModalApi.close();
      gridApi.query();
    } finally {
      invitationModalApi.lock(false);
    }
  },
  async onOpenChange(open: boolean) {
    if (!open) return;
    invitationModalApi.setState({ loading: true, showConfirmButton: false });
    try {
      invitationRoles.value = await getIdentityInvitationRoles();
      await invitationFormApi.reset();
      invitationModalApi.setState({ showConfirmButton: true });
    } finally {
      invitationModalApi.setState({ loading: false });
    }
  },
});

const columns = [
  {
    field: 'displayName',
    minWidth: 180,
    title: $t('identity.members.displayName'),
  },
  {
    field: 'membershipStatus',
    minWidth: 130,
    title: $t('identity.members.membershipStatus'),
  },
  {
    field: 'identityStatus',
    minWidth: 130,
    title: $t('identity.members.identityStatus'),
  },
  {
    field: 'provisioningStatus',
    minWidth: 150,
    title: $t('identity.members.provisioningStatus'),
  },
  {
    field: 'systemAdministrator',
    minWidth: 150,
    slots: { default: 'administrator' },
    title: $t('identity.members.administrator'),
  },
  {
    field: 'action',
    fixed: 'right',
    slots: { default: 'action' },
    title: $t('common.operation'),
    width: 150,
  },
];

const [Grid, gridApi] = useVbenVxeGrid<IdentityGovernanceApi.Member>({
  gridOptions: {
    columns,
    height: 'auto',
    pagerConfig: { pageSize: 20 },
    proxyConfig: {
      ajax: {
        query: async ({ page }) =>
          getIdentityMembers(page.currentPage, page.pageSize),
      },
    },
    rowConfig: { keyField: 'membershipId' },
    toolbarConfig: {
      custom: true,
      export: false,
      refresh: true,
      zoom: true,
    },
  } as VxeTableGridOptions<IdentityGovernanceApi.Member>,
});

function canRecover(member: IdentityGovernanceApi.Member) {
  return (
    !member.currentMembership &&
    member.membershipStatus === 'ACTIVE' &&
    member.identityStatus === 'ACTIVE' &&
    member.provisioningStatus === 'PROVISIONED'
  );
}

function requestRecovery(member: IdentityGovernanceApi.Member) {
  if (!canRecover(member)) return;
  Modal.confirm({
    content: $t('identity.members.recoveryConfirm'),
    async onOk() {
      await requestIdentityMfaRecovery(member.membershipId);
      message.success($t('identity.members.recoverySuccess'));
      gridApi.query();
    },
    title: $t('identity.members.recovery'),
  });
}

function startStepUp() {
  void authStore.startOidcStepUp();
}
</script>

<template>
  <Page auto-content-height>
    <InvitationModal :title="$t('identity.members.invite')">
      <InvitationForm class="mx-4" />
    </InvitationModal>

    <Grid :table-title="$t('identity.members.list')">
      <template #toolbar-tools>
        <Tooltip :title="$t('identity.members.stepUpRequired')">
          <Button @click="startStepUp">
            <IconifyIcon class="size-5" icon="lucide:shield-check" />
            {{ $t('identity.members.stepUp') }}
          </Button>
        </Tooltip>
        <Button type="primary" @click="invitationModalApi.open()">
          <IconifyIcon class="size-5" icon="lucide:user-plus" />
          {{ $t('identity.members.invite') }}
        </Button>
      </template>

      <template #administrator="{ row }">
        <Tag :color="row.systemAdministrator ? 'blue' : 'default'">
          {{
            $t(
              row.systemAdministrator
                ? 'identity.members.administrator'
                : 'identity.members.ordinaryMember',
            )
          }}
        </Tag>
      </template>

      <template #action="{ row }">
        <VbenTableAction
          v-if="canRecover(row)"
          :actions="[
            {
              icon: 'lucide:key-round',
              onClick: () => requestRecovery(row),
              text: $t('identity.members.recovery'),
            },
          ]"
        />
      </template>
    </Grid>
  </Page>
</template>
