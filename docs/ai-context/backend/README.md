# 后端工程上下文

> 适用目录：`backend/**`
> 当前事实基线：2026-08-04 工作区
> 注意：本文记录已实现事实；目标与取舍以已接受 ADR 和目标架构为准。代码与本文冲突时必须修代码或显式修改决策，不能把偶然实现反写成架构。

## 1. 技术基线与运行单元

- Java 25（Maven Compiler release 25；Enforcer 固定 `[25,26)`）；
- Spring Boot 4.1.0；
- Maven 3.9.9 Wrapper 多模块 reactor；
- jOOQ 3.21.5，生成模型来自真实 PostgreSQL 18 迁移链；
- Sa-Token 1.45.0 Boot 4 starter；
- PostgreSQL 18.4 + Flyway 12.4；
- Valkey 7.2.13（Redis 协议兼容，BSD-3-Clause）；
- PostgreSQL JDBC 42.7.11；测试依赖跟随 Spring Boot BOM，API 集成测试使用 Testcontainers。

根 POM 聚合 Identity 上下文、三个可部署 API 组合根和进程级边界测试：

```text
backend
├── applications/platform-admin-api                 PLATFORM 管理 API，默认 8080
├── applications/merchant-admin-api        MERCHANT 最小后台 API，默认 8082
├── applications/agent-admin-api           AGENT 最小后台 API，默认 8083
├── modules/identity                       Identity 业务上下文及所属适配器
└── tests/iam001-blackbox                  三个独立 JVM 的外部边界验收
```

`applications` 必须只表示可启动部署单元。Identity 的用户、登录、角色、菜单和权限规则属于 `modules/identity`，不能再回到 application Controller 或建立 `applications/identity-authorization` 业务模块。

## 2. 模块与依赖边界

| 模块 | 职责 | 可以依赖 | 不应承载 |
| --- | --- | --- | --- |
| `applications/platform-admin-api` | Spring Boot 启动、Bean 组合、HTTP/CORS/安全拦截、DTO、异常 envelope、本地 fixture 入口 | identity 的 core 与 adapters | 领域规则、jOOQ 查询细节 |
| `applications/merchant-admin-api` | MERCHANT 独立启动、固定账号域和最小 HTTP 暴露面 | identity 的 core 与 adapters | PLATFORM 管理 API、领域规则、jOOQ 查询细节 |
| `applications/agent-admin-api` | AGENT 独立启动、固定账号域和最小 HTTP 暴露面 | identity 的 core 与 adapters | PLATFORM 管理 API、领域规则、jOOQ 查询细节 |
| `identity/core` | 身份、授权、数据范围模型；应用服务；外部端口 | JDK 和内部领域代码 | Spring、jOOQ、Redis、Sa-Token |
| `identity/persistence-postgres` | jOOQ repository、生成表模型、Identity 表和 Flyway | identity-core、jOOQ | 其他业务上下文的表 |
| `identity/cache-redis` | 权限快照缓存、登录失败限流 | identity-core、Redis 抽象/adapter | 业务真相、会话真相 |
| `identity/session-satoken` | 登录会话签发、可信 session 属性、session 版本校验 | identity-core port、Sa-Token | 权限业务决策 |
| `identity/oidc-bff` | 服务端 OIDC Code + PKCE、token 校验、Redis 单次事务、可信 Host/handoff、外部身份映射与 RP logout | identity adapters、Spring Security OAuth2/JWT | Realm 业务配置、浏览器 token、跨账号域选择 |

依赖方向：

