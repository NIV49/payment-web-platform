# Payment Web Platform Admin Frontend

The canonical project guide is maintained in Chinese in [README.zh-CN.md](./README.zh-CN.md). [日本語](./README.ja-JP.md)

This pnpm + Turborepo workspace contains independent `platform-admin`, `merchant-admin`, and `agent-admin` applications backed by the shared `backoffice-runtime`, plus the local-only `backend-mock` and Vben reference Playground. It requires Node.js `>=24.11.0 <25` and pnpm `11.7.0`.

```bash
corepack enable
pnpm install --frozen-lockfile
pnpm run dev:platform
pnpm run dev:merchant
pnpm run dev:agent
```

Do not use the Playground as product code. Read [AGENTS.md](../../AGENTS.md) and the [frontend context](../../docs/ai-context/frontend/README.md) before making changes.
