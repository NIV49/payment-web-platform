# Playground 模块总览

## 项目地图

`playground` 是 Vben 5 仓库里的独立前端应用，不是 `apps/web-antd` 的子模块。它主要承担三件事：

- 管理后台基础能力演示：登录、鉴权、菜单、布局、偏好设置、时区。
- 系统管理业务雏形：用户、角色、菜单、部门 CRUD 页面。
- 组件/功能示例：表单、表格、上传、下载、请求序列化、BigInt、Vue Query 等。

核心目录：

| 路径 | 作用 |
| --- | --- |
| `playground/package.json` | `@vben/playground` 应用包，`pnpm -F @vben/playground run dev` 启动。 |
| `playground/.env` | 应用标题、命名空间、store 加密 key。 |
| `playground/.env.development` | 本地端口 `5555`、API 基址 `/api`、Nitro Mock 开关。 |
| `playground/.env.production` | 生产 API 默认指向远端 mock。 |
| `playground/vite.config.ts` | `/api` 代理到 `http://localhost:5320/api`，并 rewrite 掉前缀 `/api`。 |
| `playground/src/main.ts` | 应用初始化入口。 |
| `playground/src/bootstrap.ts` | Vue、Pinia、i18n、路由、插件、时区初始化入口。 |
| `playground/src/api/**` | 前端请求封装与业务 API。 |
| `playground/src/store/auth.ts` | 登录、退出、用户信息、权限码拉取。 |
| `playground/src/router/**` | 路由、守卫、权限路由生成。 |
| `playground/src/views/_core/**` | 登录页、注册页、个人中心、fallback 页面。 |
| `playground/src/views/system/**` | 用户、角色、菜单、部门管理页面。 |
| `playground/src/views/examples/**` | 组件示例，部分会调 mock 接口。 |
| `playground/src/views/demos/**` | 功能示例，部分会调 mock 接口。 |
| `apps/backend-mock/**` | Nitro mock 后端。不是浏览器内 mock，没有数据库。 |
| `packages/effects/request/**` | Axios 请求客户端、响应拦截器、token 刷新逻辑。 |
| `packages/effects/access/**` | 权限判断、动态路由生成。 |
| `packages/stores/src/modules/**` | 全局 Pinia store：access、user、tabbar、timezone。 |
| `packages/effects/plugins/src/vxe-table/**` | VXE 表格封装，决定分页响应字段。 |

### 扫描结果：相关文件清单

模块级边界很明确：`playground/**` 下 193 个文件都属于 playground 应用。后端交互和接手优先级最高的文件如下。

应用启动与配置：

- `playground/package.json`
- `playground/.env`
- `playground/.env.development`
- `playground/.env.production`
- `playground/vite.config.ts`
- `playground/src/main.ts`
- `playground/src/bootstrap.ts`
- `playground/src/app.vue`
- `playground/src/preferences.ts`
- `playground/src/timezone-init.ts`

接口层：

- `playground/src/api/request.ts`
- `playground/src/api/index.ts`
- `playground/src/api/core/auth.ts`
- `playground/src/api/core/user.ts`
- `playground/src/api/core/menu.ts`
- `playground/src/api/core/timezone.ts`
- `playground/src/api/system/dept.ts`
- `playground/src/api/system/menu.ts`
- `playground/src/api/system/role.ts`
- `playground/src/api/system/user.ts`
- `playground/src/api/examples/table.ts`
- `playground/src/api/examples/upload.ts`
- `playground/src/api/examples/status.ts`
- `playground/src/api/examples/params.ts`
- `playground/src/api/examples/json-bigint.ts`
- `playground/src/api/examples/download.ts`

路由、权限、状态：

- `playground/src/router/index.ts`
- `playground/src/router/guard.ts`
- `playground/src/router/access.ts`
- `playground/src/router/routes/index.ts`
- `playground/src/router/routes/core.ts`
- `playground/src/router/routes/modules/dashboard.ts`
- `playground/src/router/routes/modules/system.ts`
- `playground/src/router/routes/modules/demos.ts`
- `playground/src/router/routes/modules/examples.ts`
- `playground/src/router/routes/modules/vben.ts`
- `playground/src/store/auth.ts`
- `playground/src/store/index.ts`

