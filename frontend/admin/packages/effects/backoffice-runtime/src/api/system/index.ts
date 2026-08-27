// Shared same-tenant administration API used by every backoffice deployment.
export * from './dept';
export * from './dictionary-data';
export * from './dictionary-type';
export * from './menu';
export type {
  PlatformDirectoryContext,
  PlatformDirectoryTarget,
} from './platform-directory';
export { hasExactPlatformDirectoryResponseContext } from './platform-directory';
export * from './role';
export * from './role-grant';
export * from './types';
export * from './user';
