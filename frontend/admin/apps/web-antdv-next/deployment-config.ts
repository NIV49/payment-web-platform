export const BUILD_VARIANTS = ['agent', 'merchant', 'platform'] as const;

export type BuildVariant = (typeof BUILD_VARIANTS)[number];

export function isBuildVariant(value: string): value is BuildVariant {
  return BUILD_VARIANTS.some((variant) => variant === value);
}

export function resolveDeployment(mode: string): BuildVariant {
  return isBuildVariant(mode) ? mode : 'platform';
}
