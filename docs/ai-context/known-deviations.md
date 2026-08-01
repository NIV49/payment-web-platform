# 当前偏差与待治理项

> 本文件记录已经由源码确认的问题及治理状态。它不是愿望清单；未解决项必须在后续任务中有迁移、测试和验收。

## 生产门禁总结：当前仍是 NO-GO

| 未闭环能力 | 当前事实 | 生产前必须完成 |
| --- | --- | --- |
| finite `valid_until` 管理写授权 | RoleGrant 与角色 configuration PUT 已锁后重验；User、普通 Role lifecycle、Menu、Department 仍有 HTTP PEP 与写锁之间的纯时间 TOCTOU | 为其余管理写入口补同等数据库时间重验，或使用绑定 actor/permission/resource 的可验证短时授权凭据 |
| RoleGrant 写闭环 | 第一阶段原子角色配置 UI/API、版本推进、审计、Outbox、锁后入口权限重验已实现；默认 production cutover 开关仍关闭 | 清零 N-1 旧调用方、完成双版本验证和生产审批后显式打开 cutover；不得从 menuIds 或 BUTTON 暗推 Grant |
| 权限与安全审计 | 角色 configuration 已写真实 before/after/reason；多数其他成功 IAM 写仍是空 JSON | 补齐其他资源 before/after、拒绝事件、登录失败、reason、检索/告警和跨进程 correlation |
| 身份与敏感操作 | 只有本地账密原型，新建身份默认不可登录；缺少可信 workflow evidence | 外部 IdP、MFA/step-up、邀请/激活/重置、可信审批、防重放与 break-glass 双人流程 |
| 关系数据权限 | Core 只有 fail-closed 模型，未连接真实商户/市场/渠道/客户/历史关系 | Provider、历史快照和真实业务查询中的 tenant + scope 集成测试 |
| Outbox 投递 | Schema 已拆成 append-only fact + relay state，但没有 relay 进程 | 投递、租约、重试、Inbox/幂等、重放、告警和恢复演练 |
| Payment/Ledger 可执行规格 | 只有目标架构约束，没有可实施的资金链规格 | 状态机、金额/币种/精度、幂等、账本分录、调账/对账、API/事件、迁移和回滚演练 |

在上表全部闭环前，不得把当前原型连接到余额、账本、代付、提现、退款、冲正或调账写路径。

## Required：时间型管理写授权仍存在锁等待 TOCTOU

当前 HTTP PEP 会在进入 User/Role/Menu/Department 写事务前验证 RoleGrant。事务内已经通过包含 userId、membershipId、permissionVersion、sessionVersion 的 `AdministrationActor` 锁定 tenant 和 actor 的 Membership/User/Credential tuple，并复核四态、受 V13 保证的可验证 BCrypt 凭证与两个版本，因此禁用主体或推进版本不能在排队期间悄悄放行。

RoleGrant PUT 和角色 configuration PUT 已关闭该边界：它们在锁定 tenant、actor、目标 role 和 ACTIVE、未删除 system role 后，以单条 PostgreSQL `statement_timestamp()` 查询分别重验两项或四项精确入口权限；等待锁期间过期或任一权限缺失都会回滚且不写角色版本、菜单关系、Grant、审计或 Outbox。

尚未关闭的是 User、普通 Role lifecycle、Menu、Department 其他写接口：有限 `valid_until` 仍可能在请求通过 PEP 后、等待写锁期间过期，但 permissionVersion 不会因为时钟经过而自动变化。

治理结论：RoleGrant PUT 和角色 configuration PUT 的具体竞态已修复，但其余入口仍是生产 **NO-GO / Required**。过渡期禁止给 User、普通 Role lifecycle、Menu、Department 写权限设置有限 `valid_until`；正式关闭必须在各写事务取得锁后用可信数据库时间重新验证授权，或者引入可验证、绑定 actor/permission/resource 且带短时效的事务授权凭据，并补真实 PostgreSQL 锁等待并发测试。

## 已解决：Grant 时间边界受应用时钟影响且 cache-hit 可越过已提交撤权

带 `valid_from/valid_until` 的 GrantSnapshot 仍记录最近 temporal boundary，但只要 `refreshAfter` 非空，Loader 与 Redis adapter 都拒绝缓存读写；每次授权都重新执行 PostgreSQL 单条查询，以同一 MVCC statement snapshot 和 `statement_timestamp()` 同时判断 Membership 版本、Grant 有效期及授权明细。应用节点不再用自己的 Clock 比较数据库绝对时间，时钟无论超前还是落后都不参与授权判断。

