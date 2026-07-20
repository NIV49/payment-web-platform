# Identity Admin API Contract

> 状态：已实现的本地原型契约，非生产认证与资金权限方案  
> 适用应用：`frontend/admin/apps/web-antdv-next`、`backend/applications/admin-api`  
> 复核日期：2026-07-20  
> 事实优先级：当前代码与集成测试 > 本文；实现变化必须同步更新本文

本文分三层记录：

1. **Current Contract**：当前前后端已经实现并由代码或集成测试证明的行为；
2. **Target Prototype Contract**：本轮原型认可的边界和不变量；
3. **Compatibility Plan**：从当前原型走向可用于生产身份管理和支付数据权限的后续路径。

当前结论：登录、Cookie 会话、当前用户、14 个管理权限码、动态菜单以及用户/角色/菜单/部门管理页面和 API 已形成可启动原型。它可以用于本地联调和领域验证，但外部 IdP、MFA、用户激活/重置、RoleGrant 管理、支付数据范围、资金权限和正式可观测性仍未完成。

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
  -> MyBatis/PostgreSQL
  -> Sa-Token/Redis session
```

当前事实：

- `backend/applications/admin-api` 是可启动 Spring Boot composition root；
- 默认端口为 `8080`；
- `web-antdv-next` 已迁入用户、角色、菜单、部门页面和对应 API；
- 前端 `accessMode` 已设置为 `backend`，登录后使用 `/menu/all` 生成动态菜单；
- 当前用户首页为 `/dashboard`，动态 Dashboard 路由将其重定向到 `/dashboard/analytics`；
- 后端已有 Testcontainers/MockMvc 契约集成测试覆盖 Cookie、14 个权限码、菜单、列表、状态 PATCH 和部分租户隔离场景。

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
- Session bridge 校验当前 membership 的 sessionVersion；
- 用户完整更新、状态变化和终止会递增 Membership 的 sessionVersion 与 permissionVersion；
- 角色更新、状态变化和删除会递增该角色成员的 permissionVersion；
- 权限码从当前租户、当前 Membership 的有效 RoleGrant 读取。

当前用户状态 PATCH 只改变当前租户 Membership；不会同时禁用全局 User 或全局 credential。这是多租户身份模型的当前行为。

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
| 401 | 40102 | `SESSION_INVALID` | sessionVersion 失效或 Session 不合法 |
| 403 | 40301 | `PERMISSION_DENIED` | 缺少动作权限、Origin 不可信或 API method/path 未登记 |
| 404 | 40401 | `RESOURCE_NOT_FOUND` | 当前租户下目标资源不存在，或非 API 资源未命中 |
| 409 | 40901 | `DATA_CONFLICT` | 乐观锁、数据依赖或唯一约束冲突 |
| 422 | 42201 | `IAM_ROLE_NOT_ASSIGNABLE` | 系统角色、不可分配角色、自提权或越权委派被拒绝 |
| 422 | 42202 | `IAM_LAST_ADMIN_PROTECTED` | 禁用或移除最后一个活动系统管理员被拒绝 |
| 429 | 42901 | `LOGIN_RATE_LIMITED` | 登录失败次数超过限制 |
| 500 | 50001 | `INTERNAL_ERROR` | 未映射异常 |

平台租户写限制抛出的 `SecurityException` 已统一映射为 403 `PERMISSION_DENIED`，不会向调用方泄露租户类型判断细节。

## 1.6 ID、状态、分页和时间

### ID

- API response 中的 User、Role、Menu、Department ID 全部为 JSON string；
- path/query 中 ID 按正 Long 解析；
- `/system/user/**` 的 `{id}` 和列表 `id` 当前表示全局 `iam_user.id`，不是 membershipId；
- membershipId 当前不暴露给用户管理前端；
- userVersion 来自当前租户 Membership 的 `row_version`。

### Status

```text
1 = ACTIVE
0 = DISABLED
```

User DELETE 将当前租户 Membership 标记为 `TERMINATED`；Role、Menu、Department DELETE 当前都是逻辑禁用，不物理删除主要业务记录。

### Pagination

```text
page      默认 1，最小 1
pageSize  默认 20，允许 1..200
```

响应固定为：

```json
{
  "items": [],
  "total": 0
}
```

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
- Redis 登录失败限制为 15 分钟内 5 次；
- 成功后创建 Sa-Token Session Cookie；
- 本地管理员密码只有显式提供 `PAYMENT_BOOTSTRAP_PASSWORD` 时才初始化；
- 不记录或返回真实 token。

当前限制：没有外部 IdP、MFA、激活流程、忘记密码或重置密码。

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
  "homePath": "/dashboard"
}
```

不返回完整 RoleGrant、商户、市场或渠道数据范围。

### GET `/auth/codes`

返回当前 Membership 的有效权限码 `string[]`。查询要求：

- Role ACTIVE；
- RoleGrant ACTIVE 且处于有效期；
- Permission ACTIVE；
- 本轮 Permission 的 requiredDimensions 仅为 TENANT；
- Grant 有 `TENANT + TENANT_ALL` 维度。

### GET `/menu/all`

返回当前 Membership 的有效 Role 对应菜单树。`web-antdv-next` 已使用 backend access mode。

当前动态菜单来源是 Role -> role_menu -> Menu；按钮权限仍由 `/auth/codes` 决定。菜单展示关系不等于业务授权。

Vben 路由不变量：

- `meta.title` 必须是至少包含一个 `.` 的 i18n key，例如 `system.title`；
- PAGE component 必须来自当前应用维护的显式页面清单，格式不含 `views/` 和 `.vue`；
- 一级 catalog 不返回 `BasicLayout`，因为根路由已经统一提供布局；
- link/embedded URL 只允许绝对 `http/https` 地址；
- 写菜单时前端和后端都会校验，但后端是最终完整性边界。

## 1.8 本轮 14 个权限码

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
menu:view
menu:manage
department:view
department:manage
```

当前后端方法映射按实际 endpoint 精确匹配，不使用 `startsWith`：

| Endpoint group | GET | POST | PUT | PATCH | DELETE |
| --- | --- | --- | --- | --- | --- |
| `/api/system/user`、`/list`、`/{id}`、`/{id}/status` | `user:view` | `user:create` | `user:update` | `user:disable` | `user:delete` |
| `/api/system/role`、`/list`、`/{id}`、`/{id}/status` | `role:view` | `role:create` | `role:update` | `role:update` | `role:delete` |
| `/api/system/menu`、查询端点、`/{id}` | `menu:view` | `menu:manage` | `menu:manage` | — | `menu:manage` |
| `/api/system/dept`、`/list`、`/{id}` | `department:view` | `department:manage` | `department:manage` | — | `department:manage` |

用户创建时 roleIds 非空、或用户更新确实改变 roleIds 时，Controller 额外要求 `user:assign-role`。

除 `POST /api/auth/login` 外，所有 API 都先要求有效 session；未在 `AdminApiPermissionPolicy` 登记的 method/path 即使已经登录也返回 403。已登记的系统 CRUD 由 `AdminAuthorizationEnforcer` 调用完整 `DefaultAuthorizationService`，使用版本化 Redis GrantSnapshot；`/auth/codes` 仅用于 UI 展示。

本轮没有 `role:assign`、`role:grant:update`、订单权限或资金权限。

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
  "userVersion": 0,
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
  "userVersion": 0,
  "remark": ""
}
```

规则：

- roleIds 必须存在且不能为 null；
- `[]` 明确表示无角色；
- roleIds 非空时额外检查 `user:assign-role`；
- Role 必须在当前租户且 ACTIVE；
- 创建 User、Membership 和 credential row，返回全局 userId string；
- 新 credential 的 password_hash 当前为 null，因此新建用户无法登录；
- 没有密码激活、初始密码、邀请或重置流程。

### PUT `/system/user/{userId}`

Request 与创建相同，包含完整 roleIds、status 和当前 userVersion。

规则：

- roleIds 是最终全集；
- roleIds 改变时额外要求 `user:assign-role`；
- userVersion 不匹配返回 409；
- 更新当前 Membership 的部门、状态、角色、permissionVersion、sessionVersion；
- 当前实现还同步更新全局 User 和 credential 的名称/username/status；
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

### DELETE `/system/user/{userId}`

将当前租户 Membership 标记为 TERMINATED，并递增权限/会话版本。全局 User 和历史审计不物理删除。

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
  "createTime": "2026-07-17T00:00:00Z"
}
```

### POST `/system/role`

### PUT `/system/role/{roleId}`

两者请求：

```json
{
  "name": "Operations Viewer",
  "menuIds": ["6000", "6001"],
  "status": 1,
  "remark": ""
}
```

当前实现：

- role:create/update 直接管理 menuIds，无额外 role:assign；
- role code 由后端根据名称和 ID 生成；
- 系统预置角色不能通过普通 update 修改；
- Role 更新会递增成员 permissionVersion；
- 当前没有 roleVersion 乐观锁。

### PATCH `/system/role/{roleId}/status`

```json
{
  "status": 0
}
```

Permission 为 `role:update`。系统角色不能修改；变化会递增成员 permissionVersion。当前没有 expectedVersion。

### DELETE `/system/role/{roleId}`

逻辑禁用非系统角色，不物理删除；变化会递增成员 permissionVersion。

### menuIds 与 RoleGrant

```text
Role.menuIds -> 导航、页面、按钮展示
RoleGrant     -> 后端动作和数据范围
```

两者已在数据库模型中分离。当前角色 UI/API 只管理 menuIds，不管理 RoleGrant。

V2 历史迁移为预置 `platform-admin` 建立 14 个 `TENANT_ALL` RoleGrant；这些固定 bootstrap 行目前仍在基础 migration，并非 local-only。通过 UI 新建 Role 即使选择了 menuIds，也不会自动获得 `/auth/codes` 中的业务权限。这是明确边界，不得用 menuIds 自动推导 Grant。

## 1.11 Department API

| Endpoint | Request/response |
| --- | --- |
| `GET /system/dept/list` | 返回 `id/pid/name/status/remark/createTime/children` 树 |
| `POST /system/dept` | `{pid,name,status,remark}`，返回 `{id}` |
| `PUT /system/dept/{id}` | `{pid,name,status,remark}` |
| `DELETE /system/dept/{id}` | 逻辑禁用 |

约束：

- pid 为 `0` 或正 ID；0 映射为根节点；
- 父部门必须属于当前租户；
- 拒绝自身/后代作为父节点；
- 有 ACTIVE 子部门或 ACTIVE Membership 时不能删除。

## 1.12 Menu API

| Endpoint | Purpose |
| --- | --- |
| `GET /system/menu/list` | 返回管理树，包含按钮节点 |
| `GET /system/menu/name-exists?name=&id=` | name 唯一性检查 |
| `GET /system/menu/path-exists?path=&id=` | path 唯一性检查 |
| `POST /system/menu` | 创建，返回 `{id}` |
| `PUT /system/menu/{id}` | 更新 |
| `DELETE /system/menu/{id}` | 逻辑禁用 |

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
```

当前约束：

- 父节点必须属于当前租户；
- 拒绝树循环；
- 存在 ACTIVE 子菜单时不能删除；
- `activePath` 已位于 `meta.activePath`；
- `meta.title` 必须是 i18n key，PAGE component 必须在后端 allowlist；
- catalog/button 不接受 component；link/embedded 必须有内部 path 且 component 固定为 `IFrameView`；
- 所有类型都拒绝旧 `BasicLayout` 或任意清单外页面 component；
- link/embedded 只接受绝对 `http/https` URL；
- 当前后端尚未强制 authCode 必须存在于 Permission Catalog；
- 删除是逻辑禁用，现存 role_menu 关系不会自动变成 RoleGrant。

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

## 1.14 Application-local Flyway baseline

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
```

只有 `local` profile 启用：

```yaml
spring:
  flyway:
    baseline-on-migrate: true
    baseline-version: 1
```

语义：

- `baseline-on-migrate` 不是全局生产默认；
- 启动时必须显式启用 `local` profile 才生效；
- 用于兼容已经手工执行 V1、但没有 Flyway history 的本地数据库；
- 全新空库正常执行 V1 到 V7；
- V2 创建固定 bootstrap 平台租户、部门、管理员身份、14 个 Permission、预置 RoleGrant 和系统菜单；
- V3 为预置平台管理员补充 Dashboard、Analytics 和 Workspace 动态路由，保证 `/dashboard` 登录首页可用；
- V4 把系统菜单 title 修正为 i18n key，并清除一级目录旧 `BasicLayout`；
- V2 不写默认密码；只有提供 `PAYMENT_BOOTSTRAP_PASSWORD` 时应用才会 BCrypt 初始化尚为空的 bootstrap 密码；
- 生产迁移不得依赖 `baseline-on-migrate=true` 自动猜测历史状态。

## 1.15 当前审计和可观测性

已实现：

- 管理写操作向 `iam_audit_event` 写入 operator Membership、tenant、target、action、permission；
- API body 和 `X-Trace-Id` header 返回请求级 UUID；
- HTTP RequestTrace 会贯穿当前请求日志和成功写入的 audit `trace_id`；
- 未处理异常按请求 traceId 记录日志；
- 密码和 Cookie 不进入 API 响应。

当前缺口：

- audit before/after 当前没有真实变更快照，after_value 只是空 JSON；
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
8. 本轮权限目录严格限定为 14 个码；
9. 用户角色使用完整 roleIds，userVersion 防并发覆盖；
10. 用户和角色状态使用独立 PATCH；
11. role menuIds 与 RoleGrant 分离；
12. 所有管理写入仅允许 ACTIVE PLATFORM Tenant；
13. local Flyway baseline 不扩散到生产默认；
14. 原型不连接任何资金写链路。

## 2.2 本轮已实现、但不代表生产完成

| Capability | Prototype status | Production meaning |
| --- | --- | --- |
| username/password login | BCrypt + Redis limiter 已实现 | 仅本地过渡凭证，不替代 IdP/MFA |
| Cookie session | Sa-Token/Redis 已实现 | 尚需部署拓扑、密钥、TTL、故障和撤权演练 |
| RBAC management | 用户/角色/菜单/部门已实现 | 仅平台租户、本轮 14 个普通权限 |
| Permission load | HTTP PEP + 版本化 Redis GrantSnapshot 已接通 | 没有 RoleGrant 管理 UI/API |
| Cross-tenant model | `SAME_TENANT_ONLY` 默认 + `RELATED_PARTY_READ` 元数据已实现 | 没有 Party/Relationship adapter，运行时仍 fail closed |
| Dynamic menu | backend mode 已实现 | Menu 仍只是 Presentation |
| Audit | HTTP 与成功写审计共享 traceId | 未完成 before/after、拒绝事件、检索和告警 |
| Flyway | V1→V7 fresh/upgrade 测试可运行 | 生产 migration 审批和回滚演练未完成 |

## 2.3 明确不在本轮实现

- 外部 IdP/OIDC；
- MFA、step-up、MFA 重置审批；
- 新建用户密码激活、邀请、首次改密、忘记密码和管理员重置；
- RoleGrant 管理页面和写 API；
- 商户、市场、渠道、销售客户关系和历史代理关系数据范围 Provider；
- 订单、导出、资金查看或资金写权限；
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
- access mode 切为 backend；
- Cookie marker 不再进入 Authorization。

## 3.2 下一阶段兼容工作

1. 增加用户详情接口，编辑前重新加载最新 roleIds/userVersion，避免仅使用列表行快照；
2. 用户创建流程拆成“创建身份/成员”与“邀请、激活、设置密码、绑定 MFA”；
3. 给用户状态 PATCH 增加 reason，并补正式审计 before/after；
4. 给角色更新和状态 PATCH 增加 expectedVersion；
5. 为非平台租户设计独立的成员管理用例和权限目录；当前继续明确拒绝写入；
6. 菜单 authCode 接入 Permission Catalog 强校验；component allowlist 已完成；
7. 设计独立 RoleGrant 管理 API/UI，不复用 menuIds；
8. RoleGrant 上线前实现商户、市场、渠道等服务端 Provider；
9. 在现有请求 trace 关联基础上接入正式 OpenTelemetry 和跨进程 log correlation；
10. 外部 IdP 接管凭证后，保留 Vben Cookie-session 适配层，避免向浏览器暴露长期 token。

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

这些只是后续目标设计。当前没有浏览器可调用的 RoleGrant 管理 API，也没有管理 UI；不得把角色 menuIds 保存自动转换为 RoleGrant。

## 3.4 Rollout order

```text
当前平台 IAM 原型
-> 用户激活/重置 + IdP/MFA
-> 审计 correlation 和正式可观测性
-> RoleGrant 管理与版本控制
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
3. 新建用户没有可用的密码激活、邀请、首次改密或管理员重置流程；
4. Cookie Secure 的生产强制、代理拓扑、TTL、Redis 故障和会话撤权未演练；
5. CSRF/Origin/CORS 策略尚未经过真实部署安全测试；
6. 普通角色分配已保护最后管理员、禁止自提权并拒绝 system/non-assignable role；仍缺经过审批、双人执行、可审计的 break-glass provisioning；
7. Role 没有 optimistic version；用户编辑仍使用列表行而非详情重载；
8. RoleGrant 管理 UI/API 尚未实现，新建角色无法配置真正的业务授权；
9. Merchant、Market、Channel、Customer、AgentRelation、HistoricalSnapshot Provider 未实现；
10. 数据范围没有在真实订单/报表 Mapper 上完成 tenant + scope 集成测试；
11. 资金权限目录、step-up、职责分离、审批和审计未实现；
12. 菜单 authCode 仍缺 Permission Catalog 强校验；component 服务端 allowlist 已完成；
13. 审计 before/after、拒绝事件、reason 和跨进程 trace correlation 未完成；
14. 没有 OpenTelemetry、结构化日志上下文、关键指标、Dashboard、Alert 和 Runbook；
15. application-local Flyway baseline 不能作为生产迁移策略，V2 固定 fixture 尚未拆分，生产升级/回滚仍需单独门禁；
16. 跨租户、跨商户、多角色组合、撤权时效和故障恢复矩阵未完整验证；
17. 当前实现不得连接余额、账本、代付、提现、退款、调账等真实资金写路径。

---

# 5. Uncertainty

> Uncertain：登录 API 已支持可选 `tenantId`；多个 ACTIVE Membership 时省略会返回通用 401，但前端尚未提供工作空间选择/发现流程。

> Uncertain：本地 username/password 过渡方案何时切换为外部 IdP，以及是否保留紧急本地管理员。

> Uncertain：用户状态完整 PUT 同步更新全局 User/credential，而状态 PATCH 只更新当前 Membership；多租户开放前必须定版两者的产品语义。

> Uncertain：RoleGrant 管理权限码、API 版本、审批规则和乐观锁契约尚未批准。

> Uncertain：Agent/Merchant Tenant 何时开放自己的用户、角色、菜单、部门写入；本轮明确只允许 PLATFORM Tenant。

> Uncertain：生产 Cookie absolute timeout、active timeout 是否继续使用当前 8 小时/30 分钟，需要安全评审。

> Uncertain：生产数据库初始化、baseline、迁移重跑和回滚责任人尚未确定。

---

# 6. Current acceptance checklist

- `admin-api` 可以作为 Spring Boot 应用启动；
- 前端四个系统管理模块已迁入 `web-antdv-next`；
- 登录设置 `PAYMENT_SESSION` HttpOnly、SameSite=Strict Cookie；
- 登录响应只返回 `cookie-session` marker；
- 前端所有请求 `withCredentials=true` 且不发送 Authorization marker；
- `/user/info` 返回 `/dashboard`；
- `/auth/codes` 返回且仅返回本轮 14 个码；
- 用户/角色/菜单/部门接口受后端权限拦截；
- 未登记的 API method/path 默认返回 403；
- 用户列表查询字段与本文一致；
- 角色列表查询字段与本文一致；
- 用户状态 PATCH 使用 `status + userVersion` 并返回新版本；
- 角色状态 PATCH 使用 `status`，权限为 `role:update`；
- role menuIds 与 RoleGrant 不混用；
- 所有管理写入要求 ACTIVE PLATFORM Tenant；
- API ID 为 string，分页为 `items/total`；
- 动态菜单 title 使用 i18n key，PAGE component 使用前后端 allowlist；
- local profile 的 Flyway baseline 边界已明确；
- 未实现能力和生产阻断项没有被描述为已完成。