布局与认证：

- `playground/src/layouts/basic.vue`
- `playground/src/layouts/auth.vue`
- `playground/src/views/_core/authentication/login.vue`
- `playground/src/views/_core/authentication/code-login.vue`
- `playground/src/views/_core/authentication/qrcode-login.vue`
- `playground/src/views/_core/authentication/forget-password.vue`
- `playground/src/views/_core/authentication/register.vue`
- `playground/src/views/_core/profile/index.vue`
- `playground/src/views/_core/profile/base-setting.vue`
- `playground/src/views/_core/profile/security-setting.vue`
- `playground/src/views/_core/profile/password-setting.vue`
- `playground/src/views/_core/profile/notification-setting.vue`

系统管理页面：

- `playground/src/views/system/user/list.vue`
- `playground/src/views/system/user/data.ts`
- `playground/src/views/system/user/modules/form.vue`
- `playground/src/views/system/user/modules/detail.vue`
- `playground/src/views/system/role/list.vue`
- `playground/src/views/system/role/data.ts`
- `playground/src/views/system/role/modules/form.vue`
- `playground/src/views/system/menu/list.vue`
- `playground/src/views/system/menu/data.ts`
- `playground/src/views/system/menu/modules/form.vue`
- `playground/src/views/system/dept/list.vue`
- `playground/src/views/system/dept/data.ts`
- `playground/src/views/system/dept/modules/form.vue`

mock 后端：

- `apps/backend-mock/api/auth/login.post.ts`
- `apps/backend-mock/api/auth/logout.post.ts`
- `apps/backend-mock/api/auth/refresh.post.ts`
- `apps/backend-mock/api/auth/codes.ts`
- `apps/backend-mock/api/user/info.ts`
- `apps/backend-mock/api/menu/all.ts`
- `apps/backend-mock/api/system/user/list.ts`
- `apps/backend-mock/api/system/role/list.ts`
- `apps/backend-mock/api/system/menu/list.ts`
- `apps/backend-mock/api/system/menu/name-exists.ts`
- `apps/backend-mock/api/system/menu/path-exists.ts`
- `apps/backend-mock/api/system/dept/list.ts`
- `apps/backend-mock/api/system/dept/[id].put.ts`
- `apps/backend-mock/api/system/dept/[id].delete.ts`
- `apps/backend-mock/api/table/list.ts`
- `apps/backend-mock/api/upload.ts`
- `apps/backend-mock/api/status.ts`
- `apps/backend-mock/api/demo/bigint.ts`
- `apps/backend-mock/api/timezone/getTimezone.ts`
- `apps/backend-mock/api/timezone/getTimezoneOptions.ts`
- `apps/backend-mock/api/timezone/setTimezone.ts`
- `apps/backend-mock/middleware/1.api.ts`
- `apps/backend-mock/utils/mock-data.ts`
- `apps/backend-mock/utils/response.ts`
- `apps/backend-mock/utils/jwt-utils.ts`
- `apps/backend-mock/utils/cookie-utils.ts`
- `apps/backend-mock/utils/timezone-utils.ts`

共享依赖关键文件：

- `packages/effects/request/src/request-client/request-client.ts`
- `packages/effects/request/src/request-client/preset-interceptors.ts`
- `packages/effects/access/src/accessible.ts`
- `packages/effects/access/src/use-access.ts`
- `packages/effects/access/src/directive.ts`
- `packages/stores/src/modules/access.ts`
- `packages/stores/src/modules/user.ts`
- `packages/stores/src/modules/timezone.ts`
- `packages/effects/plugins/src/vxe-table/use-vxe-grid.vue`
- `packages/effects/plugins/src/vxe-table/extends.ts`
- `packages/utils/src/helpers/generate-routes-backend.ts`
- `packages/utils/src/helpers/generate-routes-frontend.ts`
- `packages/utils/src/helpers/generate-menus.ts`

