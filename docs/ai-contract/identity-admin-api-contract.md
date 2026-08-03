# Identity Admin API Contract

> 状态：已实现的本地原型契约，非生产认证与资金权限方案<br>
> 适用应用：`frontend/admin/apps/web-antdv-next`、`backend/applications/admin-api`<br>
> 复核日期：2026-08-01
> 事实优先级：已接受 ADR / 已批准契约 > 实现；集成测试证明实现现状，但无权把偶然实现升级为架构决策

本文分三层记录：

1. **Current Contract**：当前前后端已经实现并由代码或集成测试证明的行为；
2. **Target Prototype Contract**：本轮原型认可的边界和不变量；
3. **Compatibility Plan**：从当前原型走向可用于生产身份管理和支付数据权限的后续路径。

当前结论：登录、Cookie 会话、当前用户、19 个本地 system-admin 管理权限码、动态菜单以及用户/角色/菜单/部门管理页面和 API 已形成可启动原型。RoleGrant 已提供仅覆盖 18 个 NORMAL、TENANT/TENANT_ALL 管理权限的受限管理闭环；`local` profile 支持统一初始密码和系统管理员重置密码。外部 IdP、MFA、生产用户邀请/激活/重置、支付数据范围、资金权限和正式可观测性仍未完成。

---

# 1. Current Contract

## 1.1 已实现拓扑

```text
web-antdv-next
  -> /api（withCredentials=true）
  -> admin-api Spring Boot application
  -> method/path permission registry
  -> DefaultAuthorizationService
  -> versioned GrantSnapshot in Redis / PostgreSQL fallback
  -> jOOQ/PostgreSQL 18
  -> Sa-Token/Redis session
```

当前事实：

- `backend/applications/admin-api` 是可启动 Spring Boot composition root；
- 默认端口为 `8080`；
- `web-antdv-next` 已迁入用户、角色、菜单、部门页面和对应 API；
- 产品路由模式已由应用常量固定为 `mixed`，登录后使用 `/menu/all` 生成业务路由，本地只合并隐藏的 `Profile`；缓存偏好不决定路由模式；
- 本地 bootstrap 管理员的首选首页为 `/dashboard`；`/user/info` 只返回当前安全菜单树中存在的首选路径，否则回退到第一个可访问叶子，无业务菜单时回退到本地 `/profile`；
- 后端已有 Testcontainers/MockMvc 契约集成测试覆盖 Cookie、19 个 ACTIVE 管理权限码、角色授权、菜单、列表、状态 PATCH 和部分租户隔离场景。

## 1.2 Base URL、CORS 与可信 Origin

前端 API 方法使用 `/auth/login` 等相对路径，浏览器 base URL 为 `/api`：

```text
POST /api/auth/login
GET  /api/system/user/list
```

请求客户端统一配置：

```text
withCredentials = true
Accept-Language = 当前前端语言
```

后端 CORS：

- 只允许 `payment.security.allowed-origins` 中的精确 Origin；
- 默认本地值为 `http://localhost:5999,http://127.0.0.1:5999`；
- 允许 GET、POST、PUT、PATCH、DELETE、OPTIONS；
- `allowCredentials=true`；
- 允许请求头仅包含 `Content-Type`、`Accept-Language`、`X-Requested-With`；
- 不允许通配 Origin。

所有 POST、PUT、PATCH、DELETE 请求，包括登录和退出，都必须带受信任的 `Origin`。GET、HEAD、OPTIONS 不执行 Origin 写保护。

服务端 `forward-headers-strategy` 默认是 `NONE`。调用方传入的 `Forwarded`、`X-Forwarded-For`、`X-Forwarded-Proto` 等头默认不能改变后端看到的客户端地址或协议，避免通过伪造来源地址绕过登录限流。只有部署在可信边界代理之后，并确认代理会先删除外部请求携带的全部 `Forwarded`/`X-Forwarded-*`、再写入自身可信值时，才允许显式设置 `PAYMENT_FORWARD_HEADERS_STRATEGY` 启用处理。

## 1.3 Cookie session 与 marker

真实认证凭证只存在于 Sa-Token HttpOnly Cookie：

```text
Cookie name       PAYMENT_SESSION
Path              /
HttpOnly          true
SameSite          Strict
Secure            由 PAYMENT_COOKIE_SECURE 控制；非本地环境必须为 true
Absolute timeout  8 hours
Active timeout    30 minutes
Concurrent login  false
Shared token      false
```

Sa-Token 当前配置：

- 从 Cookie 读取会话；
- 不从 Authorization header 读取；
- 不从 request body 读取；
- 不把 token 写入响应 header；
- Session 保存服务端可信的 userId、membershipId、tenantId、departmentId、permissionVersion、sessionVersion。

上述属性由 `application.yml` 交给 Sa-Token Boot auto-configuration，在 ApplicationContext 创建期完成绑定；不存在等 Web Server 已接受请求后再修改全局安全配置的 `ApplicationRunner`。集成测试在 context 中核对最终 `SaTokenConfig` 值并断言该 runner bean 不存在。

登录响应仍为兼容 Vben 路由守卫返回：

```json
{
  "code": 0,
  "data": {
    "accessToken": "cookie-session"
  },
  "error": null,
  "message": "success",
  "traceId": "..."
}
```

`cookie-session` 只是非秘密 marker：

- 前端可以把它持久化，以判断是否需要进入鉴权流程；
- `formatSessionAuthorization()` 当前固定返回 `null`；
- 请求拦截器会删除 `Authorization`；
- 前端绝不发送 `Authorization: Bearer cookie-session`；
- 服务端也不接受 header/body marker 作为凭证；
- Cookie 失效而 marker 尚在时，后端返回 401，前端清理 marker 并回到登录流程。

`/auth/refresh` 当前没有后端实现，前端默认 `enableRefreshToken=false`，不得开启。

## 1.4 会话版本和租户来源

后端从 Cookie Session 恢复：

```text
userId
membershipId
tenantId
departmentId
permissionVersion
sessionVersion
stepUpVerified
```

约束：

- tenantId、membershipId、operatorId 不从浏览器 body/query/header 获取；
- 所有管理查询都强制使用 Session tenantId；
- Session bridge 以 session 中的 tenantId + membershipId + userId 单次精确查询当前 permissionVersion 与 sessionVersion，并校验 Tenant、User、Credential、Membership 全部 `ACTIVE`、Credential hash 受 V13 约束满足统一 BCrypt 格式/成本策略；任一版本失效时，包含 `/auth/codes` 在内的下一个已认证请求立即返回 401 `SESSION_INVALID`，服务端注销当前会话并清除 Cookie；Admin 写事务还会在锁后复核两个版本；
- Membership 更新、状态变化和终止会递增 Membership 的 sessionVersion 与 permissionVersion；
- 角色更新、状态变化和删除会递增该角色成员的 permissionVersion；
- 权限码从当前租户、当前 Membership 的有效 RoleGrant 读取。

当前 PUT/PATCH/DELETE 都只改变当前租户 Membership；不会修改或禁用全局 User/credential。这是已定版的多租户身份边界。全局身份资料、凭证状态和 IdP 生命周期必须由独立用例管理。

## 1.5 Vben envelope 和 trace

所有 Controller 使用：

```json
{
  "code": 0,
  "data": {},
  "error": null,
  "message": "success",
  "traceId": "uuid"
}
```

规则：

- 成功 `code=0`；
- 失败使用真实 HTTP status 和非零 numeric code；
- `error` 是机器错误码；
- `message` 是可展示的安全文案；
- response body 和 `X-Trace-Id` header 返回同一个请求级 UUID；
- 当前 trace 是进程内 ThreadLocal UUID，不是 OpenTelemetry trace，也未形成跨服务 correlation。

当前错误契约：

