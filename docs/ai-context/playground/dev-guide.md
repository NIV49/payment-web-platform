# Playground 后续开发指南

## 修改指南总览

| 需求 | 优先改哪些文件 |
| --- | --- |
| 接 Spring Boot API 地址 | `playground/vite.config.ts`、`playground/.env.development.local`、`playground/.env.production` |
| 改统一响应格式 | `playground/src/api/request.ts` |
| 改分页字段 | `playground/src/adapter/vxe-table.ts` |
| 开启 refresh token | `packages/@core/preferences/src/config.ts` 或 `playground/src/preferences.ts` 覆盖；同时改 `/auth/refresh` 契约 |
| 改登录表单字段 | `playground/src/views/_core/authentication/login.vue`、`playground/src/api/core/auth.ts`、`playground/src/store/auth.ts` |
| 接手机验证码登录 | `playground/src/views/_core/authentication/code-login.vue`、新增 `playground/src/api/core/*` |
| 接注册/忘记密码 | `playground/src/views/_core/authentication/register.vue`、`forget-password.vue`、新增 API |
| 改菜单来源为后端 | `playground/src/preferences.ts` 覆盖 `app.accessMode='backend'` 或改默认偏好 |
| 新增业务页面 | `playground/src/views/**`、`playground/src/router/routes/modules/*.ts`、可选 `playground/src/api/**` |
| 新增系统管理接口 | `playground/src/api/system/*.ts`、对应 `playground/src/views/system/**` |
| 改按钮权限 | `playground/src/adapter/vxe-table.ts`、页面里的 `VbenTableAction auth`、后端 `/auth/codes` |
| 改角色授权 | `playground/src/views/system/role/modules/form.vue`、`playground/src/api/system/role.ts`、`/system/menu/list` |
| 改菜单管理字段 | `playground/src/views/system/menu/modules/form.vue`、`playground/src/api/system/menu.ts` |
| 改部门树 | `playground/src/views/system/dept/**`、`playground/src/api/system/dept.ts` |
| 改用户列表/部门筛选 | `playground/src/views/system/user/**`、`playground/src/api/system/user.ts`、`dept.ts` |

## 和 Spring Boot 联调的最短路径

1. 保持前端 API 基址 `/api` 不变。
2. 把 `playground/vite.config.ts` 代理 target 改为 Spring Boot：

```ts
target: 'http://localhost:8080/api'
```

3. Spring Boot 暴露和 mock 一致的接口路径，例如前端 `/api/auth/login` 经代理后命中后端 `/api/auth/login`，如果保留当前 rewrite，需要注意实际路径。
4. 后端统一返回 `{code,data,error,message}`。
5. 分页返回 `{items,total}`。
6. 登录成功返回 `{accessToken}`。
7. `/user/info` 返回 `username/realName/roles/homePath`。
8. `/auth/codes` 返回按钮权限码数组。

注意当前代理配置：

```ts
rewrite: (path) => path.replace(/^\/api/, '')
target: 'http://localhost:5320/api'
```

这等价于：浏览器 `/api/auth/login` -> target `/api` + rewritten `/auth/login` -> `/api/auth/login`。

如果 Spring Boot 本身 context-path 已经是 `/api`，可以把 target 改成 `http://localhost:8080/api` 并保留 rewrite。<br>
如果 Spring Boot controller 已经从 `/api` 开始且 target 是 `http://localhost:8080`，就不要 rewrite 或要重新核路径。

## 后端第一阶段实现清单

优先级高：

- `POST /api/auth/login`
- `GET /api/user/info`
- `GET /api/auth/codes`
- `POST /api/auth/logout`
- `GET /api/system/dept/list`
- `GET /api/system/user/list`
- `GET /api/system/role/list`
- `GET /api/system/menu/list`
- `GET /api/system/menu/name-exists`
- `GET /api/system/menu/path-exists`
- `POST/PUT/DELETE /api/system/*`

优先级中：

- `GET /api/menu/all`，仅后端权限模式需要。
- `GET/POST /api/timezone/*`。
- `POST /api/upload`。

优先级低：

- `/api/table/list`
- `/api/status`
- `/api/demo/bigint`

## 前端开发常见改法

### 改登录请求参数

文件：