无时间边界的快照仍可使用版本化 Redis key。cache-hit 返回前会再次读取 permissionVersion：最终复核前已经提交的撤权会使旧命中失效，并按新版本有界重试一次；最终复核后才提交的撤权属于已经进入处理的请求。该线性化语义不替代管理写事务内的 tenant/actor 锁和版本复核；RoleGrant PUT 与角色 configuration PUT 已独立执行锁后权限重验，其余管理写入口仍保留上文时间边界。

## 已解决：生产关闭 Flyway 时 Web 进程可在旧 Schema 上启动

生产仍默认 `spring.flyway.enabled=false`，数据库变更由独立部署 Job 负责；这不再等于跳过兼容性检查。`FlywaySchemaReadinessGuard` 在 ApplicationContext 创建期执行只读 `validateWithResult()` 和显式 `info().pending()` 检查，通过 Spring Boot 数据库初始化依赖保证 local 自动迁移先于门禁，且不使用 `ApplicationRunner`。当前二进制中的 versioned migration 必须全部成功应用，missing、failed、pending、checksum/description/type 漂移一律拒绝启动。

数据库中高于当前二进制的 `FUTURE_SUCCESS` 与 `FUTURE_FAILED` 默认全部拒绝，二进制版本范围内的 missing 记录和任何当前二进制未应用迁移同样拒绝。门禁不调用 `migrate`、`repair` 或修改 history；失败异常只携带稳定脱敏原因码，不串接 Flyway 原始异常。真实 PostgreSQL 18 集成测试覆盖旧 V7、动态 latest、local 初始化顺序、checksum 漂移、known failed、range missing、future success/failure，并断言拒绝路径不泄露 JDBC URL、用户名或密码。只有建立 expand/contract 规则和 N/N-1 双版本兼容门禁后才可重新评估滚动回滚；独立迁移 Job/CD 尚未实现，生产仍是 **NO-GO**。

## 已解决：默认不信任调用方 Forwarded headers

`admin-api` 的 `forward-headers-strategy` 默认是 `NONE`，调用方伪造 `Forwarded` 或 `X-Forwarded-For` 不能改变登录限流使用的来源地址。真实 MockMvc 限流回归使用固定 socket 地址、每次轮换 `X-Forwarded-For`，前五次失败仍进入同一桶，第六次返回 429 `LOGIN_RATE_LIMITED`。部署方只有在可信边界代理会先剥离外部的全部 `Forwarded`/`X-Forwarded-*`、再写入自身可信值时才可显式启用；代理存在本身不构成信任依据。

## 已解决：Sa-Token 安全属性在 Web Server 启动后才设置

Sa-Token 的 Cookie 和 token 安全属性现在由 `application.yml` 交给 Boot auto-configuration，在 ApplicationContext 创建期完成绑定；不再使用 Web Server 已可接受请求后才执行的 `ApplicationRunner`。集成测试断言 runner bean 不存在，并在 context 中核对 `PAYMENT_SESSION`、Cookie-only、HttpOnly、SameSite Strict、Secure、超时与禁止并发/共享等最终属性。

## 已解决：写事务发现 stale actor 时只返回 403

tenant/user/membership/credential 主体失效、sessionVersion 漂移或 permissionVersion 漂移现在分别抛出会话/版本异常，HTTP 统一返回 401 `SESSION_INVALID`。异常处理会尝试注销 Sa-Token 会话并清除 Cookie；清理本身失败会记安全日志，不把旧会话降级成可继续使用的 403 状态。

## 已解决：登录限流可被账号轮换与并发绕过

限流在查账号和 BCrypt 前先执行 Redis Lua，一次原子预留 client 全局桶与 client/username 桶；15 分钟内分别允许最多 30 和 5 个已失败/在途尝试。两个 key 都包含同一 client SHA-256 digest 的 Redis hash tag，因此在 Cluster 中保持同槽；成功登录删除该 client/username 失败记录，并释放当次 client 预留。真实 Valkey 测试覆盖并发原子性、轮换用户名和成功释放；这不代表分布式 botnet 防护或生产告警已完成。

## 已解决：Admin 旧表单可以覆盖或删除较新的资源版本

Role、Department、Menu 管理列表现返回 `rowVersion`，User 继续返回 Membership 的 `userVersion`。前端对 Role/Department/Menu 的 PUT/PATCH body 回传 `expectedVersion`，DELETE 用同名 query 参数；User DELETE 把当前 userVersion 作为 expectedVersion。jOOQ 的最终 UPDATE/逻辑 DELETE 在同一个 WHERE 中比较 tenant、resource ID 和 rowVersion，成功时原子递增版本。