| HTTP | code | error | 当前场景 |
| --- | ---: | --- | --- |
| 400 | 40001 | `INVALID_REQUEST` | DTO 校验、JSON、ID、时间、状态等非法 |
| 401 | 40101 | `INVALID_CREDENTIALS` | 用户名或密码错误 |
| 401 | 40101 | `AUTH_REQUIRED` | 无有效 Sa-Token 会话 |
| 401 | 40102 | `SESSION_INVALID` | sessionVersion/permissionVersion 失效、actor 主体状态变化、密码不可用或 Session 不合法；服务端注销并清 Cookie |
| 403 | 40301 | `PERMISSION_DENIED` | 缺少动作权限、Origin 不可信或 API method/path 未登记 |
| 404 | 40401 | `RESOURCE_NOT_FOUND` | 当前租户下目标资源不存在，或非 API 资源未命中 |
| 409 | 40901 | `DATA_CONFLICT` | 数据依赖、业务不变量或数据库唯一约束冲突 |
| 409 | 40902 | `OPTIMISTIC_LOCK_CONFLICT` | 目标仍存在，但 expectedVersion 已落后；调用方必须重载后再决定是否重试 |
| 409 | 40903 | `LEGACY_ADMINISTRATION_CUTOVER_REQUIRED` | N-1 旧管理权限兼容期尚未结束，RoleGrant 全量替换被部署闸门拒绝 |
| 413 | 41301 | `PAYLOAD_TOO_LARGE` | mutating `/api/**` 请求体超过 256 KiB（含无 Content-Length 请求） |
| 422 | 42201 | `IAM_ROLE_NOT_ASSIGNABLE` | 系统角色、不可分配角色、自提权或越权委派被拒绝 |
| 422 | 42202 | `IAM_LAST_ADMIN_PROTECTED` | 禁用或移除最后一个活动系统管理员被拒绝 |
| 429 | 42901 | `LOGIN_RATE_LIMITED` | 15 分钟内 client+username 已失败/在途预留达到 5 次，或 client 全局已失败/在途预留达到 30 次 |
| 500 | 50001 | `INTERNAL_ERROR` | 未映射异常 |

平台租户写限制抛出的 `SecurityException` 已统一映射为 403 `PERMISSION_DENIED`，不会向调用方泄露租户类型判断细节。

## 1.6 ID、状态、分页和时间

### ID

- API response 中的 User、Role、Menu、Department ID 全部为 JSON string；
- path/query 中 ID 按正 Long 解析；
- `/system/user/**` 的 `{id}` 和列表 `id` 当前表示全局 `iam_user.id`，不是 membershipId；
- membershipId 当前不暴露给用户管理前端；
- userVersion 来自当前租户 Membership 的 `row_version`。

### Membership status 与 identityStatus

```text
status=1 = 当前租户 Membership ACTIVE
status=0 = 当前租户 Membership DISABLED

identityStatus=PENDING_ACTIVATION = 全局身份待激活
identityStatus=ACTIVE             = 全局身份正常
identityStatus=DISABLED           = 全局身份禁用
identityStatus=LOCKED             = 全局身份锁定
```

`status` 只表达当前工作区 Membership，`identityStatus` 表达全局 `iam_user`，两者不得合并展示或互相推导。创建用户时，Membership 仍按请求的 `status` 预配置。未配置 `payment.bootstrap-password` 时，全局 User 创建为 `PENDING_ACTIVATION`，Credential 创建为 `DISABLED` 且 `password_hash=NULL`；`local` profile 通过运行时 `PAYMENT_BOOTSTRAP_PASSWORD` 绑定该属性，为每个新用户生成独立 BCrypt hash，并把 User/Credential 创建为 ACTIVE。明文不得进入源码、数据库、响应、日志或构建产物。

当前没有生产可用的邀请、密码设置或身份激活流程，也没有对应 Admin API。测试中的直接 SQL 状态推进仅模拟未来受控激活边界，不是运维方案；正式激活流程上线前禁止人工把这些三项状态拼成可登录账号。

User DELETE 将当前租户 Membership 标记为 `TERMINATED`；Role、Menu、Department DELETE 是带 `deleted_at` 墓碑的软删除，同时把状态置为 DISABLED。墓碑行及其历史关系保留在数据库和审计中，但不得再出现在管理列表、选择器、动态菜单或有效授权查询中。单纯 DISABLED 且未删除的记录仍出现在自身管理列表，便于恢复，但不得作为其他模块的新依赖候选。

### Pagination

```text
page      默认 1，最小 1
pageSize  默认 20，允许 1..200
```

offset 使用 long 计算；当 `(page - 1) * pageSize` 超过当前仓储 int offset 参数上限时返回 400 `INVALID_REQUEST`，不会发生 int 回绕后查询错误页或落成 500。

响应固定为：

```json
{
  "items": [],
  "total": 0
}
```

### Request、DTO、meta 和树边界

- 所有 mutating `/api/**` 请求体最多 256 KiB；服务端读取有界字节，即使没有 `Content-Length` 也会在超限时返回 413 `PAYLOAD_TOO_LARGE`；
- Login DTO 限制 username 最长 100、password 最长 256、tenantId 为最多 19 位的正 Long string；Admin DTO 对名称、ID、备注、状态、集合和路由字段执行显式长度/格式约束；
- 菜单 `meta` 每个 map/list 最多 32 项，key 最长 64，string 最长 1024，嵌套最深 4 层，总 value 最多 128；数字必须有限，不支持的 value type 拒绝；
- 部门树和菜单树每个 tenant 最多 2000 节点、最深 32 层。仓储使用第 2001 行探测超限，Core 在 Controller 递归组树前检查节点数、深度、重复 ID 和环，create/update 对 candidate tree 使用同一上限。遇到存储中的超限/成环树时 fail closed，不构造部分树。

### Time query

`startTime/endTime` 当前接受：

- ISO Instant；
- ISO OffsetDateTime；
- `yyyy-MM-dd HH:mm:ss`，按 `payment.time-zone` 解释；
- `yyyy-MM-dd`，按同一配置时区计算边界，endTime 使用该日末尾。

生产默认时区为 UTC，本地 profile 默认 Asia/Shanghai；推荐跨系统调用始终传 ISO Instant 或 OffsetDateTime。

响应 `createTime` 来自 PostgreSQL `TIMESTAMPTZ` 的 ISO 字符串。

## 1.7 已实现认证和导航接口

### POST `/auth/login`

Request：

```json
{
  "username": "admin",
  "password": "******",
  "tenantId": "1"
}
```

当前实现：

- username trim 后转小写；
- `tenantId` 为可选正 Long string；只有一个活动 Membership 时可以省略；
- 同一用户有多个活动 Membership 时必须显式传 `tenantId`，否则返回与错误凭证相同的 401，避免静默选择工作区；
- 指定 tenant 不属于该用户时也返回相同 401，不泄露 membership；
- BCrypt cost 12 校验；
- 未找到用户时也执行 dummy hash 比较，降低账号枚举时序差异；
- 查账号与 BCrypt 前先由 Redis Lua 原子预留 client 全局桶和 client/username 桶，15 分钟窗口分别为 30 和 5；无法通过轮换 username 逃逸 client 全局桶，并发请求也必须先取得预留；
- 两个 Redis key 使用相同 client SHA-256 digest hash tag，在 Redis Cluster 中保持同槽；成功登录清除该 client/username 桶并释放当次 client 预留；
- 成功后创建 Sa-Token Session Cookie；
- 只有显式启用 `local` profile 才创建本地管理员；没有默认开发身份口令，启动时必须显式提供 `PAYMENT_BOOTSTRAP_PASSWORD`；默认/生产 profile 不注册 bootstrap 组件；
- Admin 登录页可在本地 Vite 开发进程中通过 `VITE_LOCAL_ADMIN_USERNAME/VITE_LOCAL_ADMIN_PASSWORD` 预填同一开发凭据，但仅 `DEV` 模式读取且不自动提交；真实值不得进入受版本控制的 `.env*`、源码或构建产物，生产模式默认值固定为空；
- 不记录或返回真实 token。

当前限制：没有外部 IdP、MFA、生产激活、首次改密或忘记密码流程；现有密码重置仅服务 `local` profile 的开发验收，不是生产身份生命周期能力。

### POST `/auth/logout`

- 需要有效 Cookie Session；
- 需要受信任 Origin；
- 服务端执行 Sa-Token logout；
- 前端无论退出 API 成败都会清理本地 store。

### GET `/user/info`

Response data：

```json
{
  "userId": "100",
  "username": "admin",
  "realName": "Platform Administrator",
  "avatar": "",
  "roles": ["platform-admin"],
  "homePath": "/dashboard",
  "desc": "",
  "token": "cookie-session",
  "systemAdministrator": true
}
```

`token` 只是前端 `UserInfo` 兼容所需的固定非秘密 marker，不是 Sa-Token 或 refresh credential。`systemAdministrator` 仅当当前 Membership 持有 ACTIVE `system_role` 时为 `true`，供界面与后端角色委派策略使用同一身份事实；它不替代后端鉴权。前端运行时校验完整响应，字段缺失或不是严格布尔 `true` 时按 `false` 处理，并忽略未知附加字段。不返回完整 RoleGrant、商户、市场或渠道数据范围。

`homePath` 是服务端根据当前 Membership 的安全菜单树解析后的落点，不直接透传持久化首选值。首选路径不在安全树中时返回第一个可访问叶子；没有任何业务菜单时返回本地保留路由 `/profile`。

### GET `/auth/codes`

返回当前 Membership 的有效权限码 `string[]`。查询要求：

- Role ACTIVE；
- RoleGrant ACTIVE 且处于有效期；
- Permission ACTIVE；
- 本轮 Permission 的 requiredDimensions 仅为 TENANT；
- Grant 有 `TENANT + TENANT_ALL` 维度。

