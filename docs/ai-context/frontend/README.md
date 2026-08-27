# Admin 前端工程上下文

> 适用目录：`frontend/admin/**`
> 当前基线：Node.js 24.16.0 / Vben 5.7.0 / Vue 3.5.40 / Vite 8.1.5 / pnpm 11.7.0 / Antdv Next 1.4.5

版本事实来源：`.node-version` 与产品 Docker builder 固定 Node.js 24.16.0，`engines.node` 允许同一 major 内的 `>=24.11.0 <25`；`packageManager` 与 `engines.pnpm` 固定 pnpm 11.7.0；工作区 package version 为 Vben 5.7.0，当前锁文件解析 Vue 3.5.40 和 `antdv-next` 1.4.5。2026-07-30 已把 Vben 框架增量同步至官方 `main` 的 `418c16e0939262b3d0037fbd0c378c3ce34c7642`，但项目工具链、裁剪边界和业务适配仍以本仓库为准。不能再用本机偶然安装的版本或未同步的上游最新版本描述本项目。

## 1. 工程定位

`frontend/admin` 是独立 pnpm + Turborepo monorepo。PLATFORM、MERCHANT、AGENT 分别由三个可独立构建和部署的应用承载；三者只复用不含应用专属页面的 `backoffice-runtime`。工作区另保留 Mock 应用、Playground、Vben 共享包与工程工具。`frontend/portal` 是未来 Nuxt 4 多应用 monorepo 的占位目录，不是后台入口。

运行时主要依赖方向：

```text
apps/platform-admin | apps/merchant-admin | apps/agent-admin
  -> packages/effects/backoffice-runtime
  -> packages/effects/*        组合后的业务框架能力
  -> packages/*                locale/store/preferences/types/utils
  -> packages/@core/*          无业务或低耦合核心
  -> internal/*                构建、类型和代码规范（构建期）
```

应用代码可以依赖共享包；共享包不能反向依赖应用。

导入路径同时表达模块关系：同一功能目录内使用 `./` 或短 `../`；应用内部跨
目录使用该应用的 `#/* -> src/*` 别名；产品共享的 `backoffice-runtime` 内部跨
模块通过自身 `package.json#exports` 做 package self-reference，跨 package 也只
使用对方公开的包名子路径，禁止穿透到其他包的 `src`。`backoffice-runtime` 不
定义或使用 `#/*`，避免源码 package 被应用消费时解析到应用自身目录；它的
`views/system/**` 也不允许三层及以上父级导入。上述规则由
根 `eslint.config.mjs` 提供编辑期反馈，并由
`scripts/deploy/backoffice-topology.test.ts` 做结构回归。构建器明确依赖相对路径
语义的动态 import 或 glob 保留相对写法，不为了统一形式破坏制品发现规则。

## 2. 顶层目录职责

| 目录/文件 | 职责 | 修改原则 |
| --- | --- | --- |
| `apps/platform-admin` | 运维后台应用 | PLATFORM 专属页面、API、路由清单和部署配置 |
| `apps/merchant-admin` | 商户后台应用 | MERCHANT 专属页面、API、路由清单和部署配置 |
| `apps/agent-admin` | 代理商后台应用 | AGENT 专属页面、API、路由清单和部署配置 |
| `apps/backend-mock` | Nitro Mock 服务 | 仅本地演示/隔离开发，不是生产后端 |
| `playground` | Vben 完整演示应用与 E2E | 查用法、提取模式；不承载产品功能 |
| `packages/@core` | 设计、基础工具、类型、偏好和 UI 内核 | 框架层；非通用需求不改 |
| `packages/effects/backoffice-runtime` | 三后台共享启动、认证、请求、布局、locale、字典 composable 和基础页面 | 不能导入或隐式注册任一应用专属页面/API |
| `packages/effects` 其他包 | access、request、layouts、common-ui、plugins | 跨应用集成层；改动影响面大 |
| `packages/*` | 常量、图标、locale、store、style、type、utils 等公共门面 | 保持通用、稳定、无应用业务 |
| `internal/lint-configs` | Oxfmt/Oxlint/ESLint/Stylelint/Commitlint 配置 | 代码规范基础设施 |
| `internal/node-utils` | 工程脚本共用的 Node 工具 | 只服务构建/脚本 |
| `internal/tailwind-config` | Tailwind 主题与共享配置 | 全局视觉基础 |
| `internal/tsconfig` | 共享 TypeScript 配置 | 不在应用内复制配置 |
| `internal/vite-config` | `defineConfig`、插件和默认 Loading | 应用 Vite 配置的统一入口 |
| `scripts/turbo-run` | 交互选择并运行 turbo task | 工程命令 |
| `scripts/vsh` | 循环依赖、依赖、lint、发布检查 CLI | 工程质量门禁 |
| `scripts/deploy` | 三后台容器/Nginx 构建与生产安全回归测试 | `APP_NAME` 只允许三个产品应用，禁止部署 Playground |
| `.changeset` | 上游包版本变更 | 当前业务仓库暂不发布 Vben 包 |
| 仓库根目录 `.vscode` | 全仓统一编辑器配置 | Admin 工具路径必须带 `frontend/admin/` 前缀；子工程不再维护嵌套配置 |
| `pnpm-workspace.yaml` | workspace 范围和依赖 catalog | 增删 package 必须同步 |
| `turbo.json` | task 依赖、缓存和输出 | 新任务需声明缓存语义 |
| `vitest.config.ts` | 单测环境与排除项 | 当前使用 happy-dom |

