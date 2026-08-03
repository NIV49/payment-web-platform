# Admin 前端工程上下文

> 适用目录：`frontend/admin/**`
> 当前基线：Node.js 24.16.0 / Vben 5.7.0 / Vue 3.5.40 / Vite 8.1.5 / pnpm 11.7.0 / Antdv Next 1.4.5

版本事实来源：`.node-version` 与产品 Docker builder 固定 Node.js 24.16.0，`engines.node` 允许同一 major 内的 `>=24.11.0 <25`；`packageManager` 与 `engines.pnpm` 固定 pnpm 11.7.0；工作区 package version 为 Vben 5.7.0，当前锁文件解析 Vue 3.5.40 和 `antdv-next` 1.4.5。2026-07-30 已把 Vben 框架增量同步至官方 `main` 的 `418c16e0939262b3d0037fbd0c378c3ce34c7642`，但项目工具链、裁剪边界和业务适配仍以本仓库为准。不能再用本机偶然安装的版本或未同步的上游最新版本描述本项目。

## 1. 工程定位

`frontend/admin` 是独立 pnpm + Turborepo monorepo。它保留一个产品应用、一个 Mock 应用和 Playground，同时保留 Vben 共享包与工程工具。`frontend/portal` 是未来 Nuxt 4 多应用 monorepo 的占位目录，不属于本工程工作区。

运行时主要依赖方向：

```text
apps/web-antdv-next
  -> packages/effects/*        组合后的业务框架能力
  -> packages/*                locale/store/preferences/types/utils
  -> packages/@core/*          无业务或低耦合核心
  -> internal/*                构建、类型和代码规范（构建期）
```

应用代码可以依赖共享包；共享包不能反向依赖应用。

## 2. 顶层目录职责

| 目录/文件 | 职责 | 修改原则 |
| --- | --- | --- |
| `apps/web-antdv-next` | 产品 Admin 应用 | 业务页面、API、路由、locale、应用适配放这里 |
| `apps/backend-mock` | Nitro Mock 服务 | 仅本地演示/隔离开发，不是生产后端 |
| `playground` | Vben 完整演示应用与 E2E | 查用法、提取模式；不承载产品功能 |
| `packages/@core` | 设计、基础工具、类型、偏好和 UI 内核 | 框架层；非通用需求不改 |
| `packages/effects` | access、request、layouts、common-ui、plugins | 跨应用集成层；改动影响面大 |
| `packages/*` | 常量、图标、locale、store、style、type、utils 等公共门面 | 保持通用、稳定、无应用业务 |
| `internal/lint-configs` | Oxfmt/Oxlint/ESLint/Stylelint/Commitlint 配置 | 代码规范基础设施 |
| `internal/node-utils` | 工程脚本共用的 Node 工具 | 只服务构建/脚本 |
| `internal/tailwind-config` | Tailwind 主题与共享配置 | 全局视觉基础 |
| `internal/tsconfig` | 共享 TypeScript 配置 | 不在应用内复制配置 |
| `internal/vite-config` | `defineConfig`、插件和默认 Loading | 应用 Vite 配置的统一入口 |
| `scripts/turbo-run` | 交互选择并运行 turbo task | 工程命令 |
| `scripts/vsh` | 循环依赖、依赖、lint、发布检查 CLI | 工程质量门禁 |
| `scripts/deploy` | 产品 Admin 容器/Nginx 构建与生产安全回归测试 | 只构建、复制 `web-antdv-next`，禁止部署 Playground |
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

## 4. `web-antdv-next/src` 模块地图

