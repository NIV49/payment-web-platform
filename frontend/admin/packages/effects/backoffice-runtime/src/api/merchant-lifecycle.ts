import { getInstalledBackofficeDeployment } from '../deployment';
import { isAssignedIsoCountryCode } from './merchant-country';
import { isMaskedMerchantProtectedValue } from './merchant-masked-value';
import { requestClient } from './request';

export const MERCHANT_TYPE_CODES = [
  'DIRECT',
  'INDIRECT',
  'COMMISSION',
  'SALES',
  'PLATFORM',
] as const;
export const MERCHANT_AUTHENTICATION_TYPES = [
  'ENTERPRISE',
  'NON_PROFIT_ORGANIZATIONS',
  'CLIQUE',
  'INDIVIDUAL',
  'INDIVIDUAL_HOUSEHOLD',
] as const;

export namespace MerchantLifecycleApi {
  export type MerchantStatus =
    | 'ACTIVE'
    | 'DISABLED'
    | 'PENDING_REVIEW'
    | 'REVIEW_REJECTED'
    | 'TERMINATED';

  export type ReviewDecision = 'APPROVE' | 'REJECT';
  export type MerchantTypeCode = (typeof MERCHANT_TYPE_CODES)[number];
  export type AuthenticationType =
    (typeof MERCHANT_AUTHENTICATION_TYPES)[number];

  export type ReasonCode =
    | 'BUSINESS_CLOSED'
    | 'COMPLIANCE_CLEARED'
    | 'COMPLIANCE_HOLD'
    | 'COMPLIANCE_REJECTED'
    | 'COMPLIANCE_TERMINATION'
    | 'PROFILE_MISMATCH'
    | 'PROFILE_VERIFIED'
    | 'REGISTRATION_UNVERIFIED'
    | 'RISK_CLEARED'
    | 'RISK_CONTROL';

  export type StatusReasonCode =
    | 'APPLICATION_RESUBMITTED'
    | 'APPLICATION_SUBMITTED'
    | 'PLATFORM_APPLICATION_SUBMITTED'
    | ReasonCode;

  export interface LastDecision {
    decidedAt: string;
    decidedByMembershipId?: string;
    decision: ReviewDecision;
    reasonCode: ReasonCode;
  }

  export interface MerchantDetail {
    authenticationType: AuthenticationType | null;
    createdAt: string;
    displayName: string;
    lastDecision: LastDecision | null;
    legalName: string;
    legalPersonName: null | string;
    marketCodes: string[];
    merchantCode: string;
    merchantId: string;
    merchantTypeCode: MerchantTypeCode | null;
    registrationCountry: string;
    registrationNumberMasked: string;
    remarks: string;
    reviewedAt: null | string;
    rowVersion: number;
    status: MerchantStatus;
    statusReasonCode: null | StatusReasonCode;
    submittedAt: string;
    tenantId: string;
    updatedAt: string;
  }

  export type MerchantDocumentKind =
    | 'BRAND_LOGO'
    | 'BUSINESS_LICENSE'
    | 'LEGAL_ID_BACK'
    | 'LEGAL_ID_FRONT'
    | 'LEGAL_ID_HOLDING';

  export interface MerchantDocumentMetadata {
    documentId: string;
    height: number;
    kind: MerchantDocumentKind;
    mediaType: 'image/jpeg' | 'image/png';
    sizeBytes: number;
    width: number;
  }

  export interface PlatformMerchantDetail extends MerchantDetail {
    brandLogoDocument: MerchantDocumentMetadata | null;
    brandName: null | string;
    businessLicenseDocument: MerchantDocumentMetadata | null;
    contactEmail: null | string;
    contactPhone: null | string;
    industryCode: null | string;
    legalIdBackDocument: MerchantDocumentMetadata | null;
    legalIdFrontDocument: MerchantDocumentMetadata | null;
    legalIdHoldingDocument: MerchantDocumentMetadata | null;
    legalIdNoMasked: null | string;
    legalIdTypeCode: null | string;
    legalIdValidity: null | { validFrom: string; validTo: string };
    operatingAddress: null | string;
    registeredAddress: null | string;
  }

