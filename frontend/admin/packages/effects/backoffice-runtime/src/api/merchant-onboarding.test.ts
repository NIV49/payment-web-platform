import { beforeEach, describe, expect, it, vi } from 'vitest';

import {
  createPlatformMerchant,
  deleteTemporaryMerchantDocument,
  getAttachedMerchantDocumentContent,
  getEligibleMerchantTenants,
  getPendingMerchantAmendment,
  getTemporaryMerchantDocumentContent,
  reviewMerchantAmendment,
  submitMerchantAmendment,
  uploadTemporaryMerchantDocument,
} from './merchant-onboarding';

const harness = vi.hoisted(() => ({
  baseDownload: vi.fn(),
  delete: vi.fn(),
  get: vi.fn(),
  post: vi.fn(),
  upload: vi.fn(),
}));

vi.mock('../deployment', () => ({
  getInstalledBackofficeDeployment: () => ({ accountDomain: 'PLATFORM' }),
}));
vi.mock('./request', () => ({
  baseRequestClient: { download: harness.baseDownload },
  requestClient: {
    delete: harness.delete,
    get: harness.get,
    post: harness.post,
    upload: harness.upload,
  },
}));

const documentMetadata = (kind: string, documentId = '740000000000000006') => ({
  documentId,
  height: 512,
  kind,
  mediaType: 'image/png',
  sizeBytes: 65_536,
  width: 512,
});

const replacementProfile = {
  authenticationType: 'ENTERPRISE',
  brandLogoDocumentId: '740000000000000006',
  brandName: 'Example',
  businessLicenseDocumentId: '740000000000000002',
  contactEmail: 'merchant-contact@example.test',
  contactPhone: '+551100000000',
  displayName: 'Example Pay',
  industryCode: 'FINANCIAL_SERVICES',
  legalIdBackDocumentId: '740000000000000004',
  legalIdFrontDocumentId: '740000000000000003',
  legalIdHoldingDocumentId: '740000000000000005',
  legalIdNo: { mode: 'REPLACE', value: 'SYNTHETIC-ID-0001' },
  legalIdTypeCode: 'NATIONAL_ID',
  legalIdValidity: { validFrom: '2026-01-01', validTo: '2036-01-01' },
  legalName: 'Example Payments Ltd.',
  legalPersonName: 'Example Director',
  marketCodes: ['BRA', 'PHL'] as Array<'BRA' | 'PHL'>,
  merchantTypeCode: 'PLATFORM',
  operatingAddress: 'Operating address',
  registeredAddress: 'Registered address',
  registrationCountry: 'BR',
  registrationNumber: { mode: 'REPLACE', value: 'SYNTHETIC-REG-0001' },
  remarks: '',
} as const;

const pendingAmendment = {
  amendmentId: '750000000000000001',
  authorMembershipId: '630000000000000001',
  canCurrentActorReview: false,
  createdAt: '2026-08-15T08:00:00Z',
  decision: null,
  merchantId: '720000000000000001',
  originMerchantVersion: 7,
  originStatus: 'ACTIVE',
  profile: {
    authenticationType: 'ENTERPRISE',
    brandLogoDocument: documentMetadata('BRAND_LOGO'),
    brandName: 'Example',
    businessLicenseDocument: documentMetadata(
      'BUSINESS_LICENSE',
      '740000000000000002',
    ),
    contactEmail: 'merchant-contact@example.test',
    contactPhone: '+551100000000',
    displayName: 'Example Pay',
    industryCode: 'FINANCIAL_SERVICES',
    legalIdBackDocument: documentMetadata(
      'LEGAL_ID_BACK',
      '740000000000000004',
    ),
    legalIdFrontDocument: documentMetadata(
      'LEGAL_ID_FRONT',
      '740000000000000003',
    ),
    legalIdHoldingDocument: documentMetadata(
      'LEGAL_ID_HOLDING',
      '740000000000000005',
    ),
    legalIdNoMasked: '********0001',
    legalIdTypeCode: 'NATIONAL_ID',
    legalIdValidity: { validFrom: '2026-01-01', validTo: '2036-01-01' },
    legalName: 'Example Payments Ltd.',
    legalPersonName: 'Example Director',
    marketCodes: ['BRA', 'PHL'],
    merchantTypeCode: 'PLATFORM',
    operatingAddress: 'Operating address',
    registeredAddress: 'Registered address',
    registrationCountry: 'BR',
    registrationNumberMasked: '********0001',
    remarks: '',
  },
  rowVersion: 0,
  status: 'PENDING_REVIEW',
  updatedAt: '2026-08-15T08:00:00Z',
} as const;

