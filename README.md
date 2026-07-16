# Payment Web Platform

支付平台统一仓库，集中管理前端和后端项目。

## 目录结构

```text
payment-web-platform/
├── frontend/  # Vben Admin 前端、Playground 和本地 Mock 服务
└── backend/   # 真实后端项目
```

## 前端开发

```bash
cd frontend
pnpm install
pnpm dev:antdv-next
```

Playground 和 Mock 服务仍位于前端工程中：

```bash
cd frontend
pnpm dev:play
pnpm --filter @vben/backend-mock dev
```

## Git 远端

- `origin`：业务仓库 `NIV49/payment-web-platform`
- `upstream`：Vben 官方仓库，仅允许拉取

Vben 上游仍以仓库根目录组织代码，而本仓库已将其移动到 `frontend/`。
同步上游版本时不要直接执行无审查的合并，应先在独立分支拉取并处理目录差异。
