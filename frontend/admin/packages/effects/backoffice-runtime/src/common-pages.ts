import type { ComponentRecordType } from '@vben/types';

export const COMMON_BACKOFFICE_PAGE_MAP: ComponentRecordType = import.meta.glob(
  './views/dashboard/workspace/index.vue',
);