### GET `/menu/all`

返回当前 Membership 的有效 Role 对应菜单树。`web-antdv-next` 已使用固定 mixed 路由模式：后端拥有业务路由，本地只合并隐藏的 `Profile`。该 Profile 仅只读展示 `/user/info` 已校验的姓名、登录账号、用户 ID 和角色；密码修改、MFA、手机号、邮箱和通知偏好尚无后端契约，页面不得展示演示状态或伪成功操作。响应只包含 ACTIVE DIRECTORY/PAGE/EMBEDDED/LINK，不包含 BUTTON。直接分配的路由节点会补齐同 tenant 且 ACTIVE 的显式祖先，不带入 sibling；祖先缺失、禁用、不是可路由类型或成环时，对应直接分配分支 fail closed。存储的 redirect 只有在目标仍存在于本次安全菜单树时才保留，否则父节点改为重定向到第一个可访问子节点；没有可访问子节点则不返回 redirect。后端若返回 route name `Profile` 或 canonical path `/profile`，前端在合并前 fail closed。

当前动态菜单来源是 Role -> role_menu -> Menu；按钮权限仍由 `/auth/codes` 决定。菜单展示关系不等于业务授权。

Vben 路由不变量：

- `meta.title` 必须是至少包含一个 `.` 的 i18n key，例如 `system.title`；
- PAGE component 必须来自当前应用维护的显式页面清单，格式不含 `views/` 和 `.vue`；
- 一级 catalog 不返回 `BasicLayout`，因为根路由已经统一提供布局；
- route path 与 redirect 必须是单 `/` 开头的内部路径，拒绝 `//evil.example/path` 这类 protocol-relative URL；
- 响应中的 redirect 目标必须属于当前安全菜单树；工作台快捷导航只显示本次已注册的动态路由，不得通过静态快捷入口重新暴露无权限页面；
- `iframeSrc` 只允许 EMBEDDED 持有，`link` 只允许 LINK 持有；都必须是带 host 的绝对 `http/https` 地址，字段互换或者 catalog/menu/button 持有任一外链字段都拒绝；
- 写菜单时前端和后端都会校验，但后端是最终完整性边界。

## 1.8 本轮 19 个当前管理权限码

当前数据库迁移、后端拦截器和前端常量一致：

```text
user:view
user:create
user:update
user:delete
user:disable
user:assign-role
role:view
role:create
role:update
role:delete
role:grant-update
menu:view
menu:create
menu:update
menu:delete
department:view
department:create
department:update
department:delete
```

其中除管理入口自身使用的 `role:grant-update` 外，其余 18 个 NORMAL、TENANT、`TENANT_ALL` 权限构成平台管理员可向普通角色分配的精确目录。V15 expand 阶段暂时把历史 `menu:manage`、`department:manage` Permission Catalog 记录保持为 ACTIVE，只供旧二进制在滚动兼容窗口继续鉴权；当前 endpoint、前端按钮和新授权都不绑定这两个码，它们也不出现在 18 项可分配目录中。旧码最终停用必须通过后续独立 contract 迁移完成，不能回写 V14/V15。

当前后端方法映射按实际 endpoint 精确匹配，不使用 `startsWith`：

| Endpoint group | GET | POST | PUT | PATCH | DELETE |
| --- | --- | --- | --- | --- | --- |
| `/api/system/user`、`/list`、`/{id}`、`/{id}/status` | `user:view` | `user:create` | `user:update` + `user:disable` + `user:assign-role` | `user:disable` | `user:delete` |
| `/api/system/user/{id}/password/reset` | — | `user:update` | — | — | — |
| `/api/system/role`、`/list`、`/{id}`、`/{id}/status` | `role:view` | `role:create` | `role:update` | `role:update` | `role:delete` |
| `/api/system/menu`、查询端点、`/{id}` | `menu:view` | `menu:create` | `menu:update` | — | `menu:delete` |
| `/api/system/dept`、`/list`、`/{id}` | `department:view` | `department:create` | `department:update` | — | `department:delete` |
| `/api/v1/iam/permissions/grantable` | `role:view` + `role:grant-update` | — | — | — | — |
| `/api/v1/iam/roles/{roleId}/grants` | `role:view` + `role:grant-update` | — | `role:view` + `role:grant-update` | — | — |
| `/api/v1/iam/roles/{roleId}/configuration` | — | — | `role:view` + `role:update` + `menu:view` + `role:grant-update` | — | — |
| `/api/v1/iam/roles/configuration` | — | `role:view` + `role:create` + `menu:view` + `role:grant-update` | — | — | — |

用户创建时 roleIds 非空，Controller 额外要求 `user:assign-role`。当前用户 PUT 同时提交部门、状态和角色最终全集，因此入口固定要求 `user:update`、`user:disable`、`user:assign-role`；不在事务外先读角色差异来决定是否鉴权，避免检查与写入之间的竞态。后续若要降低权限粒度，应把它拆成独立的部门、状态和角色命令，而不是恢复数据相关的预检查。

除 `POST /api/auth/login` 外，所有 API 都先要求有效 session；未在 `AdminApiPermissionPolicy` 登记的 method/path 即使已经登录也返回 403。已登记的系统 CRUD 由 `AdminAuthorizationEnforcer` 调用完整 `DefaultAuthorizationService`，使用版本化 Redis GrantSnapshot；`/auth/codes` 仅用于 UI 展示。

### 管理写操作的事务 actor

Controller 从可信 Session 构造：

```text
AdministrationActor(
  membershipId,
  expectedUserId,
  expectedPermissionVersion,
  expectedSessionVersion
)
```

这些字段不接受 body、query 或 header 覆盖。User、Role、Menu、Department 写事务先锁定并验证 ACTIVE PLATFORM tenant，再以 `FOR UPDATE` 锁定 actor 对应的 Membership、User、Credential tuple，并在真正修改前复核：

- membership 确实属于该 tenant 和 user；
- Tenant、User、Membership、Credential 均为 `ACTIVE`；
- Credential `password_hash` 非空且由 V13 CHECK 保证符合 `$2a/$2b/$2y`、cost 10..14、53 字符编码体；
- 当前 permissionVersion/sessionVersion 与请求 Session 捕获值一致。

锁后发现任一主体状态或版本漂移时，接口返回 401 `SESSION_INVALID`，注销服务端会话并清 Cookie。它关闭了主体禁用、密码失效和版本撤权发生在事务排队期间的写入窗口。

`PUT /api/v1/iam/roles/{roleId}/grants` 在锁定 tenant、actor、目标 role 和 ACTIVE system role 后，使用单条 PostgreSQL `statement_timestamp()` 查询重新验证操作者同时持有有效的 `role:view` 与 `role:grant-update`。角色编辑使用的 `PUT /api/v1/iam/roles/{roleId}/configuration` 在同一锁后边界精确重验 `role:view`、`role:update`、`menu:view`、`role:grant-update` 四项权限；角色新增使用 `POST /api/v1/iam/roles/configuration`，对应重验 `role:view`、`role:create`、`menu:view`、`role:grant-update`。新增和编辑都在单事务中写角色字段、导航关系和 RoleGrant。三个入口都只接受 NORMAL、SAME_TENANT_ONLY、精确 `TENANT/TENANT_ALL`、无 target、无 step-up/approval 的授权；失败时角色、Grant、菜单关系、审计和 Outbox 均不产生部分写入。

该关闭范围只覆盖 RoleGrant 替换写入。User、Role、Menu、Department 的其他写接口仍主要依赖事务前 HTTP PEP；若其管理权限 Grant 设置有限 `valid_until`，仍可能在等待写锁期间过期而 permissionVersion 不变。这一剩余边界继续是生产 **NO-GO / Required**，过渡期不得给这些管理写权限配置有限有效期。

本轮没有 `role:assign`、订单权限或资金权限。`role:grant-update` 只保护本节定义的受限角色授权管理 API，不允许作为普通角色的可分配权限。

## 1.9 User API

### GET `/system/user/list`

Query：

| Field | Match |
| --- | --- |
| `username` | 不区分大小写模糊匹配 |
| `name` | 不区分大小写模糊匹配 |
| `id` | 全局 userId 精确匹配 |
| `status` | 0/1 精确匹配当前 Membership 状态 |
| `deptId` | Department ID 精确匹配 |
| `startTime` | Membership createTime 下界 |
| `endTime` | Membership createTime 上界 |
| `page/pageSize` | 分页 |

User 列表当前不支持 remark query。

Response item：

