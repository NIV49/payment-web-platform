# Payment Web Platform Admin Frontend

The canonical project guide is maintained in Chinese in [README.zh-CN.md](./README.zh-CN.md). [日本語](./README.ja-JP.md)

This pnpm + Turborepo workspace contains the `web-antdv-next` product application, the local-only `backend-mock`, and the Vben reference Playground. It requires Node.js `>=24.11.0 <25` and pnpm `11.7.0`.

```bash
corepack enable
pnpm install --frozen-lockfile
pnpm dev:antdv-next
```

Do not use the Playground as product code. Read [AGENTS.md](../../AGENTS.md) and the [frontend context](../../docs/ai-context/frontend/README.md) before making changes.
