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

前端工作区要求 Node.js `22.18+` 或 `24.x`；不要使用尚未纳入项目支持范围的 Node.js 25。

Playground 和 Mock 服务仍位于前端工程中：

```bash
cd frontend/admin
pnpm dev:play
pnpm --filter @vben/backend-mock dev
```

## 后端开发

当前后端已迁入 `backend/`：`applications` 只允许可启动部署单元，`modules` 按业务领域同时管理核心与所属适配器。Identity 模块包含权限核心、MyBatis、Redis、Sa-Token 边界和 PostgreSQL Flyway 迁移：

```bash
cd backend
./mvnw -s maven-settings.xml clean verify
```

启动本地 PostgreSQL 和 Redis：

```bash
docker compose -f infra/docker-compose.local.yml up -d
```

本地数据库和 `local` 管理 API 只绑定到回环地址，不对局域网开放。已有本地 V1 数据卷必须启用 `local` profile，Flyway 会先建立基线再执行后续迁移：

```bash
cd backend
./mvnw -s maven-settings.xml -pl applications/admin-api -am package -DskipTests
java -jar applications/admin-api/target/admin-api-0.1.0-SNAPSHOT.jar \
  --spring.profiles.active=local
```

`local` profile 提供公开的本地开发凭据 `admin / Admin@123456`，只在 `admin` 尚未初始化密码时写入 BCrypt 哈希。可通过 `PAYMENT_BOOTSTRAP_PASSWORD` 覆盖；生产 profile 没有默认密码。注意：V2 历史迁移仍包含固定 ID 的 bootstrap 身份行，生产启用 Flyway 前必须先完成持久环境盘点和 fixture 拆分。API 默认监听 `http://127.0.0.1:8080/api`，前端开发服务器默认监听 `http://127.0.0.1:5999`。

Navicat 本地连接参数：

```text
Host: 127.0.0.1
Port: 15432
Database: payment_platform
User: payment_dev
Password: 留空
```

## Git 远端

- `origin`：业务仓库 `NIV49/payment-web-platform`
- `upstream`：Vben 官方仓库，仅允许拉取

Vben 上游仍以仓库根目录组织代码，而本仓库已将其移动到 `frontend/`。
同步上游版本时不要直接执行无审查的合并，应先在独立分支拉取并处理目录差异。
