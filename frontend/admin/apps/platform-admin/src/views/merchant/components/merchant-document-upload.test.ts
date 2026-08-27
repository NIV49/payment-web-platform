import { createApp, nextTick, ref } from 'vue';

import { beforeEach, describe, expect, it, vi } from 'vitest';

import MerchantDocumentUpload from './merchant-document-upload.vue';

const harness = vi.hoisted(() => ({
  createObjectUrl: vi.fn(() => 'blob:private-preview'),
  deleteUpload: vi.fn(),
  getAttached: vi.fn(),
  getTemporary: vi.fn(),
  revokeObjectUrl: vi.fn(),
  upload: vi.fn(),
}));

vi.mock('@payment/backoffice-runtime/api/merchant-onboarding', () => ({
  deleteTemporaryMerchantDocument: harness.deleteUpload,
  getAttachedMerchantDocumentContent: harness.getAttached,
  getTemporaryMerchantDocumentContent: harness.getTemporary,
  uploadTemporaryMerchantDocument: harness.upload,
}));
vi.mock('@payment/backoffice-runtime/locales', () => ({
  $t: (key: string) => key,
}));
vi.mock('@vben/icons', () => ({
  Eye: { template: '<i />' },
  ImagePlus: { template: '<i />' },
  X: { template: '<i />' },
}));
vi.mock('antdv-next', () => ({
  Alert: { props: ['message'], template: '<div>{{ message }}</div>' },
  Button: { template: '<button><slot /></button>' },
  Image: { props: ['src'], template: '<img :src="src" />' },
  Spin: { template: '<div><slot /></div>' },
}));

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((done) => {
    resolve = done;
  });
  return { promise, resolve };
}

function pngFile() {
  return new File(
    [new Uint8Array([137, 80, 78, 71, 13, 10, 26, 10])],
    'must-not-render.png',
    { type: 'image/png' },
  );
}

function mountUpload(props: Record<string, unknown> = {}): {
  app: ReturnType<typeof createApp>;
  instance: any;
  root: HTMLElement;
} {
  const root = document.createElement('div');
  const app = createApp(MerchantDocumentUpload, {
    kind: 'BRAND_LOGO',
    modelValue: '',
    targetTenantId: '17',
    ...props,
  });
  const instance = app.mount(root);
  return { app, instance, root };
}