| 目录/文件 | 职责 | 关键入口 |
| --- | --- | --- |
| `main.ts` | 偏好命名空间初始化与延迟 bootstrap | `initApplication` |
| `bootstrap.ts` | Vue App 组合根 | `bootstrap` |
| `app.vue` | Antdv Next ConfigProvider、主题、RouterView | 根组件 |
| `preferences.ts` | 应用覆盖配置 | 保留 backend 按钮权限码语义，不决定产品路由模式 |
| `adapter/component` | Antdv Next 控件注册、ApiComponent、全局共享组件 | `initComponentAdapter` |
| `adapter/form.ts` | VbenForm model/rule 适配 | `initSetupVbenForm` |
| `adapter/vxe-table.ts` | VxeTable 分页/Cell/权限操作适配 | `setupVbenVxeTable` |
| `api/request.ts` | baseURL、Cookie、envelope、401 和错误拦截 | `requestClient` |
| `api/core` | 登录、用户、菜单、上传契约；当前用户响应显式校验后映射 | `/auth/*`, `/user/info`, `/menu/all` |
| `api/system` | 用户、角色、菜单、部门管理契约 | `/system/*` |
| `layouts` | Auth、Basic、IFrame 布局应用封装 | Router component |
| `locales` | 应用语言包与 Antdv/Day.js locale | `setupI18n`, `$t` |
| `router/routes/core.ts` | 登录、错误页等无业务核心路由 | 永久注册 |
| `router/routes/modules` | mixed 模式本地 allowlist 与未注册参考源码 | 产品只注册 `profile.ts` |
| `router/product-access.ts` | 固定 mixed 模式和本地 Profile name/path 冲突保护 | `PRODUCT_ACCESS_MODE` |
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
  -> reject reserved Profile name/path collisions
  -> backend route conversion + local Profile
  -> accessStore menus/routes
