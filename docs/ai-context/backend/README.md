# 后端工程上下文

> 适用目录：`backend/**`
> 当前事实基线：2026-07-20 工作区
> 注意：当前 `admin-api` 已经是可启动应用；仓库内较早的 `backend/README.md` 仍描述“尚不可部署”，该段已经过时。

## 1. 技术基线与运行单元

- Java 17（Maven Enforcer 固定为 17.x）；
- Spring Boot 4.1.0；
- Maven 3.9.9 Wrapper 多模块 reactor；
- MyBatis Spring Boot 4.0.0 / MyBatis 3.5.19；
- Sa-Token 1.45.0 Boot 4 starter；
- PostgreSQL 16.14 + Flyway；
- Valkey 7.2.13（Redis 协议兼容，BSD-3-Clause）；
- 测试版本跟随 Spring Boot BOM，API 集成测试使用 Testcontainers。

根 POM 只聚合两个模块：

```text
backend
├── applications/admin-api       可启动 Spring Boot 管理 API
└── modules/identity             Identity 业务上下文及所属适配器
```

`applications` 必须只表示可启动部署单元。Identity 的用户、登录、角色、菜单和权限规则属于 `modules/identity`，不能再回到 application Controller 或建立 `applications/identity-authorization` 业务模块。

## 2. 模块与依赖边界

| 模块 | 职责 | 可以依赖 | 不应承载 |
| --- | --- | --- | --- |
| `applications/admin-api` | Spring Boot 启动、Bean 组合、HTTP/CORS/安全拦截、DTO、异常 envelope | identity 的 core 与 adapters | 领域规则、MyBatis SQL 细节 |
| `identity/core` | 身份、授权、数据范围模型；应用服务；外部端口 | JDK 和内部领域代码 | Spring、MyBatis、Redis、Sa-Token |
| `identity/persistence-postgres` | MyBatis mapper/repository、Identity 表和 Flyway | identity-core | 其他业务上下文的表 |
| `identity/cache-redis` | 权限快照缓存、登录失败限流 | identity-core、Redis 抽象/adapter | 业务真相、会话真相 |
| `identity/session-satoken` | 登录会话签发、可信 session 属性、session 版本校验 | identity-core port、Sa-Token | 权限业务决策 |

依赖方向：

```text
admin-api composition root
  -> identity adapters
  -> identity core ports/model
```

Core 定义端口，adapter 实现端口，application 负责组装。共享“persistence-postgres 大仓库”不是目标结构；适配器归业务上下文所有。

## 3. Identity Core 内部地图

### `domain`

拥有稳定业务值和授权事实：`AuthorizationSubject`、`PermissionCode`、`PermissionDefinition`、`PermissionGrant`、`GrantSnapshot`、`DimensionScope`、`RiskLevel`、`ScopeDimension`、`ScopeMode` 等。

核心不变量：

- TenantMembership 所在 tenant 是授权工作区；业务资源另有 resource-owner tenant；
- 一个 RoleGrant 把 permission、全部数据维度和资金限制绑定为一个不可拆散的授权项；
- 同一 grant 内各维度 AND，不同 grant OR；
- 跨资源归属租户默认拒绝；只读权限必须显式标为 RELATED_PARTY_READ，并同时命中商户/客户范围和可信关系证据；
- FUND 权限由领域模型和数据库约束固定为 SAME_TENANT_ONLY，并需要可信目录、step-up 与审批约束；
- 数据范围 SQL 参数化，列名只能来自服务端白名单。

### `application` 与 `datascope`

- `DefaultAuthorizationService`：根据 subject、permission、resource 和 grants 作允许/拒绝决定。
- `CachedPermissionGrantLoader`：按 tenant + membership + permissionVersion 读取/回源权限快照。
- `DefaultDataScopePlanner`：把 grant 维度规划为结构化范围。
- `PermissionDataScopeInterceptor`：数据范围执行边界。
- `StructuredPredicateCompiler`：把结构化 predicate 编译为参数化 SQL。
- `WhitelistedColumns`：阻止调用方控制 SQL 列名。

这套授权/数据范围引擎是支付业务目标能力，但当前 `admin-api` 的 CRUD 请求尚未接入该完整决策链；当前 Web 层使用权限码集合检查。

### `service`

