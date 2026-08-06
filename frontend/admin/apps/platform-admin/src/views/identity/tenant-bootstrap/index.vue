<script lang="ts" setup>
import type { VbenFormSchema } from '@payment/backoffice-runtime/adapter/form';
import type { IdentityGovernanceApi } from '@payment/backoffice-runtime/api';

import { ref } from 'vue';

import { Page } from '@vben/common-ui';
import { IconifyIcon } from '@vben/icons';

import { useVbenForm, z } from '@payment/backoffice-runtime/adapter/form';
import { createIdentityTenantBootstrap } from '@payment/backoffice-runtime/api';
import { $t } from '@payment/backoffice-runtime/locales';
import { useAuthStore } from '@payment/backoffice-runtime/store';
import { Button, message, Tooltip } from 'antdv-next';

defineOptions({ name: 'PlatformTenantBootstrap' });

type BootstrapValues = {
  administratorEmail: string;
  administratorName: string;
  entryHost: string;
  tenantCode: string;
  tenantName: string;
  tenantType: IdentityGovernanceApi.TenantBootstrapParams['tenantType'];
};

const authStore = useAuthStore();
const submitting = ref(false);
const hostPattern =
  /^[a-z0-9](?:[a-z0-9-]*[a-z0-9])?(?:\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)+$/;

const schema: VbenFormSchema[] = [
  {
    component: 'Input',
    fieldName: 'tenantCode',
    label: $t('identity.bootstrap.tenantCode'),
    rules: z.string().regex(/^[a-z0-9][a-z0-9-]{1,62}$/),
  },
  {
    component: 'Input',
    fieldName: 'tenantName',
    label: $t('identity.bootstrap.tenantName'),
    rules: z.string().trim().min(1).max(128),
  },
  {
    component: 'RadioGroup',
    componentProps: {
      buttonStyle: 'solid',
      optionType: 'button',
      options: [
        { label: 'Direct Merchant', value: 'DIRECT_MERCHANT' },
        { label: 'Indirect Merchant', value: 'INDIRECT_MERCHANT' },
        { label: 'Agent', value: 'AGENT' },
      ],
    },
    defaultValue: 'DIRECT_MERCHANT',
    fieldName: 'tenantType',
    label: $t('identity.bootstrap.tenantType'),
    rules: 'required',
  },
  {
    component: 'Input',
    fieldName: 'entryHost',
    label: $t('identity.bootstrap.entryHost'),
    rules: z.string().trim().max(253).regex(hostPattern),
  },
  {
    component: 'Input',
    fieldName: 'administratorEmail',
    label: $t('identity.bootstrap.administratorEmail'),
    rules: z.string().trim().email().max(254),
  },
  {
    component: 'Input',
    fieldName: 'administratorName',
    label: $t('identity.bootstrap.administratorName'),
    rules: z.string().trim().min(1).max(128),
  },
];

const [Form, formApi] = useVbenForm({
  layout: 'vertical',
  schema,
  showDefaultActions: false,
});

async function submit() {
  const { valid } = await formApi.validate();
  if (!valid || submitting.value) return;
  const values = await formApi.getValues<BootstrapValues>();
  submitting.value = true;
  try {
    await createIdentityTenantBootstrap({
      entryHost: values.entryHost,
      firstAdministrator: {
        displayName: values.administratorName,
        email: values.administratorEmail,
      },
      idempotencyKey: crypto.randomUUID(),
      tenantCode: values.tenantCode,
      tenantName: values.tenantName,
      tenantType: values.tenantType,
    });
    message.success($t('identity.bootstrap.success'));
    await formApi.reset();
  } finally {
    submitting.value = false;
  }
}

function startStepUp() {
  void authStore.startOidcStepUp();
}
</script>

<template>
  <Page :title="$t('identity.bootstrap.title')">
    <div class="max-w-3xl">
      <Form />
      <div class="mt-6 flex flex-wrap justify-end gap-3">
        <Tooltip :title="$t('identity.members.stepUpRequired')">
          <Button @click="startStepUp">
            <IconifyIcon class="size-5" icon="lucide:shield-check" />
            {{ $t('identity.members.stepUp') }}
          </Button>
        </Tooltip>
        <Button :loading="submitting" type="primary" @click="submit">
          <IconifyIcon class="size-5" icon="lucide:building-2" />
          {{ $t('identity.bootstrap.create') }}
        </Button>
      </div>
    </div>
  </Page>
</template>