生成目录 `node_modules`、`dist`、`.turbo`、Nitro `.output/.nitro` 不是源码事实，分析和提交时应忽略。

## 3. 共享包地图

### `packages/@core`

| 包 | 职责 |
| --- | --- |
| `base/design` | design token、全局 CSS、BEM 工具 |
| `base/icons` | 核心图标抽象 |
| `base/shared` | cache、color、constants、tree/utils、global state |
| `base/typings` | 全局类型、RouteMeta 和动态路由类型 |
| `composables` | UI 无关组合式能力 |
| `preferences` | 偏好默认值、合并、持久化内核 |
| `ui-kit/form-ui` | Schema Form 内核 |
| `ui-kit/layout-ui` | Page 等基础布局 UI |
| `ui-kit/menu-ui` | 菜单渲染内核 |
| `ui-kit/popup-ui` | Alert/Modal/Drawer 内核 |
| `ui-kit/shadcn-ui` | 基础 UI primitives |
| `ui-kit/tabs-ui` | 标签页内核 |

### `packages/effects`

| 包 | 职责 |
| --- | --- |
| `access` | frontend/backend/mixed 路由生成、权限组件与指令 |
| `common-ui` | Page、认证页、Dashboard、Form、Table、Modal 等公共能力门面 |
| `hooks` | 依赖项目能力的组合式 hooks |
| `layouts` | Auth/Basic/IFrame/RouteCached 布局与 widgets |
| `plugins` | ECharts、Motion、Tiptap、VxeTable 适配 |
| `request` | Axios RequestClient、拦截器和认证恢复 |

### 其他公共门面

- `constants`：应用常量和标准路径；
- `icons`：Iconify、本地图标和 SVG；
- `locales`：Vue I18n 初始化和框架语言包；
- `preferences`：对 core preferences 的公共导出；
- `stores`：Pinia access/user/tabbar 等全局 store；
- `styles`：全局与各 UI 库样式入口；
- `types`：公共类型门面；
- `utils`：路由生成、tree、window、loading 等工具门面。

## 4. 共享运行时与应用模块地图

下表除 `api/system`、`views/system`、Analytics、Demo 和应用专属 Merchant 页面外均位于 `packages/effects/backoffice-runtime/src`。PLATFORM 的 Merchant 列表/详情只位于 `apps/platform-admin/src/views/merchant`；MERCHANT 的 self-service 页面只位于 `apps/merchant-admin/src/views/merchant`；AGENT 不包含 Merchant 页面。