- `AuthenticationService`：用户名标准化、失败限流、恒定风格密码校验、会话签发。
- `IdentityAdministrationService`：当前用户、权限码、可见菜单、用户/角色/部门/菜单管理的应用门面。
- `RoleGrantAdministrationService`：原子 RoleGrant 写入、版本推进和缓存失效的业务边界。
- `IdentityModels`：应用层 command/query/result records，不是 HTTP DTO。

### `port`

端口按能力拆分：Identity 查询、用户/角色/部门/菜单管理、权限目录、grant 仓库与缓存、membership 版本、部门层级和跨域关系范围。Controller 不能越过 service 直接调用这些端口。

## 4. 可启动 Admin API

### Composition root

- `AdminApiApplication`：唯一 Spring Boot main。
- `IdentityConfiguration`：MyBatis repository、Identity services、BCrypt、Redis 登录限流、Sa-Token bridge 和本地 bootstrap 密码组装。
- `SecurityConfiguration`：Cookie 会话校验、可信 Origin、URL 到权限码映射、CORS 与安全响应头。
- `application.yml`：DB、Redis、Flyway、MyBatis 和安全配置。
- `application-local.yml`：只绑定 `127.0.0.1`，允许本地 baseline 和开发密码。

### HTTP 层

- `AuthUserMenuController`：`/api/auth/login`、logout、当前用户、权限码和运行时菜单。
- `SystemAdministrationController`：`/api/system/user|role|dept|menu` 管理接口。
- `ApiResponse`：`{ code, data, error, message, traceId }`。
- `ApiExceptionHandler`：校验、认证、授权、冲突和内部异常转换。
- `RequestTrace`：每个请求 trace ID。

HTTP DTO 是 Controller 内部 record，Identity service 使用 `IdentityModels`。这是防止前端字段直接污染领域模型的边界，但 Controller 已较大；继续增加业务时应按资源拆 Controller 与 mapper，而不是让单文件无限增长。

## 5. 关键调用链

### 登录与会话

```text
POST /api/auth/login
  -> AuthUserMenuController
  -> AuthenticationService.login
  -> CredentialLookup(MyBatis repository)
  -> BCryptPasswordEncoder.matches
  -> RedisLoginAttemptLimiter
  -> SaTokenSessionIssuer
  -> PAYMENT_SESSION HttpOnly Cookie
```

登录成功把 `userId/membershipId/tenantId/departmentId/permissionVersion/sessionVersion/stepUpVerified` 写入 Sa-Token session。后续请求由 `SaTokenSessionBridge.currentSubject()` 读取，并从数据库核对当前 `sessionVersion`；版本不一致直接拒绝。

Sa-Token 配置：8 小时总超时、30 分钟 active timeout、禁止并发共享、只读 Cookie、不读 Header/Body、HttpOnly、SameSite Strict。生产环境应启用 Secure Cookie。

### 请求鉴权

```text
/api/**
  -> security headers filter / trace
  -> AdminSecurityInterceptor
  -> 非 GET 请求校验 Origin
  -> SaTokenSessionBridge.currentSubject
  -> AdminApiPermissionPolicy 精确匹配 HTTP method + path
  -> AdminAuthorizationEnforcer
  -> CachedPermissionGrantLoader
  -> Redis versioned snapshot / PostgreSQL grants
  -> DefaultAuthorizationService
  -> Controller
```

当前策略只公开 `POST /api/auth/login`。用户信息、权限码、动态菜单和退出是 session-only；系统 CRUD 使用精确 method/path 注册表和完整授权服务。未知路径、未知方法和相似前缀默认拒绝。`permissionCodes` 只服务 UI 展示，不再承担 HTTP PEP。Admin 资源上下文由服务端 Session 构造；未来业务详情/列表还必须从可信资源授权视图补齐 merchant、market、channel 和 resource-owner tenant。

### 动态菜单

```text
GET /api/menu/all
  -> trusted AuthorizationSubject
  -> IdentityAdministrationService.accessibleMenus
  -> MyBatis query by tenant/membership/role
  -> Controller 组树 + 解析 meta_json
  -> RouteRecordStringComponent[]
  -> Vben generateRoutesByBackend
```

跨端强约定：

