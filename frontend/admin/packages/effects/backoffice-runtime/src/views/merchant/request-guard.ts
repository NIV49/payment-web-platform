interface MerchantRequestToken {
  generation: number;
  scope: string;
}

function createMerchantRequestGuard() {
  let generation = 0;
  let currentScope: string | undefined;
  return {
    begin(scope: string): MerchantRequestToken {
      generation += 1;
      currentScope = scope;
      return { generation, scope };
    },
    invalidate() {
      generation += 1;
      currentScope = undefined;
    },
    isCurrent(token: MerchantRequestToken, scope: string) {
      return (
        token.generation === generation &&
        token.scope === scope &&
        currentScope === scope
      );
    },
  };
}

export { createMerchantRequestGuard };
export type { MerchantRequestToken };
