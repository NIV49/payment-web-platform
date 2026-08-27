/** Shared pagination contract for all three backoffice applications. */
export interface PageResult<T> {
  items: T[];
  total: number;
}

export function hasExplicitRoleIds(value: unknown): value is string[] {
  return Array.isArray(value);
}