```json
{
  "id": "100",
  "username": "admin",
  "name": "Platform Administrator",
  "deptId": "10",
  "deptName": "Head Office",
  "roleIds": ["2000"],
  "roleNames": ["Platform Administrator"],
  "status": 1,
  "identityStatus": "ACTIVE",
  "userVersion": 0,
  "identityVersion": 0,
  "credentialVersion": 0,
  "remark": "",
  "createTime": "2026-07-17T00:00:00Z"
}
```

当前没有 `GET /system/user/{id}` 详情接口；编辑和详情 Drawer 使用列表行数据。

### POST `/system/user`

```json
{
  "username": "alice",
  "name": "Alice",
  "deptId": "10",
  "roleIds": ["2000"],
  "status": 1,
  "remark": ""
}
```

规则：

- roleIds 必须存在且不能为 null；
- `[]` 明确表示无角色；
- roleIds 最多 256 项，HTTP DTO 和 Core 都会拒绝超限请求；
- roleIds 非空时额外检查 `user:assign-role`；
- 仅持有用户创建及表单依赖权限、没有 `user:assign-role` 时，前端必须显式提交 `roleIds=[]`，不能静默取消创建；
- 新分配的 Role 必须在当前租户、ACTIVE、`assignable=true` 且 `systemRole=false`；
- 创建 User、Membership 和 credential row，返回全局 userId string；Membership 只按请求的 `status` 预配置；
- `local` profile 使用运行时统一初始密码生成每个账号独立的 BCrypt hash，并创建 ACTIVE User/Credential；未配置初始密码的其他 profile 保持 `PENDING_ACTIVATION` User、`DISABLED` Credential 和空 hash；
- 统一初始密码只服务本地开发验收，不是生产邀请、激活或首次改密方案。

### PUT `/system/user/{userId}`

普通管理员请求只包含当前租户 Membership 可变字段：

```json
{
  "deptId": "10",
  "roleIds": ["2000"],
  "status": 1,
  "userVersion": 0
}
```

规则：

- roleIds 是最终全集；
- roleIds 最多 256 项；
- 入口始终要求 `user:update`、`user:disable`、`user:assign-role`；
- 新增的角色必须是当前租户 ACTIVE、assignable、非 system 的普通角色；已有禁用普通角色可原样保留或由系统管理员移除，但不能分配给其他用户；system/non-assignable 受保护角色和目录缺失的历史关系由普通编辑流程只读保留；
- 系统管理员清理能力只取 `/user/info.systemAdministrator`，不得用权限码或角色名称推断；角色候选首屏和名称搜索均使用最大 `pageSize=200`，首屏未覆盖的当前角色通过租户内 `id` 精确查询补齐，不允许为了打开表单串行扫描完整租户角色目录；
- userVersion 不匹配返回 409；
- 更新当前 Membership 的部门、状态、角色、permissionVersion、sessionVersion；
- PLATFORM 系统管理员由可信 Session 的 ACTIVE、未删除 system role 判定，可额外提交列表返回的 `username/name/remark/identityVersion/credentialVersion`，在同一事务中更新全局 User 和本地 Credential；用户名改变会同步 local issuer 的 `idp_subject`、推进全部未终止 Membership 的 sessionVersion，并写 User 审计；
- 外部 IdP 用户的 subject 不允许通过本地管理接口改写；普通管理员前端将身份字段设为只读，payload 白名单也会剔除这些字段；
- userVersion、identityVersion 或 credentialVersion 任一不匹配都返回 409；
- 成功响应 `data=null`。

### PATCH `/system/user/{userId}/status`

```json
{
  "status": 0,
  "userVersion": 0
}
```

本轮只有这两个请求字段。成功返回新的 userVersion：

```json
{
  "userVersion": 1
}
```

该命令只修改当前租户 Membership，递增 permissionVersion、sessionVersion 和 row_version；不禁用全局 User/credential。`reason` 尚未实现，生产前必须补充。

### POST `/system/user/{userId}/password/reset`

请求为 `{ "credentialVersion": 0 }`，入口权限为 `user:update`。仓储在 tenant/actor 锁后额外要求操作者持有当前 PLATFORM tenant 的 ACTIVE、未删除 system role，目标必须是当前 tenant 的未终止 local identity，并原子比较 Credential rowVersion。成功后使用运行时统一初始密码生成新的 BCrypt hash，将目标 User/Credential 置为 ACTIVE，递增 Credential/User 版本，并递增该全局用户全部未终止 Membership 的 sessionVersion 与 rowVersion，使所有旧会话立即失效。响应返回 `{credentialVersion,identityVersion,userVersion}`；明文密码绝不返回。未配置初始密码、外部 IdP、非系统管理员或旧版本分别失败关闭。

### DELETE `/system/user/{userId}?expectedVersion={userVersion}`

`expectedVersion` 必填。原子条件包含 tenant、user、非 TERMINATED 状态和
`iam_membership.row_version = expectedVersion`。成功后将当前租户 Membership 标记为 TERMINATED，并递增权限/会话版本；全局 User 和历史审计不物理删除。

## 1.10 Role API

### GET `/system/role/list`

Query：

```text
name（模糊）
id（精确）
status（0/1）
remark（模糊）
startTime/endTime
page/pageSize
```

Response item：

```json
{
  "id": "2000",
  "name": "Platform Administrator",
  "menuIds": ["6000", "6001"],
  "status": 1,
  "remark": "",
  "rowVersion": 0,
  "systemRole": true,
  "assignable": false,
  "createTime": "2026-07-17T00:00:00Z"
}
```

### POST `/system/role`

### PUT `/system/role/{roleId}`

创建请求：

```json
{
  "name": "Operations Viewer",
  "menuIds": ["6000", "6001"],
  "status": 1,
  "remark": ""
}
```

更新请求在相同业务字段之外必须携带列表返回的版本：

```json
{
  "name": "Operations Viewer",
  "menuIds": ["6000", "6001"],
  "status": 1,
  "remark": "",
  "expectedVersion": 0
}
```

当前实现：

- role:create/update 直接管理 menuIds，无额外 role:assign；
- menuIds 最多 2048 项，HTTP DTO 和 Core 都会拒绝超限请求；
- 角色列表响应中的 menuIds 只包含当前 ACTIVE DIRECTORY/PAGE/EMBEDDED/LINK；历史 BUTTON、DISABLED 或已删除菜单关系不会回显；
- menuIds 只接受当前 Session tenant 下状态为 ACTIVE、类型为 DIRECTORY/PAGE/EMBEDDED/LINK 的菜单；BUTTON 或 DISABLED 菜单返回 409 `DATA_CONFLICT`，不存在或其他 tenant 的 ID 返回 404 `RESOURCE_NOT_FOUND`；
- role code 由后端根据名称和 ID 生成；
- `systemRole=true` 或 `assignable=false` 的受保护角色不能通过普通 update 修改，返回 422 `IAM_ROLE_NOT_ASSIGNABLE`；
- Role 更新原子比较并递增 `iam_role.row_version`，同时递增相关成员 permissionVersion。

### PATCH `/system/role/{roleId}/status`

```json
{
  "status": 0,
  "expectedVersion": 0
}
```

Permission 为 `role:update`。`systemRole=true` 或 `assignable=false` 的受保护角色不能修改，返回 422 `IAM_ROLE_NOT_ASSIGNABLE`；原子版本比较成功后递增 role rowVersion 和相关成员 permissionVersion。

### DELETE `/system/role/{roleId}?expectedVersion={rowVersion}`

`expectedVersion` 必填。只允许软删除 `systemRole=false AND assignable=true` 的普通角色：设置 `status=DISABLED` 和 `deleted_at`，递增 role rowVersion 与相关成员 permissionVersion，并显式删除 `iam_membership_role` 使权限立即失效；`iam_role_menu`、`iam_role_grant` 和角色主记录保留为历史。受保护角色返回 422 `IAM_ROLE_NOT_ASSIGNABLE`。

### menuIds 与 RoleGrant

```text
Role.menuIds      -> 导航、页面展示
BUTTON.authCode   -> 权限目录在树中的展示绑定
RoleGrant         -> 后端动作和数据范围
```

三者已在数据库模型和写入契约中分离。角色新增和编辑抽屉在同一个多层树中展示 ACTIVE 导航节点及其可分配 BUTTON。用户主动勾选导航节点表示对该分支执行批量授权：前端显式选中其全部 ACTIVE 导航后代和可分配 BUTTON 后代，并在请求中分别生成 navigation `menuIds` 与 BUTTON 对应的 RoleGrant intent；取消导航节点只清除该子树。单独勾选 BUTTON 只补齐导航祖先，不自动选择兄弟或跨分支权限；单独取消 BUTTON 也不取消其他权限。BUTTON ID 绝不写入 `iam_role_menu`，服务端也不得根据 navigation menuIds 或前端动作依赖暗推 Grant。新增调用 `POST /api/v1/iam/roles/configuration`，编辑调用 `PUT /api/v1/iam/roles/{roleId}/configuration`，都在一个事务中写角色字段、ACTIVE 可路由 menuIds 和页面可无损表达的 RoleGrant。替换导航时只删除当前 ACTIVE、未删除、可路由菜单的旧关系，已禁用、BUTTON 或墓碑菜单的既有 `iam_role_menu` 必须保留为历史。未知、高风险、有效期、多维度或带 target 的现有 Grant 会使表单只读，禁止静默覆盖。

