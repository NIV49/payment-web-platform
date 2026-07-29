export interface PageResult<T> {
  items: T[];
  total: number;
}

export function hasExplicitRoleIds(value: unknown): value is string[] {
  return Array.isArray(value);
}
