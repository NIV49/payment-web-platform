# Payment Web Platform Admin 前端

本目录是支付平台 Admin 的 pnpm + Turborepo 前端工作区，基于 Vben 5.7.0。产品应用固定使用 `web-antdv-next` 和 `antdv-next`；Playground 仅用于查阅框架示例，backend-mock 仅用于本地隔离开发。

[English](./README.md) · [日本語](./README.ja-JP.md)

## 环境要求

- Node.js `>=24.11.0 <25`，仓库默认版本为 `.node-version` 中的 `24.16.0`；
- pnpm `11.7.0`，版本由根 `packageManager` 固定；
- 建议通过 Corepack 使用项目声明的 pnpm。

```bash
corepack enable
pnpm install --frozen-lockfile
```

## 工作区

| 路径                   | 用途                            |
| ---------------------- | ------------------------------- |
| `apps/web-antdv-next`  | 支付平台 Admin 产品应用         |
| `apps/backend-mock`    | 本地 Mock 服务，不进入生产部署  |
| `playground`           | Vben 示例知识库，不承载产品功能 |
| `packages`、`internal` | Vben 共享框架能力和工程工具     |

## 常用命令

在当前目录执行：

```bash
# 产品应用
pnpm dev:antdv-next

# Playground
pnpm dev:play

# 本地 Mock
pnpm --filter @vben/backend-mock start

# 最低验证
pnpm -F @vben/web-antdv-next run typecheck
pnpm run test:production-safety
pnpm build:antdv-next
```

## 开发前必读

- [仓库开发规则](../../AGENTS.md)
- [Admin 前端工程上下文](../../docs/ai-context/frontend/README.md)
- [Vben 5.7.0 项目基线](../../docs/ai-context/vben/README.md)

业务功能只进入 `apps/web-antdv-next`。不要从其他 Vben UI 应用复制 `ant-design-vue`、Element Plus 或 Naive UI 实现，也不要把 Playground 当作产品代码。