| 目录/文件 | 职责 | 关键入口 |
| --- | --- | --- |
| `main.ts` | 偏好命名空间初始化与延迟 bootstrap | `initApplication` |
| `bootstrap.ts` | Vue App 组合根 | `bootstrap` |
| `app.vue` | Antdv Next ConfigProvider、主题、RouterView | 根组件 |
| `preferences.ts` | 应用覆盖配置 | 保留 backend 按钮权限码语义，不决定产品路由模式 |
| `adapter/component` | Antdv Next 控件注册、ApiComponent、全局共享组件 | `initComponentAdapter` |
| `adapter/form.ts` | VbenForm model/rule 适配 | `initSetupVbenForm` |
| `adapter/vxe-table.ts` | VxeTable 分页/Cell/权限操作适配 | `setupVbenVxeTable` |
| `api/request.ts` | baseURL、Cookie、envelope、Session-bound CSRF、401 和错误拦截 | `requestClient` |
| `api/core` | 登录、用户、菜单、上传契约；当前用户响应显式校验后映射 | `/auth/*`, `/user/info`, `/menu/all` |
| `api/system` | 用户、角色、菜单、部门管理契约 | `/system/*` |
| `api/merchant-lifecycle.ts` | 严格校验 Long string、状态、时间、受保护字段、列表 `reviewPending` 和八个 MCH-001 endpoint | `/merchant/application`, `/platform/merchants*` |
| `views/merchant` | 共享表单契约、幂等 key generation 与迟到请求 guard，不拥有应用页面 | PLATFORM/MERCHANT Merchant pages |
| `layouts` | Auth、Basic、IFrame 布局应用封装 | Router component |
| `locales` | 应用语言包与 Antdv/Day.js locale | `setupI18n`, `$t` |
| `router/routes/core.ts` | 登录、错误页等无业务核心路由 | 永久注册 |
| `router/routes/modules` | mixed 模式本地 allowlist 与未注册参考源码 | 产品只注册 `profile.ts` |
| `router/product-access.ts` | 固定 mixed 模式，递归保护核心/fallback/Profile canonical name/path | `PRODUCT_ACCESS_MODE` |
| `router/route-lifecycle.ts` | 按启动时冻结的核心 route name 清除用户动态路由；route generation 阻止旧路由回挂，session generation 阻止旧身份与权限码回写 | 登录换用户、退出 |
| `router/access.ts` | pageMap/layoutMap、后端菜单加载和合并前校验 | `generateAccess` |
| `router/guard.ts` | token、用户信息、动态路由注入 | `setupAccessGuard` |
| `store/auth.ts` | 登录、用户/权限码加载、退出 | `useAuthStore` |
| `views/_core` | 登录、错误、关于、个人页 | Profile 只读展示 `/user/info` 的会话身份字段；不暴露未接后端的密码、MFA 或通知设置 |
| `views/dashboard` | Dashboard 页面 | 后端 V3 菜单映射；Workspace 快捷导航按当前已注册 route name 过滤 |
| `views/system` | 用户、角色、菜单、部门页面 | 当前 IAM 管理 UI |

## 5. 关键运行数据流

### 登录与权限

```text
login.vue
  -> authStore.authLogin
  -> POST /auth/login
  -> store Cookie 会话标记（真实 token 仅 HttpOnly Cookie）
  -> GET /user/info + GET /auth/codes
  -> router guard
  -> GET /menu/all
  -> reject static core/fallback/local route name/path collisions
  -> backend route conversion + local Profile
  -> accessStore menus/routes
```

前端 store 保存的是 `cookie-session` 非敏感状态标记，真正会话由当前账号域独立的 HttpOnly Cookie 持有；marker 不会作为 Authorization 发送。共享请求层在首个 Cookie 写请求前单次获取 `GET /auth/csrf`，只在内存缓存严格格式的 Session-bound request proof，并以 `X-CSRF-Token` 附加到 POST/PUT/PATCH/DELETE；并发首次写共享同一请求，登录、退出和 401 恢复都会推进 generation 并清除旧 proof，迟到响应不能污染新 Session。登录和 OIDC handoff 没有既有 Cookie 授权上下文，不触发该 proof 获取；step-up start/handoff 使用当前 Cookie，因此必须经过同一 CSRF 客户端。三种构建策略使用独立 title、storage namespace、API 同源路径和 literal component glob/allowlist，MERCHANT/AGENT 产物不能静态包含 PLATFORM 系统管理页面。三套生产登录页不渲染用户名/密码字段，只跳转同源 `GET /api/auth/oidc/start`；Realm callback 经服务端验证后将短期一次性 login 或 step-up handoff 送到 history 路由 `/auth/oidc/callback`，页面严格要求两者只出现一个；登录 handoff 成功后才建立本端状态，step-up handoff 不重建会话。本地开发构建仍保留账密表单用于验收。

三套应用统一装配 `/system/user` 与 `/system/role`，旧 `/identity/members` 和 `/identity/tenant-bootstrap` 页面已退出路由与构建产物。IAM-002 的邀请、MFA 恢复和租户初始化 endpoint 暂按 expand/contract 保留，但不再形成独立产品页面；后续能力进入 User Management 或 PLATFORM 专用系统管理员命令。PLATFORM 只有在部署账号域为 PLATFORM 且 `/user/info.systemAdministrator=true` 时才启用跨域目录、账号域筛选和目标 Tenant 选择；普通 PLATFORM 管理员与 MERCHANT/AGENT 一样走当前 Session Tenant 的 User/Role API。前端标记只控制界面，后端仍重验固定账号域、可信 Tenant、受保护系统角色、当前版本和需要时的 LoA 2。

