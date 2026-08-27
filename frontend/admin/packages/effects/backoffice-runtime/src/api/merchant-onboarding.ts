import { getInstalledBackofficeDeployment } from '../deployment';
import { isAssignedIsoCountryCode } from './merchant-country';
import { isMaskedMerchantProtectedValue } from './merchant-masked-value';
import { baseRequestClient, requestClient } from './request';

const MCH003_MERCHANT_TYPES = [
  'PLATFORM',
  'INDIRECT',
  'COMMISSION',
  'SALES',
] as const;
const INDUSTRY_CODES = [
  'FINANCIAL_SERVICES',
  'ECOMMERCE',
  'RETAIL',
  'TRAVEL',
  'EDUCATION',
  'OTHER',
] as const;
const LEGAL_ID_TYPE_CODES = [
  'NATIONAL_ID',
  'PASSPORT',
  'DRIVER_LICENSE',
] as const;
const MERCHANT_DOCUMENT_KINDS = [
  'BRAND_LOGO',
  'BUSINESS_LICENSE',
  'LEGAL_ID_FRONT',
  'LEGAL_ID_BACK',
  'LEGAL_ID_HOLDING',
] as const;
const AUTHENTICATION_TYPES = [
  'ENTERPRISE',
  'NON_PROFIT_ORGANIZATIONS',
  'CLIQUE',
  'INDIVIDUAL',
  'INDIVIDUAL_HOUSEHOLD',
] as const;
const MARKET_CODES = ['BRA', 'PHL'] as const;
const UUID_PATTERN =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const POSITIVE_LONG_PATTERN = /^[1-9][0-9]{0,18}$/;
const MAX_POSITIVE_LONG = '9223372036854775807';
const PROFILE_KEYS = new Set([
  'authenticationType',
  'brandLogoDocumentId',
  'brandName',
  'businessLicenseDocumentId',
  'contactEmail',
  'contactPhone',
  'displayName',
  'industryCode',
  'legalIdBackDocumentId',
  'legalIdFrontDocumentId',
  'legalIdHoldingDocumentId',
  'legalIdNo',
  'legalIdTypeCode',
  'legalIdValidity',
  'legalName',
  'legalPersonName',
  'marketCodes',
  'merchantTypeCode',
  'operatingAddress',
  'registeredAddress',
  'registrationCountry',
  'registrationNumber',
  'remarks',
]);

export namespace MerchantOnboardingApi {
  export type AuthenticationType = (typeof AUTHENTICATION_TYPES)[number];
  export type DocumentKind = (typeof MERCHANT_DOCUMENT_KINDS)[number];
  export type IndustryCode = (typeof INDUSTRY_CODES)[number];
  export type LegalIdTypeCode = (typeof LEGAL_ID_TYPE_CODES)[number];
  export type MerchantTypeCode = (typeof MCH003_MERCHANT_TYPES)[number];
  export type MerchantStatus = 'ACTIVE' | 'DISABLED' | 'PENDING_REVIEW';
  export type AmendmentStatus =
    | 'APPROVED'
    | 'PENDING_REVIEW'
    | 'REJECTED'
    | 'STALE';
  export type SensitiveField =
    | { mode: 'REPLACE'; value: string }
    | { mode: 'RETAIN' };

  export interface LegalIdValidity {
    validFrom: string;
    validTo: string;
  }

  export interface ProfileInput {
    authenticationType: AuthenticationType;
    brandLogoDocumentId: string;
    brandName: string;
    businessLicenseDocumentId: string;
    contactEmail: string;
    contactPhone: string;
    displayName: string;
    industryCode: IndustryCode;
    legalIdBackDocumentId: string;
    legalIdFrontDocumentId: string;
    legalIdHoldingDocumentId: string;
    legalIdNo: SensitiveField;
    legalIdTypeCode: LegalIdTypeCode;
    legalIdValidity: LegalIdValidity;
    legalName: string;
    legalPersonName: string;
    marketCodes: Array<'BRA' | 'PHL'>;
    merchantTypeCode: MerchantTypeCode;
    operatingAddress: string;
    registeredAddress: string;
    registrationCountry: string;
    registrationNumber: SensitiveField;
    remarks: string;
  }

  export interface EligibleTenant {
    tenantCode: string;
    tenantId: string;
    tenantName: string;
  }