`local` profile 的独立 bootstrap 为预置 `platform-admin` 建立 19 个现代 `TENANT_ALL` RoleGrant，并在 4 个系统页面下建立 19 个 ACTIVE BUTTON 目录节点；两个旧 `menu:manage`、`department:manage` BUTTON 保留为 DISABLED/隐藏历史节点。BUTTON 不写入 `platform-admin` 的 `role_menu`；该角色仍只有 8 条导航展示关系。bootstrap 仅自动升级精确匹配的旧 8 菜单无按钮或旧 14 按钮基线。升级完成后，`system_managed` 只表示 local bootstrap 来源：预留菜单仍须保有固定 ID、tenant 和来源标记，但允许正常编辑或写入墓碑且重启不回填；预置部门允许业务字段和 rowVersion 推进。物理缺失、租户/来源所有权漂移、预置 authCode 出现第二个活动绑定，以及身份/系统角色/Grant/role-menu 固定关系漂移仍失败关闭。

### 角色授权第一阶段 API

以下端点统一返回 `ApiResponse<T>`，Long ID 仍使用字符串。grantable/grants 三个端点要求当前会话同时拥有 `role:view` 与 `role:grant-update`；configuration PUT 额外要求 `role:update` 与 `menu:view`，configuration POST 改为要求 `role:create` 与 `menu:view`。所有写入都验证操作者当前持有 ACTIVE、未删除的 `system_role`，并执行上一节定义的锁后事务内权限重验：

- `GET /api/v1/iam/permissions/grantable`：只返回精确 18 个 NORMAL、SAME_TENANT_ONLY 管理权限；`role:grant-update` 仅属于 system-admin，不可委派；
- `GET /api/v1/iam/roles/{roleId}/grants`：返回 `{roleId,roleVersion,editable,grants}`；system role、`assignable=false`、存在当前页面不能无损表达的授权，或旧管理权限 cutover 尚未完成时 `editable=false`；
- `PUT /api/v1/iam/roles/{roleId}/grants`：只接受 `systemRole=false AND assignable=true` 的普通角色、全量替换、必填 `expectedVersion` 与非空 `reason`；non-assignable role 返回 422 `IAM_ROLE_NOT_ASSIGNABLE`。`payment.permissions.legacy-administration-cutover-complete` 默认为 `false`；未完成 cutover 时返回 40903 `LEGACY_ADMINISTRATION_CUTOVER_REQUIRED`。
- `PUT /api/v1/iam/roles/{roleId}/configuration`：角色编辑页专用原子入口，请求为 `{expectedVersion,name,status,remark,menuIds,reason,grants}`；成功只递增一次 role rowVersion 和每个成员一次 permissionVersion，写一组 before/after audit 与 Outbox。审计中的 `menuIds` 前后值只比较本次可管理的 ACTIVE、未删除、可路由导航关系；被保留的 DISABLED、BUTTON 或墓碑历史关系不得误报为本次删除。普通 role PUT 和独立 Grant PUT 继续作为兼容 API 存在，但当前角色编辑 UI 不并发调用它们。
- `POST /api/v1/iam/roles/configuration`：角色新增页专用原子入口，请求为 `{name,status,remark,menuIds,grants}`；成功返回新 roleId、`roleVersion=0`，并在一个事务中写导航、Grant、CREATE audit 与 `ROLE_CONFIGURATION_CREATED` Outbox。任何目录、菜单、权限或数据库约束失败都回滚角色主记录。

Grant 和 dimension 数组中的 `null` 元素统一返回 400 `INVALID_REQUEST`，并且必须在任何角色版本、Grant、审计或 Outbox 写入前失败。

V15 为旧 manage Grant 建立等价的现代 Grant，同时保留旧 Grant 作为滚动兼容影子。GET 不把两个已知兼容影子暴露到现代编辑集合。只在所有 N-1 实例和旧调用方已经清零、双版本验证与生产审批完成后，部署方才可显式设置 `PAYMENT_LEGACY_ADMINISTRATION_CUTOVER_COMPLETE=true`；此后第一次 PUT 会在同一事务内停用目标角色全部 ACTIVE Grant（包含兼容影子），再写入请求的现代全集。`local` profile 不存在 N-1 共存，默认打开该开关用于验收。其他未知权限、高风险、有效期、多维度或带 target 的 Grant 仍使页面只读，禁止静默覆盖。

Grant 请求和响应固定为：

```json
{
  "grantKey": "user-view",
  "permissionCode": "user:view",
  "dimensions": [
    {"code": "TENANT", "mode": "TENANT_ALL", "targets": []}
  ]
}
```

grantable 元数据使用绑定维度与模式的对象数组：

```json
{
  "permissionCode": "user:view",
  "resourceCode": "user",
  "actionCode": "view",
  "riskLevel": "NORMAL",
  "requiredDimensions": [
    {"code": "TENANT", "allowedModes": ["TENANT_ALL"]}
  ]
}
```

请求不得提交风险、审批或 step-up 元数据；服务端从 ACTIVE Permission Catalog 校验并补齐。第一阶段拒绝 targets、有效期、FUND、approval 和非 TENANT/TENANT_ALL 授权。PUT 在同一事务内锁 tenant/actor/role，替换 grants/dimensions，递增 role rowVersion 与受影响 Membership permissionVersion，并追加 before/after audit 与 append-only outbox；`iam_role_menu` 保持不变。

第一阶段授权 UI 允许管理员逐个选择 Grant，不自动改写其他 BUTTON。当前管理页面的组合操作仍按其真实调用链失败关闭：用户新增需要 `user:view/department:view/role:view` 才能完整加载表单；用户完整编辑需要 `user:view/user:update/user:disable/user:assign-role/department:view/role:view`；角色新增或编辑需要 `role:view/menu:view`；菜单和部门写操作分别依赖同资源的 view 权限。缺少组合操作所需权限时，对应前端操作不可用，后端接口仍按 endpoint policy 拒绝；角色配置页不得因此自动补权、自动撤权或把已有角色强制设为只读。

## 1.11 Department API

| Endpoint | Request/response |
| --- | --- |
| `GET /system/dept/list` | 返回未删除的管理树；`selectableOnly=true` 时只返回 ACTIVE 候选 |
| `POST /system/dept` | `{pid,name,status,remark}`，返回 `{id}` |
| `PUT /system/dept/{id}` | `{pid,name,status,remark,expectedVersion}` |
| `DELETE /system/dept/{id}?expectedVersion={rowVersion}` | 软删除并写 `deleted_at` |

约束：

- pid 为 `0` 或正 ID；0 映射为根节点；
- 父部门必须属于当前租户；活动部门的父节点也必须活动；
- 拒绝自身/后代作为父节点；
- 活动 Membership 只能创建或移动到活动部门；
- 禁用/删除部门时检查整个子树；任一活动子部门或活动 Membership 都会拒绝；
- 同租户部门写入在数据库 tenant lock 内串行化，避免并发检查后写入制造环或绕过依赖检查。
- PUT/DELETE 都在最终 jOOQ UPDATE 的 WHERE 中原子比较 `row_version = expectedVersion`；成功递增 rowVersion；
- 每 tenant 最多 2000 个部门节点、最深 32 层；查询、组树、create/update 均 fail closed。

## 1.12 Menu API

| Endpoint | Purpose |
| --- | --- |
| `GET /system/menu/list` | 返回未删除的管理树，包含按钮节点；`selectableOnly=true` 时只返回 ACTIVE 候选 |
| `GET /system/menu/name-exists?name=&id=` | name 唯一性检查 |
| `GET /system/menu/path-exists?path=&id=` | path 唯一性检查 |
| `POST /system/menu` | 创建，返回 `{id}` |
| `PUT /system/menu/{id}` | 更新；业务字段之外必填 `expectedVersion` |
| `DELETE /system/menu/{id}?expectedVersion={rowVersion}` | 软删除并写 `deleted_at` |

请求字段：

```text
pid
type = catalog | menu | embedded | link | button
name
path
component
redirect
authCode
meta
status
expectedVersion（仅 PUT）
```

当前约束：