登录后的 redirect 只接受当前动态路由中可访问的站内绝对路径；历史双重编码值最多解两层，根路径、登录页、站外或不可访问路径统一回退到后端 `/user/info.homePath`，避免受限角色被固定 `/dashboard` 导向 404。退出登录把原始当前路径交给 Vue Router 编码，不再手工预编码查询参数。同一前端实例对重复 login 使用 single-flight，并按响应完成顺序串行 login→logout 和 logout→login，避免迟到的 `Set-Cookie` 或清 Cookie 响应覆盖较新的会话；共享登录组件在 loading 时也拒绝 Enter/点击重复提交。登录和退出使用专用认证请求客户端：它保留 Cookie、响应解包和错误提示，但不安装业务请求的 401 refresh/re-auth 拦截器，因此错误凭证只拒绝当前登录，logout 401 也不会再次调用 logout。新的登录尝试及退出都会清空旧用户、权限码、会话 marker 与动态路由，并推进 session generation 和 route generation；`/user/info` 与 `/auth/codes` 必须同时返回且 session generation 仍有效才原子写入，旧菜单请求还必须通过 route generation 复核才能写 Router 或 access store。动态可访问路由生成成功后，守卫还会按当前 Router 重新解析 sessionStorage 恢复的标签页，通用移除已退役或不再授权的路径并同步访问历史与页面缓存；合法 query 和 affix 标签保持不变。真实 memory Router 与 store 回归覆盖正常清理、退出发生在菜单请求完成前、login/logout 响应排序、401 后重试、旧身份请求延迟返回和持久标签收敛，保证单一前端实例内旧身份、按钮权限、Cookie 写顺序、路由和标签页不回挂，且 `/auth/login` 始终解析为原始 `Login`。跨标签页认证写请求尚未使用 Web Locks 或服务端 attempt nonce 串行化，仍属于真实浏览器安全演练项。

本地开发服务器可在进程环境中注入 `VITE_LOCAL_ADMIN_USERNAME` 和 `VITE_LOCAL_ADMIN_PASSWORD`，登录页只在 `import.meta.env.DEV=true` 时预填，仍由开发者点击登录。真实值不得写入受版本控制的 `.env*`、源码、日志、截图或测试产物；生产模式即使存在同名变量也必须返回空默认值，并用合成哨兵构建确认产物不包含凭据。

Workspace 的快捷导航不是独立授权来源。`views/dashboard/workspace/workspace-navigation.ts` 为每个入口绑定后端菜单契约使用的 route name，页面通过 `router.hasRoute` 过滤未被当前动态菜单注册的入口；新增快捷入口必须继续满足该约束。

### 列表与表单

```text
views/system/*/list.vue
  -> data.ts schemas/columns
  -> app adapter VbenForm/VxeTable
  -> api/system/*.ts
  -> { code, data: { items, total } }
```

权限按钮通过 action `auth` 或 Cell renderer 的 `auth` 调用 `useAccess().hasAccessByCodes`。这只决定前端是否显示；服务端仍须授权。

用户和角色查询表单只在显式查询或重置时提交；部门树选择是独立的即时筛选，并只发送标量 `deptId`，提供明确清空入口和失败重试。PLATFORM control plane 仅在账号域筛选为 `PLATFORM` 时显示并使用当前 Session Tenant 部门；切换到 `MERCHANT/AGENT` 会立即清空部门筛选，查询构造器也会二次删除残留 `deptId`。普通 PLATFORM、MERCHANT 和 AGENT 页面仍使用各自当前 Tenant 的部门。管理列表保留 DISABLED live row 供恢复；墓碑一律隐藏，跨模块部门、角色、菜单候选只提供 ACTIVE live row，编辑历史对象时仅把其当前禁用依赖作为只读固定项。用户抽屉每次打开都递增请求版本并重载部门候选；过滤后的空子树必须覆盖原 `children`，不能让启用父节点把已禁用子部门重新带入选择器。用户新建表单只提供 ACTIVE、assignable、非 system 的角色；只有 `user:create` 而没有 `user:assign-role` 时仍显式提交空 `roleIds`。用户抽屉读取一页 `pageSize=200` 的 ACTIVE 角色并最多 8 并发精确补取当前角色；下拉搜索 300ms 防抖并丢弃旧响应。普通管理员编辑 payload 只含 Membership 字段；`/user/info.systemAdministrator=true` 时才启用 username/name/remark，并提交 user/identity/credential 三版本。用户列表只为同时持有 `user:update` 且 `/user/info.systemAdministrator=true` 的当前会话提供本地密码重置。弹窗使用 Web Crypto 无偏生成 16 至 24 位密码，固定包含大小写字母、数字和恰好 4 个 `!@#$%^&*` 特殊字符；重新生成不提交，确认时才发送。普通同租户行走 `/system/user/{id}/password/reset`，PLATFORM control plane 的 MERCHANT/AGENT 行走 `/platform/users/{id}/password/reset` 并携带服务端复核所需 domain/tenant 绑定；关闭、成功和失败均清空前端密码值，成功或乐观锁冲突后刷新列表。