影响行数为 0 后，仓储在 tenant 写锁事务内区分资源是否仍存在：不存在返回 404 `RESOURCE_NOT_FOUND`，存在但版本已变化返回 40902 `OPTIMISTIC_LOCK_CONFLICT`。树依赖、唯一键等普通业务/数据库冲突仍返回 40901 `DATA_CONFLICT`。前端只对专用乐观锁机器码关闭旧表单并刷新数据，避免把所有 409 都错误当成“刷新后重试”。当前不做字段级自动合并；用户和角色仍没有单条详情重载 endpoint。

## 已解决：菜单路由与活动树只有 UI/仓储弱校验

V9 的 canonical route name 是 `lower(COALESCE(NULLIF(BTRIM(route_name), ''), menu_name))`；canonical route path 是小写后去除尾部斜杠，根路径 `/` 例外。两者都在 tenant 内建唯一索引；迁移 preflight 与索引使用同一表达式，扫描到历史重复会使 Flyway 原子回滚，不自动选边或合并。仓储在 tenant 锁事务内按相同语义预检，数据库索引负责最终并发兜底。ACTIVE 菜单只能挂 ACTIVE parent；禁用或逻辑删除祖先时遍历完整后代树，任何 ACTIVE 深层后代都会返回 409。`name-exists/path-exists` 仍只是交互提示。

## 已解决：`/menu/all` 把按钮和断裂树交给 Vben

`/menu/all` 只查 ACTIVE 的 DIRECTORY/PAGE/EMBEDDED/LINK，BUTTON 只用于管理视图和权限码展示，不作为动态 route 返回。直接分配的菜单会补齐同 tenant 且 ACTIVE 的显式祖先，不会带入 sibling；祖先缺失、禁用、不是可路由类型或者成环时，整条直接分配分支 fail closed。角色配置页中用户主动勾选导航节点会在前端显式展开为全部 ACTIVE 导航与 BUTTON 后代，再分别提交 navigation menuIds 和 RoleGrant intent；服务端仍不从 menuIds 暗推 Grant。

## 已解决：路由和批量输入缺少硬边界

后端 Vben 契约对 route path 与 redirect 同时拒绝 `//` protocol-relative URL。所有 mutating `/api/**` 请求体上限是 256 KiB，过滤器会实际读取有界字节，因此无 `Content-Length` 的请求也不能绕过。登录和 Admin DTO 限制字段长度/格式；菜单 meta 限制每容器 32 项、key 64 字符、string 1024 字符、最深 4 层、总值数 128，不支持的 value type 直接拒绝。分页 offset 使用 long 计算，超过当前仓储 int offset 参数上限时返回 400；`roleIds` 最多 256、`menuIds` 最多 2048，HTTP Bean Validation 与 Identity Core 都执行上限检查，不能通过绕过 Controller 调用 Core 放大写入。部门/菜单树每 tenant 最多 2000 节点、最深 32 层；仓储查询使用 `limit(2001)` 探测超限，Core 在 Web 递归组树前检查节点、深度、重复 ID 和环，写入对 candidate tree 执行相同上限。

## 已解决：Grant dimension 与 scope mode 可以语义错配

Core `DimensionScope` 只接受批准矩阵，V10 在 `iam_grant_dimension` 以相同矩阵增加 `CHECK`。V10 对既有行执行 `VALIDATE CONSTRAINT`；历史非法组合会阻断迁移，禁止系统猜测应该收窄、放宽还是改成另一种授权。MERCHANT 明确允许 `ASSIGNED/SPECIFIED/RELATION_CURRENT/RELATION_AT_EVENT`，CHANNEL 只允许 `SPECIFIED`，其余完整矩阵见权限数据库设计。

## 已解决：RELATED_PARTY_READ 可以包装跨租户写权限

权限 action 现在由 `PermissionCode` 精确映射到受控 `PermissionAction`，只有 `READ/VIEW` 具有只读语义，未知 action 默认非只读。`PermissionDefinition`、`PermissionGrant` 和 `DefaultAuthorizationService` 都拒绝 `RELATED_PARTY_READ + 非只读 action`；V12 在数据库增加同语义 CHECK，历史错配会原子阻断迁移，直接 SQL 也不能创建 `merchant:update/order:update` 跨租户授权。

## 已解决：非法 password hash 可以冒充备用系统管理员