- `meta.title` 是前端语言包 key；
- `component_path` 是相对前端 `views` 的组件路径；
- PAGE component 必须出现在 `payment.menu.allowed-page-components`；
- 一级 catalog 不写 `BasicLayout`，由根路由统一承载布局；
- embedded/link 必须有内部 route path，component 固定为 `IFrameView`，外部 URL 只进入 meta；
- route `name` 必须唯一；
- Long ID 转字符串；
- `meta_json` 无法解析时失败，不吞掉脏数据。

### 用户/角色/部门/菜单写操作

```text
SystemAdministrationController
  -> request record validation
  -> IdentityAdministrationService
  -> capability port
  -> MyBatisIdentityAdministrationRepository @Transactional
  -> IdentityAdminMapper
  -> IAM tables + audit + version bump
```

用户状态更新使用 membership `row_version` 乐观锁。角色菜单变化推进相关 membership 的 `permission_version`。部门和菜单更新校验不能把节点移动到自身后代；存在活动依赖时拒绝删除/禁用。

## 6. 数据库所有权与迁移

### V1 权限基线

拥有：

- `iam_user`、`iam_tenant`、`iam_department`、`iam_membership`；
- `iam_role`、`iam_membership_role`；
- `iam_permission`、`iam_role_grant`、`iam_grant_dimension`、`iam_grant_target`；
- `iam_menu`、`iam_role_menu`；
- `iam_audit_event`、`iam_permission_change_outbox`。

### V2 Admin API

增加 `iam_authentication_credential`、菜单路由/meta 字段、本地 admin fixture、系统权限和菜单种子。V2 中系统菜单 title 使用了英文展示文案，属于已知数据错误；迁移已应用后不可回改。

### V3 Dashboard

增加 Dashboard/Analytics/Workspace 菜单，正确使用 `page.dashboard.*` i18n key，component 路径也符合 Vben 约定。

### V4 Vben 菜单契约修正

以前向数据迁移把系统菜单 title 修正为 `system.*` key，并清除 System/Dashboard 一级目录的旧 `BasicLayout`。应用层同时引入 `VbenMenuContract`，阻止错误 title、清单外 PAGE component、旧 layout component 和不安全外链再次入库。

### V5 共享序列修复

以前向迁移按 sequence 当前值、10000 以及所有共享 `iam_id_seq` 的 IAM 表最大 ID 取最大值，修复 V3 只按菜单表回拨序列的问题；升级测试覆盖 V2 已有数据到 latest。

### V6 跨租户权限元数据

为 `iam_permission` 增加 `cross_tenant_mode`，默认 `SAME_TENANT_ONLY`；只有显式 `RELATED_PARTY_READ` 才进入关系授权分支。数据库约束禁止 FUND 权限配置为跨租户。

### V7 append-only Outbox

把 `iam_permission_change_outbox` 固定为不可更新/删除的事件事实，增加 eventId、aggregate/schema version、partition key 和 traceId；polling 的状态、租约、重试与错误移到 `iam_permission_change_relay_state`。事件插入会在同一事务初始化 relay state。当前仍没有 relay 进程，不能宣称消息已发布。

### 迁移纪律

- 所有已执行版本不可修改 checksum；
- 结构和数据修正新增 V4+；
- 同时测试空库从 V1 全量迁移，以及已有 V3 数据卷升级；
- 密码初始化只允许 local profile；V2 历史迁移中的固定 ID fixture 尚未拆分，生产启用 Flyway 前必须先盘点已执行环境并制定前向清理/拆分方案；
- 菜单 component 和 i18n key 属于跨端协议，迁移前要有契约校验。

## 7. Redis 与缓存现状

- Sa-Token Redis：保存 Sa-Token 会话/登录状态；
- `iam:login-attempt:<sha256>`：15 分钟窗口内最多 5 次失败；
- `iam:grant:{tenantId}:{membershipId}:v{permissionVersion}`：HTTP PEP 使用的版本化 GrantSnapshot，TTL 5 分钟，解码后再次核验 tenant/membership/version；
- 快照携带当前角色 Grant 的最近 `valid_from/valid_until` 边界；到达边界即使 permissionVersion 未变化也强制回源，避免定时授权多放或少放 5 分钟；
- 权限版本变化后新请求使用新 key，旧版本快照不能被当前 subject 命中。