- `playground/src/views/_core/authentication/login.vue`
- `playground/src/api/core/auth.ts`
- `playground/src/store/auth.ts`

当前登录页提交整个表单值，包括 `selectAccount` 和 `captcha`。如果后端只要 `username/password`，前端可以在 `onSubmit` 里裁剪；如果后端要验证码票据，需要把滑块组件替换为真实验证码组件。

### 改 token 方案

文件：

- `playground/src/api/request.ts`
- `playground/src/api/core/auth.ts`
- `packages/@core/preferences/src/config.ts`

当前 access token 在 Pinia 持久化，refresh token mock 放 httpOnly cookie。默认没开启 refresh。

如果改成双 token body 返回：

1. `LoginResult` 加 `refreshToken`。
2. `authLogin` 存 `accessToken` 和 `refreshToken`。
3. `refreshTokenApi` 传 refresh token。
4. `doRefreshToken` 按新响应解 token。

### 改响应 envelope

文件：`playground/src/api/request.ts`

当前：

```ts
defaultResponseInterceptor({
  codeField: 'code',
  dataField: 'data',
  successCode: 0,
})
```

如果后端返回 `{success,result,msg}`，在这里改，不要在每个 API 单独适配。

### 改分页字段

文件：`playground/src/adapter/vxe-table.ts`

当前：

```ts
response: {
  result: 'items',
  total: 'total',
  list: '',
}
```

如果后端返回 `records/total`，改为：

```ts
response: {
  result: 'records',
  total: 'total',
  list: '',
}
```

更务实的做法：后端为这个前端专门输出 `items/total`，少动前端基础适配器。

### 切后端菜单模式

文件：`playground/src/preferences.ts`

添加覆盖：

```ts
export const overridesPreferences = defineOverridesPreferences({
  app: {
    name: import.meta.env.VITE_APP_TITLE,
    accessMode: 'backend',
  },
});
```

然后实现 `/menu/all`。

后端菜单的 `component` 必须和 `playground/src/views` 匹配：

- 正确：`/system/user/list`
- 正确：`/dashboard/analytics/index`
- 特殊：`IFrameView`
- 错误：`SystemUserList`，除非前端增加映射

### 新增一个系统管理页面

建议沿用现有四件套：

1. `playground/src/api/system/foo.ts`
2. `playground/src/views/system/foo/data.ts`
3. `playground/src/views/system/foo/list.vue`
4. `playground/src/views/system/foo/modules/form.vue`

然后加路由：

- `playground/src/router/routes/modules/system.ts`

若走后端菜单模式，还要在后端 `/menu/all` 或 `/system/menu/list` 加菜单节点。

## 高风险代码清单

### `/auth/refresh` 响应格式特殊

位置：

- `playground/src/api/core/auth.ts`
- `playground/src/api/request.ts`
- `apps/backend-mock/api/auth/refresh.post.ts`

风险：普通接口都是 envelope，refresh 直接返回字符串。后端一旦统一 envelope，前端刷新 token 会坏。

建议：联调前先决定 refresh token 是否启用；不启用就明确关闭。

### 默认权限模式不是后端菜单

位置：

- `packages/@core/preferences/src/config.ts`
- `playground/src/router/access.ts`

风险：后端实现了 `/menu/all`，但默认 `frontend` 模式不会在首屏鉴权时调用它。后端菜单改了前端也不生效。

建议：真实 RBAC 项目改为 `backend` 或 `mixed`。

### 系统管理写操作 mock 永远 403

位置：`apps/backend-mock/middleware/1.api.ts`

风险：前端表单的成功闭环在 mock 环境无法验证。

建议：联调 Spring Boot 后再验证新增、编辑、删除、状态切换。

### 用户权限模型不完整

位置：

- `playground/src/views/system/user/modules/form.vue`
- `playground/src/views/system/user/data.ts`
- `playground/src/api/system/user.ts`

风险：`SystemUser` 类型有 `permissions`，form 模块也加载了菜单树，但 schema 没有权限字段。真实业务到底是“用户-角色”还是“用户-菜单直授权”没定。

建议：优先按标准 RBAC：用户分配角色，角色分配菜单/按钮。

### 权限码体系混用

位置：

- `apps/backend-mock/utils/mock-data.ts`
- `playground/src/adapter/vxe-table.ts`
- `playground/src/views/system/user/list.vue`

