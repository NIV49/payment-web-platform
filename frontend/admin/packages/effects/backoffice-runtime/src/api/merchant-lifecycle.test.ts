import { beforeEach, describe, expect, it, vi } from 'vitest';

import {
  disableMerchant,
  enableMerchant,
  getMerchantApplication,
  getPlatformMerchant,
  getPlatformMerchants,
  reviewMerchant,
  submitMerchantApplication,
  terminateMerchant,
  updatePlatformMerchantProfile,
} from './merchant-lifecycle';

const harness = vi.hoisted(() => ({
  accountDomain: 'PLATFORM' as 'AGENT' | 'MERCHANT' | 'PLATFORM',
  requestClient: {
    get: vi.fn(),
    post: vi.fn(),
    put: vi.fn(),
  },
}));

const platformSubmittedDetail = {
  authenticationType: 'ENTERPRISE',
  createdAt: '2026-08-15T14:00:00Z',
  displayName: 'Platform Created Merchant',
  lastDecision: null,
  legalName: 'Platform Created Merchant Ltd.',
  legalPersonName: 'Ada Lovelace',
  marketCodes: ['BRA'],
  merchantCode: 'MCH_PLATFORM_CREATED',
  merchantId: '9007199254740993',
  merchantTypeCode: 'PLATFORM',
  registrationCountry: 'BR',
  registrationNumberMasked: '********0123',
  remarks: '',
  reviewedAt: null,
  rowVersion: 0,
  status: 'PENDING_REVIEW',
  statusReasonCode: 'PLATFORM_APPLICATION_SUBMITTED',
  submittedAt: '2026-08-15T14:00:00Z',
  tenantId: '4000',
  updatedAt: '2026-08-15T14:00:00Z',
} as const;

vi.mock('../deployment', () => ({
  getInstalledBackofficeDeployment: () => ({
    accountDomain: harness.accountDomain,
  }),
}));
vi.mock('./request', () => ({ requestClient: harness.requestClient }));