用户列表的“分配角色”使用独立抽屉和 `PUT /system/user/{id}/roles`，只在同租户行且具备 `user:assign-role/user:view/role:view` 时显示，不能用于 PLATFORM 跨域目录。角色列表的“分配用户”只对 assignable、非 system 角色显示；抽屉加载已分配与未分配成员，提交最多 200 个带 `userVersion` 的差异到单个 PATCH，不允许前端循环单用户请求形成部分成功。

[ADR-0012](../../adr/0012-expose-platform-cross-domain-role-and-menu-directories.md) 已接受 Role/Menu 的 PLATFORM 跨域只读模式。Candidate 源码基线已包含 directory target 类型、Role/Menu API client、归属列、页面 target 请求编排和基于 `managementMode=READ_ONLY` 的动作收敛；`useAccountDomainDictionary` 已固定 allowlist、消费字典 color/order，并按 locale-safe 规则始终使用应用 `zh-CN/en-US` i18n label。目标页面的 `accountDomain` 缺省为 `PLATFORM`：此时 directory endpoint 由服务端固定 source Session Tenant，并返回 `SAME_TENANT`，页面按原权限保留 CRUD。选择 `MERCHANT/AGENT` 后必须先选择精确 target Tenant，再分别只调用 `/platform/role-directory` 或 `/platform/menu-directory`；角色的新增/编辑/启停/删除/分配用户/Grant 配置和菜单的新增下级/编辑/删除/唯一性检查全部不可装配。切换域或 Tenant 必须清理旧页码、选中行、展开树和弹窗快照，迟到响应不得覆盖新 target。

固定 Node.js 24.16.0 下的 Vitest、runtime/platform typecheck、三应用构建、制品隔离和 PLATFORM 真实浏览器回归已纳入 Candidate 验证面。代码、规则或文档变化后必须对新的不可变 commit 重跑，不能据任何旧 SHA 的证据宣称 Production GO。

Role item/Menu node 的 `accountDomain`、字符串 `tenantId`、`tenantName` 和 `managementMode` 来自服务端，只用于归属展示与防误操作上下文。菜单响应每次只有一个 Tenant 的树，前端不得把多个 Tenant 的节点合并。UI 隐藏不是权限边界；任何 `READ_ONLY` row 都不能复用普通 `/system/role/**`、`/v1/iam/roles/**` 或 `/system/menu/**` mutation helper。

角色列表不再提供独立“功能权限”操作；新增和编辑抽屉在同一个多层树中展示 ACTIVE 导航和可分配 BUTTON。用户显式勾选导航节点时，前端联动其全部 ACTIVE 导航后代与可分配 BUTTON 后代；单独勾选 BUTTON 只选择该 BUTTON 和必要的导航祖先，不自动勾选任何兄弟或跨分支 BUTTON，单独取消也只取消该 BUTTON。编辑页初始化只按既有 `menuIds` 与 Grant 回显，禁止从导航关系或前端动作依赖静默推导新 Grant。取消导航节点只清除该导航子树。新建通过 `POST /v1/iam/roles/configuration` 原子提交完整配置，编辑通过 `PUT /v1/iam/roles/{id}/configuration` 原子替换；两者都只把导航 ID 写入 `iam_role_menu`，BUTTON 权限写入 RoleGrant，BUTTON ID 不进入 `menuIds`。system/non-assignable 角色不可变更，包含当前页面无法无损表达的 Grant 时整个配置只读。

字典数据隐藏路由及其兼容 BUTTON 不进入角色树；角色只选择 `dictionary:view/create/update/delete`。任一字典管理权限在组装 Grant 时自动携带内部 `dictionary-data:view` 供批量枚举消费，旧 `dictionary-data:create/update/delete` 不展示也不新授予；字典数据 CRUD 的真实按钮和 API 权限统一使用 `dictionary:update`。