`LoginCredentialPolicy` 是登录与管理员可达性判断的统一规则：只接受 BCrypt `$2a/$2b/$2y`、cost 10..14 和 53 字符编码体。登录在 verifier 前 fail closed，最后管理员查询复用同一规则；V13 把它固化为数据库 CHECK，使 Session、版本和 Grant 查询中的非 NULL 凭证判断具备同一前提。历史非法摘要阻断迁移，不自动改密或清洗。回归覆盖 active User + active Credential + 非法 hash 仍不能解除唯一真实管理员。

## 已解决：系统动态菜单标题违反 Vben i18n 协议

### 证据

- 前端语言包已经定义 `system.title`、`system.user.title`、`system.role.title`、`system.menu.title`、`system.dept.title`。
- 前端静态路由 `apps/web-antdv-next/src/router/routes/modules/system.ts` 使用这些 key。
- 当前应用是 `accessMode: 'backend'`，实际菜单来自 `/api/menu/all`。
- `V2__iam_admin_api.sql` 却把 `meta_json.title` 写成 `System Management`、`User Management` 等英文展示值。
- `V3__dashboard_menu.sql` 已经采用正确的 `page.dashboard.*` key，说明系统菜单属于实现不一致。

### 影响

后端动态路由是运行时事实来源，静态路由中的 `$t('system.title')` 不会纠正数据库值。中文环境仍可能显示英文，切换语言也无法正确解析。

### 已实施治理

1. 保留已经执行的 V2，不修改历史 checksum；
2. `V4__align_vben_menu_contract.sql` 把系统菜单改为 `system.*` key；
3. V4 同时清除一级 System/Dashboard 的旧 `BasicLayout`，使用根路由统一布局；
4. `VbenMenuContract` 在写入前校验 title key、页面组件、权限码、路由路径和外链协议；
5. 后端集成测试断言 `/menu/all` 与 `/system/menu/list` 返回正确 key/component；
6. 前端表单要求 key 在当前语言包存在，页面组件必须来自显式清单。

## 已解决：动态 component 只有弱校验

### 历史现状

V2/V3 的 `/system/*/list` 和 `/dashboard/*/index` 路径符合 Vben pageMap 规则。菜单管理 UI 也从 `componentKeys` 提供候选值。

原实现的后端会接收任意 `component` 字符串并写入数据库。前端下拉不是安全和完整性边界；脚本、旧数据或其他调用方能够写入无效路径，最终运行时落到 404。

### 已实施治理

- 前端 `MENU_PAGE_COMPONENTS` 只登记当前 7 个路由入口，不再把所有 `views/**/*.vue` 都暴露为菜单页面；
- 应用启动/构建会核对清单中的页面文件真实存在，缺失时直接失败，不再静默删除清单项；
- 表单拒绝清单外 component，并在类型切换时清除遗留 component；
- 后端 `payment.menu.allowed-page-components` 使用对应 allowlist，PAGE 写入前强制校验；
- catalog/button 不接受 component；link/embedded 必须使用 Vben `IFrameView`，且四类都拒绝旧 `BasicLayout` 或任意页面 component；
- 新增页面时必须同时更新前端清单、后端配置、双语语言包和契约测试。

## 已解决：菜单 meta 可携带不匹配的外链字段

`iframeSrc` 仅允许出现在 EMBEDDED，`link` 仅允许出现在 LINK；两者都必须是带 host 的绝对 `http/https` URL。EMBEDDED/LINK 字段互换或者 catalog/menu/button 保留任一字段都会在入库前拒绝。真实 HTTP 回归也证明 PAGE/BUTTON 携带 `javascript:` 注入值会返回 400。这是持久化 XSS/外部导航的最小服务端边界，不取代未来的 CSP、嵌入域白名单和浏览器安全评审。

## 已解决：Admin API 权限映射尚未默认拒绝

`AdminApiPermissionPolicy` 现按精确 method/path 形状登记接口；只有登录是公开接口，用户信息、权限码、菜单和退出是 session-only，系统 CRUD 映射稳定权限码。未知路径、未知方法和相似前缀默认返回 403。后续接口必须先登记策略和测试；长期仍可演进为注解 + 启动期扫描，减少手工注册表维护成本。

## 已解决：前端初始化产生无命名空间 LocalStorage

Vben `PreferenceManager` 构造阶段原本会创建无 prefix 的 `StorageManager`，不仅产生浏览器警告，若初始化前误调用 `clear()` 还会影响同源全部 LocalStorage。构造阶段现使用内存驱动，`initPreferences(namespace)` 后才切换到有命名空间的 LocalStorage，并有单测防止回归。

## 已解决：不存在的非 API 资源被包装成 500

Spring MVC 的 `NoResourceFoundException` 原先落入兜底异常处理，导致不存在资源返回 `INTERNAL_ERROR`。现在统一返回 40401 `RESOURCE_NOT_FOUND`，并由集成测试覆盖。