```text
platform-admin-api | merchant-admin-api | agent-admin-api composition root
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
- 跨资源归属租户默认拒绝；只有受控 `READ/VIEW` action 可显式标为 RELATED_PARTY_READ，并同时命中商户/客户范围和可信关系证据；未知或写 action 一律 fail closed；
- FUND 权限由领域模型和数据库约束固定为 SAME_TENANT_ONLY，并需要可信目录、step-up 与审批约束；
- 数据范围 SQL 参数化，列名只能来自服务端白名单。

### `application` 与 `datascope`

- `DefaultAuthorizationService`：根据 subject、permission、resource 和 grants 作允许/拒绝决定。
- `CachedPermissionGrantLoader`：按 tenant + membership + permissionVersion 读取/回源权限快照。
- `DefaultDataScopePlanner`：把 grant 维度规划为结构化范围。
- `PermissionDataScopeInterceptor`：数据范围执行边界。
- `StructuredPredicateCompiler`：把结构化 predicate 编译为参数化 SQL。
- `WhitelistedColumns`：阻止调用方控制 SQL 列名。

Admin CRUD 的 HTTP PEP 已接入 `DefaultAuthorizationService` 和版本化 GrantSnapshot。业务详情/列表的商户、市场、渠道、关系和 resource-owner tenant 尚无真实业务表，因此 `DataScopePlan` 仍未接到支付查询；该缺口不能用 UI 权限码代替。

### `service`

- `AuthenticationService`：用户名标准化、失败限流、恒定风格密码校验、会话签发。
- `IdentityAdministrationService`：当前用户、权限码、可见菜单、用户/角色/部门/菜单管理的应用门面。
- `RoleGrantAdministrationService`：原子 RoleGrant 写入、版本推进和缓存失效的业务边界。
- `RoleConfigurationAdministrationService`：把普通角色字段、导航菜单和可表达 RoleGrant 作为一个版本化原子配置写入。
- `IdentityModels`：应用层 command/query/result records，不是 HTTP DTO。

### `port`

端口按能力拆分：Identity 查询、用户/角色/部门/菜单管理、权限目录、grant 仓库与缓存、membership 版本、部门层级和跨域关系范围。Controller 不能越过 service 直接调用这些端口。

## 4. 可启动 Admin API

### Composition roots

- `AdminApiApplication`、`MerchantAdminApiApplication`、`AgentAdminApiApplication`：三个独立 Spring Boot main，分别固定 PLATFORM、MERCHANT、AGENT 账号域。
- `IdentityConfiguration`、`MerchantAdminApiApplication`、`AgentAdminApiApplication`：三个组合根分别固定账号域并显式装配自己的 OIDC client credential、trace bridge、Sa-Token realm 和共享 OIDC BFF adapter；共享 adapter 不选择 Realm，也不持有任何环境的 client credential。RoleGrant 全量替换受默认关闭的 `payment.permissions.legacy-administration-cutover-complete` 控制；Sa-Token 安全属性由 Boot auto-configuration 在 ApplicationContext 创建期绑定，不使用启服后 `ApplicationRunner`。
- `LocalIdentityFixtureBootstrap`：仅在 `local` profile、Flyway 完成后事务性装载开发身份和 BCrypt 密码。
- `SecurityConfiguration`：Cookie 会话校验、可信 Origin、URL 到权限码映射、CORS 与安全响应头。
- `application.yml`：生产默认 fail-closed 的 DB、Redis、Flyway、jOOQ 和安全配置。
- `application-local.yml`：只绑定 `127.0.0.1`，启用本地自动迁移，并强制操作者显式提供本地 bootstrap 口令；不启用 baseline，缺少 Flyway history 的旧手工 V1 开发卷必须备份后重建。

### HTTP 层

- `LocalAuthController`、`BackofficeLocalAuthController`：只在 `local` profile 且 `payment.identity.local-login-enabled=true` 时注册 `/api/auth/login|logout`；非 local profile 禁止开启。每个组合根必须在 local password 与 OIDC 两种模式中恰好启用一种，否则启动失败。
- `AuthUserMenuController`：当前用户、权限码、运行时菜单和健康检查；`/user/info` 的 Web DTO 补齐空 `desc` 和固定非秘密 `cookie-session` marker，不把这些展示/适配字段下沉到 Core。
- `OidcBffController`：仅 `payment.oidc.enabled=true` 时在当前 PLATFORM、MERCHANT 或 AGENT 组合根注册 `/api/auth/oidc/start|callback|handoff|backchannel-logout` 与生产 logout；协议失败统一且不泄露身份存在性。
- `SessionSecurityController`：为有效 Cookie Session 返回服务端绑定的请求凭据；不接受客户端选择 Session 或账号域。
- `SystemAdministrationController`：`/api/system/user|role|dept|menu` 管理接口。
- `ApiResponse`：`{ code, data, error, message, traceId }`。
- `ApiExceptionHandler`：校验、认证、授权、冲突和内部异常转换。
- `RequestTrace`：每个请求 trace ID。

HTTP DTO 是 Controller 内部 record，Identity service 使用 `IdentityModels`。这是防止前端字段直接污染领域模型的边界，但 Controller 已较大；继续增加业务时应按资源拆 Controller 与 mapper，而不是让单文件无限增长。

所有 mutating `/api/**` 请求体最多 256 KiB，无 `Content-Length` 也会实际有界读取后判定；登录/Admin DTO 对字段长度和格式显式约束。菜单 meta 每容器最多 32 项、key 最长 64、string 最长 1024、最深 4 层、总 value 最多 128；部门/菜单树每 tenant 最多 2000 节点、最深 32 层。

## 5. 关键调用链

### 登录与会话

```text
POST /api/auth/login
  -> AuthUserMenuController
  -> AuthenticationService.login
  -> RedisLoginAttemptLimiter.acquire (atomic client + client/username reservation)
  -> CredentialLookup(jOOQ repository)
  -> LoginCredentialPolicy (BCrypt 2a/2b/2y, cost 10..14, exact encoded length)
  -> BCryptPasswordEncoder.matches
  -> RedisLoginAttemptLimiter.recordSuccess
  -> SaTokenSessionIssuer
  -> account-domain-specific HttpOnly Cookie
```

本地登录请求只接受 username/password；账号域由 composition root 注入 `AuthenticationService`，不能由 body/query/header 选择。登录成功把 `accountDomain/userId/membershipId/tenantId/departmentId/permissionVersion/sessionVersion/identityVersion/requestProof` 写入 Sa-Token session；外部登录另写入 Host、issuer、subject、OIDC Session 和初始为空的 `stepUpAt`。Core 在调用 BCrypt verifier 前先用统一 `LoginCredentialPolicy` 校验摘要。Session bridge 精确匹配 account domain、tenant、membership、user、credential，要求四者均为 `ACTIVE`，逐请求比较 permissionVersion、sessionVersion 和 identityVersion；本地会话继续要求可登录的 BCrypt 摘要，外部会话允许空摘要但必须精确匹配当前 `issuer + subject`，且 HTTP PEP 复核 `entryHost`。`stepUpVerified` 不从永久 boolean 读取，而是每次按 UTC 时钟校验 `stepUpAt` 是否在最近 10 分钟。任一域、映射或版本失效时，下一个已认证请求返回 401 `SESSION_INVALID` 并清 Cookie。

Sa-Token 配置：PLATFORM/MERCHANT/AGENT 分别使用 `platform-admin`/`merchant-admin`/`agent-admin` login type 和 `PAYMENT_PLATFORM_SESSION`/`PAYMENT_MERCHANT_SESSION`/`PAYMENT_AGENT_SESSION` Cookie；均为 8 小时总超时、30 分钟 active timeout、禁止并发共享、只读 Cookie、不读 Header/Body、HttpOnly、SameSite Strict。生产环境必须启用 Secure Cookie。

登录限流在查账号与 BCrypt 前使用 Redis Lua 原子预留两个 15 分钟桶：client 全局最多 30，client/username 最多 5。两个 key 使用同一 client digest hash tag，在 Redis Cluster 中同 slot；成功登录清理该账号桶并释放当次 client 预留。这可防止轮换用户名和并发绕过单桶，不等于已具备分布式 botnet 防护。

服务默认使用 `PAYMENT_FORWARD_HEADERS_STRATEGY=NONE`，不会把调用方自行提供的 `Forwarded`、`X-Forwarded-For` 等头当成客户端地址。这也是登录失败限流正确性的组成部分。只有当请求必经可信反向代理，且该代理会先剥离外部请求携带的全部 `Forwarded`/`X-Forwarded-*` 再写入自己的值时，部署方才可显式启用 forwarded-header 处理；不能仅因“部署在代理后”就打开。

三个组合根都已增加默认关闭且必须显式配置的生产 OIDC 流：登记 Host -> Authorization Code + S256 PKCE -> callback 单次消费 state -> 服务端 token exchange -> RS256/JWKS 及 issuer/audience/azp/nonce/ACR/time/sid 校验 -> 60 秒 Host-bound handoff -> `issuer + subject` 精确映射 -> Sa-Token Cookie。各服务使用独立环境变量、client credential、Cookie/login type 和账号域 Redis namespace；外部 Session 逐请求复核 Host、映射和 identityVersion。签名 back-channel logout 使用 issuer+sid 优先、无 sid 时 issuer+subject 的 Redis 索引撤销应用 Session，并以带所有者的短租约和完成标记处理并发及重放；OIDC callback/back-channel 均不依赖 Origin，browser handoff 仍要求可信 Origin，Cookie logout 还要求独立 CSRF。三 Realm bootstrap 已在全新 Keycloak 26.7.0 实例导入并验证三个 lifecycle service account；真实用户浏览器流、已有 Realm 漂移治理和生产故障演练仍未完成，不能据此宣称撤销链路已生产闭环。

### 请求鉴权

```text
/api/**
  -> security headers filter / trace
  -> AdminSecurityInterceptor
  -> 浏览器写请求校验 Origin；OIDC callback/back-channel 按协议豁免
  -> SaTokenSessionBridge.currentSubject（含 identityVersion/Host/issuer+subject）
  -> Cookie 写请求校验 Session-bound X-CSRF-Token
  -> AdminApiPermissionPolicy 精确匹配 HTTP method + path
  -> AdminAuthorizationEnforcer
  -> CachedPermissionGrantLoader
  -> Redis versioned snapshot / PostgreSQL grants
  -> DefaultAuthorizationService
  -> Controller
```

当前策略公开 `GET /api/auth/oidc/start|callback`、`POST /api/auth/oidc/handoff|backchannel-logout`、local-only `POST /api/auth/login` 和健康检查；callback 与 server-to-server logout 不依赖 Origin，后者必须通过 Logout Token 协议校验。`GET /api/auth/csrf`、用户信息、权限码、动态菜单和退出是 session-only；所有 Cookie 认证写请求同时要求可信 Origin 和 Session-bound `X-CSRF-Token`。系统 CRUD 使用精确 method/path 注册表和完整授权服务。未知路径、未知方法和相似前缀默认拒绝。`permissionCodes` 只服务 UI 展示，不再承担 HTTP PEP。`/user/info.systemAdministrator` 由当前 Membership 的 ACTIVE `system_role` 服务端计算，只用于让前端与角色委派策略共享身份事实，不能替代后端授权。Admin 资源上下文由服务端 Session 构造；未来业务详情/列表还必须从可信资源授权视图补齐 merchant、market、channel 和 resource-owner tenant。

### 动态菜单

```text
GET /api/menu/all
  -> trusted AuthorizationSubject
  -> IdentityAdministrationService.accessibleMenus
  -> jOOQ query by tenant/membership/role
  -> Controller 组树 + 解析 meta_json
  -> RouteRecordStringComponent[]
  -> 前端拒绝静态 core/fallback/local canonical name/path 冲突 -> Vben mixed route generation
```

`/menu/all` 只返回 ACTIVE DIRECTORY/PAGE/EMBEDDED/LINK，BUTTON 不进入动态路由。直接授权节点只在同 tenant 且 ACTIVE 的显式祖先链完整时返回，缺少的祖先会补齐但不带 sibling；祖先缺失、禁用、不是可路由类型或成环时整支 fail closed。

`local` profile 的应用级 bootstrap 另外在 User、Role、Menu、Department 页面下预置 19 个 ACTIVE BUTTON 权限目录节点，并保留 2 个 DISABLED/隐藏的旧 manage BUTTON；ACTIVE `auth_code` 与本地 19 个现代管理权限一一对应。管理接口 `/system/menu/list` 返回这些节点；它们不写入 `role_menu`，不进入 `/menu/all`，也不替代 RoleGrant。V15 expand 窗口内两个旧 Permission Catalog 码仍为 ACTIVE，但旧 BUTTON 不会重新启用。精确旧版 8 菜单无按钮、旧 14 BUTTON 或已执行 V14+V15 的过渡夹具可在 bootstrap 事务内收敛为 29 个菜单节点，任何部分升级或冲突数据继续失败关闭。

跨端强约定：

- `meta.title` 是前端语言包 key；
- `component_path` 是相对前端 `views` 的组件路径；
- PAGE component 必须出现在 `payment.menu.allowed-page-components`；
- 一级 catalog 不写 `BasicLayout`，由根路由统一承载布局；
- embedded/link 必须有内部 route path，component 固定为 `IFrameView`；`iframeSrc` 仅 EMBEDDED、`link` 仅 LINK 可用，且必须是带 host 的绝对 `http/https` URL，字段错配或其他菜单类型持有外链字段直接拒绝；
- route path 和 redirect 必须是单 `/` 开头的内部路径，拒绝 `//host/path` 这类 protocol-relative URL；
- 同一 tenant 的 canonical route name 按 `lower(COALESCE(NULLIF(BTRIM(route_name), ''), menu_name))` 唯一，canonical route path 按小写并去尾斜杠（根 `/` 例外）唯一；
- Long ID 转字符串；
- `meta_json` 无法解析时失败，不吞掉脏数据。

### 用户/角色/部门/菜单写操作

```text
SystemAdministrationController
  -> request record validation
  -> IdentityAdministrationService
  -> capability port
  -> capability-specific JooqUser/Role/Menu/DepartmentAdministrationRepository @Transactional
  -> lock ACTIVE PLATFORM tenant + actor identity tuple
  -> generated jOOQ tables and typed records
  -> IAM tables + audit + version bump
```

Controller 从可信 Session 构造 `AdministrationActor(membershipId, expectedUserId, expectedPermissionVersion, expectedSessionVersion)`，不接受浏览器提供这些字段。每个 Admin 写事务先锁定并校验当前 ACTIVE PLATFORM tenant，再以 `FOR UPDATE` 锁定 actor 对应的 Membership、User 和 Credential tuple；写入前重新确认 userId 归属关系、四态、受 V13 保证的可验证 BCrypt 凭证，以及 permissionVersion/sessionVersion。最后管理员判定还会在 Java 侧复用 `LoginCredentialPolicy`，即使数据库约束被旁路，非法 hash 也不能冒充备用管理员。任何主体状态或版本漂移都返回 401 `SESSION_INVALID`，注销会话并清 Cookie。

主体/版本复核本身不等于完整权限判定。RoleGrant PUT 额外在锁定 tenant、actor、目标 role 和 ACTIVE system role 后，以单条 PostgreSQL `statement_timestamp()` 查询重新验证 `role:view` 与 `role:grant-update`；角色 configuration PUT 在同一边界精确重验 `role:view/role:update/menu:view/role:grant-update`。两者都拒绝非 NORMAL/SAME_TENANT_ONLY、非精确 `TENANT/TENANT_ALL`、带 target、step-up 或 approval 的入口授权；双连接测试证明等待锁期间过期会 fail closed。User、普通 Role、Menu、Department 其他写接口尚未执行同等权限重验，有限 `valid_until` 的同类 TOCTOU 对这些接口仍是生产 **NO-GO / Required**；过渡期不得给这些写权限设置有限有效期。

创建用户是“全局 Identity + 当前 Tenant Membership”用例；Membership 只按请求预配置。配置 `payment.bootstrap-password` 的 `local` profile 使用同一运行时开发口令为每个新用户生成独立 BCrypt hash，并创建 ACTIVE User/Credential；未配置该属性的 profile 仍创建 `PENDING_ACTIVATION` User、`DISABLED` Credential 且不写 password hash。API 用 `identityStatus` 与 Membership `status` 分开表达；本地系统管理员可把 local identity 重置为该运行时开发口令，但当前仍没有生产邀请、首次改密、忘记密码或身份激活流程。普通管理员更新只修改 Membership；活动 PLATFORM 系统管理员可在同一事务中额外修改全局 username、display name、remark 和本地 Credential username，使用 user/identity/credential 三个版本防止覆盖。local issuer 的 `idp_subject` 随用户名同步，外部 IdP subject 拒绝本地改写，用户名改变推进该 User 全部未终止 Membership 的 sessionVersion。用户角色全集先校验同租户存在性，再由 added/removed diff 策略判断可分配性：新分配只接受 ACTIVE、未删除、assignable、非 system 角色；已有禁用普通角色可保留，只有活动系统管理员可将其移除；受保护角色不能通过普通流程增删。部门依赖采用同一规则：新建或改绑只接受 ACTIVE、未删除部门，编辑时可原样保留当前禁用部门，但不能把其他用户新绑到禁用部门。

Role 普通 update/status/delete 在 tenant/actor 锁之后以 `FOR UPDATE` 锁定目标角色，只允许 `system_role=false AND assignable=true`；受保护角色统一返回 422 `IAM_ROLE_NOT_ASSIGNABLE`。角色编辑使用 configuration PUT，在一个事务中锁定一次目标角色并替换字段、ACTIVE 可路由 `role_menu` 和可表达 RoleGrant，只递增一次 role/member 版本并写一组 audit/outbox；替换范围只包含当前 ACTIVE、未删除、可路由菜单，已禁用、BUTTON 或墓碑菜单的既有 `role_menu` 作为历史关系原样保留。RoleGrant GET 对 system/non-assignable、墓碑或含不可表达 Grant 的角色拒绝编辑。角色软删除设置 `status=DISABLED/deleted_at`，显式清除 membership_role 使授权立即失效，保留 role_menu/role_grant 历史；所有角色列表和有效授权 join 都排除墓碑。禁用但未删除角色保留在自身管理列表，依赖选择器只接收 ACTIVE、未删除记录。

Role、Department、Menu 的管理读模型显式返回 `rowVersion`；PUT/PATCH body 必须携带 `expectedVersion`，DELETE 通过 query 参数携带。User DELETE 同样要求把列表的 `userVersion` 作为 `expectedVersion`。Repository 的最终 jOOQ UPDATE 在同一个 WHERE 中比较 tenant、资源 ID 与 rowVersion，并原子递增版本。0 row 后在 tenant 写锁事务内区分：资源不存在返回 404 `RESOURCE_NOT_FOUND`，资源仍存在但版本过期返回 40902 `OPTIMISTIC_LOCK_CONFLICT`。树依赖、唯一约束等业务/数据库冲突单独映射为 40901 `DATA_CONFLICT`，不能复用乐观锁异常。

部门写入在 tenant 级数据库锁内执行：活动 Membership 只能落在活动部门；活动部门的父节点必须活动；禁用节点编辑时只能原样保留其当前禁用父节点，不能新建或改绑到禁用父节点；不能移动到自身后代；禁用/删除前会检查整个子树中的活动部门和活动 Membership。部门/菜单树每 tenant 最多 2000 节点、最深 32 层，查询、Web 组树和写入 candidate tree 都 fail closed。

菜单写入同样在 tenant 锁事务内执行：仓储按上述 canonical route name/path 语义预检，数据库 V9 unique index 作最终并发兜底；ACTIVE 菜单只能挂在 ACTIVE 直接父节点下；禁用节点编辑时只能原样保留其当前禁用父节点，不能新建或改绑到禁用父节点；禁用或逻辑删除祖先时检查完整后代树，存在任意 ACTIVE 深层后代即返回 409。查询型 `name-exists/path-exists` 只用于 UI 提示，不是完整性边界。

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

为 `iam_permission` 增加 `cross_tenant_mode`，默认 `SAME_TENANT_ONLY`；只有显式 `RELATED_PARTY_READ` 才进入关系授权分支。数据库约束禁止 FUND 权限配置为跨租户；V12 进一步要求该模式只能搭配 `read/view` action。

### V7 append-only Outbox

把 `iam_permission_change_outbox` 固定为不可更新/删除的事件事实，增加 eventId、aggregate/schema version、partition key 和 traceId；polling 的状态、租约、重试与错误移到 `iam_permission_change_relay_state`。事件插入会在同一事务初始化 relay state。当前仍没有 relay 进程，不能宣称消息已发布。

### V8 生产 fixture 隔离

以前向迁移移除 V2/V3 遗留的固定 Tenant、Admin、Department、Membership、Credential、Role、Grant 和 Menu，同时保留必需的 14 条全局 Permission Catalog。判断范围只覆盖预留 ID、自然键以及与该 fixture/tenant `1` 直接关联的行；其他租户、用户、审计、Outbox 和扩展权限不会阻止迁移，也不会被删除。预留键碰撞、固定数据被修改、必需权限被篡改或缺失、tenant `1` 出现额外依赖关系时，V8 在同一事务中回滚并要求人工分类，禁止用 `ON CONFLICT` 静默拼接真实主体。

本地开发数据不再属于 Flyway 生产路径。`platform-admin-api/src/main/resources/db/local/iam-local-bootstrap.sql` 只由 `local` profile 的 `LocalIdentityFixtureBootstrap` 在 Flyway 后执行。
精确匹配的预置部门允许 `row_version` 自然递增到任意非负值；ID、租户、父级、编码、名称、状态、备注和预留键碰撞仍按原规则失败关闭。
Local bootstrap 的 fixture 归属只由预留 ID、预留自然键/authCode、预置主体和预置主体自身关系确定。`assigned_by/created_by/updated_by` 只是审计来源，菜单 `parent_id` 只是树关系，二者都不能把管理员后续创建的数据扩大为 fixture。因而管理员创建的额外部门、用户、Membership、普通角色、Grant、菜单，以及普通角色对预置或新增菜单的合法展示关系会在重启后保留；直接修改预置行，或给预置 Membership/Role 增加非预置授权关系仍失败关闭。MERCHANT/AGENT 预置系统角色还必须保持 ACTIVE、未墓碑，并各自只有一条符合登录查询条件的 canonical 入口 Grant；有效期、额外维度、Target 或其他活动 portal Grant 任一漂移都会使整个 bootstrap 事务回滚。

### V9 菜单路由唯一性

在 tenant 内为 canonical name `lower(COALESCE(NULLIF(BTRIM(route_name), ''), menu_name))` 和 canonical path（小写、去尾斜杠，根 `/` 例外）建唯一索引。迁移 preflight 与索引使用同一表达式；发现历史重复 route name 或 route path 时整个 Flyway 迁移原子回滚，不猜测保留哪一条。运行时仓储在 tenant 锁事务内使用相同语义预检，数据库索引仍是最终并发约束。

### V10 Grant 维度/模式兼容性

数据库 `CHECK` 与 Core `DimensionScope` 使用同一允许矩阵；未列出的 dimension/mode 组合全部拒绝。V10 先以 `NOT VALID` 加约束，再执行 `VALIDATE CONSTRAINT`，因此历史非法授权行会让迁移失败，不会被静默改写成另一种权限。

### V11-V13 安全边界前向约束

- V11 限制菜单外链字段的类型、菜单归属和绝对 `http/https + host` 协议；历史危险值阻断升级；
- V12 只允许 `read/view` action 使用 `RELATED_PARTY_READ`，历史写权限错配阻断升级；
- V13 只允许 NULL 或符合统一 `LoginCredentialPolicy` 的 BCrypt hash（`2a/2b/2y`、cost 10..14、53 字符编码体），历史非法摘要阻断升级。

三个迁移都使用 `NOT VALID` 后 `VALIDATE CONSTRAINT`，不静默清洗安全语义不明确的历史数据。

### V14-V16 管理权限展开与滚动兼容

V14 建立 19 个现代管理权限和 `role:grant-update` 管理面，并把可证明为单一 `TENANT/TENANT_ALL`、无 target、无有效期的旧 manage Grant 展开为细粒度 Grant。V15 修复 V14 的升级兼容缺口：对带有效期、多维度或 target 的旧 Grant 按原始范围和元数据克隆现代等价 Grant；恢复两个旧 manage Permission 供旧二进制滚动读取；递增所有受影响 role 与 membership 版本，并逐角色写审计、逐成员写 Outbox。

旧 Grant 在 V15 期间作为兼容影子保留。现代 RoleGrant GET 忽略且不返回这两个已知影子；生产默认令 `PAYMENT_LEGACY_ADMINISTRATION_CUTOVER_COMPLETE=false`，因此 GET 返回只读且 PUT 返回 40903。只有 N-1 实例和旧调用方清零、双版本验证与生产审批完成后，部署方才可显式打开开关；第一次 PUT 才会原子停用目标角色全部旧/新 ACTIVE Grant 后写入现代全集。local profile 无 N-1 共存，默认开启用于本地验收。旧码不绑定当前 endpoint、不进入 grantable 目录，也没有 ACTIVE BUTTON。最终停用旧 Permission/Grant 仍需要独立 contract 迁移，当前不得宣称滚动迁移已经闭环。

V16 是只增不改的前向守卫：已执行的 V14/V15 不回写；V16 精确核验 21 条管理 Permission 的固定 ID、code/resource/action、风险、维度、step-up、approval、跨租户模式和状态，额外业务 Permission 不受影响。目录漂移会原子阻断升级，不自动修复权限事实。V15 已提交且可能已经执行，因此 V16 只能让停在 V15 的漂移库无法升级，并由 Schema readiness guard 阻止应用启动；它不能让已成功执行的 V15 事后回滚。

### V17 管理资源墓碑

V17 为 `iam_role/iam_menu/iam_department` 增加 `deleted_at`，为菜单和部门增加 `system_managed`，并把角色 name、菜单 canonical name/path 的唯一索引改成只约束 `deleted_at IS NULL` 的 live rows。迁移不删除历史业务行；local bootstrap 只对精确预置 ID 设置 system-managed 标记。旧二进制不理解 tombstone，因此写入墓碑后的数据库只能前向恢复，不能依赖回滚旧应用继续写入。

### V18-V20 三账号域约束与入口授权

V18 为 Tenant、User、Credential、Membership 增加 `account_domain` 并用组合外键拒绝跨域关系；V19 为三个组合根增加服务端维护的入口 Permission/Grant；V20 收敛保留 grant key 冲突并保留原授权语义、审计和版本证据。登录入口固定账号域，客户端不能提交 tenant、realm 或 portal 切换后台。

### V21-V24 生产身份数据与恢复基础

V21 增加 User `identity_version` 和 IdP provisioning 状态、服务端管理的 `iam_tenant_entry_host`，以及独立的 append-only `iam_identity_lifecycle_outbox` 与 mutable relay state。生命周期事件只允许 user、tenant、realm、操作类型、幂等键和时间，不提供可保存邮箱、邀请 Token、密码、TOTP Secret 或 Recovery Code 的 payload 字段；`issuer + subject` 唯一约束继续沿用 V1。

用户名迁移处于 expand 阶段。V21 先证明旧的全局 `uk_iam_authentication_username` 仍存在；V22 使用 `CREATE UNIQUE INDEX CONCURRENTLY` 建立 `(account_domain, username)` 唯一索引；V23 将其附加为 `uk_iam_authentication_domain_username`。运行时代码已按账号域预检用户名冲突，但旧全局约束尚未删除，因此跨域同名仍会被数据库拒绝。只有旧实例清零和 N/N-1 兼容证据通过后，才能用新的 contract 迁移删除旧约束。

V24 追加 `iam_mfa_recovery`，把 MFA 恢复建模为四步 durable state machine。请求事务写独立 lifecycle Outbox 后立即阻断目标身份并推进 identity/session version；relay 按 Keycloak MFA Credential、Recovery Code、Keycloak Session、应用 Session 顺序执行，使用行租约、`SKIP LOCKED`、有界错误码和退避重试。数据库 CHECK 禁止在四个完成时间齐备前写 `COMPLETED`，partial unique index 禁止同一 User 同时存在两个 pending recovery。该表没有 profile/secret payload 字段。

V22 的 sidecar 明确 `executeInTransaction=false`。三个应用、测试 helper 和 jOOQ codegen 都关闭 PostgreSQL transactional advisory lock，避免非事务并发索引等待 Flyway 自身事务锁。生产迁移 Job 必须使用同一设置；V22 异常中断后先检查同名索引是否 `indisvalid=false`，仅删除该精确无效索引后再重试，不得直接 `repair` 掩盖未完成 DDL。

### 迁移纪律

- 所有已执行版本不可修改 checksum；
- 结构和数据修正新增前向版本；
- 同时测试空库从 V1 全量迁移、历史版本升级，以及各拒绝路径；V21-V24 还要覆盖 IdP 状态回填、跨域 Host/Outbox 原子拒绝、事件不可变、expand 前置约束、账号域用户名唯一性和 MFA 四步完成约束；
- 密码和固定身份初始化只允许 local profile；已有库先按 [V8 fixture 隔离迁移手册](../../runbooks/iam-v8-fixture-isolation.md) 盘点。无关真实数据可原样保留，只有落入预留 footprint 或依赖 tenant `1` 的历史数据才需要单独的前向迁移；
- 菜单 component 和 i18n key 属于跨端协议，迁移前要有契约校验。

生产 Web 进程默认不执行 Flyway，迁移必须由独立部署 Job 先完成；仓库尚未实现该 Job/CD 编排，因此生产仍为 **NO-GO**。应用仍在接流量前运行只读 Schema 门禁。门禁同时使用 `validateWithResult()`、显式 `info().pending()` 和全部 versioned migration 状态检查：当前二进制的 pending/missing/failed/future/checksum/description/type 漂移都会终止启动。失败异常只携带稳定原因码，不包含连接信息或 Flyway 原始错误。门禁绝不执行 `migrate`/`repair`。local profile 由 Boot 数据库初始化依赖保证自动迁移先于同一门禁，且不会推断 baseline。只有先建立 expand/contract 约束和 N/N-1 双版本真实数据库兼容门禁，才可评估放宽 `FUTURE_SUCCESS`；当前不承诺滚动回滚。

### jOOQ 生成纪律

- Flyway 是唯一 Schema 真相；禁止从开发、共享或生产数据库反向生成代码。
- `-Pjooq-codegen` 只连接一次性 PostgreSQL 18 数据库，完整执行迁移后生成 `persistence.jooq.generated`。
- 生成源码提交入库；普通编译不依赖在线数据库。
- 后端 CI 会在干净 PG18 上重新生成并执行 `git diff --exit-code`，模型漂移直接失败。
- Repository 使用生成字段、类型化 `Condition`、`JSONB`、数组和 `OffsetDateTime`；原始字符串 SQL 必须单独说明并有真实 PG 集成测试。

## 7. Redis 与缓存现状

- Sa-Token Redis：三个 login type 使用各自 Cookie/login/session key 前缀保存会话和登录状态；
- `iam:{platform|merchant|agent}:login-attempt:{client-sha256}:client`：账号域独立的 15 分钟 client 桶，最多 30 个已失败/在途尝试；
- `iam:{platform|merchant|agent}:login-attempt:{client-sha256}:username:<username-sha256>`：账号域独立的 client/username 桶，最多 5；同一请求的两个 key 同 hash slot，Lua 原子检查并预留；
- `iam:{platform|merchant|agent}:grant:{tenantId}:{membershipId}:v{permissionVersion}`：账号域独立的版本化 GrantSnapshot；只有不含 temporal boundary 的快照才进入 Redis，TTL 5 分钟，解码后再次核验 domain/tenant/membership/version；
- `iam:{account-domain}:oidc:transaction:{sha256(state)}` 与 `...:handoff:{sha256(code)}`：PLATFORM 已接入的单次 OIDC state/PKCE 事务和 Host-bound handoff，默认 TTL 分别为 5 分钟和 1 分钟；Redis key 不保存原始 bearer 值，读取使用原子 GETDEL；
- `iam:{account-domain}:oidc-session:{sid|sub|event}:{sha256(...)}`：PLATFORM 外部 Session 的 sid/subject 撤销索引与 logout event 状态；原始 issuer、subject、sid、jti 不进入 key，处理租约使用 owner CAS，Session 索引 9 小时、完成重放标记 24 小时；
- 快照携带当前角色 Grant 的最近 `valid_from/valid_until` 边界；只要该边界存在就完全绕过 Redis，每个请求都回源，禁止应用节点 Clock 延长或提前截断数据库授权时间；
- PostgreSQL 回源在单条 SQL、同一 MVCC statement snapshot 内同时校验 ACTIVE Membership、permissionVersion、角色/权限状态和时间边界，并使用数据库 `statement_timestamp()` 作为统一判定时间；
- 登录凭证查询还要求 Membership 通过角色持有当前 composition root 的 `backoffice:{platform|merchant|agent}-access` Grant；该 Grant 必须是服务端维护的 canonical `system-backoffice-access`、`TENANT/TENANT_ALL` 记录。V19 回填历史角色，V20 把 V17 可能合法存在的同 key 普通 Grant 确定性重命名并保留全部授权语义、审计和版本证据；两条角色创建事务为新角色生成 canonical Grant。18 项授权编辑器不返回或替换它，读取发现普通 Permission 再占保留 key 或 portal 存量错误时只读失败。ACTIVE Membership 本身不能进入后台。缓存 payload 以账号域前缀编码并在解码时复核，复制到另一账号域 key 的快照会被拒绝；
- 单次快照最多接受 4096 条 grant/dimension/target 明细，超限直接拒绝，避免无界授权展开拖垮请求；
- 权限版本变化后新请求使用新 key；无时间边界的 cache-hit 返回前再次读取 permissionVersion，最终复核前已提交的撤权会丢弃旧命中并按新版本有界重试一次，复核后提交属于已进入处理的请求。若 Session 中 permissionVersion 最终仍旧，统一返回 401 `SESSION_INVALID`、注销会话并清 Cookie，调用方必须重新登录取得新主体版本。

重要事实：Admin HTTP PEP 已装配版本化 Redis Grant 缓存；`/api/auth/codes` 的权限码集合仍只用于 UI。跨租户 Party/Relationship Provider 与业务列表 `DataScopePlan` 尚未接入，所以相关路径继续 fail closed。

## 8. 本地运行

从仓库根目录启动基础设施：

```bash
docker compose -f infra/docker-compose.local.yml up -d
```

PostgreSQL：`127.0.0.1:15432/payment_platform`，用户为 `payment_dev`，本地密码 sentinel 为 `disabled`。Valkey（Redis 协议）：`127.0.0.1:16379`，同样使用每次启动都生效的 `--requirepass disabled`。该 sentinel 是仅用于 loopback 开发服务的公开非秘密值，不得用于生产。镜像使用精确 tag + manifest digest。

Flyway 只负责 schema 前向升级，不会轮换已有 `payment-web-platform-postgres18-data` 中的 PostgreSQL role 密码；修改 compose 的 `POSTGRES_PASSWORD` 也只影响新数据目录初始化。已有卷必须按 [`backend/README.md`](../../../backend/README.md#existing-local-volume-credential-alignment) 先执行 `pg_dumpall` 备份，再通过容器本地 trusted socket 原地 `ALTER ROLE`。只有数据明确可丢弃且备份已验证时，才可停止 compose 并删除这个精确 named volume；删除不可恢复。Valkey 的 `--requirepass disabled` 每次进程启动都会应用，不需要同类数据迁移。

构建和运行：

```bash
cd backend
./mvnw -s maven-settings.xml clean verify
./mvnw -s maven-settings.xml -pl applications/platform-admin-api -am package -DskipTests
printf 'Local bootstrap password: '
read -r -s PAYMENT_BOOTSTRAP_PASSWORD
printf '\n'
export PAYMENT_BOOTSTRAP_PASSWORD
java -jar applications/platform-admin-api/target/platform-admin-api-0.1.0-SNAPSHOT.jar --spring.profiles.active=local
unset PAYMENT_BOOTSTRAP_PASSWORD
```

local 默认仅本机访问，Admin API 为 `http://127.0.0.1:8080/api`；local profile 在 V8 后单独创建用户名 `admin`，但没有默认身份口令，启动时必须显式提供 `PAYMENT_BOOTSTRAP_PASSWORD`。默认/生产 profile 不注册该组件，也不会创建活动管理员。

## 9. 测试地图

- Core：认证、授权默认拒绝、资金权限加固、grant 缓存、数据范围、RoleGrant 管理；
- PostgreSQL adapter：jOOQ 身份管理、权限目录、grant、session 四态校验、未来时间边界、菜单树/路由唯一性和授权维度约束；真实 PG18 测试覆盖 V9-V13 的成功升级、历史脏数据原子阻断和新写入拒绝，以及非法 hash 不能充当备用系统管理员；
- Redis adapter：权限快照缓存；
- Sa-Token adapter：可信 session 属性和 stale session；
- Admin API：Testcontainers 下的登录、菜单和管理接口契约集成测试；覆盖 Role/Department/Menu stale update/delete、User stale delete、40902 与 404 区分，以及管理列表 rowVersion。
- `/user/info` 契约：返回字符串 `userId`、空 `desc` 和固定 `cookie-session` marker；marker 不是 Sa-Token，也不是 refresh credential。

最低全量命令：

```bash
cd backend
./mvnw -s maven-settings.xml clean verify
```

涉及 Flyway 还要验证空卷和已有卷升级；涉及前端契约还要运行前端契约测试和真实浏览器联调。

## 10. 当前风险与扩展点

- Admin API 已有默认拒绝的 method/path 权限注册表和完整授权服务，但仍是手工登记；新增 endpoint 必须同步策略与回归测试。
- Admin CRUD 使用服务端构造的 tenant 资源上下文；跨租户 Party/Relationship、订单授权视图和列表 DataScopePlan 尚未接入。
- Admin 写事务会锁定 tenant 和 actor tuple 并复核主体状态、密码可用性及 session/permission 两个版本；RoleGrant PUT 和角色 configuration PUT 还会在锁后用数据库时间分别重验两项/四项入口权限。其他管理写接口尚未关闭 finite `valid_until` 的同类 TOCTOU，过渡期间禁止给这些权限配置有限过期时间。
- `cross_tenant_mode` 已落库，但当前没有权限被标为 `RELATED_PARTY_READ`，也没有关系适配器，因此现有运行时不会开放跨租户访问。
- menu component 由前后端白名单与契约测试共同约束，发布新组件时仍需同步两端清单。
- `meta_json` 已有容器、深度、key/string 和总 value 硬上限，外链字段也按菜单类型隔离；新增字段仍必须先定义跨端语义和测试，不得把任意 JSON 当成无约束扩展口。
- `SystemAdministrationController` 同时承担多资源 DTO/映射，继续扩展会形成浅而宽的入口层。
- Role、Department、Menu 与 User 管理写入已统一执行 optimistic version 契约；Local fixture 仍不是生产 provisioning；命中 V8 预留 footprint 冲突的历史库需要人工前向迁移，无关业务数据不受 V8 影响。
- 角色 `menuIds` 只是导航/展示，BUTTON authCode 只是目录展示绑定；统一角色配置 UI/API 仍分别写 `role_menu` 与 RoleGrant，不从任一方推导另一方。RoleGrant 写在生产默认受 legacy cutover 闸门禁用，N-1 清退、正式审批和演练完成前不得打开；三端 OIDC、step-up 和 MFA 恢复 relay 已接入，三份 Realm bootstrap 已在全新 Keycloak 26.7.0 实例验证导入，但仍缺邀请/通用 lifecycle、已有 Realm 漂移治理、可信审批证据、关系数据权限、审计拒绝/登录失败和生产级可观测性。
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
- 启动与组装：`applications/platform-admin-api/src/main/java/.../AdminApiApplication.java`、`config/*`。
- HTTP 与契约：`applications/platform-admin-api/src/main/java/.../web/*`。
- Core：`modules/identity/core/src/main/java/.../{domain,application,datascope,service,port}`。
- PostgreSQL：`modules/identity/persistence-postgres/src/main/java`、`src/main/resources/db/migration`。
- Redis：`modules/identity/cache-redis/src/main/java`。
- Sa-Token：`modules/identity/session-satoken/src/main/java`。
- 领域语言：`backend/modules/identity/CONTEXT.md`。
- 目标权限设计：`docs/ai-context/permission`。
- 审计整改状态：`docs/ai-context/backend/architecture-audit-remediation-2026-07-20.md`。
- V8 运维步骤：`docs/runbooks/iam-v8-fixture-isolation.md`。