部门和菜单管理中的 `systemManaged` 只表示 local bootstrap 来源，不是前端不可变锁。未软删除的预置部门/菜单允许编辑；ACTIVE 预置部门和 ACTIVE 非 BUTTON 预置菜单允许新增下级。BUTTON、禁用父节点、墓碑、自身/后代父节点和权限依赖等通用约束继续执行。

## 6. API 与类型约定

- 普通接口经 `defaultResponseInterceptor` 解包：成功码 `0`，数据字段 `data`。
- 页面列表返回 `{ items, total }`，对应 VxeTable adapter 的响应映射。
- Long ID 使用字符串，避免 JavaScript 精度丢失。
- 产品路由模式由 `router/product-access.ts` 固定为 `mixed`；缓存偏好、偏好重置和框架切换控件不能改变该模式。
- Role、Department、Menu 列表项必须保留后端 `rowVersion`；更新/状态切换用 body `expectedVersion`，删除用 query `expectedVersion`。User 删除把 `userVersion` 作为 expectedVersion。
- 删除成功表示软删除：行仍在数据库但管理列表必须消失；DISABLED 不等于删除，仍可在自身管理页面查询。
- 40902 `OPTIMISTIC_LOCK_CONFLICT` 表示当前表单快照已过期：错误拦截器展示后端可读 message，页面关闭旧编辑态并刷新列表。40901 `DATA_CONFLICT` 是唯一键、树依赖等业务冲突，不能自动按 stale reload 处理。
- 登录返回 `{ accessToken: 'cookie-session' }`；这只是前端状态协议。
- `/menu/all` 返回 `RouteRecordStringComponent[]`；title 和 component 规则见 [Vben 基线](../vben/README.md)。
- 业务接口类型放在 API 模块，跨模块稳定类型才进入 `@vben/types`。
- 页面批量消费系统字典时从 `@payment/backoffice-runtime/composables` 调用 `useSystemDictionaries(...dictTypes)`；调用方通过 `getOptions/getItem/getLabel` 读取响应式结果并处理公开的 loading/error 状态。权限感知调用在缺少 `dictionary-data:view` 时不得发请求，可使用受控静态回退；已有权限但加载失败时必须暴露错误和重试入口。`SYS_COMMON_STATUS` 只允许固定数值语义 `1|0`，字典响应决定顺序和 Tag color，label 在字典具备 locale 模型前继续走 `common.enabled/common.disabled`，额外值不会进入表单或 DTO。禁止在页面或共享工具中另建永久浏览器缓存、逐类型请求、静默吞错或让字典改变鉴权、状态机和账号域判断。
- “所属平台”Select/Tag 使用固定应用映射 `1 -> PLATFORM`、`2 -> MERCHANT`、`3 -> AGENT`，只消费 `BELONG_SYSTEM` 的 color/order；label 始终来自应用 `zh-CN/en-US` i18n，因为当前字典没有 locale 维度。过滤器的合法选项和发给 Identity API 的值始终是 `PLATFORM|MERCHANT|AGENT`，未知字典值忽略；缺失、重复、非法或加载失败时保留三项固定选项和应用 color/order 回退。字典不能增加第四个域、移除合法域、选择 Tenant 或改变按钮权限。
- 字典管理及隐藏的字典数据详情页只进入 PLATFORM deployment。MERCHANT/AGENT 不注册 `/system/dict/data`、不包含对应页面制品且不显示字典菜单；它们保留 `dictionary-data:view` 与批量读取 composable，仅供用户、角色等实际业务页面渲染字典化 Select/Tag。前端隐藏不是鉴权边界，后端组合根仍必须拒绝两端字典写入。

详细字段见 [Identity Admin API 契约](../../ai-contract/identity-admin-api-contract.md)；
系统级字典的三端只读/写入边界及单一 query 路由见
[System Dictionary API 契约](../../ai-contract/system-dictionary-api-contract.md)。

## 7. 开发和验证

编辑器统一从仓库根目录打开。共享配置位于仓库根目录 `.vscode`；其中 Tailwind、Oxc、TypeScript SDK、i18n、CSS Variables 和调试配置使用 `frontend/admin/` 前缀定位本工作区。i18n Ally 必须同时扫描基础语言包、共享 backoffice runtime、Playground 和应用语言包目录，主语言固定为实际目录名 `en-US`，并按 `{locale}/{namespace}.{ext}` 解析。两空格缩进和 Oxc 默认 formatter 只绑定前端语言，不能覆盖后端 Java 或全仓 Markdown。禁止在 `frontend/admin` 下恢复嵌套 `.vscode` 或 `.code-workspace`，避免同一工具在不同打开方式下产生不同结果。