  export interface EligibleTenantQuery {
    page?: number;
    pageSize?: number;
    tenantCode?: string;
    tenantName?: string;
  }

  export interface PageResult<T> {
    items: T[];
    total: number;
  }

  export interface DocumentMetadata {
    documentId: string;
    height: number;
    kind: DocumentKind;
    mediaType: 'image/jpeg' | 'image/png';
    sizeBytes: number;
    width: number;
  }

  export interface TemporaryDocument extends DocumentMetadata {
    expiresAt: string;
  }

  export interface CreateRequest {
    idempotencyKey: string;
    profile: ProfileInput;
    targetTenantId: string;
  }

  export interface MerchantMutationResult {
    merchantCode: string;
    merchantId: string;
    rowVersion: number;
    status: 'PENDING_REVIEW';
  }

  export interface AmendmentRequest {
    expectedMerchantVersion: number;
    idempotencyKey: string;
    profile: ProfileInput;
  }

  export interface AmendmentSubmissionResult {
    amendmentId: string;
    createdAt: string;
    merchantId: string;
    originMerchantVersion: number;
    originStatus: 'ACTIVE' | 'DISABLED';
    rowVersion: number;
    status: 'PENDING_REVIEW';
  }

  export interface PendingProfile {
    authenticationType: AuthenticationType;
    brandLogoDocument: DocumentMetadata;
    brandName: string;
    businessLicenseDocument: DocumentMetadata;
    contactEmail: string;
    contactPhone: string;
    displayName: string;
    industryCode: IndustryCode;
    legalIdBackDocument: DocumentMetadata;
    legalIdFrontDocument: DocumentMetadata;
    legalIdHoldingDocument: DocumentMetadata;
    legalIdNoMasked: string;
    legalIdTypeCode: LegalIdTypeCode;
    legalIdValidity: LegalIdValidity;
    legalName: string;
    legalPersonName: string;
    marketCodes: Array<'BRA' | 'PHL'>;
    merchantTypeCode: MerchantTypeCode;
    operatingAddress: string;
    registeredAddress: string;
    registrationCountry: string;
    registrationNumberMasked: string;
    remarks: string;
  }

  export interface AmendmentDecision {
    decidedAt: string;
    decision: 'APPROVE' | 'REJECT';
    reasonCode:
      | 'COMPLIANCE_REJECTED'
      | 'DOCUMENT_UNVERIFIED'
      | 'PROFILE_AMENDMENT_MISMATCH'
      | 'PROFILE_AMENDMENT_VERIFIED';
  }

  export interface PendingAmendment {
    amendmentId: string;
    authorMembershipId: string;
    canCurrentActorReview: boolean;
    createdAt: string;
    decision: AmendmentDecision | null;
    merchantId: string;
    originMerchantVersion: number;
    originStatus: 'ACTIVE' | 'DISABLED';
    profile: PendingProfile;
    rowVersion: number;
    status: 'PENDING_REVIEW';
    updatedAt: string;
  }

  export interface AmendmentReviewRequest {
    decision: 'APPROVE' | 'REJECT';
    expectedVersion: number;
    idempotencyKey: string;
    reasonCode:
      | 'COMPLIANCE_REJECTED'
      | 'DOCUMENT_UNVERIFIED'
      | 'PROFILE_AMENDMENT_MISMATCH'
      | 'PROFILE_AMENDMENT_VERIFIED';
  }

  export interface AmendmentReviewResult {
    amendmentId: string;
    merchantId: string;
    merchantRowVersion: number;
    merchantStatus: 'ACTIVE' | 'DISABLED';
    rowVersion: number;
    status: 'APPROVED' | 'REJECTED';
  }
}