  export type MerchantListItem = Omit<
    MerchantDetail,
    'lastDecision' | 'reviewedAt'
  > & { reviewPending: boolean };

  export interface MerchantMutationResult {
    merchantCode: string;
    merchantId: string;
    rowVersion: number;
    status: MerchantStatus;
  }

  export interface MerchantApplicationResponse {
    merchant: MerchantDetail | null;
  }

  export interface MerchantListQuery {
    authenticationType?: AuthenticationType;
    createdFrom?: string;
    createdTo?: string;
    marketCode?: string;
    merchantCode?: string;
    merchantTypeCode?: MerchantTypeCode;
    name?: string;
    page?: number;
    pageSize?: number;
    registrationCountry?: string;
    status?: MerchantStatus;
  }

  export interface ProfileUpdateRequest {
    authenticationType: AuthenticationType;
    displayName: string;
    expectedVersion: number;
    idempotencyKey: string;
    legalName: string;
    legalPersonName: string;
    marketCodes: string[];
    merchantTypeCode: MerchantTypeCode;
    remarks: string;
  }

  export interface PageResult<T> {
    items: T[];
    total: number;
  }

  export interface SubmissionRequest {
    displayName: string;
    expectedVersion: null | number;
    idempotencyKey: string;
    legalName: string;
    registrationCountry: string;
    registrationNumber?: string;
  }

  export interface LifecycleCommand {
    expectedVersion: number;
    idempotencyKey: string;
    reasonCode: ReasonCode;
  }

  export interface ReviewCommand extends LifecycleCommand {
    decision: ReviewDecision;
  }
}