describe('mCH-001 merchant lifecycle API boundary', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    harness.accountDomain = 'PLATFORM';
    harness.requestClient.get.mockResolvedValue({ merchant: null });
    harness.requestClient.post.mockImplementation((path: string) => {
      let status = 'PENDING_REVIEW';
      if (path.endsWith('/review-decisions')) status = 'REVIEW_REJECTED';
      if (path.endsWith('/disable')) status = 'DISABLED';
      if (path.endsWith('/enable')) status = 'ACTIVE';
      if (path.endsWith('/terminate')) status = 'TERMINATED';
      return Promise.resolve({
        merchantCode: 'MCH_EXAMPLE_ALPHA',
        merchantId: path.includes('/platform/merchants/')
          ? '9007199254740993'
          : '1',
        rowVersion: 1,
        status,
      });
    });
    harness.requestClient.put.mockResolvedValue({
      merchantCode: 'MCH_EXAMPLE_ALPHA',
      merchantId: '9007199254740993',
      rowVersion: 4,
      status: 'ACTIVE',
    });
  });

  it('sends the exact PLATFORM profile command and market query', async () => {
    harness.requestClient.get.mockResolvedValueOnce({ items: [], total: 0 });
    await getPlatformMerchants({
      authenticationType: 'ENTERPRISE',
      marketCode: 'BRA',
      merchantTypeCode: 'DIRECT',
      page: 1,
      pageSize: 20,
    });
    await updatePlatformMerchantProfile('9007199254740993', {
      authenticationType: 'ENTERPRISE',
      displayName: 'Example Pay',
      expectedVersion: 3,
      idempotencyKey: '8ce154cf-4f13-4aac-b0de-74922513a14f',
      legalPersonName: 'Ada Lovelace',
      legalName: 'Example Payments Pte. Ltd.',
      marketCodes: ['PHL', 'BRA'],
      merchantTypeCode: 'DIRECT',
      remarks: 'Key merchant',
    });

    expect(harness.requestClient.get).toHaveBeenCalledWith(
      '/platform/merchants',
      {
        params: {
          authenticationType: 'ENTERPRISE',
          marketCode: 'BRA',
          merchantTypeCode: 'DIRECT',
          page: 1,
          pageSize: 20,
        },
      },
    );
    expect(harness.requestClient.put).toHaveBeenCalledWith(
      '/platform/merchants/9007199254740993/profile',
      {
        authenticationType: 'ENTERPRISE',
        displayName: 'Example Pay',
        expectedVersion: 3,
        idempotencyKey: '8ce154cf-4f13-4aac-b0de-74922513a14f',
        legalPersonName: 'Ada Lovelace',
        legalName: 'Example Payments Pte. Ltd.',
        marketCodes: ['PHL', 'BRA'],
        merchantTypeCode: 'DIRECT',
        remarks: 'Key merchant',
      },
    );
  });

  it('counts profile text limits by Unicode code points', async () => {
    await expect(
      updatePlatformMerchantProfile('9007199254740993', {
        authenticationType: 'INDIVIDUAL',
        displayName: '😀'.repeat(128),
        expectedVersion: 3,
        idempotencyKey: '8ce154cf-4f13-4aac-b0de-74922513a14f',
        legalName: '😀'.repeat(200),
        legalPersonName: '😀'.repeat(200),
        marketCodes: ['BRA'],
        merchantTypeCode: 'INDIRECT',
        remarks: '😀'.repeat(300),
      }),
    ).resolves.toMatchObject({ merchantId: '9007199254740993' });

    vi.clearAllMocks();
    await expect(
      updatePlatformMerchantProfile('9007199254740993', {
        authenticationType: 'ENTERPRISE',
        displayName: '😀'.repeat(129),
        expectedVersion: 3,
        idempotencyKey: '8ce154cf-4f13-4aac-b0de-74922513a14f',
        legalName: 'Example Payments',
        legalPersonName: 'Ada Lovelace',
        marketCodes: ['BRA'],
        merchantTypeCode: 'DIRECT',
        remarks: '',
      }),
    ).rejects.toThrow('Invalid merchant profile update request');
    expect(harness.requestClient.put).not.toHaveBeenCalled();
  });

  it('rejects a legal person name above 200 Unicode code points', async () => {
    await expect(
      updatePlatformMerchantProfile('9007199254740993', {
        authenticationType: 'ENTERPRISE',
        displayName: 'Example Pay',
        expectedVersion: 3,
        idempotencyKey: '8ce154cf-4f13-4aac-b0de-74922513a14f',
        legalName: 'Example Payments',
        legalPersonName: '😀'.repeat(201),
        marketCodes: ['BRA'],
        merchantTypeCode: 'DIRECT',
        remarks: '',
      }),
    ).rejects.toThrow('Invalid merchant profile update request');
    expect(harness.requestClient.put).not.toHaveBeenCalled();
  });

  it.each([
    { marketCodes: [], remarks: '' },
    { marketCodes: ['BRA', 'BRA'], remarks: '' },
    { marketCodes: ['USA'], remarks: '' },
    { marketCodes: ['BRA'], remarks: 'x'.repeat(301) },
  ])('rejects an invalid profile update before I/O: %o', async (invalid) => {
    await expect(
      updatePlatformMerchantProfile('9007199254740993', {
        authenticationType: 'ENTERPRISE',
        displayName: 'Example Pay',
        expectedVersion: 3,
        idempotencyKey: '8ce154cf-4f13-4aac-b0de-74922513a14f',
        legalName: 'Example Payments Pte. Ltd.',
        legalPersonName: 'Ada Lovelace',
        marketCodes: invalid.marketCodes,
        merchantTypeCode: 'DIRECT',
        remarks: invalid.remarks,
      }),
    ).rejects.toThrow('Invalid merchant profile update request');
    expect(harness.requestClient.put).not.toHaveBeenCalled();
  });

  it.each([
    { authenticationType: 'UNKNOWN', merchantTypeCode: 'DIRECT' },
    { authenticationType: 'ENTERPRISE', merchantTypeCode: 'UNKNOWN' },
  ])(
    'rejects unknown merchant classifications before I/O: %o',
    async (invalid) => {
      await expect(
        updatePlatformMerchantProfile('9007199254740993', {
          authenticationType: invalid.authenticationType as never,
          displayName: 'Example Pay',
          expectedVersion: 3,
          idempotencyKey: '8ce154cf-4f13-4aac-b0de-74922513a14f',
          legalName: 'Example Payments Pte. Ltd.',
          legalPersonName: 'Ada Lovelace',
          marketCodes: ['BRA'],
          merchantTypeCode: invalid.merchantTypeCode as never,
          remarks: '',
        }),
      ).rejects.toThrow('Invalid merchant profile update request');
      expect(harness.requestClient.put).not.toHaveBeenCalled();
    },
  );

  it('exposes only the two MERCHANT self-service requests in the MERCHANT deployment', async () => {
    harness.accountDomain = 'MERCHANT';

    await getMerchantApplication();
    await submitMerchantApplication({
      displayName: 'Example Pay',
      expectedVersion: null,
      idempotencyKey: '8ce154cf-4f13-4aac-b0de-74922513a14f',
      legalName: 'Example Payments Pte. Ltd.',
      registrationCountry: 'SG',
      registrationNumber: '2026-001234-Z',
    });

    expect(harness.requestClient.get).toHaveBeenCalledWith(
      '/merchant/application',
    );
    expect(harness.requestClient.post).toHaveBeenCalledWith(
      '/merchant/application/submissions',
      {
        displayName: 'Example Pay',
        expectedVersion: null,
        idempotencyKey: '8ce154cf-4f13-4aac-b0de-74922513a14f',
        legalName: 'Example Payments Pte. Ltd.',
        registrationCountry: 'SG',
        registrationNumber: '2026-001234-Z',
      },
    );
  });

  it('keeps parsing a MERCHANT self detail without PLATFORM MCH-003 fields', async () => {
    harness.accountDomain = 'MERCHANT';
    harness.requestClient.get.mockResolvedValueOnce({
      merchant: {
        authenticationType: 'ENTERPRISE',
        createdAt: '2026-08-13T08:00:00Z',
        displayName: 'Example Pay',
        lastDecision: null,
        legalName: 'Example Payments Pte. Ltd.',
        legalPersonName: 'Ada Lovelace',
        marketCodes: ['BRA'],
        merchantCode: 'MCH_EXAMPLE_ALPHA',
        merchantId: '1',
        merchantTypeCode: 'DIRECT',
        registrationCountry: 'SG',
        registrationNumberMasked: '******6789',
        remarks: '',
        reviewedAt: null,
        rowVersion: 3,
        status: 'ACTIVE',
        statusReasonCode: 'PROFILE_VERIFIED',
        submittedAt: '2026-08-13T08:00:00Z',
        tenantId: '2',
        updatedAt: '2026-08-13T08:00:00Z',
      },
    });

    await expect(getMerchantApplication()).resolves.toMatchObject({
      merchant: { merchantId: '1' },
    });
  });

  it('accepts PLATFORM_APPLICATION_SUBMITTED in list, detail, and self responses', async () => {
    const {
      lastDecision: _lastDecision,
      reviewedAt: _reviewedAt,
      ...listItem
    } = platformSubmittedDetail;
    harness.requestClient.get.mockResolvedValueOnce({
      items: [{ ...listItem, reviewPending: true }],
      total: 1,
    });
    await expect(
      getPlatformMerchants({ page: 1, pageSize: 20 }),
    ).resolves.toMatchObject({
      items: [
        {
          merchantId: '9007199254740993',
          statusReasonCode: 'PLATFORM_APPLICATION_SUBMITTED',
        },
      ],
    });

    harness.requestClient.get.mockResolvedValueOnce({
      ...platformSubmittedDetail,
      brandLogoDocument: null,
      brandName: null,
      businessLicenseDocument: null,
      contactEmail: null,
      contactPhone: null,
      industryCode: null,
      legalIdBackDocument: null,
      legalIdFrontDocument: null,
      legalIdHoldingDocument: null,
      legalIdNoMasked: null,
      legalIdTypeCode: null,
      legalIdValidity: null,
      operatingAddress: null,
      registeredAddress: null,
    });
    await expect(
      getPlatformMerchant('9007199254740993'),
    ).resolves.toMatchObject({
      statusReasonCode: 'PLATFORM_APPLICATION_SUBMITTED',
    });

    harness.accountDomain = 'MERCHANT';
    harness.requestClient.get.mockResolvedValueOnce({
      merchant: platformSubmittedDetail,
    });
    await expect(getMerchantApplication()).resolves.toMatchObject({
      merchant: { statusReasonCode: 'PLATFORM_APPLICATION_SUBMITTED' },
    });
  });

  it('preserves the resubmit command shape and never invents reason or tenant selectors', async () => {
    harness.accountDomain = 'MERCHANT';
    const request = {
      displayName: 'Example Pay',
      expectedVersion: 7,
      idempotencyKey: '8ce154cf-4f13-4aac-b0de-74922513a14f',
      legalName: 'Example Payments Pte. Ltd.',
      registrationCountry: 'SG',
    };

    await submitMerchantApplication(request);

    expect(harness.requestClient.post).toHaveBeenCalledWith(
      '/merchant/application/submissions',
      request,
    );
    const submittedBody = harness.requestClient.post.mock.calls[0]?.[1];
    expect(Object.keys(submittedBody ?? {})).toEqual([
      'displayName',
      'expectedVersion',
      'idempotencyKey',
      'legalName',
      'registrationCountry',
    ]);
  });

  it('rejects a lowercase country at the API boundary instead of silently normalizing it', async () => {
    harness.accountDomain = 'MERCHANT';

    await expect(
      submitMerchantApplication({
        displayName: 'Example Pay',
        expectedVersion: null,
        idempotencyKey: '8ce154cf-4f13-4aac-b0de-74922513a14f',
        legalName: 'Example Payments Pte. Ltd.',
        registrationCountry: 'sg',
        registrationNumber: '2026-001234-Z',
      }),
    ).rejects.toThrow('Invalid merchant submission request');
    expect(harness.requestClient.post).not.toHaveBeenCalled();
  });

  it('rejects a first submission without a registration number before I/O', async () => {
    harness.accountDomain = 'MERCHANT';

    await expect(
      submitMerchantApplication({
        displayName: 'Example Pay',
        expectedVersion: null,
        idempotencyKey: '8ce154cf-4f13-4aac-b0de-74922513a14f',
        legalName: 'Example Payments Pte. Ltd.',
        registrationCountry: 'SG',
      }),
    ).rejects.toThrow('Invalid merchant submission request');
    expect(harness.requestClient.post).not.toHaveBeenCalled();
  });

  it('rejects an unassigned uppercase country before I/O', async () => {
    harness.accountDomain = 'MERCHANT';

    await expect(
      submitMerchantApplication({
        displayName: 'Example Pay',
        expectedVersion: null,
        idempotencyKey: '8ce154cf-4f13-4aac-b0de-74922513a14f',
        legalName: 'Example Payments Pte. Ltd.',
        registrationCountry: 'ZZ',
        registrationNumber: '2026-001234-Z',
      }),
    ).rejects.toThrow('Invalid merchant submission request');
    expect(harness.requestClient.post).not.toHaveBeenCalled();
  });

  it('uses exactly the six PLATFORM control-plane requests and keeps Long IDs as strings', async () => {
    harness.requestClient.get
      .mockResolvedValueOnce({ items: [], total: 0 })
      .mockResolvedValueOnce({
        brandLogoDocument: {
          documentId: '740000000000000006',
          height: 512,
          kind: 'BRAND_LOGO',
          mediaType: 'image/png',
          sizeBytes: 65_536,
          width: 512,
        },
        brandName: 'Example',
        businessLicenseDocument: {
          documentId: '740000000000000002',
          height: 1200,
          kind: 'BUSINESS_LICENSE',
          mediaType: 'image/jpeg',
          sizeBytes: 524_288,
          width: 1600,
        },
        contactEmail: 'merchant-contact@example.test',
        contactPhone: '+551100000000',
        createdAt: '2026-08-13T08:00:00Z',
        authenticationType: 'ENTERPRISE',
        displayName: 'Example Pay',
        industryCode: 'FINANCIAL_SERVICES',
        lastDecision: null,
        legalIdBackDocument: {
          documentId: '740000000000000004',
          height: 800,
          kind: 'LEGAL_ID_BACK',
          mediaType: 'image/jpeg',
          sizeBytes: 262_144,
          width: 1200,
        },
        legalIdFrontDocument: {
          documentId: '740000000000000003',
          height: 800,
          kind: 'LEGAL_ID_FRONT',
          mediaType: 'image/jpeg',
          sizeBytes: 262_144,
          width: 1200,
        },
        legalIdHoldingDocument: {
          documentId: '740000000000000005',
          height: 1600,
          kind: 'LEGAL_ID_HOLDING',
          mediaType: 'image/jpeg',
          sizeBytes: 393_216,
          width: 1200,
        },
        legalIdNoMasked: '********0001',
        legalIdTypeCode: 'NATIONAL_ID',
        legalIdValidity: {
          validFrom: '2026-01-01',
          validTo: '2036-01-01',
        },
        legalName: 'Example Payments Pte. Ltd.',
        legalPersonName: 'Ada Lovelace',
        marketCodes: ['BRA'],
        merchantCode: 'MCH_EXAMPLE_ALPHA',
        merchantId: '9007199254740993',
        merchantTypeCode: 'DIRECT',
        operatingAddress: 'Operating address',
        registeredAddress: 'Registered address',
        registrationCountry: 'SG',
        registrationNumberMasked: '******6789',
        remarks: '',
        reviewedAt: null,
        rowVersion: 3,
        status: 'ACTIVE',
        statusReasonCode: 'PROFILE_VERIFIED',
        submittedAt: '2026-08-13T08:00:00Z',
        tenantId: '2',
        updatedAt: '2026-08-13T08:00:00Z',
      });
    await getPlatformMerchants({ page: 1, pageSize: 20, status: 'ACTIVE' });
    await getPlatformMerchant('9007199254740993');
    const command = {
      expectedVersion: 3,
      idempotencyKey: '8ce154cf-4f13-4aac-b0de-74922513a14f',
      reasonCode: 'BUSINESS_CLOSED' as const,
    };
    await reviewMerchant('9007199254740993', {
      ...command,
      decision: 'REJECT',
      reasonCode: 'PROFILE_MISMATCH',
    });
    await disableMerchant('9007199254740993', {
      ...command,
      reasonCode: 'RISK_CONTROL',
    });
    await enableMerchant('9007199254740993', {
      ...command,
      reasonCode: 'RISK_CLEARED',
    });
    await terminateMerchant('9007199254740993', command);

    expect(harness.requestClient.get).toHaveBeenNthCalledWith(
      1,
      '/platform/merchants',
      { params: { page: 1, pageSize: 20, status: 'ACTIVE' } },
    );
    expect(harness.requestClient.get).toHaveBeenNthCalledWith(
      2,
      '/platform/merchants/9007199254740993',
    );
    expect(harness.requestClient.post.mock.calls.map(([path]) => path)).toEqual(
      [
        '/platform/merchants/9007199254740993/review-decisions',
        '/platform/merchants/9007199254740993/disable',
        '/platform/merchants/9007199254740993/enable',
        '/platform/merchants/9007199254740993/terminate',
      ],
    );
  });

  it('fails closed before every cross-deployment or AGENT request', async () => {
    harness.accountDomain = 'AGENT';
    await expect(getMerchantApplication()).rejects.toThrow(
      'Merchant self-service is unavailable in this deployment',
    );
    await expect(
      getPlatformMerchants({ page: 1, pageSize: 20 }),
    ).rejects.toThrow(
      'Platform merchant control plane is unavailable in this deployment',
    );

    harness.accountDomain = 'MERCHANT';
    await expect(getPlatformMerchant('1')).rejects.toThrow(
      'Platform merchant control plane is unavailable in this deployment',
    );

    harness.accountDomain = 'PLATFORM';
    await expect(getMerchantApplication()).rejects.toThrow(
      'Merchant self-service is unavailable in this deployment',
    );
    expect(harness.requestClient.get).not.toHaveBeenCalled();
    expect(harness.requestClient.post).not.toHaveBeenCalled();
  });

  it('rejects invalid IDs, unexpected fields, and command-specific reason codes before I/O', async () => {
    await expect(getPlatformMerchant('9007199254740993x')).rejects.toThrow(
      'Invalid Merchant ID',
    );
    await expect(
      getPlatformMerchants({ page: 1, pageSize: 20, tenantId: '9' } as never),
    ).rejects.toThrow('Invalid merchant list query');
    await expect(
      disableMerchant('1', {
        expectedVersion: 1,
        idempotencyKey: '8ce154cf-4f13-4aac-b0de-74922513a14f',
        reasonCode: 'PROFILE_VERIFIED',
      } as never),
    ).rejects.toThrow('Invalid disable reason code');

    harness.accountDomain = 'MERCHANT';
    await expect(
      submitMerchantApplication({
        displayName: 'Example Pay',
        expectedVersion: null,
        idempotencyKey: '8ce154cf-4f13-4aac-b0de-74922513a14f',
        legalName: 'Example Payments Pte. Ltd.',
        reasonCode: 'APPLICATION_SUBMITTED',
        registrationCountry: 'SG',
        registrationNumber: '2026-001234-Z',
        tenantId: '9',
      } as never),
    ).rejects.toThrow('Invalid merchant submission request');
    expect(harness.requestClient.get).not.toHaveBeenCalled();
    expect(harness.requestClient.post).not.toHaveBeenCalled();
  });

  it('accepts the maximum Java Long ID and rejects overflow before I/O', async () => {
    harness.requestClient.get.mockResolvedValueOnce({
      brandLogoDocument: null,
      brandName: null,
      businessLicenseDocument: null,
      contactEmail: null,
      contactPhone: null,
      createdAt: '2026-08-13T08:00:00Z',
      authenticationType: null,
      displayName: 'Example Pay',
      industryCode: null,
      lastDecision: null,
      legalIdBackDocument: null,
      legalIdFrontDocument: null,
      legalIdHoldingDocument: null,
      legalIdNoMasked: null,
      legalIdTypeCode: null,
      legalIdValidity: null,
      legalName: 'Example Payments Pte. Ltd.',
      legalPersonName: null,
      marketCodes: [],
      merchantCode: 'MCH_EXAMPLE_ALPHA',
      merchantId: '9223372036854775807',
      merchantTypeCode: null,
      operatingAddress: null,
      registeredAddress: null,
      registrationCountry: 'SG',
      registrationNumberMasked: '******6789',
      remarks: '',
      reviewedAt: null,
      rowVersion: 3,
      status: 'ACTIVE',
      statusReasonCode: null,
      submittedAt: '2026-08-13T08:00:00Z',
      tenantId: '2',
      updatedAt: '2026-08-13T08:00:00Z',
    });

    await expect(
      getPlatformMerchant('9223372036854775807'),
    ).resolves.toMatchObject({
      merchantId: '9223372036854775807',
    });
    vi.clearAllMocks();
    await expect(getPlatformMerchant('9223372036854775808')).rejects.toThrow(
      'Invalid Merchant ID',
    );
    expect(harness.requestClient.get).not.toHaveBeenCalled();
  });

  it.each([
    { page: 0, pageSize: 20 },
    { page: 2_147_483_648, pageSize: 20 },
    { page: 1, pageSize: 101 },
    {
      createdFrom: '2026-08-14T00:00:00Z',
      createdTo: '2026-08-13T00:00:00Z',
      page: 1,
      pageSize: 20,
    },
    { createdFrom: 'not-an-instant', page: 1, pageSize: 20 },
  ])(
    'rejects invalid page and UTC time-range query values: %o',
    async (query) => {
      await expect(getPlatformMerchants(query as never)).rejects.toThrow(
        'Invalid merchant list query',
      );
      expect(harness.requestClient.get).not.toHaveBeenCalled();
    },
  );

  it('rejects a merchant code query above the database limit before I/O', async () => {
    await expect(
      getPlatformMerchants({ merchantCode: 'M'.repeat(65) }),
    ).rejects.toThrow('Invalid merchant list query');
    expect(harness.requestClient.get).not.toHaveBeenCalled();
  });

  it('rejects a merchant response code above the database limit', async () => {
    harness.requestClient.get.mockResolvedValueOnce({
      items: [
        {
          authenticationType: null,
          createdAt: '2026-08-13T08:00:00Z',
          displayName: 'Example Pay',
          legalName: 'Example Payments Pte. Ltd.',
          legalPersonName: null,
          marketCodes: [],
          merchantCode: 'M'.repeat(65),
          merchantId: '1',
          merchantTypeCode: null,
          registrationCountry: 'SG',
          registrationNumberMasked: '******6789',
          remarks: '',
          reviewPending: true,
          rowVersion: 0,
          status: 'PENDING_REVIEW',
          statusReasonCode: 'APPLICATION_SUBMITTED',
          submittedAt: '2026-08-13T08:00:00Z',
          tenantId: '2',
          updatedAt: '2026-08-13T08:00:00Z',
        },
      ],
      total: 1,
    });

    await expect(
      getPlatformMerchants({ page: 1, pageSize: 20 }),
    ).rejects.toThrow('Invalid platform merchant list response');
  });

  it('accepts exact string Long IDs and exact response fields', async () => {
    harness.requestClient.get.mockResolvedValueOnce({
      items: [
        {
          createdAt: '2026-08-13T08:00:00Z',
          authenticationType: null,
          displayName: 'Example Pay',
          legalName: 'Example Payments Pte. Ltd.',
          legalPersonName: null,
          marketCodes: [],
          merchantCode: 'MCH_EXAMPLE_ALPHA',
          merchantId: '9007199254740993',
          merchantTypeCode: null,
          registrationCountry: 'SG',
          registrationNumberMasked: '******6789',
          remarks: '',
          reviewPending: true,
          rowVersion: 0,
          status: 'PENDING_REVIEW',
          statusReasonCode: 'APPLICATION_SUBMITTED',
          submittedAt: '2026-08-13T08:00:00Z',
          tenantId: '2',
          updatedAt: '2026-08-13T08:00:00Z',
        },
      ],
      total: 1,
    });

    await expect(
      getPlatformMerchants({ page: 1, pageSize: 20 }),
    ).resolves.toMatchObject({
      items: [{ merchantId: '9007199254740993' }],
      total: 1,
    });
  });

  it('accepts the exact list item model without detail-only decision fields', async () => {
    harness.requestClient.get.mockResolvedValueOnce({
      items: [
        {
          createdAt: '2026-08-13T08:00:00Z',
          authenticationType: 'NON_PROFIT_ORGANIZATIONS',
          displayName: 'Example Pay',
          legalName: 'Example Payments Pte. Ltd.',
          legalPersonName: 'Grace Hopper',
          marketCodes: ['BRA', 'PHL'],
          merchantCode: 'MCH_EXAMPLE_ALPHA',
          merchantId: '1',
          merchantTypeCode: 'COMMISSION',
          registrationCountry: 'SG',
          registrationNumberMasked: '******6789',
          remarks: 'Key merchant',
          reviewPending: true,
          rowVersion: 0,
          status: 'PENDING_REVIEW',
          statusReasonCode: null,
          submittedAt: '2026-08-13T08:00:00Z',
          tenantId: '2',
          updatedAt: '2026-08-13T08:00:00Z',
        },
      ],
      total: 1,
    });

    await expect(
      getPlatformMerchants({ page: 1, pageSize: 20 }),
    ).resolves.toMatchObject({ total: 1 });
  });

  it('rejects an unknown nullable classification value in a response', async () => {
    harness.requestClient.get.mockResolvedValueOnce({
      items: [
        {
          authenticationType: 'UNKNOWN',
          createdAt: '2026-08-13T08:00:00Z',
          displayName: 'Example Pay',
          legalName: 'Example Payments Pte. Ltd.',
          legalPersonName: null,
          marketCodes: [],
          merchantCode: 'MCH_EXAMPLE_ALPHA',
          merchantId: '1',
          merchantTypeCode: 'DIRECT',
          registrationCountry: 'SG',
          registrationNumberMasked: '******6789',
          remarks: '',
          rowVersion: 0,
          status: 'PENDING_REVIEW',
          statusReasonCode: null,
          submittedAt: '2026-08-13T08:00:00Z',
          tenantId: '2',
          updatedAt: '2026-08-13T08:00:00Z',
        },
      ],
      total: 1,
    });

    await expect(
      getPlatformMerchants({ page: 1, pageSize: 20 }),
    ).rejects.toThrow('Invalid platform merchant list response');
  });

  it('rejects a status reason that cannot produce the declared status', async () => {
    harness.requestClient.get.mockResolvedValueOnce({
      items: [
        {
          createdAt: '2026-08-13T08:00:00Z',
          authenticationType: null,
          displayName: 'Example Pay',
          legalName: 'Example Payments Pte. Ltd.',
          legalPersonName: null,
          marketCodes: ['BRA'],
          merchantCode: 'MCH_EXAMPLE_ALPHA',
          merchantId: '1',
          merchantTypeCode: null,
          registrationCountry: 'SG',
          registrationNumberMasked: '******6789',
          remarks: '',
          rowVersion: 1,
          status: 'ACTIVE',
          statusReasonCode: 'APPLICATION_SUBMITTED',
          submittedAt: '2026-08-13T08:00:00Z',
          tenantId: '2',
          updatedAt: '2026-08-13T08:00:00Z',
        },
      ],
      total: 1,
    });

    await expect(
      getPlatformMerchants({ page: 1, pageSize: 20 }),
    ).rejects.toThrow('Invalid platform merchant list response');
  });

  it('rejects non-canonical market ordering in a response', async () => {
    harness.requestClient.get.mockResolvedValueOnce({
      items: [
        {
          createdAt: '2026-08-13T08:00:00Z',
          authenticationType: null,
          displayName: 'Example Pay',
          legalName: 'Example Payments Pte. Ltd.',
          legalPersonName: null,
          marketCodes: ['PHL', 'BRA'],
          merchantCode: 'MCH_EXAMPLE_ALPHA',
          merchantId: '1',
          merchantTypeCode: null,
          registrationCountry: 'SG',
          registrationNumberMasked: '******6789',
          remarks: '',
          rowVersion: 1,
          status: 'ACTIVE',
          statusReasonCode: null,
          submittedAt: '2026-08-13T08:00:00Z',
          tenantId: '2',
          updatedAt: '2026-08-13T08:00:00Z',
        },
      ],
      total: 1,
    });

    await expect(
      getPlatformMerchants({ page: 1, pageSize: 20 }),
    ).rejects.toThrow('Invalid platform merchant list response');
  });

  it.each([
    [
      'numeric Long IDs',
      {
        merchant: {
          merchantId: Number('9007199254740993'),
          status: 'ACTIVE',
          tenantId: '2',
        },
      },
    ],
    [
      'unknown status values',
      {
        merchant: {
          merchantId: '1',
          status: 'DRAFT',
          tenantId: '2',
        },
      },
    ],
    [
      'registration protection metadata',
      {
        merchant: {
          aeadKeyId: 'key-1',
          merchantId: '1',
          registrationCiphertext: 'secret',
          registrationFingerprint: 'fingerprint',
          status: 'ACTIVE',
          tenantId: '2',
        },
      },
    ],
  ])('fails closed on %s in a MERCHANT response', async (_name, response) => {
    harness.accountDomain = 'MERCHANT';
    harness.requestClient.get.mockResolvedValueOnce(response);

    await expect(getMerchantApplication()).rejects.toThrow(
      'Invalid merchant application response',
    );
  });

  it('rejects a PLATFORM list whose item scope or protected fields are malformed', async () => {
    harness.requestClient.get.mockResolvedValueOnce({
      items: [
        {
          merchantId: '1',
          registrationNumber: 'plaintext',
          status: 'PENDING_REVIEW',
          tenantId: '2',
        },
      ],
      total: 1,
    });

    await expect(
      getPlatformMerchants({ page: 1, pageSize: 20 }),
    ).rejects.toThrow('Invalid platform merchant list response');
  });

  it('rejects a plaintext registration number in a MERCHANT self response', async () => {
    harness.accountDomain = 'MERCHANT';
    harness.requestClient.get.mockResolvedValueOnce({
      merchant: {
        createdAt: '2026-08-13T08:00:00Z',
        displayName: 'Example Pay',
        lastDecision: null,
        legalName: 'Example Payments Pte. Ltd.',
        merchantCode: 'MCH_EXAMPLE_ALPHA',
        merchantId: '1',
        registrationCountry: 'SG',
        registrationNumberMasked: '2026-001234-Z',
        reviewedAt: null,
        rowVersion: 0,
        status: 'PENDING_REVIEW',
        submittedAt: '2026-08-13T08:00:00Z',
        tenantId: '2',
        updatedAt: '2026-08-13T08:00:00Z',
      },
    });

    await expect(getMerchantApplication()).rejects.toThrow(
      'Invalid merchant application response',
    );
  });

  it('rejects a plaintext registration number in a PLATFORM list response', async () => {
    harness.requestClient.get.mockResolvedValueOnce({
      items: [
        {
          createdAt: '2026-08-13T08:00:00Z',
          displayName: 'Example Pay',
          legalName: 'Example Payments Pte. Ltd.',
          merchantCode: 'MCH_EXAMPLE_ALPHA',
          merchantId: '1',
          registrationCountry: 'SG',
          registrationNumberMasked: '2026-001234-Z',
          rowVersion: 0,
          status: 'PENDING_REVIEW',
          submittedAt: '2026-08-13T08:00:00Z',
          tenantId: '2',
          updatedAt: '2026-08-13T08:00:00Z',
        },
      ],
      total: 1,
    });

    await expect(
      getPlatformMerchants({ page: 1, pageSize: 20 }),
    ).rejects.toThrow('Invalid platform merchant list response');
  });

  it('rejects a plaintext registration number in a PLATFORM detail response', async () => {
    harness.requestClient.get.mockResolvedValueOnce({
      createdAt: '2026-08-13T08:00:00Z',
      displayName: 'Example Pay',
      lastDecision: null,
      legalName: 'Example Payments Pte. Ltd.',
      merchantCode: 'MCH_EXAMPLE_ALPHA',
      merchantId: '1',
      registrationCountry: 'SG',
      registrationNumberMasked: '2026-001234-Z',
      reviewedAt: null,
      rowVersion: 0,
      status: 'PENDING_REVIEW',
      submittedAt: '2026-08-13T08:00:00Z',
      tenantId: '2',
      updatedAt: '2026-08-13T08:00:00Z',
    });

    await expect(getPlatformMerchant('1')).rejects.toThrow(
      'Invalid platform merchant detail response',
    );
  });

  it.each(['*', '****', '***1234', '***😀123', `${'*'.repeat(124)}1234`])(
    'accepts a contract-shaped Unicode code-point mask: %s',
    async (registrationNumberMasked) => {
      harness.requestClient.get.mockResolvedValueOnce({
        items: [
          {
            createdAt: '2026-08-13T08:00:00Z',
            authenticationType: null,
            displayName: 'Example Pay',
            legalName: 'Example Payments Pte. Ltd.',
            legalPersonName: null,
            marketCodes: [],
            merchantCode: 'MCH_EXAMPLE_ALPHA',
            merchantId: '1',
            merchantTypeCode: null,
            registrationCountry: 'SG',
            registrationNumberMasked,
            reviewPending: true,
            remarks: '',
            rowVersion: 0,
            status: 'PENDING_REVIEW',
            statusReasonCode: null,
            submittedAt: '2026-08-13T08:00:00Z',
            tenantId: '2',
            updatedAt: '2026-08-13T08:00:00Z',
          },
        ],
        total: 1,
      });

      await expect(
        getPlatformMerchants({ page: 1, pageSize: 20 }),
      ).resolves.toMatchObject({ items: [{ registrationNumberMasked }] });
    },
  );

  it.each(['', '1234', '****12345', 'AB**6789', '*'.repeat(129)])(
    'rejects an empty, plaintext-bearing, or oversized registration mask: %s',
    async (registrationNumberMasked) => {
      harness.requestClient.get.mockResolvedValueOnce({
        items: [
          {
            createdAt: '2026-08-13T08:00:00Z',
            displayName: 'Example Pay',
            legalName: 'Example Payments Pte. Ltd.',
            merchantCode: 'MCH_EXAMPLE_ALPHA',
            merchantId: '1',
            registrationCountry: 'SG',
            registrationNumberMasked,
            rowVersion: 0,
            status: 'PENDING_REVIEW',
            submittedAt: '2026-08-13T08:00:00Z',
            tenantId: '2',
            updatedAt: '2026-08-13T08:00:00Z',
          },
        ],
        total: 1,
      });

      await expect(
        getPlatformMerchants({ page: 1, pageSize: 20 }),
      ).rejects.toThrow('Invalid platform merchant list response');
    },
  );

  it('rejects an unassigned registration country in a response', async () => {
    harness.requestClient.get.mockResolvedValueOnce({
      items: [
        {
          createdAt: '2026-08-13T08:00:00Z',
          displayName: 'Example Pay',
          legalName: 'Example Payments Pte. Ltd.',
          merchantCode: 'MCH_EXAMPLE_ALPHA',
          merchantId: '1',
          registrationCountry: 'ZZ',
          registrationNumberMasked: '******6789',
          rowVersion: 0,
          status: 'PENDING_REVIEW',
          submittedAt: '2026-08-13T08:00:00Z',
          tenantId: '2',
          updatedAt: '2026-08-13T08:00:00Z',
        },
      ],
      total: 1,
    });

    await expect(
      getPlatformMerchants({ page: 1, pageSize: 20 }),
    ).rejects.toThrow('Invalid platform merchant list response');
  });

  it('rejects reviewer identity in a MERCHANT response', async () => {
    harness.accountDomain = 'MERCHANT';
    harness.requestClient.get.mockResolvedValueOnce({
      merchant: {
        createdAt: '2026-08-13T08:00:00Z',
        displayName: 'Example Pay',
        lastDecision: {
          decidedAt: '2026-08-13T09:00:00Z',
          decidedByMembershipId: '9',
          decision: 'REJECT',
          reasonCode: 'PROFILE_MISMATCH',
        },
        legalName: 'Example Payments Pte. Ltd.',
        merchantCode: 'MCH_EXAMPLE_ALPHA',
        merchantId: '1',
        registrationCountry: 'SG',
        registrationNumberMasked: '******6789',
        reviewedAt: '2026-08-13T09:00:00Z',
        rowVersion: 1,
        status: 'REVIEW_REJECTED',
        submittedAt: '2026-08-13T08:00:00Z',
        tenantId: '2',
        updatedAt: '2026-08-13T09:00:00Z',
      },
    });

    await expect(getMerchantApplication()).rejects.toThrow(
      'Invalid merchant application response',
    );
  });

  it('rejects a mutation response for the wrong target Merchant or state', async () => {
    harness.requestClient.post.mockResolvedValueOnce({
      merchantCode: 'MCH_OTHER',
      merchantId: '2',
      rowVersion: 4,
      status: 'ACTIVE',
    });

    await expect(
      enableMerchant('1', {
        expectedVersion: 3,
        idempotencyKey: '8ce154cf-4f13-4aac-b0de-74922513a14f',
        reasonCode: 'RISK_CLEARED',
      }),
    ).rejects.toThrow('Invalid merchant mutation response');
  });
});