风险：按钮权限一处用 `AC_100100`，菜单数据里用 `System:Menu:Create`。后端若照抄会产生两套权限码。

建议：统一成业务语义码，如 `System:User:Delete`。

### 菜单 `activePath` 字段疑似放错层级

位置：`playground/src/views/system/menu/modules/form.vue`

风险：表单字段为 `activePath`，但路由 meta 类型定义为 `meta.activePath`。提交后后端若原样保存，前端路由可能不识别。

建议：改前先确认 Vben 表单是否有额外映射；目前代码里没看到。

### ID 类型不统一

位置：

- `playground/src/api/system/*.ts`
- `apps/backend-mock/utils/mock-data.ts`

风险：类型多写 string，mock 菜单 ID 多是 number。Long ID 若以 number 返回，会被 JS 精度截断。

建议：后端 ID 全部返回字符串。

### 菜单 component 是隐式依赖

位置：

- `packages/utils/src/helpers/generate-routes-backend.ts`
- `playground/src/router/access.ts`

风险：后端返回的 component 字符串必须与前端视图路径匹配，数据库里填错不会编译报错，只会运行时进 404。

建议：菜单管理新增/编辑时对 component 做白名单校验，白名单来自前端 `componentKeys`。

### 请求错误被二次包装后丢 Axios 细节

位置：`packages/effects/request/src/request-client/request-client.ts`

风险：catch 中 `throw error.response ? error.response.data : error`，上层拿到的可能不是完整 AxiosError。复杂错误处理要谨慎。

建议：业务层不要依赖 AxiosError 结构，统一靠响应 body 的 `message/error`。

## 后端数据设计建议

### 菜单与权限

第一性原则：菜单树同时承担“导航”和“权限资源”两种职责。不要把所有字段都平铺到菜单表，也不要把 meta 完全黑盒化。

建议：

- 基础字段入列：`id/pid/name/path/type/component/auth_code/status/order_no`。
- UI 扩展字段放 JSON：`meta_json`。
- 按钮作为 `type=button` 的菜单资源，参与角色授权树。
- 角色授权保存菜单 ID，登录后 `/auth/codes` 返回这些菜单节点对应的 `authCode`。

### 用户与角色

建议采用标准 RBAC：

```text
sys_user
sys_role
sys_menu
sys_dept
sys_user_role
sys_role_menu
```

不要第一版就做用户直接授权，除非业务明确需要。当前前端对用户直接授权支持不完整。

### 响应 DTO

建议后端为前端建专用 DTO：

- `LoginResponse { accessToken }`
- `UserInfoResponse { userId, username, realName, avatar, roles, homePath }`
- `PageResponse<T> { items, total }`
- `MenuRouteResponse` 和 `SystemMenuResponse` 可共用大部分字段，但用途不同：
  - `/menu/all` 面向运行时路由。
  - `/system/menu/list` 面向菜单管理和授权树。

## 测试建议

### 前端本地验证

```bash
pnpm -F @vben/playground run typecheck
pnpm -F @vben/playground run dev
```

E2E：

```bash
pnpm -F @vben/playground run test:e2e
```

现有 E2E 只覆盖登录页基础元素和成功登录，覆盖不足。

### 联调验收清单

- 登录成功后能跳 `/analytics` 或用户 `homePath`。
- 刷新页面后仍能通过 token 拉 `/user/info` 并恢复菜单。
- `/auth/codes` 返回后，带 `auth` 的按钮展示符合预期。
- 用户列表分页字段正确，切换页码正常。
- 角色列表查询条件 `startTime/endTime/status` 生效。
- 菜单管理 name/path 唯一校验生效。
- 菜单 component 填错时有后端校验，不让脏数据入库。
- 部门有子节点时后端拒绝删除。
- 401 能跳登录页或弹登录过期框。
- 403 能展示后端错误文案。
- Long ID 返回字符串，前端无精度丢失。

## 不确定项

- 是否要保留示例模块。若做业务系统，建议删或隐藏 `demos/examples`，否则后端会背上无意义接口。
- 是否要启用后端动态菜单。当前默认不是。
- refresh token 是否要做。当前默认关闭。
- 用户管理是否需要角色分配 UI。当前没有成型。