function assertPlatform() {
  if (getInstalledBackofficeDeployment()?.accountDomain !== 'PLATFORM') {
    throw new Error('Platform merchant onboarding is unavailable');
  }
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function hasExactKeys(value: object, keys: Set<string>) {
  const actual = Object.keys(value);
  return actual.length === keys.size && actual.every((key) => keys.has(key));
}

function isPositiveLong(value: unknown): value is string {
  return (
    typeof value === 'string' &&
    POSITIVE_LONG_PATTERN.test(value) &&
    (value.length < MAX_POSITIVE_LONG.length || value <= MAX_POSITIVE_LONG)
  );
}

function isVersion(value: unknown): value is number {
  return Number.isSafeInteger(value) && (value as number) >= 0;
}

function isBoundedString(value: unknown, minimum: number, maximum: number) {
  if (typeof value !== 'string' || value !== value.trim()) return false;
  const length = [...value].length;
  return length >= minimum && length <= maximum;
}

function isEnum<T extends string>(
  value: unknown,
  allowed: readonly T[],
): value is T {
  return typeof value === 'string' && allowed.includes(value as T);
}

function isUtcInstant(value: unknown): value is string {
  return (
    typeof value === 'string' &&
    /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?Z$/.test(value) &&
    Number.isFinite(Date.parse(value))
  );
}

function isDate(value: unknown): value is string {
  if (typeof value !== 'string' || !/^\d{4}-\d{2}-\d{2}$/.test(value)) {
    return false;
  }
  const date = new Date(`${value}T00:00:00Z`);
  return !Number.isNaN(date.getTime()) && date.toISOString().startsWith(value);
}

function isValidity(
  value: unknown,
): value is MerchantOnboardingApi.LegalIdValidity {
  return (
    isRecord(value) &&
    hasExactKeys(value, new Set(['validFrom', 'validTo'])) &&
    isDate(value.validFrom) &&
    isDate(value.validTo) &&
    value.validFrom <= value.validTo
  );
}

function isSensitive(value: unknown, create: boolean) {
  if (!isRecord(value)) return false;
  if (value.mode === 'RETAIN') {
    return !create && hasExactKeys(value, new Set(['mode']));
  }
  return (
    value.mode === 'REPLACE' &&
    hasExactKeys(value, new Set(['mode', 'value'])) &&
    isBoundedString(value.value, 1, 128)
  );
}

function isMarkets(value: unknown) {
  return (
    Array.isArray(value) &&
    value.length > 0 &&
    value.length <= 2 &&
    new Set(value).size === value.length &&
    value.every((item) => isEnum(item, MARKET_CODES)) &&
    value.every(
      (item, index) =>
        index === 0 ||
        MARKET_CODES.indexOf(value[index - 1] as 'BRA' | 'PHL') <
          MARKET_CODES.indexOf(item),
    )
  );
}

function assertProfile(
  value: unknown,
  create: boolean,
): asserts value is MerchantOnboardingApi.ProfileInput {
  if (
    !isRecord(value) ||
    !hasExactKeys(value, PROFILE_KEYS) ||
    !isBoundedString(value.displayName, 1, 128) ||
    !isBoundedString(value.brandName, 1, 128) ||
    !isEnum(value.authenticationType, AUTHENTICATION_TYPES) ||
    !isEnum(value.merchantTypeCode, MCH003_MERCHANT_TYPES) ||
    !isEnum(value.industryCode, INDUSTRY_CODES) ||
    !isPositiveLong(value.brandLogoDocumentId) ||
    !isBoundedString(value.legalName, 1, 200) ||
    !isAssignedIsoCountryCode(value.registrationCountry) ||
    !isMarkets(value.marketCodes) ||
    !isBoundedString(value.registeredAddress, 1, 300) ||
    !isBoundedString(value.operatingAddress, 1, 300) ||
    !isPositiveLong(value.businessLicenseDocumentId) ||
    !isBoundedString(value.legalPersonName, 1, 200) ||
    typeof value.contactEmail !== 'string' ||
    value.contactEmail !== value.contactEmail.trim() ||
    value.contactEmail.length < 3 ||
    value.contactEmail.length > 254 ||
    !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(value.contactEmail) ||
    typeof value.contactPhone !== 'string' ||
    !/^\+[1-9][0-9]{1,14}$/.test(value.contactPhone) ||
    !isEnum(value.legalIdTypeCode, LEGAL_ID_TYPE_CODES) ||
    !isSensitive(value.legalIdNo, create) ||
    !isValidity(value.legalIdValidity) ||
    !isPositiveLong(value.legalIdFrontDocumentId) ||
    !isPositiveLong(value.legalIdBackDocumentId) ||
    !isPositiveLong(value.legalIdHoldingDocumentId) ||
    !isBoundedString(value.remarks, 0, 300) ||
    !isSensitive(value.registrationNumber, create)
  ) {
    throw new Error('Invalid merchant onboarding profile');
  }
}

function isDocumentMetadata(
  value: unknown,
  expectedKind?: MerchantOnboardingApi.DocumentKind,
): value is MerchantOnboardingApi.DocumentMetadata {
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
    isPositiveLong(value.documentId) &&
    isEnum(value.kind, MERCHANT_DOCUMENT_KINDS) &&
    (expectedKind === undefined || value.kind === expectedKind) &&
    (value.mediaType === 'image/png' || value.mediaType === 'image/jpeg') &&
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

export async function getEligibleMerchantTenants(
  query: MerchantOnboardingApi.EligibleTenantQuery,
) {
  assertPlatform();
  if (
    !hasExactOrOptionalKeys(
      query,
      new Set(['page', 'pageSize', 'tenantCode', 'tenantName']),
    ) ||
    (query.page !== undefined &&
      (!Number.isInteger(query.page) || query.page < 1)) ||
    (query.pageSize !== undefined &&
      (!Number.isInteger(query.pageSize) ||
        query.pageSize < 1 ||
        query.pageSize > 100)) ||
    (query.tenantCode !== undefined &&
      !isBoundedString(query.tenantCode, 1, 64)) ||
    (query.tenantName !== undefined &&
      !isBoundedString(query.tenantName, 1, 200))
  ) {
    throw new Error('Invalid eligible Tenant query');
  }
  const response = await requestClient.get<unknown>(
    '/platform/merchant-onboarding/eligible-tenants',
    { params: query },
  );
  if (
    !isRecord(response) ||
    !hasExactKeys(response, new Set(['items', 'total'])) ||
    !Array.isArray(response.items) ||
    !response.items.every(
      (item) =>
        isRecord(item) &&
        hasExactKeys(item, new Set(['tenantCode', 'tenantId', 'tenantName'])) &&
        isPositiveLong(item.tenantId) &&
        isBoundedString(item.tenantCode, 1, 64) &&
        isBoundedString(item.tenantName, 1, 200),
    ) ||
    !Number.isSafeInteger(response.total) ||
    (response.total as number) < 0
  ) {
    throw new Error('Invalid eligible Tenant response');
  }
  return response as unknown as MerchantOnboardingApi.PageResult<MerchantOnboardingApi.EligibleTenant>;
}

function hasExactOrOptionalKeys(value: object, keys: Set<string>) {
  return Object.keys(value).every((key) => keys.has(key));
}

export async function uploadTemporaryMerchantDocument(input: {
  file: File;
  kind: MerchantOnboardingApi.DocumentKind;
  targetTenantId: string;
}) {
  assertPlatform();
  if (
    !hasExactKeys(input, new Set(['file', 'kind', 'targetTenantId'])) ||
    !(input.file instanceof File) ||
    (input.file.type !== 'image/png' && input.file.type !== 'image/jpeg') ||
    input.file.size === 0 ||
    input.file.size > 2 * 1024 * 1024 ||
    !isEnum(input.kind, MERCHANT_DOCUMENT_KINDS) ||
    !isPositiveLong(input.targetTenantId)
  ) {
    throw new Error('Merchant document must be a PNG or JPEG up to 2 MiB');
  }
  const response = await requestClient.upload<unknown>(
    '/platform/merchant-document-uploads',
    input,
  );
  if (
    !isRecord(response) ||
    !hasExactKeys(
      response,
      new Set([
        'documentId',
        'expiresAt',
        'height',
        'kind',
        'mediaType',
        'sizeBytes',
        'width',
      ]),
    ) ||
    !isDocumentMetadata(
      Object.fromEntries(
        Object.entries(response).filter(([key]) => key !== 'expiresAt'),
      ),
      input.kind,
    ) ||
    !isUtcInstant(response.expiresAt)
  ) {
    throw new Error('Invalid merchant document upload response');
  }
  return response as unknown as MerchantOnboardingApi.TemporaryDocument;
}

export async function deleteTemporaryMerchantDocument(documentId: string) {
  assertPlatform();
  assertLongId(documentId, 'Document');
  const response = await requestClient.delete<unknown>(
    `/platform/merchant-document-uploads/${documentId}`,
  );
  if (
    !isRecord(response) ||
    !hasExactKeys(response, new Set(['documentId', 'status'])) ||
    response.documentId !== documentId ||
    response.status !== 'DELETED'
  ) {
    throw new Error('Invalid merchant document deletion response');
  }
  return response as { documentId: string; status: 'DELETED' };
}

type RawDocumentResponse = {
  data: Blob;
  headers: Record<string, unknown> & { get?: (name: string) => unknown };
  status: number;
};

function header(response: RawDocumentResponse, name: string) {
  const fromGet = response.headers?.get?.(name);
  const value = fromGet ?? response.headers?.[name.toLowerCase()];
  return Array.isArray(value) ? value.join(', ') : String(value ?? '');
}

async function getDocumentContent(
  path: string,
  params?: { amendmentId: string },
) {
  const response = await baseRequestClient.download<RawDocumentResponse>(
    path,
    params ? { params, responseReturn: 'raw' } : { responseReturn: 'raw' },
  );
  const mediaType = header(response, 'content-type');
  if (
    response.status !== 200 ||
    !(response.data instanceof Blob) ||
    (mediaType !== 'image/png' && mediaType !== 'image/jpeg') ||
    response.data.type !== mediaType ||
    header(response, 'content-length') !== String(response.data.size) ||
    header(response, 'content-disposition') !== 'inline' ||
    header(response, 'cache-control') !== 'no-store' ||
    header(response, 'pragma') !== 'no-cache' ||
    header(response, 'x-content-type-options') !== 'nosniff' ||
    header(response, 'content-security-policy') !==
      "sandbox; default-src 'none'"
  ) {
    throw new Error('Invalid merchant document response');
  }
  return response.data;
}

export function getTemporaryMerchantDocumentContent(documentId: string) {
  assertPlatform();
  assertLongId(documentId, 'Document');
  return getDocumentContent(
    `/platform/merchant-document-uploads/${documentId}/content`,
  );
}

export function getAttachedMerchantDocumentContent(
  merchantId: string,
  kind: MerchantOnboardingApi.DocumentKind,
  amendmentId?: string,
) {
  assertPlatform();
  assertLongId(merchantId, 'Merchant');
  if (amendmentId !== undefined) assertLongId(amendmentId, 'Amendment');
  if (!isEnum(kind, MERCHANT_DOCUMENT_KINDS)) {
    throw new Error('Invalid merchant document kind');
  }
  return getDocumentContent(
    `/platform/merchants/${merchantId}/documents/${kind}/content`,
    amendmentId === undefined ? undefined : { amendmentId },
  );
}

function assertLongId(value: string, label: string) {
  if (!isPositiveLong(value)) throw new Error(`Invalid ${label} ID`);
}

export async function createPlatformMerchant(
  request: MerchantOnboardingApi.CreateRequest,
) {
  assertPlatform();
  if (
    !hasExactKeys(
      request,
      new Set(['idempotencyKey', 'profile', 'targetTenantId']),
    ) ||
    !UUID_PATTERN.test(request.idempotencyKey) ||
    !isPositiveLong(request.targetTenantId)
  ) {
    throw new Error('Invalid merchant create request');
  }
  assertProfile(request.profile, true);
  const response = await requestClient.post<unknown>(
    '/platform/merchants',
    request,
  );
  if (
    !isMutationResult(response) ||
    response.status !== 'PENDING_REVIEW' ||
    response.rowVersion !== 0
  ) {
    throw new Error('Invalid merchant create response');
  }
  return response as MerchantOnboardingApi.MerchantMutationResult;
}

function isMutationResult(value: unknown): value is Record<string, any> {
  return (
    isRecord(value) &&
    hasExactKeys(
      value,
      new Set(['merchantCode', 'merchantId', 'rowVersion', 'status']),
    ) &&
    isPositiveLong(value.merchantId) &&
    isBoundedString(value.merchantCode, 1, 64) &&
    isVersion(value.rowVersion)
  );
}

export async function submitMerchantAmendment(
  merchantId: string,
  request: MerchantOnboardingApi.AmendmentRequest,
) {
  assertPlatform();
  assertLongId(merchantId, 'Merchant');
  if (
    !hasExactKeys(
      request,
      new Set(['expectedMerchantVersion', 'idempotencyKey', 'profile']),
    ) ||
    !isVersion(request.expectedMerchantVersion) ||
    !UUID_PATTERN.test(request.idempotencyKey)
  ) {
    throw new Error('Invalid merchant amendment request');
  }
  assertProfile(request.profile, false);
  const response = await requestClient.post<unknown>(
    `/platform/merchants/${merchantId}/amendments`,
    request,
  );
  if (!isAmendmentSubmission(response, merchantId)) {
    throw new Error('Invalid merchant amendment response');
  }
  return response;
}

function isAmendmentSubmission(
  value: unknown,
  merchantId: string,
): value is MerchantOnboardingApi.AmendmentSubmissionResult {
  return (
    isRecord(value) &&
    hasExactKeys(
      value,
      new Set([
        'amendmentId',
        'createdAt',
        'merchantId',
        'originMerchantVersion',
        'originStatus',
        'rowVersion',
        'status',
      ]),
    ) &&
    isPositiveLong(value.amendmentId) &&
    value.merchantId === merchantId &&
    value.status === 'PENDING_REVIEW' &&
    value.rowVersion === 0 &&
    isVersion(value.originMerchantVersion) &&
    (value.originStatus === 'ACTIVE' || value.originStatus === 'DISABLED') &&
    isUtcInstant(value.createdAt)
  );
}

export async function getPendingMerchantAmendment(merchantId: string) {
  assertPlatform();
  assertLongId(merchantId, 'Merchant');
  const response = await requestClient.get<unknown>(
    `/platform/merchants/${merchantId}/amendments/pending`,
  );
  if (response === null) return null;
  if (!isPendingAmendment(response, merchantId)) {
    throw new Error('Invalid pending merchant amendment response');
  }
  return response;
}

function isPendingProfile(
  value: unknown,
): value is MerchantOnboardingApi.PendingProfile {
  return (
    isRecord(value) &&
    hasExactKeys(
      value,
      new Set([
        'authenticationType',
        'brandLogoDocument',
        'brandName',
        'businessLicenseDocument',
        'contactEmail',
        'contactPhone',
        'displayName',
        'industryCode',
        'legalIdBackDocument',
        'legalIdFrontDocument',
        'legalIdHoldingDocument',
        'legalIdNoMasked',
        'legalIdTypeCode',
        'legalIdValidity',
        'legalName',
        'legalPersonName',
        'marketCodes',
        'merchantTypeCode',
        'operatingAddress',
        'registeredAddress',
        'registrationCountry',
        'registrationNumberMasked',
        'remarks',
      ]),
    ) &&
    isBoundedString(value.displayName, 1, 128) &&
    isBoundedString(value.brandName, 1, 128) &&
    isEnum(value.authenticationType, AUTHENTICATION_TYPES) &&
    isEnum(value.merchantTypeCode, MCH003_MERCHANT_TYPES) &&
    isEnum(value.industryCode, INDUSTRY_CODES) &&
    isDocumentMetadata(value.brandLogoDocument, 'BRAND_LOGO') &&
    isBoundedString(value.legalName, 1, 200) &&
    isAssignedIsoCountryCode(value.registrationCountry) &&
    isMaskedMerchantProtectedValue(value.registrationNumberMasked) &&
    isMarkets(value.marketCodes) &&
    isBoundedString(value.registeredAddress, 1, 300) &&
    isBoundedString(value.operatingAddress, 1, 300) &&
    isDocumentMetadata(value.businessLicenseDocument, 'BUSINESS_LICENSE') &&
    isBoundedString(value.legalPersonName, 1, 200) &&
    isBoundedString(value.contactEmail, 3, 254) &&
    /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(value.contactEmail as string) &&
    typeof value.contactPhone === 'string' &&
    /^\+[1-9][0-9]{1,14}$/.test(value.contactPhone) &&
    isEnum(value.legalIdTypeCode, LEGAL_ID_TYPE_CODES) &&
    isMaskedMerchantProtectedValue(value.legalIdNoMasked) &&
    isValidity(value.legalIdValidity) &&
    isDocumentMetadata(value.legalIdFrontDocument, 'LEGAL_ID_FRONT') &&
    isDocumentMetadata(value.legalIdBackDocument, 'LEGAL_ID_BACK') &&
    isDocumentMetadata(value.legalIdHoldingDocument, 'LEGAL_ID_HOLDING') &&
    isBoundedString(value.remarks, 0, 300)
  );
}

function isAmendmentDecision(value: unknown) {
  if (value === null) return true;
  if (
    !isRecord(value) ||
    !hasExactKeys(value, new Set(['decidedAt', 'decision', 'reasonCode']))
  ) {
    return false;
  }
  const approve =
    value.decision === 'APPROVE' &&
    value.reasonCode === 'PROFILE_AMENDMENT_VERIFIED';
  const reject =
    value.decision === 'REJECT' &&
    [
      'COMPLIANCE_REJECTED',
      'DOCUMENT_UNVERIFIED',
      'PROFILE_AMENDMENT_MISMATCH',
    ].includes(value.reasonCode as string);
  return (approve || reject) && isUtcInstant(value.decidedAt);
}

function isPendingAmendment(
  value: unknown,
  merchantId: string,
): value is MerchantOnboardingApi.PendingAmendment {
  return (
    isRecord(value) &&
    hasExactKeys(
      value,
      new Set([
        'amendmentId',
        'authorMembershipId',
        'canCurrentActorReview',
        'createdAt',
        'decision',
        'merchantId',
        'originMerchantVersion',
        'originStatus',
        'profile',
        'rowVersion',
        'status',
        'updatedAt',
      ]),
    ) &&
    isPositiveLong(value.amendmentId) &&
    value.merchantId === merchantId &&
    value.status === 'PENDING_REVIEW' &&
    isVersion(value.rowVersion) &&
    isVersion(value.originMerchantVersion) &&
    (value.originStatus === 'ACTIVE' || value.originStatus === 'DISABLED') &&
    isPositiveLong(value.authorMembershipId) &&
    typeof value.canCurrentActorReview === 'boolean' &&
    isPendingProfile(value.profile) &&
    isAmendmentDecision(value.decision) &&
    isUtcInstant(value.createdAt) &&
    isUtcInstant(value.updatedAt)
  );
}

export async function reviewMerchantAmendment(
  merchantId: string,
  amendmentId: string,
  request: MerchantOnboardingApi.AmendmentReviewRequest,
) {
  assertPlatform();
  assertLongId(merchantId, 'Merchant');
  assertLongId(amendmentId, 'Amendment');
  const approve =
    request.decision === 'APPROVE' &&
    request.reasonCode === 'PROFILE_AMENDMENT_VERIFIED';
  const reject =
    request.decision === 'REJECT' &&
    [
      'COMPLIANCE_REJECTED',
      'DOCUMENT_UNVERIFIED',
      'PROFILE_AMENDMENT_MISMATCH',
    ].includes(request.reasonCode);
  if (
    !hasExactKeys(
      request,
      new Set(['decision', 'expectedVersion', 'idempotencyKey', 'reasonCode']),
    ) ||
    (!approve && !reject) ||
    !isVersion(request.expectedVersion) ||
    !UUID_PATTERN.test(request.idempotencyKey)
  ) {
    throw new Error('Invalid merchant amendment review request');
  }
  const response = await requestClient.post<unknown>(
    `/platform/merchants/${merchantId}/amendments/${amendmentId}/review-decisions`,
    request,
  );
  if (
    !isRecord(response) ||
    !hasExactKeys(
      response,
      new Set([
        'amendmentId',
        'merchantId',
        'merchantRowVersion',
        'merchantStatus',
        'rowVersion',
        'status',
      ]),
    ) ||
    response.amendmentId !== amendmentId ||
    response.merchantId !== merchantId ||
    response.status !==
      (request.decision === 'APPROVE' ? 'APPROVED' : 'REJECTED') ||
    (response.merchantStatus !== 'ACTIVE' &&
      response.merchantStatus !== 'DISABLED') ||
    !isVersion(response.rowVersion) ||
    !isVersion(response.merchantRowVersion)
  ) {
    throw new Error('Invalid merchant amendment review response');
  }
  return response as unknown as MerchantOnboardingApi.AmendmentReviewResult;
}

export {
  INDUSTRY_CODES,
  LEGAL_ID_TYPE_CODES,
  MCH003_MERCHANT_TYPES,
  MERCHANT_DOCUMENT_KINDS,
};