- 父节点必须属于当前租户；ACTIVE 菜单的直接父节点必须为 ACTIVE；
- 拒绝树循环；
- 禁用或逻辑删除菜单前遍历完整后代树；存在任意 ACTIVE 深层后代时返回 409 `DATA_CONFLICT`；
- tenant 内 canonical route name 按 `lower(COALESCE(NULLIF(BTRIM(route_name), ''), menu_name))` 唯一；canonical route path 按小写并去尾斜杠（根 `/` 例外）唯一；仓储在 tenant 锁事务内按同一语义预检，V9 数据库 unique index 作最终并发兜底；
- `name-exists/path-exists` 只用于 UI 即时提示，不是写入完整性边界；
- `activePath` 已位于 `meta.activePath`；
- `meta.title` 必须是 i18n key，PAGE component 必须在后端 allowlist；
- route path 和 redirect 都拒绝反斜杠、查询/片段、父级跳转、空白以及 `//` protocol-relative 值；
- catalog/button 不接受 component；link/embedded 必须有内部 path 且 component 固定为 `IFrameView`；
- 所有类型都拒绝旧 `BasicLayout` 或任意清单外页面 component；
- `iframeSrc` 仅 EMBEDDED、`link` 仅 LINK 可用，且只接受带 host 的绝对 `http/https` URL；字段错配或 catalog/menu/button 保留外链字段均拒绝；
- `meta` 每个 map/list 最多 32 项、key 64 字符、string 1024 字符、最深 4 层、总 value 最多 128；
- 每 tenant 最多 2000 个菜单节点、最深 32 层；管理树、可访问树与 create/update 候选树均使用同一上限；
- PUT/DELETE 在最终 jOOQ UPDATE 的 WHERE 中原子比较 `row_version = expectedVersion`，成功递增 rowVersion；
- BUTTON 必须填写 authCode，所有非空 authCode 都必须命中当前 ACTIVE Permission Catalog；滚动兼容期仍为 ACTIVE 的 `menu:manage`、`department:manage` 只服务旧二进制鉴权，create/update 均禁止将其绑定到菜单；
- BUTTON 不能成为父节点，已有子节点的菜单也不能转换为 BUTTON；
- 删除写入墓碑并从后续管理树、选择器和动态菜单隐藏；现存 role_menu 关系作为历史保留，也不会自动变成 RoleGrant。
- local bootstrap 标记的 system-managed 只表示来源，不再禁止更新、软删除或作为父节点；权限、乐观锁、ACTIVE 父节点、BUTTON 不能有子级、活动后代和其他通用依赖规则仍完全适用；
- `local` bootstrap 预置 19 个 ACTIVE BUTTON，并保留 2 个 DISABLED/隐藏的历史 `menu:manage`、`department:manage` BUTTON；ACTIVE BUTTON authCode 与 19 个现代管理权限码严格相等，path/component/redirect 为空。

### 管理资源的并发冲突协议

Role、Department、Menu 的管理列表都返回 `rowVersion`；User 继续使用语义等价的 `userVersion`（Membership rowVersion）。浏览器必须原样回传当前快照版本：PUT/PATCH 放在 JSON `expectedVersion`，DELETE 放在 query `expectedVersion`；User PUT/PATCH 仍使用已定版的 `userVersion` 字段，User DELETE 使用 query `expectedVersion`。

Repository 的最终 UPDATE/逻辑 DELETE 都把 tenant、resource ID 和 rowVersion 放在同一个 WHERE 中。影响行数为 0 时，在同一 tenant 写锁事务内再次按资源范围判断：资源不存在或已终止返回 404 `RESOURCE_NOT_FOUND`；资源仍存在但版本不同返回 HTTP 409、code `40902`、error `OPTIMISTIC_LOCK_CONFLICT`、message `The record has changed; reload and retry`。数据库唯一键、树依赖等非版本冲突继续返回 HTTP 409、code `40901`、error `DATA_CONFLICT`，前端不能把两者混为同一种恢复动作。

前端收到 `OPTIMISTIC_LOCK_CONFLICT` 时展示可读 message，关闭旧编辑快照并刷新列表；收到 `DATA_CONFLICT` 时保留普通业务冲突处理，不自动假定刷新即可成功。

## 1.13 平台租户写限制

本轮所有 User、Role、Department、Menu 写入仓储方法都会执行：

```text
tenant exists
AND tenant_type = PLATFORM
AND tenant status = ACTIVE
```

因此：

- 读取仍由当前 Session tenantId 和权限码约束；
- 写操作只支持 ACTIVE PLATFORM Tenant；
- Agent、Direct Merchant、Indirect Merchant 的后台身份管理写入不在本轮范围；
- 前端不能通过传 tenantId 绕过限制；
- 非平台租户写入统一返回 403 `PERMISSION_DENIED`。

## 1.14 Application-local Flyway migration policy

可启动应用配置：

```yaml
spring:
  flyway:
    enabled: true
    locations: classpath:db/migration
```

迁移脚本由 `identity-persistence-postgres` 模块放入 application runtime classpath：

```text
V1__permission_schema.sql
V2__iam_admin_api.sql
V3__dashboard_menu.sql
V4__align_vben_menu_contract.sql
V5__repair_shared_iam_sequence.sql
V6__add_cross_tenant_permission_mode.sql
V7__separate_permission_outbox_relay_state.sql
V8__isolate_local_identity_fixture.sql
V9__enforce_menu_route_uniqueness.sql
V10__enforce_grant_dimension_mode_compatibility.sql
V11__enforce_menu_external_navigation_safety.sql
V12__restrict_related_party_mode_to_read_actions.sql
V13__enforce_login_credential_hash_safety.sql
V14__granular_administration_permissions.sql
V15__expand_legacy_administration_permission_compatibility.sql
V16__enforce_exact_administration_permission_catalog.sql
```

语义：

- `local` profile 启用自动迁移，但不启用 `baseline-on-migrate`；
- 已经手工执行 V1、但没有 `flyway_schema_history` 的旧开发卷不属于升级契约；需要的数据先备份，然后从空库重建；
- 全新空库正常执行 V1 到 V17；
- V2/V3 的历史固定身份和菜单只为兼容旧迁移链存在；V8 只在预留 footprint 仍是精确 fixture 时删除它，其他租户、用户、审计、Outbox 和扩展权限原样保留；
- V3 为预置平台管理员补充 Dashboard、Analytics 和 Workspace 动态路由，保证 `/dashboard` 登录首页可用；
- V4 把系统菜单 title 修正为 i18n key，并清除一级目录旧 `BasicLayout`；
- V8 检测到预留 ID/自然键碰撞、fixture 被修改、必需 Permission Catalog 被篡改，或 tenant `1` 存在额外依赖关系时会回滚并要求按 runbook 编写前向迁移；
- V9 为 tenant 内 canonical name `lower(COALESCE(NULLIF(BTRIM(route_name), ''), menu_name))` 和 canonical path（小写、去尾斜杠，根 `/` 例外）建唯一索引；preflight 与索引使用同一表达式，发现历史重复时整个迁移原子回滚，不猜测合并；
- V10 以 Core 相同的 dimension/mode 允许矩阵增加 `CHECK` 并验证历史行；非法历史授权使迁移失败，不自动改权；
- V11-V13 分别约束菜单外链、跨租户只读 action 和 BCrypt hash；历史非法数据使迁移失败，不做静默清洗；
- V14 建立细粒度管理权限，V15 前向保留旧 manage Grant 的滚动兼容，V16 在不回写已执行 V14/V15 的前提下精确核验 21 条管理 Permission 的固定 ID、code/resource/action、风险、维度、step-up、approval、跨租户模式和状态；目录漂移使应用升级失败关闭且不自动修复；
- V17 为 Role、Menu、Department 增加 `deleted_at`，为 Menu、Department 增加 `system_managed`，并把角色/菜单唯一索引调整为只约束未删除行；它只建立软删除能力和精确 local fixture 标记，不物理清理已有业务数据；
- `LocalIdentityFixtureBootstrap` 只在 `local` profile、Flyway 完成后事务性执行 `db/local/iam-local-bootstrap.sql` 并写入 BCrypt 密码；最终 fixture 对预置菜单只保留预留 ID、tenant、`system_managed` 所有权和活动 authCode 歧义校验，允许字段编辑与墓碑在重启后保留。身份、系统角色、Grant、role-menu 固定关系、物理缺失、所有权漂移、密码不匹配或预留权限码第二个活动绑定仍拒绝启动；
- 任何环境的迁移都不得依赖 `baseline-on-migrate=true` 自动猜测历史状态。

## 1.15 当前审计和可观测性

已实现：

- 管理写操作向 `iam_audit_event` 写入 operator Membership、tenant、target、action、permission；
- API body 和 `X-Trace-Id` header 返回请求级 UUID；
- HTTP RequestTrace 会贯穿当前请求日志和成功写入的 audit `trace_id`；
- 未处理异常按请求 traceId 记录日志；
- 密码和 Cookie 不进入 API 响应。