const UUID_PATTERN =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const POSITIVE_LONG_PATTERN = /^[1-9][0-9]{0,18}$/;
const MAX_POSITIVE_LONG = '9223372036854775807';
const MERCHANT_STATES = new Set<MerchantLifecycleApi.MerchantStatus>([
  'ACTIVE',
  'DISABLED',
  'PENDING_REVIEW',
  'REVIEW_REJECTED',
  'TERMINATED',
]);
const MARKET_CODE_ORDER = ['BRA', 'PHL'] as const;
const MARKET_CODES = new Set<string>(MARKET_CODE_ORDER);
const MERCHANT_TYPE_CODE_SET = new Set<string>(MERCHANT_TYPE_CODES);
const MERCHANT_AUTHENTICATION_TYPE_SET = new Set<string>(
  MERCHANT_AUTHENTICATION_TYPES,
);
const INDUSTRY_CODE_SET = new Set([
  'ECOMMERCE',
  'EDUCATION',
  'FINANCIAL_SERVICES',
  'OTHER',
  'RETAIL',
  'TRAVEL',
]);
const LEGAL_ID_TYPE_CODE_SET = new Set([
  'DRIVER_LICENSE',
  'NATIONAL_ID',
  'PASSPORT',
]);
const LIST_KEYS = new Set([
  'authenticationType',
  'createdFrom',
  'createdTo',
  'marketCode',
  'merchantCode',
  'merchantTypeCode',
  'name',
  'page',
  'pageSize',
  'registrationCountry',
  'status',
]);
const SUBMISSION_KEYS = new Set([
  'displayName',
  'expectedVersion',
  'idempotencyKey',
  'legalName',
  'registrationCountry',
  'registrationNumber',
]);
const DETAIL_KEYS = new Set([
  'authenticationType',
  'createdAt',
  'displayName',
  'lastDecision',
  'legalName',
  'legalPersonName',
  'marketCodes',
  'merchantCode',
  'merchantId',
  'merchantTypeCode',
  'registrationCountry',
  'registrationNumberMasked',
  'remarks',
  'reviewedAt',
  'rowVersion',
  'status',
  'statusReasonCode',
  'submittedAt',
  'tenantId',
  'updatedAt',
]);
const PLATFORM_DETAIL_KEYS = new Set([
  ...DETAIL_KEYS,
  'brandLogoDocument',
  'brandName',
  'businessLicenseDocument',
  'contactEmail',
  'contactPhone',
  'industryCode',
  'legalIdBackDocument',
  'legalIdFrontDocument',
  'legalIdHoldingDocument',
  'legalIdNoMasked',
  'legalIdTypeCode',
  'legalIdValidity',
  'operatingAddress',
  'registeredAddress',
]);
const LIST_ITEM_KEYS = new Set([
  ...[...DETAIL_KEYS].filter(
    (key) => key !== 'lastDecision' && key !== 'reviewedAt',
  ),
  'reviewPending',
]);
const LAST_DECISION_KEYS = new Set([
  'decidedAt',
  'decidedByMembershipId',
  'decision',
  'reasonCode',
]);
const REASON_CODES = {
  approve: new Set<MerchantLifecycleApi.ReasonCode>(['PROFILE_VERIFIED']),
  disable: new Set<MerchantLifecycleApi.ReasonCode>([
    'COMPLIANCE_HOLD',
    'RISK_CONTROL',
  ]),
  enable: new Set<MerchantLifecycleApi.ReasonCode>([
    'COMPLIANCE_CLEARED',
    'RISK_CLEARED',
  ]),
  reject: new Set<MerchantLifecycleApi.ReasonCode>([
    'COMPLIANCE_REJECTED',
    'PROFILE_MISMATCH',
    'REGISTRATION_UNVERIFIED',
  ]),
  terminate: new Set<MerchantLifecycleApi.ReasonCode>([
    'BUSINESS_CLOSED',
    'COMPLIANCE_TERMINATION',
  ]),
};
const ALL_REASON_CODES = new Set([
  'APPLICATION_RESUBMITTED',
  'APPLICATION_SUBMITTED',
  'PLATFORM_APPLICATION_SUBMITTED',
  ...Object.values(REASON_CODES).flatMap((codes) => [...codes]),
]);
const STATUS_REASON_CODES: Record<
  MerchantLifecycleApi.MerchantStatus,
  Set<MerchantLifecycleApi.StatusReasonCode>
> = {
  ACTIVE: new Set(['COMPLIANCE_CLEARED', 'PROFILE_VERIFIED', 'RISK_CLEARED']),
  DISABLED: new Set(['COMPLIANCE_HOLD', 'RISK_CONTROL']),
  PENDING_REVIEW: new Set([
    'APPLICATION_RESUBMITTED',
    'APPLICATION_SUBMITTED',
    'PLATFORM_APPLICATION_SUBMITTED',
  ]),
  REVIEW_REJECTED: new Set([
    'COMPLIANCE_REJECTED',
    'PROFILE_MISMATCH',
    'REGISTRATION_UNVERIFIED',
  ]),
  TERMINATED: new Set(['BUSINESS_CLOSED', 'COMPLIANCE_TERMINATION']),
};
const PROFILE_UPDATE_KEYS = new Set([
  'authenticationType',
  'displayName',
  'expectedVersion',
  'idempotencyKey',
  'legalName',
  'legalPersonName',
  'marketCodes',
  'merchantTypeCode',
  'remarks',
]);

function assertDeployment(expected: 'MERCHANT' | 'PLATFORM') {
  if (getInstalledBackofficeDeployment()?.accountDomain !== expected) {
    throw new Error(
      expected === 'MERCHANT'
        ? 'Merchant self-service is unavailable in this deployment'
        : 'Platform merchant control plane is unavailable in this deployment',
    );
  }
}