从 `frontend/admin` 执行：

```bash
pnpm install
pnpm run dev:platform  # 127.0.0.1:5999
pnpm run dev:merchant  # 127.0.0.1:6002
pnpm run dev:agent     # 127.0.0.1:6001
pnpm run lint
pnpm --filter @payment/backoffice-runtime --filter '@payment/*-admin' --parallel run typecheck
pnpm run test:production-safety
pnpm run build:backoffices
node scripts/deploy/verify-three-artifacts.mjs .
pnpm test:unit
```

### 生产部署边界

- 三应用各自的 `.env.production` 固定账号域、存储命名空间并使用同源 `/api`；生产入口网关必须把每个应用的 `/api` 转发到匹配的账号域 API 根，产品构建不得连接 Vben 公网 Mock。
- 产品入口默认不加载第三方统计脚本。确需接入分析服务时必须单独完成数据合规、安全评审和显式配置，不能在 HTML 中硬编码。
- `scripts/deploy/Dockerfile` 的 `APP_NAME` 只接受 `platform-admin`、`merchant-admin`、`agent-admin`，并只构建、复制对应应用的 `dist`。`verify-three-artifacts.mjs` 校验三个独立应用的 manifest、namespace、API 和页面边界，并拒绝 manifest 未引用的残留 JS/CSS。Playground 仅用于本地示例，禁止进入产品镜像。
- 依赖安装 lifecycle 不使用 `npx`/`pnpm dlx`；原 `preinstall: npx only-allow pnpm` 已删除，`production-safety.test.ts` 会扫描 lifecycle 脚本防止回归。手动 `update:deps`/`catalog` 命令不是安装 lifecycle，不得在未评审情况下自动触发。
- `scripts/deploy/production-safety.test.ts` 守护上述边界；业务 CI 在前端变更时执行 frozen install、全量 lint、产品 app typecheck、单测、production-safety、三产物构建和产物隔离验证。

Playground：

```bash
pnpm dev:play
pnpm -F @vben/playground run typecheck
pnpm -F @vben/playground run test:e2e
```

不要默认执行整个 monorepo 的格式化来改写与任务无关文件。应用功能优先跑应用级 typecheck、相关 Vitest，再按风险执行构建和浏览器测试。

## 8. 改动检查清单

- [ ] 已读 Vben 对应官方页面和本项目 Vben 基线。
- [ ] 已在 Playground/当前应用找到同版本实现，而非复制旧版 Vben 代码。
- [ ] 使用 `antdv-next` 和当前 app adapter，不引入另一套 UI 库。
- [ ] 菜单 title 是 i18n key，双语言包同步。
- [ ] component 可映射到真实 `views/**/*.vue`。
- [ ] 权限码与后端一致，后端仍执行鉴权。
- [ ] API envelope、分页和 ID 类型符合契约。
- [ ] 修改管理资源时保留 rowVersion/userVersion 并回传 expectedVersion；专用乐观锁错误触发重新加载，普通 DATA_CONFLICT 不误判。
- [ ] PLATFORM 跨域 Role/Menu 行只使用专用 directory GET；target domain/Tenant 切换清理旧状态，且没有普通 CRUD、成员分配或 Grant mutation 调用。
- [ ] 所属平台字典仅装饰固定三域 allowlist，缺失/额外/失败数据有安全回退。
- [ ] 运行 typecheck、相关测试；路由/交互变化做浏览器验证。
- [ ] 新约定或结构同步到本文件。

## 9. 证据索引

- 版本与依赖：`frontend/admin/package.json`、`pnpm-workspace.yaml`。
- 编辑器配置：仓库根目录 `.vscode`。
- 应用依赖：`apps/{platform-admin,merchant-admin,agent-admin}/package.json`、`packages/effects/backoffice-runtime/package.json`。
- 启动链：各应用 `src/main.ts` 与 `src/deployment.ts`，共享运行时 `src/start.ts`、`bootstrap.ts`、`app.vue`。
- 路由权限：共享运行时 `src/router/access.ts`、`guard.ts`、`packages/effects/access/src/accessible.ts`。
- component 转换：`packages/utils/src/helpers/generate-routes-backend.ts`。
- i18n、组件适配、请求与登录：`packages/effects/backoffice-runtime/src/{locales,adapter,api,store}`。
- 示例：`frontend/admin/playground`。它只提供当前源码模式，不是产品需求或可复制的第二套实现。
## 10. MCH-002 Candidate frontend implementation