describe('merchant private document upload', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.stubGlobal('URL', {
      createObjectURL: harness.createObjectUrl,
      revokeObjectURL: harness.revokeObjectUrl,
    });
    harness.deleteUpload.mockResolvedValue({
      documentId: '81',
      status: 'DELETED',
    });
    harness.getTemporary.mockResolvedValue(
      new Blob(['png'], { type: 'image/png' }),
    );
    harness.upload.mockResolvedValue({
      documentId: '81',
      expiresAt: '2026-08-16T00:00:00Z',
      height: 100,
      kind: 'BRAND_LOGO',
      mediaType: 'image/png',
      sizeBytes: 3,
      width: 100,
    });
  });

  it('rejects non-image and oversized files before any network request', async () => {
    const { app, instance, root } = mountUpload();

    await expect(
      instance.uploadFile(
        new File(['pdf'], 'ignored.pdf', { type: 'application/pdf' }),
      ),
    ).resolves.toBe(false);
    await expect(
      instance.uploadFile(
        new File([new Uint8Array(2 * 1024 * 1024 + 1)], 'ignored.png', {
          type: 'image/png',
        }),
      ),
    ).resolves.toBe(false);

    expect(harness.upload).not.toHaveBeenCalled();
    expect(root.textContent).toContain('merchant.onboarding.documents.invalid');
    app.unmount();
  });

  it('rejects a MIME-spoofed image whose file signature does not match', async () => {
    const { app, instance } = mountUpload();

    await expect(
      instance.uploadFile(
        new File(['not an image'], 'ignored.png', { type: 'image/png' }),
      ),
    ).resolves.toBe(false);

    expect(harness.upload).not.toHaveBeenCalled();
    app.unmount();
  });

  it('uploads with the trusted tenant and kind then previews through an authenticated Blob', async () => {
    const { app, instance, root } = mountUpload();
    const file = pngFile();

    await expect(instance.uploadFile(file)).resolves.toBe(true);
    await nextTick();

    expect(harness.upload).toHaveBeenCalledWith({
      file,
      kind: 'BRAND_LOGO',
      targetTenantId: '17',
    });
    expect(harness.getTemporary).toHaveBeenCalledWith('81');
    expect(harness.createObjectUrl).toHaveBeenCalledOnce();
    expect(root.querySelector('img')?.getAttribute('src')).toBe(
      'blob:private-preview',
    );
    expect(root.textContent).not.toContain('must-not-render.png');
    instance.markBound();
    app.unmount();
    expect(harness.deleteUpload).not.toHaveBeenCalled();
    expect(harness.revokeObjectUrl).toHaveBeenCalledWith(
      'blob:private-preview',
    );
  });

  it('does not delete a temporary document while its binding request is in flight', async () => {
    const { app, instance } = mountUpload();
    await instance.uploadFile(pngFile());

    instance.beginBinding();
    app.unmount();

    expect(harness.deleteUpload).not.toHaveBeenCalled();
  });

  it('freezes remove, replacement and tenant cleanup while binding is in flight', async () => {
    const targetTenantId = ref('17');
    const modelValue = ref('');
    const root = document.createElement('div');
    const app = createApp({
      components: { MerchantDocumentUpload },
      setup: () => ({ modelValue, targetTenantId }),
      template: `
        <MerchantDocumentUpload
          ref="upload"
          kind="BRAND_LOGO"
          :model-value="modelValue"
          :target-tenant-id="targetTenantId"
          @update:model-value="modelValue = $event"
        />
      `,
    });
    const wrapper = app.mount(root) as any;
    const instance = wrapper.$refs.upload;
    await instance.uploadFile(pngFile());
    harness.deleteUpload.mockClear();

    instance.beginBinding();
    await instance.removeDocument();
    await expect(instance.uploadFile(pngFile())).resolves.toBe(false);
    targetTenantId.value = '18';
    await nextTick();

    expect(modelValue.value).toBe('81');
    expect(harness.upload).toHaveBeenCalledOnce();
    expect(harness.deleteUpload).not.toHaveBeenCalled();

    instance.finishBinding(false);
    app.unmount();
    expect(harness.deleteUpload).toHaveBeenCalledWith('81');
  });

  it('deletes an owned temporary document when the user removes it', async () => {
    const { app, instance } = mountUpload();
    await instance.uploadFile(pngFile());

    await instance.removeDocument();

    expect(harness.deleteUpload).toHaveBeenCalledWith('81');
    expect(harness.revokeObjectUrl).toHaveBeenCalledWith(
      'blob:private-preview',
    );
    app.unmount();
  });

  it('uses the permanent protected endpoint for an existing attachment', async () => {
    harness.getAttached.mockResolvedValue(
      new Blob(['jpeg'], { type: 'image/jpeg' }),
    );
    const { app, instance } = mountUpload({
      merchantId: '29',
      modelValue: '91',
    });

    await instance.previewDocument();

    expect(harness.getAttached).toHaveBeenCalledWith('29', 'BRAND_LOGO');
    expect(harness.getTemporary).not.toHaveBeenCalled();
    app.unmount();
  });

  it('previews all five pending documents from the exact amendment source', async () => {
    harness.getAttached.mockResolvedValue(
      new Blob(['jpeg'], { type: 'image/jpeg' }),
    );
    const root = document.createElement('div');
    const app = createApp({
      components: { MerchantDocumentUpload },
      setup: () => ({
        kinds: [
          'BRAND_LOGO',
          'BUSINESS_LICENSE',
          'LEGAL_ID_FRONT',
          'LEGAL_ID_BACK',
          'LEGAL_ID_HOLDING',
        ],
      }),
      template: `
        <MerchantDocumentUpload
          v-for="(kind, index) in kinds"
          :key="kind"
          amendment-id="71"
          disabled
          :kind="kind"
          merchant-id="29"
          :model-value="String(101 + index)"
          target-tenant-id="17"
        />
      `,
    });
    app.mount(root);

    const previews = [...root.querySelectorAll('button')].filter(
      (button) =>
        button.textContent?.trim() === 'merchant.onboarding.documents.preview',
    );
    expect(previews).toHaveLength(5);
    previews.forEach((button) => button.click());
    await vi.waitFor(() =>
      expect(harness.getAttached).toHaveBeenCalledTimes(5),
    );

    expect(harness.getAttached.mock.calls).toEqual([
      ['29', 'BRAND_LOGO', '71'],
      ['29', 'BUSINESS_LICENSE', '71'],
      ['29', 'LEGAL_ID_FRONT', '71'],
      ['29', 'LEGAL_ID_BACK', '71'],
      ['29', 'LEGAL_ID_HOLDING', '71'],
    ]);
    expect(harness.getTemporary).not.toHaveBeenCalled();
    app.unmount();
  });

  it('does not fall back when an amendment document preview fails', async () => {
    harness.getAttached.mockRejectedValue(new Error('not found'));
    const { app, instance } = mountUpload({
      amendmentId: '71',
      merchantId: '29',
      modelValue: '101',
    });

    await expect(instance.previewDocument()).resolves.toBe(false);

    expect(harness.getAttached).toHaveBeenCalledOnce();
    expect(harness.getAttached).toHaveBeenCalledWith('29', 'BRAND_LOGO', '71');
    expect(harness.getTemporary).not.toHaveBeenCalled();
    app.unmount();
  });

  it('cleans up a late upload response after unmount without mutating UI state', async () => {
    const response = deferred<any>();
    harness.upload.mockReturnValue(response.promise);
    const { app, instance } = mountUpload();
    const uploading = instance.uploadFile(pngFile());

    app.unmount();
    response.resolve({
      documentId: '82',
      expiresAt: '2026-08-16T00:00:00Z',
      height: 100,
      kind: 'BRAND_LOGO',
      mediaType: 'image/png',
      sizeBytes: 3,
      width: 100,
    });
    await uploading;

    expect(harness.deleteUpload).toHaveBeenCalledWith('82');
    expect(harness.getTemporary).not.toHaveBeenCalled();
  });

  it('invalidates and deletes an upload that returns after the tenant changes', async () => {
    const response = deferred<any>();
    harness.upload.mockReturnValue(response.promise);
    const targetTenantId = ref('17');
    const onDocumentChange = vi.fn();
    const root = document.createElement('div');
    const app = createApp({
      components: { MerchantDocumentUpload },
      setup: () => ({ onDocumentChange, targetTenantId }),
      template: `
        <MerchantDocumentUpload
          ref="upload"
          kind="BRAND_LOGO"
          model-value=""
          :target-tenant-id="targetTenantId"
          @update:model-value="onDocumentChange"
        />
      `,
    });
    const wrapper = app.mount(root) as any;
    const uploading = wrapper.$refs.upload.uploadFile(pngFile());

    await vi.waitFor(() => expect(harness.upload).toHaveBeenCalledOnce());
    targetTenantId.value = '18';
    await nextTick();
    response.resolve({
      documentId: '82',
      expiresAt: '2026-08-16T00:00:00Z',
      height: 100,
      kind: 'BRAND_LOGO',
      mediaType: 'image/png',
      sizeBytes: 3,
      width: 100,
    });
    await uploading;

    expect(harness.deleteUpload).toHaveBeenCalledWith('82');
    expect(onDocumentChange).not.toHaveBeenCalledWith('82');
    expect(harness.getTemporary).not.toHaveBeenCalled();
    app.unmount();
  });

  it('invalidates an in-flight replacement before binding the submitted document', async () => {
    const replacement = deferred<any>();
    const modelValue = ref('');
    const root = document.createElement('div');
    const app = createApp({
      components: { MerchantDocumentUpload },
      setup: () => ({ modelValue }),
      template: `
        <MerchantDocumentUpload
          ref="upload"
          kind="BRAND_LOGO"
          :model-value="modelValue"
          target-tenant-id="17"
          @update:model-value="modelValue = $event"
        />
      `,
    });
    const wrapper = app.mount(root) as any;
    const instance = wrapper.$refs.upload;
    await instance.uploadFile(pngFile());
    expect(modelValue.value).toBe('81');
    harness.upload.mockReturnValueOnce(replacement.promise);

    const uploading = instance.uploadFile(pngFile());
    await vi.waitFor(() => expect(harness.upload).toHaveBeenCalledTimes(2));
    instance.beginBinding();
    replacement.resolve({
      documentId: '82',
      expiresAt: '2026-08-16T00:00:00Z',
      height: 100,
      kind: 'BRAND_LOGO',
      mediaType: 'image/png',
      sizeBytes: 3,
      width: 100,
    });
    await expect(uploading).resolves.toBe(false);

    expect(modelValue.value).toBe('81');
    expect(harness.deleteUpload).toHaveBeenCalledWith('82');
    expect(harness.getTemporary).not.toHaveBeenCalledWith('82');
    instance.finishBinding(true);
    app.unmount();
    expect(harness.deleteUpload).not.toHaveBeenCalledWith('81');
  });
});