重要事实：Admin HTTP PEP 已装配版本化 Redis Grant 缓存；`/api/auth/codes` 的权限码集合仍只用于 UI。跨租户 Party/Relationship Provider 与业务列表 `DataScopePlan` 尚未接入，所以相关路径继续 fail closed。

## 8. 本地运行

从仓库根目录启动基础设施：

```bash
docker compose -f infra/docker-compose.local.yml up -d
```

PostgreSQL：`127.0.0.1:15432/payment_platform`，用户/密码 `payment_dev / payment_dev`。Valkey（Redis 协议）：`127.0.0.1:16379`。镜像使用精确 tag + manifest digest。

构建和运行：

```bash
cd backend
./mvnw -s maven-settings.xml clean verify
./mvnw -s maven-settings.xml -pl applications/admin-api -am package -DskipTests
java -jar applications/admin-api/target/admin-api-0.1.0-SNAPSHOT.jar --spring.profiles.active=local
```

local 默认仅本机访问，Admin API 为 `http://127.0.0.1:8080/api`；local profile 会为 V2 bootstrap 账号初始化 `admin / Admin@123456`，密码可由环境变量覆盖。固定身份行仍位于基础迁移，并非 local-only migration，见上方迁移纪律。

## 9. 测试地图

- Core：认证、授权默认拒绝、资金权限加固、grant 缓存、数据范围、RoleGrant 管理；
- PostgreSQL adapter：权限目录和 grant repository；
- Redis adapter：权限快照缓存；
- Sa-Token adapter：可信 session 属性和 stale session；
- Admin API：Testcontainers 下的登录、菜单和管理接口契约集成测试。

最低全量命令：

```bash
cd backend
./mvnw -s maven-settings.xml clean verify
```

涉及 Flyway 还要验证空卷和已有卷升级；涉及前端契约还要运行前端契约测试和真实浏览器联调。

## 10. 当前风险与扩展点

- Admin API 已有默认拒绝的 method/path 权限注册表和完整授权服务，但仍是手工登记；新增 endpoint 必须同步策略与回归测试。
- Admin CRUD 使用服务端构造的 tenant 资源上下文；跨租户 Party/Relationship、订单授权视图和列表 DataScopePlan 尚未接入。
- `cross_tenant_mode` 已落库，但当前没有权限被标为 `RELATED_PARTY_READ`，也没有关系适配器，因此现有运行时不会开放跨租户访问。
- menu component 由前后端白名单与契约测试共同约束，发布新组件时仍需同步两端清单。
- `meta_json` 是必要扩展点，但 title、icon、order 等已知字段应增加结构验证。
- `SystemAdministrationController` 同时承担多资源 DTO/映射，继续扩展会形成浅而宽的入口层。
- 资金权限核心已有模型和测试，但不得在完成 [迁移计划](../permission/09-migration-plan.md) 的门禁前直接接入真实资金写路径。

## 11. 改动检查清单

- [ ] application 只做组合与传输，规则留在 Identity core。
- [ ] tenant/membership/operator 来自可信 session。
- [ ] 新 API 有稳定权限码、默认拒绝和审计策略。
- [ ] Long ID、分页、envelope 与前端契约一致。
- [ ] 角色/权限变化推进版本并定义缓存失效。
- [ ] 新迁移未修改旧版本，已测试空库与升级。
- [ ] 资金或数据范围变化阅读权限设计与产品需求。
- [ ] 执行相关模块测试和 `clean verify`。
- [ ] 同步更新接口契约和上下文文档。

## 12. 证据索引

- 聚合与版本：`backend/pom.xml`、各模块 `pom.xml`。
- 启动与组装：`applications/admin-api/src/main/java/.../AdminApiApplication.java`、`config/*`。
- HTTP 与契约：`applications/admin-api/src/main/java/.../web/*`。
- Core：`modules/identity/core/src/main/java/.../{domain,application,datascope,service,port}`。
- PostgreSQL：`modules/identity/persistence-postgres/src/main/java`、`src/main/resources/db/migration`。
- Redis：`modules/identity/cache-redis/src/main/java`。
- Sa-Token：`modules/identity/session-satoken/src/main/java`。
- 领域语言：`backend/modules/identity/CONTEXT.md`。
- 目标权限设计：`docs/ai-context/permission`。