```

前端 store 保存的是 `cookie-session` 非敏感状态标记，真正会话由 `PAYMENT_SESSION` HttpOnly Cookie 持有。`api/core/user-contract.ts` 对 `/user/info` 做显式运行时映射：`userId/avatar/desc/homePath/roles` 等字段必须满足契约，`token` 必须精确等于 `cookie-session`；`systemAdministrator` 只有严格为 `true` 才启用系统角色管理能力，缺失或畸形时默认拒绝；未知附加字段会被忽略。该 marker 不会在获取用户信息时写回 access-token store。请求设置 `withCredentials: true`。这个方案与 Vben 默认 Bearer token 示例不同，修改认证代码时必须同时核对 `api/session.ts`、`api/request.ts`、Auth Store 和后端 Sa-Token Cookie 配置。

登录后的 redirect 只接受当前动态路由中可访问的站内绝对路径；历史双重编码值最多解两层，根路径、登录页、站外或不可访问路径统一回退到后端 `/user/info.homePath`，避免受限角色被固定 `/dashboard` 导向 404。退出登录把原始当前路径交给 Vue Router 编码，不再手工预编码查询参数。

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

用户和角色查询表单只在显式查询或重置时提交；部门树选择是独立的即时筛选，并只发送标量 `deptId`，提供明确清空入口和失败重试。管理列表保留 DISABLED live row 供恢复；墓碑一律隐藏，跨模块部门、角色、菜单候选只提供 ACTIVE live row，编辑历史对象时仅把其当前禁用依赖作为只读固定项。用户抽屉每次打开都递增请求版本并重载部门候选；过滤后的空子树必须覆盖原 `children`，不能让启用父节点把已禁用子部门重新带入选择器。用户新建表单只提供 ACTIVE、assignable、非 system 的角色；只有 `user:create` 而没有 `user:assign-role` 时仍显式提交空 `roleIds`。用户抽屉读取一页 `pageSize=200` 的 ACTIVE 角色并最多 8 并发精确补取当前角色；下拉搜索 300ms 防抖并丢弃旧响应。普通管理员编辑 payload 只含 Membership 字段；`/user/info.systemAdministrator=true` 时才启用 username/name/remark，并提交 user/identity/credential 三版本。用户列表只为同时持有 `user:update` 且 `/user/info.systemAdministrator=true` 的当前会话提供重置密码操作，确认后把列表快照的 `credentialVersion` 提交给 `/system/user/{id}/password/reset`，成功或乐观锁冲突后刷新列表。

角色列表不再提供独立“功能权限”操作；新增和编辑抽屉在同一个多层树中展示 ACTIVE 导航和可分配 BUTTON。用户显式勾选导航节点时，前端联动其全部 ACTIVE 导航后代与可分配 BUTTON 后代；单独勾选 BUTTON 只选择该 BUTTON 和必要的导航祖先，不自动勾选任何兄弟或跨分支 BUTTON，单独取消也只取消该 BUTTON。编辑页初始化只按既有 `menuIds` 与 Grant 回显，禁止从导航关系或前端动作依赖静默推导新 Grant。取消导航节点只清除该导航子树。新建通过 `POST /v1/iam/roles/configuration` 原子提交完整配置，编辑通过 `PUT /v1/iam/roles/{id}/configuration` 原子替换；两者都只把导航 ID 写入 `iam_role_menu`，BUTTON 权限写入 RoleGrant，BUTTON ID 不进入 `menuIds`。system/non-assignable 角色不可变更，包含当前页面无法无损表达的 Grant 时整个配置只读。

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

详细字段见 [Identity Admin API 契约](../../ai-contract/identity-admin-api-contract.md)。

## 7. 开发和验证

编辑器统一从仓库根目录打开。共享配置位于仓库根目录 `.vscode`；其中 Tailwind、Oxc、TypeScript SDK、i18n、CSS Variables 和调试配置使用 `frontend/admin/` 前缀定位本工作区。两空格缩进和 Oxc 默认 formatter 只绑定前端语言，不能覆盖后端 Java 或全仓 Markdown。禁止在 `frontend/admin` 下恢复嵌套 `.vscode` 或 `.code-workspace`，避免同一工具在不同打开方式下产生不同结果。

从 `frontend/admin` 执行：

```bash
pnpm install
pnpm dev:antdv-next
pnpm run lint
pnpm -F @vben/web-antdv-next run typecheck
pnpm run test:production-safety
pnpm build:antdv-next
pnpm test:unit
```

### 生产部署边界

- `apps/web-antdv-next/.env.production` 固定使用同源 `/api`；生产入口网关必须把 `/api` 转发到后端，产品构建不得连接 Vben 公网 Mock。
- 产品入口默认不加载第三方统计脚本。确需接入分析服务时必须单独完成数据合规、安全评审和显式配置，不能在 HTML 中硬编码。
- `scripts/deploy/Dockerfile` 只执行 `build:antdv-next`，且只复制 `apps/web-antdv-next/dist`。Playground 仅用于本地示例，禁止进入产品镜像。
- 依赖安装 lifecycle 不使用 `npx`/`pnpm dlx`；原 `preinstall: npx only-allow pnpm` 已删除，`production-safety.test.ts` 会扫描 lifecycle 脚本防止回归。手动 `update:deps`/`catalog` 命令不是安装 lifecycle，不得在未评审情况下自动触发。
- `scripts/deploy/production-safety.test.ts` 守护上述边界；业务 CI 只由仓库根目录 `.github/workflows/frontend.yml` 定义，并在前端变更时执行 frozen install、全量 lint、产品 app typecheck、单测、production-safety 和产品构建。`frontend/admin` 内不保留嵌套 `.github`，避免出现 GitHub 不会加载的失效工作流和仓库元数据。

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
- [ ] 运行 typecheck、相关测试；路由/交互变化做浏览器验证。
- [ ] 新约定或结构同步到本文件。

## 9. 证据索引

- 版本与依赖：`frontend/admin/package.json`、`pnpm-workspace.yaml`。
- 编辑器配置：仓库根目录 `.vscode`。
- 应用依赖：`apps/web-antdv-next/package.json`。
- 启动链：`src/main.ts`、`src/bootstrap.ts`、`src/app.vue`。
- 路由权限：`src/router/access.ts`、`guard.ts`、`packages/effects/access/src/accessible.ts`。
- component 转换：`packages/utils/src/helpers/generate-routes-backend.ts`。
- i18n：`src/locales/index.ts`、`src/locales/langs/**`。
- 组件适配：`src/adapter/component/index.ts`、`form.ts`、`vxe-table.ts`。
- 请求与登录：`src/api/request.ts`、`src/api/error-contract.ts`、`src/store/auth.ts`。
- 示例：`frontend/admin/playground`、`docs/ai-context/playground`。
