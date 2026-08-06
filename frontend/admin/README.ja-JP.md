# Payment Web Platform Admin フロントエンド

正式なプロジェクトガイドは中国語の [README.zh-CN.md](./README.zh-CN.md) で管理しています。[English](./README.md)

この pnpm + Turborepo ワークスペースには、独立した `platform-admin`、`merchant-admin`、`agent-admin` と共有 `backoffice-runtime`、ローカル専用の `backend-mock`、Vben 参照用 Playground が含まれます。必要なバージョンは Node.js `>=24.11.0 <25` と pnpm `11.7.0` です。

```bash
corepack enable
pnpm install --frozen-lockfile
pnpm run dev:platform
pnpm run dev:merchant
pnpm run dev:agent
```

変更前に [AGENTS.md](../../AGENTS.md) と [フロントエンドコンテキスト](../../docs/ai-context/frontend/README.md) を確認してください。
