import { createApp, defineComponent, h, nextTick } from 'vue';

import { beforeEach, describe, expect, it, vi } from 'vitest';

import MerchantDocumentPreview from './merchant-document-preview.vue';

const harness = vi.hoisted(() => ({
  createObjectUrl: vi.fn(() => 'blob:protected-document'),
  getAttached: vi.fn(),
  revokeObjectUrl: vi.fn(),
}));

vi.mock('@payment/backoffice-runtime/api/merchant-onboarding', () => ({
  getAttachedMerchantDocumentContent: harness.getAttached,
}));
vi.mock('@payment/backoffice-runtime/locales', () => ({
  $t: (key: string) => key,
}));
vi.mock('#/locales', () => ({
  $t: (key: string) => key,
}));
vi.mock('@vben/icons', () => ({
  Eye: { template: '<i />' },
}));
vi.mock('antdv-next', () => ({
  Alert: { props: ['message'], template: '<div>{{ message }}</div>' },
  Button: defineComponent({
    emits: ['click'],
    setup(_, { emit, slots }) {
      return () =>
        h('button', { onClick: () => emit('click') }, slots.default?.());
    },
  }),
  Image: { props: ['src'], template: '<img :src="src" />' },
}));

const documentMetadata = {
  documentId: '91',
  height: 120,
  kind: 'BRAND_LOGO' as const,
  mediaType: 'image/png' as const,
  sizeBytes: 42,
  width: 160,
};

function mountPreview(props: Record<string, unknown> = {}) {
  const root = document.createElement('div');
  const app = createApp(MerchantDocumentPreview, {
    canPreview: true,
    document: documentMetadata,
    kind: 'BRAND_LOGO',
    merchantId: '29',
    ...props,
  });
  const instance = app.mount(root) as any;
  return { app, instance, root };
}

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((done) => {
    resolve = done;
  });
  return { promise, resolve };
}

describe('merchant read-only document preview', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.stubGlobal('URL', {
      createObjectURL: harness.createObjectUrl,
      revokeObjectURL: harness.revokeObjectUrl,
    });
    harness.getAttached.mockResolvedValue(
      new Blob(['sanitized'], { type: 'image/png' }),
    );
  });

  it('reads the exact pending amendment document and revokes the Blob URL', async () => {
    const { app, instance, root } = mountPreview({ amendmentId: '71' });

    await expect(instance.previewDocument()).resolves.toBe(true);
    await nextTick();

    expect(harness.getAttached).toHaveBeenCalledWith('29', 'BRAND_LOGO', '71');
    expect(root.querySelector('img')?.getAttribute('src')).toBe(
      'blob:protected-document',
    );
    app.unmount();
    expect(harness.revokeObjectUrl).toHaveBeenCalledWith(
      'blob:protected-document',
    );
  });

  it('does not request a protected document without permission', async () => {
    const { app, instance, root } = mountPreview({ canPreview: false });

    await expect(instance.previewDocument()).resolves.toBe(false);

    expect(harness.getAttached).not.toHaveBeenCalled();
    expect(root.textContent).toContain(
      'merchant.detail.documentPreviewUnavailable',
    );
    app.unmount();
  });

  it('shows a bounded error and never falls back to another source', async () => {
    harness.getAttached.mockRejectedValue(new Error('not found'));
    const { app, instance, root } = mountPreview({ amendmentId: '71' });

    await expect(instance.previewDocument()).resolves.toBe(false);
    await nextTick();

    expect(harness.getAttached).toHaveBeenCalledOnce();
    expect(root.textContent).toContain(
      'merchant.onboarding.documents.previewFailed',
    );
    app.unmount();
  });

  it('does not create a Blob URL when the response arrives after unmount', async () => {
    const response = deferred<Blob>();
    harness.getAttached.mockReturnValue(response.promise);
    const { app, instance } = mountPreview();

    const previewing = instance.previewDocument();
    app.unmount();
    response.resolve(new Blob(['sanitized'], { type: 'image/png' }));

    await expect(previewing).resolves.toBe(false);
    expect(harness.createObjectUrl).not.toHaveBeenCalled();
  });
});
