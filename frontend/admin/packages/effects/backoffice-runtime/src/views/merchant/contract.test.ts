import { describe, expect, it } from 'vitest';

import {
  actionForStatus,
  buildProfileUpdateRequest,
  buildSubmissionRequest,
  createIdempotencyKeyGeneration,
  reasonOptionsForAction,
} from './contract';

describe('mCH-001 frontend command contract', () => {
  it('exposes the exact state-dependent PLATFORM actions', () => {
    expect(actionForStatus('PENDING_REVIEW')).toEqual(['approve', 'reject']);
    expect(actionForStatus('REVIEW_REJECTED')).toEqual([]);
    expect(actionForStatus('ACTIVE')).toEqual([]);
    expect(actionForStatus('DISABLED')).toEqual([]);
    expect(actionForStatus('TERMINATED')).toEqual([]);
  });

  it('builds the exact trimmed PLATFORM profile update command', () => {
    expect(
      buildProfileUpdateRequest(
        {
          authenticationType: 'ENTERPRISE',
          displayName: ' Example Pay ',
          legalPersonName: ' Ada Lovelace ',
          legalName: ' Example Payments ',
          marketCodes: ['BRA', 'PHL'],
          merchantTypeCode: 'DIRECT',
          remarks: ' Key merchant ',
        },
        7,
        '8ce154cf-4f13-4aac-b0de-74922513a14f',
      ),
    ).toEqual({
      displayName: 'Example Pay',
      authenticationType: 'ENTERPRISE',
      expectedVersion: 7,
      idempotencyKey: '8ce154cf-4f13-4aac-b0de-74922513a14f',
      legalName: 'Example Payments',
      legalPersonName: 'Ada Lovelace',
      marketCodes: ['BRA', 'PHL'],
      merchantTypeCode: 'DIRECT',
      remarks: 'Key merchant',
    });
  });

  it('exposes only command-specific reason codes', () => {
    expect(reasonOptionsForAction('approve')).toEqual(['PROFILE_VERIFIED']);
    expect(reasonOptionsForAction('reject')).toEqual([
      'PROFILE_MISMATCH',
      'REGISTRATION_UNVERIFIED',
      'COMPLIANCE_REJECTED',
    ]);
  });

  it('builds a first submit without tenant, merchant, domain, or reason fields', () => {
    const request = buildSubmissionRequest(
      {
        displayName: ' Example Pay ',
        legalName: ' Example Payments ',
        registrationCountry: 'sg',
        registrationNumber: ' 2026-001234-Z ',
      },
      null,
      '8ce154cf-4f13-4aac-b0de-74922513a14f',
      undefined,
    );

    expect(request).toEqual({
      displayName: 'Example Pay',
      expectedVersion: null,
      idempotencyKey: '8ce154cf-4f13-4aac-b0de-74922513a14f',
      legalName: 'Example Payments',
      registrationCountry: 'sg',
      registrationNumber: '2026-001234-Z',
    });
    expect(request).not.toHaveProperty('tenantId');
    expect(request).not.toHaveProperty('merchantId');
    expect(request).not.toHaveProperty('accountDomain');
    expect(request).not.toHaveProperty('reasonCode');
  });

  it('omits an empty registration number only for rejected resubmission', () => {
    expect(
      buildSubmissionRequest(
        {
          displayName: 'Example Pay',
          legalName: 'Example Payments',
          registrationCountry: 'SG',
          registrationNumber: '',
        },
        4,
        '8ce154cf-4f13-4aac-b0de-74922513a14f',
        'SG',
      ),
    ).toEqual({
      displayName: 'Example Pay',
      expectedVersion: 4,
      idempotencyKey: '8ce154cf-4f13-4aac-b0de-74922513a14f',
      legalName: 'Example Payments',
      registrationCountry: 'SG',
    });
  });

  it('rejects a first submission without a registration number', () => {
    expect(() =>
      buildSubmissionRequest(
        {
          displayName: 'Example Pay',
          legalName: 'Example Payments',
          registrationCountry: 'SG',
          registrationNumber: '',
        },
        null,
        '8ce154cf-4f13-4aac-b0de-74922513a14f',
        undefined,
      ),
    ).toThrow('Registration number is required for first submission');
  });

  it('rejects an omitted registration number when resubmission changes country', () => {
    expect(() =>
      buildSubmissionRequest(
        {
          displayName: 'Example Pay',
          legalName: 'Example Payments',
          registrationCountry: 'US',
          registrationNumber: '',
        },
        4,
        '8ce154cf-4f13-4aac-b0de-74922513a14f',
        'SG',
      ),
    ).toThrow('Registration number is required after changing country');
  });

  it('reuses one idempotency key for an unchanged retry and rotates after edits', () => {
    const keys = ['key-1', 'key-2'];
    const generation = createIdempotencyKeyGeneration(() => {
      const key = keys.shift();
      if (!key) throw new Error('Idempotency key fixture is exhausted');
      return key;
    });

    expect(generation.current()).toBe('key-1');
    expect(generation.current()).toBe('key-1');
    generation.markEdited();
    expect(generation.current()).toBe('key-2');
  });
});