当前缺口：

- audit before/after 当前没有真实变更快照，after_value 只是空 JSON；权限拒绝和登录失败也没有安全审计事件；
- 没有 OpenTelemetry、结构化 MDC、指标、Dashboard、告警或正式审计检索；
- 没有密码/MFA/会话安全事件的完整生产审计模型。

---

# 2. Target Prototype Contract

## 2.1 本轮认可的实现边界

本轮原型必须继续保持：

1. Vben `code/data/error/message/traceId` envelope；
2. 真实凭证只在 `PAYMENT_SESSION` HttpOnly、SameSite=Strict Cookie；
3. JavaScript 只保存 `cookie-session` marker；
4. 前端永不发送 Authorization marker；
5. tenant、membership、operator 来自服务端 Session；
6. 所有 response ID 为 string；
7. 分页为 `items/total`；
8. 本轮 ACTIVE 管理权限目录严格限定为 19 个码；其中角色可分配目录严格限定为不含 `role:grant-update` 的 18 个码；
9. 用户角色使用完整 roleIds；User 使用 userVersion，Role/Department/Menu 使用 rowVersion/expectedVersion 防并发覆盖；
10. 用户和角色状态使用独立 PATCH；
11. role menuIds 与 RoleGrant 分离；
12. 所有管理写入仅允许 ACTIVE PLATFORM Tenant；
13. local Flyway 不推断 baseline，缺少 history 的旧手工开发卷必须重建；
14. 原型不连接任何资金写链路；
15. 管理写操作必须携带可信 Session 捕获的 actor 身份与两个版本，并在写锁后复核；RoleGrant PUT 已事务内重验两项入口权限，其他管理写接口完成同等重验前不得使用有限过期 Grant 授权。
16. 产品访问模式为 `mixed`；后端 `/menu/all` 继续拥有业务路由，本地只允许显式白名单路由参与合并。
17. 框架侧 `UserInfo` 必须包含 `userId/avatar/desc/token`；`token` 只能是固定非秘密 `cookie-session` marker，不能返回 Sa-Token 或 refresh credential。
18. refresh credential 的目标传输方式是独立 HttpOnly Cookie，禁止 body/header 和 JavaScript 可读存储；rotation、重放检测、并发、TTL、撤销、退出联动和 IdP 兼容批准前不得开启 `/auth/refresh`。
19. 当前阶段不增加用户详情接口；编辑继续使用列表快照，`40902` 后关闭旧表单并刷新列表。

## 2.2 本轮已实现、但不代表生产完成

| Capability | Prototype status | Production meaning |
| --- | --- | --- |
| username/password login | BCrypt + 统一 hash 格式/成本策略 + Redis 原子 client/client+username 双桶已实现 | 仅本地过渡凭证，不替代 IdP/MFA，也不代表分布式攻击防护已完成 |
| Cookie session | Sa-Token/Redis 已实现；安全配置在 context 创建期绑定 | 尚需部署拓扑、密钥、TTL、故障和撤权演练 |
| RBAC management | 用户/角色/菜单/部门与受限 RoleGrant API 已实现 | 仅平台租户、本轮 19 个当前管理权限；V15 另保留 2 个不进入新授权面的旧码；角色授权仅支持 18 个精确目录权限 |
| Permission load | HTTP PEP + 版本化 Redis GrantSnapshot 已接通 | RoleGrant 写入仍需正式审批、部署与恢复演练 |
| Cross-tenant model | `SAME_TENANT_ONLY` 默认；只有受控 `READ/VIEW` action 可使用 `RELATED_PARTY_READ`，Core 与 V12 CHECK 双重约束 | 没有 Party/Relationship adapter，运行时仍 fail closed |
| Dynamic menu | 固定 mixed mode、仅本地 Profile、保留路由冲突即拒绝、排除 BUTTON、补 ACTIVE 祖先和外链协议校验已实现 | Menu 仍只是 Presentation，外部嵌入还需 CSP/域白名单评审 |
| Audit | HTTP 与成功写审计共享 traceId | 未完成 before/after、权限拒绝、登录失败、检索和告警 |
| Flyway | V1→V16 fresh/upgrade 可运行；V8 fixture 隔离、V9-V13 拒绝式约束、V14/V15 细粒度权限兼容升级和 V16 精确目录守卫已落迁移 | 旧 manage 码的 contract 迁移、生产 migration 审批和备份恢复演练未完成；V16 只能阻断 V15 之后的漂移库，不能让已提交并执行的 V15 自身事后回滚 |

## 2.3 明确不在本轮实现

- 外部 IdP/OIDC；
- MFA、step-up、MFA 重置审批；
- 生产级的新建用户密码激活、邀请、首次改密、忘记密码和管理员重置；
- 超出本文精确目录、数据维度或审批规则的通用 RoleGrant 管理；
- 商户、市场、渠道、销售客户关系和历史代理关系数据范围 Provider；
- 可信审批 workflow evidence、资源指纹、金额/币种绑定、过期和防重放；
- Outbox relay、投递、重试、Inbox、重放和告警；
- 订单、导出、资金查看或资金写权限；
- Payment/Ledger 状态机、金额精度、幂等、账本分录、调账/对账与 API/事件可执行规格；
- payout、withdrawal、refund、ledger 等任何资金权限；
- 正式 OpenTelemetry、metrics、Dashboard、Alert 和审计 correlation。

---

# 3. Compatibility Plan

## 3.1 已完成的 Playground -> web-antdv-next 迁移

已完成：

- 系统用户、角色、菜单、部门页面迁入；
- API 类型集中为 ID string 和 `items/total`；
- 权限码集中到 `PERMISSION_CODES`；
- 用户 roleIds 取代用户直接菜单权限；
- 角色字段明确为 menuIds；
- 用户状态改用 `PATCH /system/user/{id}/status`；
- 角色状态改用 `PATCH /system/role/{id}/status`；
- `activePath` 进入 `meta`；
- 产品 access mode 固定为 mixed，业务路由由后端提供，本地只保留 Profile；
- Cookie marker 不再进入 Authorization。

## 3.2 下一阶段兼容工作

1. 用户详情接口暂缓；当前继续使用列表快照并在 `40902` 后关闭旧表单、刷新列表。未来重新启动详情接口时，必须在编辑前加载最新 roleIds/userVersion；
2. 用户创建流程拆成“创建身份/成员”与“邀请、激活、设置密码、绑定 MFA”；
3. 给用户状态 PATCH 增加 reason，并补正式审计 before/after；
4. 为非平台租户设计独立的成员管理用例和权限目录；当前继续明确拒绝写入；
5. 为已接入 Permission Catalog 强校验的菜单 authCode 补正式目录生命周期治理；component allowlist 已完成；
6. 为已落地的独立 RoleGrant 管理 API/UI 完成审批流和扩展维度设计，继续禁止复用 menuIds；
7. RoleGrant 上线前实现商户、市场、渠道等服务端 Provider；
8. 在现有请求 trace 关联基础上接入正式 OpenTelemetry 和跨进程 log correlation；
9. 外部 IdP 接管凭证后，保留 Vben Cookie-session 适配层，避免向浏览器暴露长期 token。

## 3.3 RoleGrant 后续目标

RoleGrant 仍保持原子语义：

```text
RoleGrant(permissionCode + dimensions + constraints)
```

- 同一 Grant 内不同维度 AND；
- 同一维度多个 target OR；
- 多条 Grant OR；
- 不把商户、市场、渠道分别求并集；
- 资源范围只能由服务端资源或 Provider 校验；
- 空范围默认 Deny；
- FUND 权限必须显式 Grant、step-up、职责分离和审计。

当前 `DimensionScope` 与 V10 数据库 CHECK 的完整允许矩阵如下，未列出的组合全部拒绝：

| Dimension | Allowed modes |
| --- | --- |
| TENANT | `TENANT_ALL` |
| OWNER | `SELF` |
| DEPARTMENT | `SELF`、`DEPARTMENT`、`DEPARTMENT_AND_CHILDREN`、`SPECIFIED` |
| CUSTOMER | `ASSIGNED`、`SPECIFIED` |
| MERCHANT | `ASSIGNED`、`SPECIFIED`、`RELATION_CURRENT`、`RELATION_AT_EVENT` |
| MARKET | `SPECIFIED` |
| CHANNEL | `SPECIFIED` |

原子 Grant 语义和上表矩阵已经是当前 Core/数据库不变量；浏览器可调用的受限 RoleGrant 管理 API/UI 已覆盖精确 18 个 TENANT/TENANT_ALL 管理权限，通用维度、审批流和业务 Provider 仍是后续目标。不得把角色 menuIds 保存自动转换为 RoleGrant。

