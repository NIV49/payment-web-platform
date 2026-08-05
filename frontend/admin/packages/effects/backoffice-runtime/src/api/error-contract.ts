export const OPTIMISTIC_LOCK_CONFLICT = 'OPTIMISTIC_LOCK_CONFLICT';
const OPTIMISTIC_LOCK_CONFLICT_CODE = 40_902;

interface ApiErrorBody {
  code?: unknown;
  error?: unknown;
  message?: unknown;
}

interface ApiRequestError {
  response?: {
    data?: ApiErrorBody;
  };
}

export function isOptimisticLockConflict(error: unknown): boolean {
  const responseData = (error as ApiRequestError | undefined)?.response?.data;
  return (
    responseData?.code === OPTIMISTIC_LOCK_CONFLICT_CODE &&
    responseData.error === OPTIMISTIC_LOCK_CONFLICT
  );
}

export function resolveApiErrorMessage(
  responseData: ApiErrorBody | undefined,
  fallback: string,
): string {
  if (typeof responseData?.message === 'string' && responseData.message) {
    return responseData.message;
  }
  if (typeof responseData?.error === 'string' && responseData.error) {
    return responseData.error;
  }
  return fallback;
}