其它 playground 视图文件主要是 `playground/src/views/demos/**` 和 `playground/src/views/examples/**`，它们属于演示域；只有调用 `playground/src/api/examples/**` 或 `getAllMenusApi/getMenuList` 的页面会影响后端联调。

## 模块边界

### 必须和 Spring Boot 联调的核心边界

这些接口支撑登录、鉴权、菜单、系统管理，后端应优先实现：

- 认证：`/auth/login`、`/auth/logout`、`/auth/codes`、可选 `/auth/refresh`。
- 当前用户：`/user/info`。
- 运行时菜单：`/menu/all`。
- 系统管理：`/system/user/**`、`/system/role/**`、`/system/menu/**`、`/system/dept/**`。
- 时区偏好：`/timezone/getTimezoneOptions`、`/timezone/getTimezone`、`/timezone/setTimezone`。

### 演示接口边界

这些是组件演示用，不建议直接作为业务表结构依据：

- `/table/list`：示例表格假数据。
- `/upload`：上传组件演示，只返回固定图片 URL。
- `/status`：模拟 HTTP 状态码。
- `/demo/bigint`：演示超长整数解析。
- 外部下载 URL 和 `dummyjson.com`：不是后端业务接口。

## 应用入口

启动链路：

1. `playground/src/main.ts`
2. `initPreferences({ namespace, overrides, extension })`
3. 动态导入 `playground/src/bootstrap.ts`
4. `bootstrap(namespace)`
5. 初始化组件适配器、表单、插件配置
6. `createApp(App)`
7. 注册 loading 指令、i18n、Pinia store、时区 handler、权限指令、路由、Vue Query、Motion
8. 根据路由标题动态更新浏览器 title
9. `app.mount('#app')`

## 路由入口

路由实例：`playground/src/router/index.ts`

- history 模式由 `VITE_ROUTER_HISTORY` 决定，未配置时走 `createWebHistory`；生产 `.env.production` 默认 `hash`。
- 初始路由只包含核心路由和 404：`Root`、`Authentication`、`FallbackNotFound`。
- 业务路由来自 `playground/src/router/routes/modules/*.ts`，但这些不会直接进入初始路由；它们作为权限路由候选。

核心路由：

| 路径 | 页面 | 是否鉴权 |
| --- | --- | --- |
| `/` | `BasicLayout`，重定向到默认首页 `/analytics` | 核心路由自身不拦截，子业务页鉴权 |
| `/auth/login` | 登录页 | 不鉴权 |
| `/auth/code-login` | 手机验证码登录演示 | 不鉴权，未接后端 |
| `/auth/qrcode-login` | 二维码登录演示 | 不鉴权，未接后端 |
| `/auth/forget-password` | 忘记密码演示 | 不鉴权，未接后端 |
| `/auth/register` | 注册演示 | 不鉴权，未接后端 |
| `/:path(.*)*` | 404 | 不鉴权 |

业务路由模块：

| 文件 | 业务 |
| --- | --- |
| `playground/src/router/routes/modules/dashboard.ts` | `/analytics`、`/workspace` |
| `playground/src/router/routes/modules/system.ts` | `/system/user`、`/system/role`、`/system/menu`、`/system/dept` |
| `playground/src/router/routes/modules/demos.ts` | 功能演示、权限演示 |
| `playground/src/router/routes/modules/examples.ts` | 组件示例 |
| `playground/src/router/routes/modules/vben.ts` | 文档、GitHub、个人中心等 |

## 接口入口

统一请求入口：`playground/src/api/request.ts`

关键行为：

- `apiURL` 来自 `useAppConfig(import.meta.env, import.meta.env.PROD)`，实际读取 `VITE_GLOB_API_URL`。
- `requestClient` 默认 `responseReturn: 'data'`，会把后端 envelope 的 `data` 解出来。
- 普通接口成功条件是 `code === 0`。
- 成功 envelope 格式必须是：

