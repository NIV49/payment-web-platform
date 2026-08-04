# Payment Web Platform

支付平台统一仓库，集中管理前端和后端项目。

## 目录结构

```text
payment-web-platform/
├── .vscode/     # 仓库根工作区的统一编辑器配置
├── frontend/
│   ├── admin/   # Vben Admin、Playground 和本地 Mock 服务
│   └── portal/  # Nuxt 4 多应用 Monorepo（待初始化）
├── backend/     # Maven 多模块后端工程
├── docs/        # 平台文档
├── infra/       # 部署与基础设施配置
└── .agents/     # 项目级 AI 工作流技能
```

## 开发工作区

统一从仓库根目录 `payment-web-platform` 打开编辑器。仓库根目录的 `.vscode` 是唯一共享编辑器配置；不要从 `frontend/admin` 单独打开，也不要在子工程中新增嵌套 `.vscode` 或 `.code-workspace`。

## 前端开发

```bash
cd frontend/admin
pnpm install
pnpm dev:antdv-next
```

前端工作区要求 Node.js `>=24.11.0 <25`，`.node-version` 与产品构建镜像固定 24.16.0；pnpm 固定 11.7.0。当前 Admin 工程基于 Vben 5.7.0、Vue 3.5.38 和 Antdv Next 1.3.6。

Playground 和 Mock 服务仍位于前端工程中：

```bash
cd frontend/admin
pnpm dev:play
pnpm --filter @vben/backend-mock start
```

## 后端开发

当前后端已迁入 `backend/`：`applications` 只允许可启动部署单元，`modules` 按业务领域同时管理核心与所属适配器。后端基线固定为 Java 25、Spring Boot 4.1.0、jOOQ 3.21.5、PostgreSQL 18.4 和 Valkey 7.2.13；Identity 模块还包含版本化权限快照和 Sa-Token 1.45.0 会话适配器，不使用 MyBatis：

```bash
cd backend
./mvnw -s maven-settings.xml clean verify
```

启动本地 PostgreSQL 18 和 Valkey（Redis 协议）：

```bash
docker compose -f infra/docker-compose.local.yml up -d
```

本地数据库和 `local` 管理 API 只绑定到回环地址，不对局域网开放。运行后端必须使用 JDK 25。`local` 不再启用 Flyway baseline；仅手工执行过 V1、但没有 `flyway_schema_history` 的旧开发卷不属于受支持升级路径。先备份需要保留的数据，再重建该本地卷，让 Flyway 从空库完整执行迁移：

```bash
cd backend
./mvnw -s maven-settings.xml -pl applications/admin-api -am package -DskipTests
printf 'Local bootstrap password: '
read -r -s PAYMENT_BOOTSTRAP_PASSWORD
printf '\n'
export PAYMENT_BOOTSTRAP_PASSWORD
java -jar applications/admin-api/target/admin-api-0.1.0-SNAPSHOT.jar \
  --spring.profiles.active=local
unset PAYMENT_BOOTSTRAP_PASSWORD
```

`local` profile 在生产 Flyway 迁移完成后单独加载开发 fixture，创建本地用户名 `admin`，但不提供默认身份口令。启动时必须通过 `PAYMENT_BOOTSTRAP_PASSWORD` 显式提供只用于本地 fixture 的口令，且不得把值写入仓库或 shell 历史。V8 只识别并移除 V2/V3 的那组预留 fixture，保留全局 Permission Catalog、扩展权限以及无关租户/用户/审计/Outbox。预留 ID 或自然键碰撞、fixture 被修改、租户 1 出现额外依赖关系时迁移会整体回滚，必须按 [V8 迁移手册](docs/runbooks/iam-v8-fixture-isolation.md) 处理。生产 profile 不创建本地账号。API 默认监听 `http://127.0.0.1:8080/api`，前端开发服务器默认监听 `http://127.0.0.1:5999`。

生产默认关闭 Web 进程内的 Flyway 执行，迁移必须由独立部署 Job 先完成；该 Job/CD 编排尚未实现，因此生产仍是 **NO-GO**。应用启动门禁不会关闭：它在 Web Server 接受流量前只读核对当前二进制携带的全部版本迁移，pending、missing、failed、future 或 checksum/描述/类型漂移都会拒绝启动，并只输出脱敏原因码。门禁不会执行 `migrate` 或 `repair`。只有完成 expand/contract 迁移约束和 N/N-1 双版本兼容门禁后，才可评估是否放宽成功 future migration；当前不承诺滚动回滚。

Navicat 本地连接参数：

```text
Host: 127.0.0.1
Port: 15432
Database: payment_platform
User: payment_dev
Password: disabled
```

`disabled` 是 compose 与 `application-local.yml` 当前使用的公开、非秘密开发 sentinel，仅适用于绑定 `127.0.0.1` 的本地 PostgreSQL/Valkey，不是可复用于生产的凭据。已有 PostgreSQL named volume 不会因 compose 环境变量变化自动轮换 role 密码；备份、原地轮换和可丢弃数据的精确重建步骤见 [Backend 本地 PostgreSQL 说明](backend/README.md#existing-local-volume-credential-alignment)。

## AI 开发与 Judge

所有开发任务先读 [仓库开发规则](AGENTS.md) 和 [AI 上下文入口](docs/ai-context/README.md)。迁移、重构、Judge 或多 Agent 能力切片还必须遵循 [Judge Charter](docs/judge-charter.md)；项目级 [payment-modernization skill](.agents/skills/payment-modernization/SKILL.md) 提供 Reimagine/Transform 路由、产物契约和门禁流程。

<!-- decision-status id=IAM-GLOBAL-USER-MULTI-TENANT status=accepted ref=docs/adr/0008-isolate-three-backoffice-account-domains-and-sessions.md -->

Judge 与 skill 不覆盖产品决策。[ADR-0008](docs/adr/0008-isolate-three-backoffice-account-domains-and-sessions.md) 已确认三后台独立账号域、可信服务端工作区入口及 Cookie/session realm/cache 隔离；同域多 Membership 仍允许，但客户端不得选择工作区，服务端上下文无法唯一解析时必须失败关闭。

如果你需要一段很长的注释来证明这种变通方法是可行的，那么代码就有问题——请修改代码。

## Git 远端

- `origin`：业务仓库 `NIV49/payment-web-platform`
- `upstream`：Vben 官方仓库，仅允许拉取

Vben 上游仍以仓库根目录组织代码，而本仓库已将其移动到 `frontend/`。
同步上游版本时不要直接执行无审查的合并，应先在独立分支拉取并处理目录差异。