function hasOnlyKeys(value: object, allowed: Set<string>) {
  return Object.keys(value).every((key) => allowed.has(key));
}

function hasExactKeys(value: object, required: Set<string>) {
  const keys = Object.keys(value);
  return (
    keys.length === required.size && keys.every((key) => required.has(key))
  );
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function isUtcInstant(value: unknown): value is string {
  return (
    typeof value === 'string' &&
    /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?Z$/.test(value) &&
    Number.isFinite(Date.parse(value))
  );
}

const isIsoCountry = isAssignedIsoCountryCode;

function isPositiveLongString(value: unknown): value is string {
  return (
    typeof value === 'string' &&
    POSITIVE_LONG_PATTERN.test(value) &&
    (value.length < MAX_POSITIVE_LONG.length || value <= MAX_POSITIVE_LONG)
  );
}

function isBoundedTrimmedString(
  value: unknown,
  minimum: number,
  maximum: number,
): value is string {
  if (typeof value !== 'string' || value !== value.trim()) return false;
  const length = [...value].length;
  return length >= minimum && length <= maximum;
}

function isDate(value: unknown): value is string {
  if (typeof value !== 'string' || !/^\d{4}-\d{2}-\d{2}$/.test(value)) {
    return false;
  }
  const date = new Date(`${value}T00:00:00Z`);
  return !Number.isNaN(date.getTime()) && date.toISOString().startsWith(value);
}

function isDocumentMetadata(
  value: unknown,
  kind: MerchantLifecycleApi.MerchantDocumentKind,
): value is MerchantLifecycleApi.MerchantDocumentMetadata {
  return (
    isRecord(value) &&
    hasExactKeys(
      value,
      new Set([
        'documentId',
        'height',
        'kind',
        'mediaType',
        'sizeBytes',
        'width',
      ]),
    ) &&
    isPositiveLongString(value.documentId) &&
    value.kind === kind &&
    (value.mediaType === 'image/jpeg' || value.mediaType === 'image/png') &&
    Number.isInteger(value.width) &&
    (value.width as number) >= 1 &&
    (value.width as number) <= 4096 &&
    Number.isInteger(value.height) &&
    (value.height as number) >= 1 &&
    (value.height as number) <= 4096 &&
    Number.isInteger(value.sizeBytes) &&
    (value.sizeBytes as number) >= 1 &&
    (value.sizeBytes as number) <= 2 * 1024 * 1024
  );
}

function isMarketCodes(
  value: unknown,
  minimum: 0 | 1,
  requireCanonicalOrder = false,
): value is string[] {
  return (
    Array.isArray(value) &&
    value.length >= minimum &&
    value.length <= MARKET_CODES.size &&
    new Set(value).size === value.length &&
    value.every((code) => typeof code === 'string' && MARKET_CODES.has(code)) &&
    (!requireCanonicalOrder ||
      value.every(
        (code, index) =>
          index === 0 ||
          MARKET_CODE_ORDER.indexOf(value[index - 1] as 'BRA' | 'PHL') <
            MARKET_CODE_ORDER.indexOf(code as 'BRA' | 'PHL'),
      ))
  );
}

function isLastDecision(
  value: unknown,
  allowReviewerIdentity: boolean,
): value is MerchantLifecycleApi.LastDecision {
  if (!isRecord(value) || !hasOnlyKeys(value, LAST_DECISION_KEYS)) return false;
  const expectedKeyCount = value.decidedByMembershipId === undefined ? 3 : 4;
  const reasonMatchesDecision =
    value.decision === 'APPROVE'
      ? REASON_CODES.approve.has(
          value.reasonCode as MerchantLifecycleApi.ReasonCode,
        )
      : value.decision === 'REJECT' &&
        REASON_CODES.reject.has(
          value.reasonCode as MerchantLifecycleApi.ReasonCode,
        );
  return (
    Object.keys(value).length === expectedKeyCount &&
    (value.decision === 'APPROVE' || value.decision === 'REJECT') &&
    reasonMatchesDecision &&
    isUtcInstant(value.decidedAt) &&
    (value.decidedByMembershipId === undefined ||
      (allowReviewerIdentity &&
        isPositiveLongString(value.decidedByMembershipId)))
  );
}

function hasSharedMerchantFields(value: Record<string, unknown>) {
  const status = value.status as MerchantLifecycleApi.MerchantStatus;
  const statusReasonCode =
    value.statusReasonCode as MerchantLifecycleApi.StatusReasonCode;
  return (
    isPositiveLongString(value.merchantId) &&
    isPositiveLongString(value.tenantId) &&
    isBoundedTrimmedString(value.merchantCode, 1, 64) &&
    isBoundedTrimmedString(value.legalName, 1, 200) &&
    (value.legalPersonName === null ||
      isBoundedTrimmedString(value.legalPersonName, 1, 200)) &&
    (value.merchantTypeCode === null ||
      MERCHANT_TYPE_CODE_SET.has(value.merchantTypeCode as string)) &&
    (value.authenticationType === null ||
      MERCHANT_AUTHENTICATION_TYPE_SET.has(
        value.authenticationType as string,
      )) &&
    isBoundedTrimmedString(value.displayName, 1, 128) &&
    isMarketCodes(value.marketCodes, 0, true) &&
    isIsoCountry(value.registrationCountry) &&
    isMaskedMerchantProtectedValue(value.registrationNumberMasked) &&
    isBoundedTrimmedString(value.remarks, 0, 300) &&
    MERCHANT_STATES.has(status) &&
    (value.statusReasonCode === null ||
      (ALL_REASON_CODES.has(statusReasonCode) &&
        STATUS_REASON_CODES[status]?.has(statusReasonCode))) &&
    assertVersion(value.rowVersion) &&
    isUtcInstant(value.submittedAt) &&
    isUtcInstant(value.createdAt) &&
    isUtcInstant(value.updatedAt)
  );
}

function isMerchantDetail(
  value: unknown,
  allowReviewerIdentity: boolean,
): value is MerchantLifecycleApi.MerchantDetail {
  return (
    isRecord(value) &&
    hasExactKeys(value, DETAIL_KEYS) &&
    hasSharedMerchantFields(value) &&
    (value.reviewedAt === null || isUtcInstant(value.reviewedAt)) &&
    (value.lastDecision === null ||
      isLastDecision(value.lastDecision, allowReviewerIdentity))
  );
}

function isPlatformMerchantDetail(
  value: unknown,
): value is MerchantLifecycleApi.PlatformMerchantDetail {
  return (
    isRecord(value) &&
    hasExactKeys(value, PLATFORM_DETAIL_KEYS) &&
    hasSharedMerchantFields(value) &&
    (value.reviewedAt === null || isUtcInstant(value.reviewedAt)) &&
    (value.lastDecision === null || isLastDecision(value.lastDecision, true)) &&
    (value.brandName === null ||
      isBoundedTrimmedString(value.brandName, 1, 128)) &&
    (value.industryCode === null ||
      INDUSTRY_CODE_SET.has(value.industryCode as string)) &&
    (value.brandLogoDocument === null ||
      isDocumentMetadata(value.brandLogoDocument, 'BRAND_LOGO')) &&
    (value.registeredAddress === null ||
      isBoundedTrimmedString(value.registeredAddress, 1, 300)) &&
    (value.operatingAddress === null ||
      isBoundedTrimmedString(value.operatingAddress, 1, 300)) &&
    (value.businessLicenseDocument === null ||
      isDocumentMetadata(value.businessLicenseDocument, 'BUSINESS_LICENSE')) &&
    (value.contactEmail === null ||
      (isBoundedTrimmedString(value.contactEmail, 3, 254) &&
        /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(value.contactEmail))) &&
    (value.contactPhone === null ||
      (typeof value.contactPhone === 'string' &&
        /^\+[1-9][0-9]{1,14}$/.test(value.contactPhone))) &&
    (value.legalIdTypeCode === null ||
      LEGAL_ID_TYPE_CODE_SET.has(value.legalIdTypeCode as string)) &&
    (value.legalIdNoMasked === null ||
      isMaskedMerchantProtectedValue(value.legalIdNoMasked)) &&
    (value.legalIdValidity === null ||
      (isRecord(value.legalIdValidity) &&
        hasExactKeys(
          value.legalIdValidity,
          new Set(['validFrom', 'validTo']),
        ) &&
        isDate(value.legalIdValidity.validFrom) &&
        isDate(value.legalIdValidity.validTo) &&
        value.legalIdValidity.validFrom <= value.legalIdValidity.validTo)) &&
    (value.legalIdFrontDocument === null ||
      isDocumentMetadata(value.legalIdFrontDocument, 'LEGAL_ID_FRONT')) &&
    (value.legalIdBackDocument === null ||
      isDocumentMetadata(value.legalIdBackDocument, 'LEGAL_ID_BACK')) &&
    (value.legalIdHoldingDocument === null ||
      isDocumentMetadata(value.legalIdHoldingDocument, 'LEGAL_ID_HOLDING'))
  );
}

function isMerchantListItem(
  value: unknown,
): value is MerchantLifecycleApi.MerchantListItem {
  return (
    isRecord(value) &&
    hasExactKeys(value, LIST_ITEM_KEYS) &&
    hasSharedMerchantFields(value) &&
    typeof value.reviewPending === 'boolean'
  );
}

function parseMutationResult(
  value: unknown,
  expectedStatus: MerchantLifecycleApi.MerchantStatus,
  expectedMerchantId?: string,
) {
  if (
    !isRecord(value) ||
    !hasExactKeys(
      value,
      new Set(['merchantCode', 'merchantId', 'rowVersion', 'status']),
    ) ||
    !isPositiveLongString(value.merchantId) ||
    !isBoundedTrimmedString(value.merchantCode, 1, 64) ||
    !assertVersion(value.rowVersion) ||
    value.status !== expectedStatus ||
    (expectedMerchantId !== undefined &&
      value.merchantId !== expectedMerchantId)
  ) {
    throw new Error('Invalid merchant mutation response');
  }
  return value as unknown as MerchantLifecycleApi.MerchantMutationResult;
}

function assertMerchantId(merchantId: string) {
  if (!isPositiveLongString(merchantId)) {
    throw new Error('Invalid Merchant ID');
  }
}

export function isMerchantStateConflict(error: unknown): boolean {
  const body = (
    error as { response?: { data?: { code?: unknown; error?: unknown } } }
  )?.response?.data;
  return body?.code === 40_910 && body.error === 'MERCHANT_STATE_CONFLICT';
}

function assertVersion(value: unknown): value is number {
  return Number.isSafeInteger(value) && (value as number) >= 0;
}

function assertIdempotencyKey(value: unknown): value is string {
  return typeof value === 'string' && UUID_PATTERN.test(value);
}

function assertLifecycleCommand(
  command: MerchantLifecycleApi.LifecycleCommand,
  action: keyof typeof REASON_CODES,
) {
  if (
    !hasOnlyKeys(
      command,
      new Set(['expectedVersion', 'idempotencyKey', 'reasonCode']),
    ) ||
    !assertVersion(command.expectedVersion) ||
    !assertIdempotencyKey(command.idempotencyKey) ||
    !REASON_CODES[action].has(command.reasonCode)
  ) {
    throw new Error(`Invalid ${action} reason code`);
  }
}

export async function getMerchantApplication() {
  assertDeployment('MERCHANT');
  const response = await requestClient.get<unknown>('/merchant/application');
  if (
    !isRecord(response) ||
    !hasExactKeys(response, new Set(['merchant'])) ||
    (response.merchant !== null && !isMerchantDetail(response.merchant, false))
  ) {
    throw new Error('Invalid merchant application response');
  }
  return response as unknown as MerchantLifecycleApi.MerchantApplicationResponse;
}

export async function submitMerchantApplication(
  request: MerchantLifecycleApi.SubmissionRequest,
) {
  assertDeployment('MERCHANT');
  if (
    !hasOnlyKeys(request, SUBMISSION_KEYS) ||
    (request.expectedVersion !== null &&
      !assertVersion(request.expectedVersion)) ||
    !assertIdempotencyKey(request.idempotencyKey) ||
    !isBoundedTrimmedString(request.legalName, 1, 200) ||
    !isBoundedTrimmedString(request.displayName, 1, 128) ||
    !isIsoCountry(request.registrationCountry) ||
    (request.expectedVersion === null &&
      (typeof request.registrationNumber !== 'string' ||
        !isBoundedTrimmedString(request.registrationNumber, 1, 128))) ||
    (request.registrationNumber !== undefined &&
      !isBoundedTrimmedString(request.registrationNumber, 1, 128))
  ) {
    throw new Error('Invalid merchant submission request');
  }
  const response = await requestClient.post<unknown>(
    '/merchant/application/submissions',
    request,
  );
  return parseMutationResult(response, 'PENDING_REVIEW');
}

export async function getPlatformMerchants(
  query: MerchantLifecycleApi.MerchantListQuery,
) {
  assertDeployment('PLATFORM');
  if (
    !hasOnlyKeys(query, LIST_KEYS) ||
    (query.page !== undefined &&
      (!Number.isInteger(query.page) ||
        query.page < 1 ||
        query.page > 2_147_483_647)) ||
    (query.pageSize !== undefined &&
      (!Number.isInteger(query.pageSize) ||
        query.pageSize < 1 ||
        query.pageSize > 100)) ||
    (query.status !== undefined && !MERCHANT_STATES.has(query.status)) ||
    (query.merchantTypeCode !== undefined &&
      !MERCHANT_TYPE_CODE_SET.has(query.merchantTypeCode)) ||
    (query.authenticationType !== undefined &&
      !MERCHANT_AUTHENTICATION_TYPE_SET.has(query.authenticationType)) ||
    (query.merchantCode !== undefined &&
      !isBoundedTrimmedString(query.merchantCode, 1, 64)) ||
    (query.name !== undefined && !isBoundedTrimmedString(query.name, 1, 200)) ||
    (query.marketCode !== undefined && !MARKET_CODES.has(query.marketCode)) ||
    (query.registrationCountry !== undefined &&
      !isIsoCountry(query.registrationCountry)) ||
    (query.createdFrom !== undefined && !isUtcInstant(query.createdFrom)) ||
    (query.createdTo !== undefined && !isUtcInstant(query.createdTo)) ||
    (query.createdFrom !== undefined &&
      query.createdTo !== undefined &&
      Date.parse(query.createdFrom) >= Date.parse(query.createdTo))
  ) {
    throw new Error('Invalid merchant list query');
  }
  const response = await requestClient.get<unknown>('/platform/merchants', {
    params: query,
  });
  if (
    !isRecord(response) ||
    !hasExactKeys(response, new Set(['items', 'total'])) ||
    !Array.isArray(response.items) ||
    !response.items.every(isMerchantListItem) ||
    !Number.isSafeInteger(response.total) ||
    (response.total as number) < 0
  ) {
    throw new Error('Invalid platform merchant list response');
  }
  return response as unknown as MerchantLifecycleApi.PageResult<MerchantLifecycleApi.MerchantListItem>;
}

export async function getPlatformMerchant(merchantId: string) {
  assertDeployment('PLATFORM');
  assertMerchantId(merchantId);
  const response = await requestClient.get<unknown>(
    `/platform/merchants/${merchantId}`,
  );
  if (
    !isPlatformMerchantDetail(response) ||
    response.merchantId !== merchantId
  ) {
    throw new Error('Invalid platform merchant detail response');
  }
  return response;
}

export async function updatePlatformMerchantProfile(
  merchantId: string,
  request: MerchantLifecycleApi.ProfileUpdateRequest,
) {
  assertDeployment('PLATFORM');
  assertMerchantId(merchantId);
  if (
    !hasExactKeys(request, PROFILE_UPDATE_KEYS) ||
    !assertVersion(request.expectedVersion) ||
    !assertIdempotencyKey(request.idempotencyKey) ||
    !isBoundedTrimmedString(request.legalName, 1, 200) ||
    !isBoundedTrimmedString(request.legalPersonName, 1, 200) ||
    !MERCHANT_TYPE_CODE_SET.has(request.merchantTypeCode) ||
    !MERCHANT_AUTHENTICATION_TYPE_SET.has(request.authenticationType) ||
    !isBoundedTrimmedString(request.displayName, 1, 128) ||
    !isMarketCodes(request.marketCodes, 1) ||
    !isBoundedTrimmedString(request.remarks, 0, 300)
  ) {
    throw new Error('Invalid merchant profile update request');
  }
  const response = await requestClient.put<unknown>(
    `/platform/merchants/${merchantId}/profile`,
    request,
  );
  if (
    !isRecord(response) ||
    (response.status !== 'ACTIVE' && response.status !== 'DISABLED')
  ) {
    throw new Error('Invalid merchant mutation response');
  }
  return parseMutationResult(
    response,
    response.status as 'ACTIVE' | 'DISABLED',
    merchantId,
  );
}

export async function reviewMerchant(
  merchantId: string,
  command: MerchantLifecycleApi.ReviewCommand,
) {
  assertDeployment('PLATFORM');
  assertMerchantId(merchantId);
  const action = command.decision === 'APPROVE' ? 'approve' : 'reject';
  const { decision, ...lifecycleCommand } = command;
  if (
    (decision !== 'APPROVE' && decision !== 'REJECT') ||
    !hasOnlyKeys(
      command,
      new Set(['decision', 'expectedVersion', 'idempotencyKey', 'reasonCode']),
    )
  ) {
    throw new Error('Invalid review decision');
  }
  assertLifecycleCommand(lifecycleCommand, action);
  const response = await requestClient.post<unknown>(
    `/platform/merchants/${merchantId}/review-decisions`,
    command,
  );
  return parseMutationResult(
    response,
    decision === 'APPROVE' ? 'ACTIVE' : 'REVIEW_REJECTED',
    merchantId,
  );
}

async function mutateMerchant(
  merchantId: string,
  command: MerchantLifecycleApi.LifecycleCommand,
  action: 'disable' | 'enable' | 'terminate',
) {
  assertDeployment('PLATFORM');
  assertMerchantId(merchantId);
  assertLifecycleCommand(command, action);
  const response = await requestClient.post<unknown>(
    `/platform/merchants/${merchantId}/${action}`,
    command,
  );
  const expectedStatuses = {
    disable: 'DISABLED',
    enable: 'ACTIVE',
    terminate: 'TERMINATED',
  } as const;
  return parseMutationResult(response, expectedStatuses[action], merchantId);
}

export const disableMerchant = (
  merchantId: string,
  command: MerchantLifecycleApi.LifecycleCommand,
) => mutateMerchant(merchantId, command, 'disable');
export const enableMerchant = (
  merchantId: string,
  command: MerchantLifecycleApi.LifecycleCommand,
) => mutateMerchant(merchantId, command, 'enable');
export const terminateMerchant = (
  merchantId: string,
  command: MerchantLifecycleApi.LifecycleCommand,
) => mutateMerchant(merchantId, command, 'terminate');