```json
{
  "code": 0,
  "data": {},
  "error": null,
  "message": "ok"
}
```

- 请求头自动带：
  - `Authorization: Bearer <accessToken>`
  - `Accept-Language: <preferences.app.locale>`
- 响应 JSON 使用 `json-bigint` 解析，超出 JS 安全整数的数字会按字符串保存。
- `baseRequestClient` 不走统一业务 envelope 拦截，当前用于 `/auth/refresh`、`/auth/logout`。

开发态网络路径：

```text
浏览器请求 /api/auth/login
  -> Vite proxy
  -> http://localhost:5320/api/auth/login
  -> rewrite 后 Nitro 实际路由 /auth/login
```

## 状态入口

本应用直接定义的业务 store：

- `playground/src/store/auth.ts`：登录、退出、拉用户信息。

依赖共享 store：

| Store | 文件 | 关键字段 |
| --- | --- | --- |
| Access Store | `packages/stores/src/modules/access.ts` | `accessToken`、`accessCodes`、`accessMenus`、`accessRoutes`、`isAccessChecked`、`loginExpired` |
| User Store | `packages/stores/src/modules/user.ts` | `userInfo`、`userRoles` |
| Timezone Store | `packages/stores/src/modules/timezone.ts` | 时区偏好，handler 由 `playground/src/timezone-init.ts` 注入 |
| Tabbar Store | `packages/stores/src/modules/tabbar.ts` | 标签页行为和右键菜单 |

持久化点：

- `accessToken`、`refreshToken`、`accessCodes`、锁屏状态会持久化。
- `userInfo` 当前不持久化，刷新页面后会通过 `/user/info` 重新拉。
- namespace 格式：`${VITE_APP_NAMESPACE}-${VITE_APP_VERSION}-${dev|prod}`。

## Mock 后端边界

mock 服务在 `apps/backend-mock`：

- Nitro 服务，不连接数据库。
- `VITE_NITRO_MOCK=true` 时由 Vite 插件自动启动，默认端口 `5320`。
- mock 用户：`vben/123456`、`admin/123456`、`jack/123456`。
- 所有系统管理写操作会被 `apps/backend-mock/middleware/1.api.ts` 拦截并返回 `403`，错误文案为“演示环境，禁止修改”。

这意味着：当前前端已经写了 create/update/delete 调用，但 mock 环境不能验证真实写入闭环。Spring Boot 联调时必须真实实现这些写接口。

## 核心结论

1. 后端必须先对齐统一响应 envelope、分页字段 `items/total`、JWT header、菜单 component 字符串规则。
2. 当前默认 `preferences.app.accessMode` 是 `frontend`，首次生成路由时不一定调用 `/menu/all`。如果要后端控制菜单，需要改偏好为 `backend` 或 `mixed`，否则 `/menu/all` 只在部分 ApiSelect 示例和表单中使用。
3. 登录验证码只在前端校验，不进入 mock 后端。真实风控需要新契约。
4. `/auth/refresh` 是特殊接口，mock 直接返回 token 字符串，前端也按这个读；如果后端想返回统一 envelope，需要同步改前端。
5. 系统管理页面的数据模型只是雏形，用户-角色、角色-菜单、用户-部门关系有 UI 痕迹，但当前 mock 数据并不完整。

## 不确定项

- 真实项目是否要采用 `frontend`、`backend` 还是 `mixed` 权限模式，当前代码没有业务定论。
- 用户实体最终是否必须包含 `userId/avatar/desc/token`，类型里有要求，但 mock 只返回 `id/username/realName/roles/homePath`。
- 菜单管理表单里的 `activePath` 字段提交在顶层，但路由 meta 类型里通常是 `meta.activePath`，是否为设计疏漏未确认。
- 系统用户表单组件加载了菜单权限树，但表单 schema 没有 `permissions` 字段，用户是否直接分配菜单权限未确认。