## 3.4 Rollout order

```text
当前平台 IAM 原型
-> 用户激活/重置 + IdP/MFA
-> 审计 correlation 和正式可观测性
-> RoleGrant 审批流与扩展维度
-> Merchant/Market/Channel Provider
-> 只读业务数据权限
-> 敏感查看/导出
-> 资金权限（最后，独立生产门禁）
```

---

# 4. Production blockers

以下任一项未完成，不得把当前原型标记为生产身份与支付权限系统：

1. 外部 IdP/OIDC、密码策略和身份生命周期未定版；
2. MFA、step-up、MFA 重置、激活会话和旧会话撤销流程未实现；
3. 新建用户没有生产可用的密码激活、邀请、首次改密或管理员重置流程；本地统一口令及重置能力不得用于生产；
4. Cookie Secure 的生产强制、代理拓扑、TTL、Redis 故障和会话撤权未演练；
5. CSRF/Origin/CORS 策略尚未经过真实部署安全测试；
6. RoleGrant PUT 和角色 configuration PUT 已关闭 `valid_until` 在等待锁期间过期的竞态；User、普通 Role lifecycle、Menu、Department 其他管理写接口尚未执行同等事务内授权重验，仍需通过“这些管理写权限不得配置有限 `valid_until`”的运维约束临时规避；
7. 普通角色分配已保护最后管理员、禁止自提权并拒绝 system/non-assignable role；仍缺经过审批、双人执行、可审计的 break-glass provisioning；
8. 管理资源已用 rowVersion 阻止旧快照覆盖，但用户/角色仍无详情重载 endpoint；发生 40902 时只能关闭旧表单并刷新列表，不能自动合并并发修改；
9. RoleGrant 管理 API/UI 已实现精确目录的全量替换，但生产默认由旧管理权限切换闸门禁用；清除全部 N-1 依赖、打开闸门、超出 TENANT_ALL 的数据维度和正式审批流仍未完成；
10. Merchant、Market、Channel、Customer、AgentRelation、HistoricalSnapshot Provider 未实现；
11. 数据范围没有在真实订单/报表 Mapper 上完成 tenant + scope 集成测试；
12. 资金权限目录、step-up、职责分离、审批和审计未实现；
13. 菜单 authCode ACTIVE Catalog 强校验与 component 服务端 allowlist 已完成；正式 Permission Catalog 生命周期治理仍未完成；
14. 审计 before/after、权限拒绝、登录失败、reason 和跨进程 trace correlation 未完成；
15. 没有 OpenTelemetry、结构化日志上下文、关键指标、Dashboard、Alert 和 Runbook；
16. application-local 不再启用 Flyway baseline；缺少 history 的旧手工开发卷必须备份后重建。fixture 已通过 V8 与 local bootstrap 拆分；精确匹配旧版 8 菜单无按钮、旧 14 BUTTON 或已完成 V14+V15 过渡的开发库会在同一事务内收敛到 19 个 ACTIVE BUTTON，并保留 2 个 DISABLED 历史 BUTTON，任何部分按钮、冲突 authCode 或其他 fixture 偏差仍失败关闭；无关真实 IAM/审计/Outbox 可保留，但命中预留 ID/自然键、修改过 fixture、或依赖 tenant `1` 的其他历史库必须使用人工前向迁移并单独演练恢复；
17. 跨租户、跨商户、多角色组合、撤权时效和故障恢复矩阵未完整验证；
18. Outbox 只有 append-only fact + relay state schema，尚无 relay、投递/重试、Inbox/幂等、重放、告警和恢复演练；
19. Payment/Ledger 缺少状态机、金额/币种/精度、幂等、账本分录、调账/对账、API/事件和迁移/回滚的可执行规格；
20. 当前实现不得连接余额、账本、代付、提现、退款、调账等真实资金写路径。

---

# 5. Uncertainty

> Uncertain：登录 API 已支持可选 `tenantId`；多个 ACTIVE Membership 时省略会返回通用 401，但前端尚未提供工作空间选择/发现流程。

> Uncertain：本地 username/password 过渡方案何时切换为外部 IdP，以及是否保留紧急本地管理员。

> Uncertain：当前受限 RoleGrant API 之外的审批、双人复核、更多数据维度和通用权限目录规则尚未批准。

> Uncertain：Agent/Merchant Tenant 何时开放自己的用户、角色、菜单、部门写入；本轮明确只允许 PLATFORM Tenant。

> Uncertain：生产 Cookie absolute timeout、active timeout 是否继续使用当前 8 小时/30 分钟，需要安全评审。

> Uncertain：生产数据库初始化、独立迁移 Job、迁移重跑和恢复责任人尚未确定；当前不允许自动 baseline。

---

# 6. Current acceptance checklist

- `admin-api` 可以作为 Spring Boot 应用启动；
- 前端四个系统管理模块已迁入 `web-antdv-next`；
- 登录设置 `PAYMENT_SESSION` HttpOnly、SameSite=Strict Cookie；
- Sa-Token 安全配置在 ApplicationContext 创建期绑定，不使用启服后 `ApplicationRunner`；
- 登录在查账号/BCrypt 前原子预留 15 分钟 client 30 和 client/username 5 双桶，Redis key 同 hash slot；
- 登录响应只返回 `cookie-session` marker；
- 前端所有请求 `withCredentials=true` 且不发送 Authorization marker；
- `/user/info` 返回 `/dashboard`、空 `desc`、固定非秘密 `cookie-session` marker 和服务端计算的 `systemAdministrator`；
- mixed 路由只注册本地 `Profile`，后端与其 name/path 冲突时拒绝合并；
- `/auth/codes` 对有效本地平台管理员返回且仅返回本轮 19 个 ACTIVE 管理权限码；permissionVersion 失效的旧 Cookie 立即返回 401 `SESSION_INVALID` 并清 Cookie；
- 用户/角色/菜单/部门接口受后端权限拦截；
- 未登记的 API method/path 默认返回 403；
- 用户列表查询字段与本文一致；
- 角色列表查询字段与本文一致；
- 用户状态 PATCH 使用 `status + userVersion` 并返回新版本；
- 用户创建 POST 与 Membership 更新 PUT 已使用不同 DTO；`local` profile 创建 ACTIVE User/Credential 并写统一初始密码的独立 BCrypt hash，未配置初始密码时仍创建 `PENDING_ACTIVATION` User + `DISABLED` Credential；列表返回 `identityStatus`；系统管理员可重置 local 密码并使全部旧会话失效；
- 角色状态 PATCH 使用 `status + expectedVersion`，权限为 `role:update`；
- role menuIds 与 RoleGrant 不混用；
- 所有管理写入要求 ACTIVE PLATFORM Tenant；
- 管理写入从可信 Session 构造 actor，事务锁后复核 tenant/user/membership/credential、password hash 和 permission/session 两个版本；版本失效返回 401 `SESSION_INVALID` 并清 Cookie；
- API ID 为 string，分页为 `items/total`；
- Role/Department/Menu 列表返回 rowVersion；其 PUT/PATCH/DELETE 必须回传 expectedVersion，User DELETE 必须回传当前 userVersion；旧版本返回 40902 `OPTIMISTIC_LOCK_CONFLICT`，不存在返回 404；
- mutating `/api/**` body ≤ 256 KiB（无 `Content-Length` 也受限）；DTO/meta 有结构上限；page offset 超出 int 上限返回 400；roleIds ≤ 256、menuIds ≤ 2048 在 HTTP/Core 双层约束；
- 部门/菜单树每 tenant ≤ 2000 节点、深度 ≤ 32，查询、组树和写入都 fail closed；
- 动态菜单 title 使用 i18n key，PAGE component 使用前后端 allowlist；
- route/redirect 拒绝 `//`；tenant 内 canonical route name/path 唯一性和 ACTIVE 菜单树约束由仓储事务与 V9 数据库索引共同保护；
- `/menu/all` 排除 BUTTON，只在 ACTIVE 祖先链完整时补齐祖先并返回直接授权分支；菜单外链字段按类型隔离且只允许绝对 `http/https`；
- V8 生产 fixture 隔离、local-only bootstrap 和禁止自动 baseline 的边界已明确；
- V10 与 Core 使用同一 DimensionScope 允许矩阵，历史非法组合拒绝迁移而不自动改权；
- 前端 lifecycle 不使用 `npx`/`pnpm dlx`；根 CI 执行 lint、产品 app typecheck、unit、production-safety 和 product build；
- RoleGrant PUT 已用锁后数据库时间重验关闭 finite `valid_until` 竞态；其他管理写接口的同类 TOCTOU 仍是生产阻断项，过渡期禁止给这些权限配置有限过期时间；
- 未实现能力和生产阻断项没有被描述为已完成。
