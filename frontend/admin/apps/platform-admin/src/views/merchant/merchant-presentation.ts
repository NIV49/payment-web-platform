import type { MerchantLifecycleApi } from '@payment/backoffice-runtime/api/merchant-lifecycle';

function normalizeMerchantTypeCode(
  code: MerchantLifecycleApi.MerchantTypeCode | null,
) {
  return code === 'DIRECT' ? 'PLATFORM' : code;
}

export { normalizeMerchantTypeCode };