describe('mCH-003 PLATFORM onboarding API', () => {
  beforeEach(() => vi.clearAllMocks());

  it('parses the strict eligible tenant page and keeps Long IDs as strings', async () => {
    harness.get.mockResolvedValue({
      items: [
        {
          tenantCode: 'merchant-example',
          tenantId: '620000000000000001',
          tenantName: 'Example Merchant Workspace',
        },
      ],
      total: 1,
    });
    await expect(
      getEligibleMerchantTenants({ page: 1, pageSize: 20 }),
    ).resolves.toMatchObject({ total: 1 });
    expect(harness.get).toHaveBeenCalledWith(
      '/platform/merchant-onboarding/eligible-tenants',
      { params: { page: 1, pageSize: 20 } },
    );

    harness.get.mockResolvedValue({
      items: [{ tenantCode: 'x', tenantId: 1, tenantName: 'x' }],
      total: 1,
    });
    await expect(getEligibleMerchantTenants({})).rejects.toThrow(
      'Invalid eligible Tenant response',
    );
  });

  it('uploads exactly target tenant, kind and a private PNG/JPEG up to 2 MiB', async () => {
    harness.upload.mockResolvedValue({
      ...documentMetadata('BRAND_LOGO'),
      expiresAt: '2026-08-15T08:30:00Z',
    });
    const file = new File(['png'], 'private-name.png', { type: 'image/png' });
    await expect(
      uploadTemporaryMerchantDocument({
        file,
        kind: 'BRAND_LOGO',
        targetTenantId: '620000000000000001',
      }),
    ).resolves.toMatchObject({ documentId: '740000000000000006' });
    expect(harness.upload).toHaveBeenCalledWith(
      '/platform/merchant-document-uploads',
      {
        file,
        kind: 'BRAND_LOGO',
        targetTenantId: '620000000000000001',
      },
    );

    await expect(
      uploadTemporaryMerchantDocument({
        file: new File(['x'], 'bad.gif', { type: 'image/gif' }),
        kind: 'BRAND_LOGO',
        targetTenantId: '620000000000000001',
      }),
    ).rejects.toThrow('PNG or JPEG');
    expect(harness.upload).toHaveBeenCalledTimes(1);
  });

  it('deletes only an exact temporary document ID and parses the receipt', async () => {
    harness.delete.mockResolvedValue({
      documentId: '740000000000000006',
      status: 'DELETED',
    });
    await expect(
      deleteTemporaryMerchantDocument('740000000000000006'),
    ).resolves.toEqual({
      documentId: '740000000000000006',
      status: 'DELETED',
    });
    expect(harness.delete).toHaveBeenCalledWith(
      '/platform/merchant-document-uploads/740000000000000006',
    );
  });

  it('accepts private content only with the exact binary security headers', async () => {
    const blob = new Blob(['png'], { type: 'image/png' });
    harness.baseDownload.mockResolvedValue({
      data: blob,
      headers: {
        'cache-control': 'no-store',
        'content-disposition': 'inline',
        'content-length': String(blob.size),
        'content-security-policy': "sandbox; default-src 'none'",
        'content-type': 'image/png',
        pragma: 'no-cache',
        'x-content-type-options': 'nosniff',
      },
      status: 200,
    });
    await expect(
      getTemporaryMerchantDocumentContent('740000000000000006'),
    ).resolves.toBe(blob);
    await expect(
      getAttachedMerchantDocumentContent(
        '720000000000000001',
        'BUSINESS_LICENSE',
      ),
    ).resolves.toBe(blob);
    await expect(
      getAttachedMerchantDocumentContent(
        '720000000000000001',
        'BUSINESS_LICENSE',
        '750000000000000001',
      ),
    ).resolves.toBe(blob);
    expect(harness.baseDownload.mock.calls).toEqual([
      [
        '/platform/merchant-document-uploads/740000000000000006/content',
        { responseReturn: 'raw' },
      ],
      [
        '/platform/merchants/720000000000000001/documents/BUSINESS_LICENSE/content',
        { responseReturn: 'raw' },
      ],
      [
        '/platform/merchants/720000000000000001/documents/BUSINESS_LICENSE/content',
        {
          params: { amendmentId: '750000000000000001' },
          responseReturn: 'raw',
        },
      ],
    ]);

    harness.baseDownload.mockResolvedValue({
      data: blob,
      headers: { 'content-type': 'image/png' },
      status: 200,
    });
    await expect(
      getTemporaryMerchantDocumentContent('740000000000000006'),
    ).rejects.toThrow('Invalid merchant document response');
  });

  it('creates with one exact nested profile and parses the existing mutation result', async () => {
    harness.post.mockResolvedValue({
      merchantCode: 'MCH_EXAMPLE_ALPHA',
      merchantId: '720000000000000001',
      rowVersion: 0,
      status: 'PENDING_REVIEW',
    });
    await expect(
      createPlatformMerchant({
        idempotencyKey: '8ce154cf-4f13-4aac-b0de-74922513a14f',
        profile: replacementProfile,
        targetTenantId: '620000000000000001',
      }),
    ).resolves.toMatchObject({ status: 'PENDING_REVIEW' });
    expect(harness.post).toHaveBeenCalledWith('/platform/merchants', {
      idempotencyKey: '8ce154cf-4f13-4aac-b0de-74922513a14f',
      profile: replacementProfile,
      targetTenantId: '620000000000000001',
    });
  });

  it('rejects an unassigned registration country before I/O and in responses', async () => {
    await expect(
      createPlatformMerchant({
        idempotencyKey: '8ce154cf-4f13-4aac-b0de-74922513a14f',
        profile: { ...replacementProfile, registrationCountry: 'ZZ' },
        targetTenantId: '620000000000000001',
      }),
    ).rejects.toThrow('Invalid merchant onboarding profile');
    expect(harness.post).not.toHaveBeenCalled();

    harness.get.mockResolvedValue({
      ...pendingAmendment,
      profile: { ...pendingAmendment.profile, registrationCountry: 'ZZ' },
    });
    await expect(
      getPendingMerchantAmendment('720000000000000001'),
    ).rejects.toThrow('Invalid pending merchant amendment response');
  });

  it('submits and reads an exact reviewed amendment without exposing plaintext', async () => {
    harness.post.mockResolvedValue({
      amendmentId: '750000000000000001',
      createdAt: '2026-08-15T08:00:00Z',
      merchantId: '720000000000000001',
      originMerchantVersion: 7,
      originStatus: 'ACTIVE',
      rowVersion: 0,
      status: 'PENDING_REVIEW',
    });
    await expect(
      submitMerchantAmendment('720000000000000001', {
        expectedMerchantVersion: 7,
        idempotencyKey: '8ce154cf-4f13-4aac-b0de-74922513a14f',
        profile: {
          ...replacementProfile,
          legalIdNo: { mode: 'RETAIN' },
          registrationNumber: { mode: 'RETAIN' },
        },
      }),
    ).resolves.toMatchObject({ amendmentId: '750000000000000001' });

    harness.get.mockResolvedValue(pendingAmendment);
    await expect(
      getPendingMerchantAmendment('720000000000000001'),
    ).resolves.toEqual(pendingAmendment);
    expect(JSON.stringify(pendingAmendment)).not.toContain('SYNTHETIC-ID');
    expect(JSON.stringify(pendingAmendment)).not.toContain('SYNTHETIC-REG');

    harness.get.mockResolvedValue(null);
    await expect(
      getPendingMerchantAmendment('720000000000000001'),
    ).resolves.toBeNull();
  });

  it('validates protected masks by Unicode code points including four astral suffixes', async () => {
    const astralMask = `${'*'.repeat(124)}😀😀😀😀`;
    harness.get.mockResolvedValue({
      ...pendingAmendment,
      profile: {
        ...pendingAmendment.profile,
        legalIdNoMasked: astralMask,
        registrationNumberMasked: astralMask,
      },
    });

    await expect(
      getPendingMerchantAmendment('720000000000000001'),
    ).resolves.toMatchObject({
      profile: {
        legalIdNoMasked: astralMask,
        registrationNumberMasked: astralMask,
      },
    });
  });

  it('reviews an amendment through its exact nested resource path', async () => {
    harness.post.mockResolvedValue({
      amendmentId: '750000000000000001',
      merchantId: '720000000000000001',
      merchantRowVersion: 8,
      merchantStatus: 'ACTIVE',
      rowVersion: 1,
      status: 'APPROVED',
    });
    const command = {
      decision: 'APPROVE' as const,
      expectedVersion: 0,
      idempotencyKey: '8ce154cf-4f13-4aac-b0de-74922513a14f',
      reasonCode: 'PROFILE_AMENDMENT_VERIFIED' as const,
    };
    await expect(
      reviewMerchantAmendment(
        '720000000000000001',
        '750000000000000001',
        command,
      ),
    ).resolves.toMatchObject({ status: 'APPROVED' });
    expect(harness.post).toHaveBeenCalledWith(
      '/platform/merchants/720000000000000001/amendments/750000000000000001/review-decisions',
      command,
    );
  });
});