## 已解决：前端 lifecycle 脚本可通过 `npx` 脱离锁文件

根 `package.json` 已删除 `preinstall: npx only-allow pnpm`。生产安全测试会扫描 npm lifecycle 脚本并禁止其调用 `npx` 或 `pnpm dlx`；手动维护脚本不等于依赖安装 lifecycle。根目录前端 GitHub Actions 现在依次执行 frozen install、全量 lint、产品 app typecheck、unit tests、production-safety 和 `web-antdv-next` product build。

## P1：细粒度资源范围尚未接入查询链路

IAM Admin 请求已经通过 `AdminAuthorizationEnforcer` 进入 `DefaultAuthorizationService`：它加载带版本的 Grant 快照，并对租户、Scope、step-up 和审批要求执行默认拒绝。当前 IAM Admin 列表和写操作只有租户级查询语义；Admin PEP 因此只传入可信工作区租户，不再把操作者所属部门伪装成目标资源部门。在详情操作解析真实目标、列表查询应用 `DataScopePlan`/SQL predicate 并补齐跨部门拒绝测试之前，当前 Admin 权限码的任何非 `TENANT/TENANT_ALL` Grant 都会 fail closed。

本修复发布前必须盘点现有 Admin 权限码的有效 RoleGrant；发现任何非 `TENANT/TENANT_ALL` 维度必须停止发布，先收窄数据或完成目标感知授权。支付业务仍尚未把市场、商户、渠道、客户和资金对象绑定到真实资源，也没有把结构化数据范围计划接入业务查询。后续支付查询和资金操作必须传入可信资源归属并应用对应 SQL predicate，不能退回 URL + 权限码集合检查。

## P1：生产用户激活流程尚未实现

Admin 创建用户在 `local` profile 使用运行时统一初始密码生成独立 BCrypt hash，并创建 ACTIVE User/Credential；系统管理员可通过带 Credential 乐观锁的 reset API 重置为同一运行时初始密码，重置会推进全部未终止 Membership 会话版本。未配置初始密码的其他 profile 仍只建立 `iam_user=PENDING_ACTIVATION`、Credential=`DISABLED` 且无 password hash 的身份骨架。该本地能力不构成生产邀请、首次改密、身份核验或激活方案；正式流程仍需要一次性凭据、审计原因、过期和重放防护。

## P2：Portal 尚未初始化

`frontend/portal` 只有 `.gitkeep`。在创建 Nuxt 4 大型 pnpm monorepo 前，需要先确定：按国家拆 app 的命名、共享 layers/packages、运行时配置、i18n、支付收银台安全边界、官网与收银台的部署关系。不能复制 Admin 的 Vben package 层次作为默认答案。

## P1：代理商与商户后台身份边界尚未定版且未实现

<!-- decision-status id=IAM-GLOBAL-USER-MULTI-TENANT status=pending ref=none -->

产品基线要求平台、代理商和商户具备各自的管理界面，并要求代理商、直连商户和间连商户具有彼此独立的用户、部门、角色与数据边界。但它同时把 `User` 定义为全局自然人身份、把 `TenantMembership` 定义为租户内工作身份，并将“一个全局用户是否允许加入多个租户，以及如何选择工作空间”列为技术评审未决项。见 `docs/permission-refactor-product-requirements.md` 的 5.2、7 和 21 节，以及 `docs/new-payment-system-target-architecture.md` 的 30 节。因此，当前没有依据把“三类账号必须物理隔离且不得复用全局 User”描述为已确认目标。

当前实现只有一个 `frontend/admin/apps/web-antdv-next` 和一个 `backend/applications/admin-api`。认证模型采用全局 `User` 加租户 `Membership`，`backend/applications/admin-api/src/main/java/com/niv/payment/adminapi/web/AuthUserMenuController.java` 的 `LoginRequest` 还接受可选 `tenantId`；`docs/ai-contract/identity-admin-api-contract.md` 的 1.13 节则明确当前写链路只支持 ACTIVE PLATFORM Tenant，代理商和商户后台身份管理不在本轮范围。这证明代理商、商户管理入口及其身份边界尚未实现，但不能反向证明全局 User 模型必然错误。

在建设代理商端和商户端前，产品与技术必须先定版全局用户多租户规则，再据此明确登录入口、应用边界、会话/Token audience、工作空间选择、接口边界、缓存隔离策略和数据查询边界。在该决策完成前，当前可选 `tenantId` 的登录行为只能视为原型实现，不能静默固化为目标模型。
