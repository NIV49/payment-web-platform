import type { MerchantLifecycleApi } from '../../api/merchant-lifecycle';

type MerchantAction = 'approve' | 'disable' | 'enable' | 'reject' | 'terminate';
type MerchantReviewAction = 'approve' | 'reject';

const ACTIONS_BY_STATUS: Record<
  MerchantLifecycleApi.MerchantStatus,
  MerchantReviewAction[]
> = {
  ACTIVE: [],
  DISABLED: [],
  PENDING_REVIEW: ['approve', 'reject'],
  REVIEW_REJECTED: [],
  TERMINATED: [],
};

const REASONS_BY_ACTION: Record<
  MerchantAction,
  MerchantLifecycleApi.ReasonCode[]
> = {
  approve: ['PROFILE_VERIFIED'],
  disable: ['COMPLIANCE_HOLD', 'RISK_CONTROL'],
  enable: ['COMPLIANCE_CLEARED', 'RISK_CLEARED'],
  reject: [
    'PROFILE_MISMATCH',
    'REGISTRATION_UNVERIFIED',
    'COMPLIANCE_REJECTED',
  ],
  terminate: ['BUSINESS_CLOSED', 'COMPLIANCE_TERMINATION'],
};

interface SubmissionFormValues {
  displayName: string;
  legalName: string;
  registrationCountry: string;
  registrationNumber?: string;
}

interface ProfileUpdateFormValues {
  authenticationType: MerchantLifecycleApi.AuthenticationType;
  displayName: string;
  legalName: string;
  legalPersonName: string;
  marketCodes: string[];
  merchantTypeCode: MerchantLifecycleApi.MerchantTypeCode;
  remarks: string;
}

function actionForStatus(status: MerchantLifecycleApi.MerchantStatus) {
  return [...ACTIONS_BY_STATUS[status]];
}

function reasonOptionsForAction(action: MerchantAction) {
  return [...REASONS_BY_ACTION[action]];
}

function buildSubmissionRequest(
  values: SubmissionFormValues,
  expectedVersion: null | number,
  idempotencyKey: string,
  originalCountry: string | undefined,
): MerchantLifecycleApi.SubmissionRequest {
  const registrationNumber = values.registrationNumber?.trim();
  const registrationCountry = values.registrationCountry.trim();
  if (expectedVersion === null && !registrationNumber) {
    throw new Error('Registration number is required for first submission');
  }
  if (
    expectedVersion !== null &&
    registrationCountry !== originalCountry &&
    !registrationNumber
  ) {
    throw new Error('Registration number is required after changing country');
  }
  return {
    displayName: values.displayName.trim(),
    expectedVersion,
    idempotencyKey,
    legalName: values.legalName.trim(),
    registrationCountry,
    ...(registrationNumber ? { registrationNumber } : {}),
  };
}

function buildProfileUpdateRequest(
  values: ProfileUpdateFormValues,
  expectedVersion: number,
  idempotencyKey: string,
): MerchantLifecycleApi.ProfileUpdateRequest {
  return {
    authenticationType: values.authenticationType,
    displayName: values.displayName.trim(),
    expectedVersion,
    idempotencyKey,
    legalName: values.legalName.trim(),
    legalPersonName: values.legalPersonName.trim(),
    marketCodes: [...values.marketCodes],
    merchantTypeCode: values.merchantTypeCode,
    remarks: values.remarks.trim(),
  };
}

function createIdempotencyKeyGeneration(factory: () => string) {
  let currentKey: string | undefined;
  return {
    current() {
      currentKey ??= factory();
      return currentKey;
    },
    markEdited() {
      currentKey = undefined;
    },
    reset() {
      currentKey = undefined;
    },
  };
}

export {
  actionForStatus,
  buildProfileUpdateRequest,
  buildSubmissionRequest,
  createIdempotencyKeyGeneration,
  reasonOptionsForAction,
};
export type {
  MerchantAction,
  MerchantReviewAction,
  ProfileUpdateFormValues,
  SubmissionFormValues,
};