The PLATFORM Merchant list adds Edit beside Detail. ACTIVE/DISABLED render an antdv-next Switch; all other lifecycle states render a Tag. MCH-003 retires the old detail drawer: detail and review now use separate hidden full-page routes backed by the same complete read-only profile component, while the review route adds one modal-triggering Review action. Market and filter Select controls use `allowClear`; market is multi-select BRA/PHL. The old-page business labels Merchant Type, Legal Person Name and Authentication Type are retained, while business email remains excluded. Their legal values stay in a fixed application allowlist; dictionary data may provide order/color only and cannot introduce a new value or authorization meaning. The current worktree implements these rules and has passed focused tests, type checks and real PLATFORM/MERCHANT/AGENT browser verification; it remains a mutable Candidate until exact-SHA gates and independent review complete.

## 11. MCH-003 frontend mutable Candidate

The former `implementation pending` label is historical and no longer describes the frontend.
Under Node.js 24, the mutable MCH-003 Candidate passed the full 128-file/975-test unit suite, five
typechecks, 31 production-safety tests, `build:all` and isolation checks for all three application
artifacts. The current browser flow verified three inline actions with no overflow, centered localized
market text, matching full-page detail/review content, the single review modal action, and complete
create/edit forms; page-internal timing measured about 335ms to mount create and 460ms to hydrate edit.
Unmount invalidates pending protected-document previews and create/amend submissions before they can
create a Blob URL, show success or navigate. Temporary uploads participating in a mutation are held
through teardown until server-side binding resolves instead of being deleted by child cleanup. While
that mutation is pending, Tenant, all form fields, protected-value switches and upload controls are
disabled, and component methods reject programmatic delete, replace and Tenant invalidation.
A fresh author-independent review remains required after freeze. PLATFORM create and edit reuse one `full-page` form for
the exact 23-input/five-document profile, while detail and review reuse one separate read-only
full-page presentation. Review adds a single action that opens the approve/reject and bounded-reason
modal; edit submits an amendment and never calls the MCH-002 profile PUT. The UI keeps protected
numbers masked, uses temporary document IDs for five image kinds and never persists public URLs or
identity plaintext.

Edit hydrates the five current document IDs as the default proposal. The backend classifies each
one as the exact current same-kind `RETAIN` binding or a same-actor temporary `REPLACE`, so changing
ordinary profile fields does not require five redundant uploads and replacing one document leaves
the other four unchanged. Submission freezes all upload controls and invalidates any replacement
upload that had not entered the submitted snapshot.

The market column is centered and shows localized labels without codes. Up to three actions remain
inline; additional actions use the existing `click`-triggered overflow. Incomplete endpoints or DTO
decode failures keep create/edit hidden instead of exposing a partial capability.

`registrationCountry` uses a clearable assigned-ISO-alpha-2 Select and rejects unassigned values
such as `ZZ`. When edit changes registration country, registration number is forced and locked to
`REPLACE`; changing `legalIdTypeCode` does the same for `legalIdNo`. Restoring the original context
allows `RETAIN` again, so the frontend cannot submit a context change with stale protected data.

Pending amendment preview calls the existing Merchant document-content path with the exact
`amendmentId` query. It must not retry without the query or display a cached current-effective image
after a 404; failure leaves the proposed evidence explicitly unavailable for review.

The implemented interaction also keeps the first three available actions inline and moves the rest
to the click overflow, centers localized market labels, and restricts review controls to eligible
independent reviewers. A real browser run created Merchant `10786` as `PENDING_REVIEW` with reason
`PLATFORM_APPLICATION_SUBMITTED`; reviewer Membership `1001` approved it to `ACTIVE` with
`PROFILE_VERIFIED`, and the same full-page edit route hydrated all 23 fields and five documents.
The acceptance window reported zero console errors or warnings. This browser evidence and the full
frontend gates remain mutable working-tree facts; they do not make the worktree Production GO or
replace exact-SHA gates and signed author-independent review.

The current performance correction keeps all three edit-form adapters unmounted until the detail,
pending state and dictionaries are ready, then applies one schema state and one value hydration per
adapter. It removes the previous deep watcher and duplicate load-tail schema rebuild. A warmed local
browser comparison reduced list-click-to-first-input from about 850 ms to 53.5 ms, full edit
hydration from about 1709 ms to 911 ms, and rendered nodes from about 3300 to 961. These are local
development measurements, not production SLO evidence.
