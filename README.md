# Payment Web Platform

支付平台统一仓库，集中管理前端和后端项目。

## 目录结构

```text
payment-web-platform/
├── frontend/
│   ├── admin/   # Vben Admin、Playground 和本地 Mock 服务
│   └── portal/  # Nuxt 4 多应用 Monorepo（待初始化）
├── backend/     # Maven 多模块后端工程
├── docs/        # 平台文档
└── infra/       # 部署与基础设施配置
```

## 前端开发

```bash
cd frontend/admin
pnpm install
pnpm dev:antdv-next
```

Playground 和 Mock 服务仍位于前端工程中：

```bash
cd frontend/admin
pnpm dev:play
pnpm --filter @vben/backend-mock dev
```

## 后端开发

当前后端已迁入 `backend/`，按 `applications / modules / adapters` 组织支付权限领域核心、MyBatis、Redis、Sa-Token 边界和 PostgreSQL 初始化脚本：

```bash
cd backend
mvn -s maven-settings.xml clean verify
```

启动本地 PostgreSQL：

```bash
docker compose -f infra/docker-compose.local.yml up -d
```

本地数据库只绑定到回环地址，不对局域网开放。首次创建数据卷时会自动执行权限 DDL。

## Git 远端

- `origin`：业务仓库 `NIV49/payment-web-platform`
- `upstream`：Vben 官方仓库，仅允许拉取

Vben 上游仍以仓库根目录组织代码，而本仓库已将其移动到 `frontend/`。
同步上游版本时不要直接执行无审查的合并，应先在独立分支拉取并处理目录差异。
