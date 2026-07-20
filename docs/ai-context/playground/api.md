# Playground 接口契约

## 全局约定

### Base URL

开发态：

```text
VITE_GLOB_API_URL=/api
Vite proxy target=http://localhost:5320/api
```

生产态默认：

```text
VITE_GLOB_API_URL=https://mock-napi.vben.pro/api
```

Spring Boot 联调建议：

- 本地开发：改 `playground/vite.config.ts` 里的 proxy target，或在 `.env.development.local` 覆盖。
- 部署环境：改 `VITE_GLOB_API_URL`。

### 请求头

除登录、刷新、退出外，业务请求都应支持：

| Header | 说明 |
| --- | --- |
| `Authorization: Bearer <accessToken>` | access token |
| `Accept-Language: zh-CN/en-US` | 当前前端语言 |

### 普通响应格式

`requestClient` 只认 `code === 0` 成功，并自动返回 `data`。

```json
{
  "code": 0,
  "data": {},
  "error": null,
  "message": "ok"
}
```

失败示例：

```json
{
  "code": -1,
  "data": null,
  "error": "Unauthorized Exception",
  "message": "Unauthorized Exception"
}
```

HTTP 状态码仍然重要：

| HTTP | 前端行为 |
| --- | --- |
| `400` | 弹请求错误 |
| `401` | 登录过期/刷新 token/跳登录 |
| `403` | 弹 forbidden 或后端错误文案 |
| `404` | 弹 not found |
| `5xx` | 弹服务错误 |

### 分页响应格式

VXE 表格全局配置写死读取：

- 列表字段：`data.items`
- 总数字段：`data.total`

```json
{
  "code": 0,
  "data": {
    "items": [],
    "total": 0
  },
  "error": null,
  "message": "ok"
}
```

不要直接返回 MyBatis-Plus 默认的 `records/total`，除非同步改前端适配器。

### ID 类型

前端多数类型把 `id` 定义成 `string`，mock 有些菜单 ID 是 number。建议后端统一返回字符串，尤其是雪花 ID 或 Long ID，避免 JS 精度问题。

## 认证接口

文件：

- 前端：`playground/src/api/core/auth.ts`
- Store：`playground/src/store/auth.ts`
- mock：`apps/backend-mock/api/auth/*.ts`

### POST `/auth/login`

用途：用户名密码登录。

前端调用：

```ts
loginApi(data, { withCredentials: true })
```

请求体实际可能包含：

```json
{
  "selectAccount": "vben",
  "username": "vben",
  "password": "123456",
  "captcha": true
}
```

mock 只使用：

```json
{
  "username": "vben",
  "password": "123456"
}
```

成功响应：

```json
{
  "code": 0,
  "data": {
    "accessToken": "jwt-token"
  },
  "error": null,
  "message": "ok"
}
```

mock 实际还把用户字段一起放进 `data`，但前端只解构 `accessToken`。

失败：

- 缺用户名或密码：HTTP `400`。
- 账号密码错误：HTTP `403`。

后端设计建议：

- 登录接口可以忽略 `selectAccount`。
- 如果要后端验证码，不能只靠当前 `captcha: true`，需要新增 `captchaId/captchaToken` 或滑块验证票据。
- refresh token 若采用 cookie，需配合 `withCredentials` 和 CORS。

### GET `/user/info`

用途：获取当前登录用户信息。

成功响应：

```json
{
  "code": 0,
  "data": {
    "id": 0,
    "userId": "0",
    "username": "vben",
    "realName": "Vben",
    "avatar": "https://example.com/avatar.png",
    "roles": ["super"],
    "homePath": "/analytics",
    "desc": ""
  },
  "error": null,
  "message": "ok"
}
```

字段说明：

| 字段 | 必填建议 | 说明 |
| --- | --- | --- |
| `id` | 可选 | mock 使用数字 id。 |
| `userId` | 建议必填 | 共享类型 `BasicUserInfo` 要求。 |
| `username` | 必填 | 用户名。 |
| `realName` | 必填 | 显示名称，登录成功通知使用。 |
| `avatar` | 建议必填 | 不返回时前端用默认头像。 |
| `roles` | 必填 | 页面级权限用，如 `super/admin/user`。 |
| `homePath` | 可选 | 登录后默认跳转；不传走 `/analytics`。 |
| `desc` | 可选 | 类型里有，当前页面基本不用。 |

权限失败：HTTP `401`。

### GET `/auth/codes`

用途：获取当前用户按钮权限码。

成功响应：

```json
{
  "code": 0,
  "data": ["AC_100100", "System:Menu:Create"],
  "error": null,
  "message": "ok"
}
```

当前 mock 权限码：

| 用户 | codes |
| --- | --- |
| `vben` | `AC_100100`、`AC_100110`、`AC_100120`、`AC_100010` |
| `admin` | `AC_100010`、`AC_100020`、`AC_100030` |
| `jack` | `AC_1000001`、`AC_1000002` |

风险：系统菜单 mock 里也有 `System:Menu:*`、`System:Dept:*` 等按钮权限码，但 `/auth/codes` 当前没有返回这些。权限码体系不统一，后端需要定一套。

### POST `/auth/logout`

用途：退出登录。

前端使用 `baseRequestClient`，不强制普通 envelope。mock 返回：

```json
{
  "code": 0,
  "data": "",
  "error": null,
  "message": "ok"
}
```

前端行为：接口失败也会清 store 并跳登录页。

### POST `/auth/refresh`

用途：刷新 access token。

当前默认 `preferences.app.enableRefreshToken = false`，不会自动触发。开启后才有用。

特殊点：前端用 `baseRequestClient`，并按原始 AxiosResponse 读取：

```ts
const resp = await refreshTokenApi();
const newToken = resp.data;
```

mock 直接返回纯字符串：

```text
new-access-token
```

如果 Spring Boot 返回统一 envelope：

```json
{"code":0,"data":"new-token"}
```

当前前端会把整个对象当 token，导致 `Authorization: Bearer [object Object]`。二选一：

- 后端对 `/auth/refresh` 返回纯字符串；
- 或前端同步改 `refreshTokenApi/doRefreshToken`。

## 菜单与路由接口

### GET `/menu/all`

用途：后端权限模式下生成动态路由和菜单。

前端文件：

- `playground/src/api/core/menu.ts`
- `playground/src/router/access.ts`
- `packages/utils/src/helpers/generate-routes-backend.ts`

返回类型：`RouteRecordStringComponent[]`

示例：

```json
{
  "code": 0,
  "data": [
    {
      "name": "Dashboard",
      "path": "/dashboard",
      "redirect": "/analytics",
      "meta": {
        "title": "page.dashboard.title",
        "order": -1
      },
      "children": [
        {
          "name": "Analytics",
          "path": "/analytics",
          "component": "/dashboard/analytics/index",
          "meta": {
            "title": "page.dashboard.analytics",
            "affixTab": true
          }
        }
      ]
    }
  ],
  "error": null,
  "message": "ok"
}
```

字段规则：

| 字段 | 说明 |
| --- | --- |
| `name` | 路由名，必须唯一。缺失会打印错误。 |
| `path` | 路由路径，建议以 `/` 开头。 |
| `component` | 页面组件字符串，必须能映射到 `playground/src/views/**/*.vue`。 |
| `redirect` | 可选；父路由不传时前端会自动重定向到第一个绝对子路由。 |
| `children` | 子路由。 |
| `meta.title` | 菜单/标签标题，可传 i18n key。 |
| `meta.icon` | 菜单图标。 |
| `meta.authority` | 角色权限，前端模式用得多。 |
| `meta.menuVisibleWithForbidden` | 菜单显示但访问 403。 |
| `meta.link` | 外链 URL。 |
| `meta.iframeSrc` | iframe URL。 |

component 映射规则：

- `/dashboard/analytics/index` -> `playground/src/views/dashboard/analytics/index.vue`
- `/system/menu/list` -> `playground/src/views/system/menu/list.vue`
- `IFrameView` -> 共享 iframe 布局组件
- `BasicLayout` -> 基础布局组件

不匹配时前端会使用 404 组件。

## 系统部门接口

前端文件：

- `playground/src/api/system/dept.ts`
- `playground/src/views/system/dept/**`
- mock：`apps/backend-mock/api/system/dept/**`

### 数据结构 `SystemDept`

```ts
interface SystemDept {
  id: string;
  pid?: string | number;
  name: string;
  status: 0 | 1;
  remark?: string;
  createTime?: string;
  children?: SystemDept[];
}
```

### GET `/system/dept/list`

用途：查询部门树。

响应：

```json
{
  "code": 0,
  "data": [
    {
      "id": "dept-1",
      "pid": 0,
      "name": "研发部",
      "status": 1,
      "createTime": "2024/01/01 10:00:00",
      "remark": "核心研发",
      "children": []
    }
  ],
  "error": null,
  "message": "ok"
}
```

### POST `/system/dept`

请求：

```json
{
  "name": "研发部",
  "pid": "parent-id",
  "status": 1,
  "remark": "说明"
}
```

响应建议：`data: null` 或返回新记录。

### PUT `/system/dept/{id}`

请求同创建，不包含 `id/children`。

状态切换也可能只传：

```json
{
  "status": 0
}
```

### DELETE `/system/dept/{id}`

响应建议：

```json
{
  "code": 0,
  "data": null,
  "error": null,
  "message": "ok"
}
```

业务规则：

- 前端禁用有 `children` 的部门删除按钮。
- 后端仍必须校验是否有子部门/用户绑定，不能信前端。

## 系统用户接口

前端文件：

- `playground/src/api/system/user.ts`
- `playground/src/views/system/user/**`
- mock：`apps/backend-mock/api/system/user/list.ts`

### 数据结构 `SystemUser`

当前类型：

```ts
interface SystemUser {
  id: string;
  name: string;
  permissions: string[];
  remark?: string;
  status: 0 | 1;
}
```

实际页面/列表还用到：

```ts
{
  createTime?: string;
  deptId?: string;
}
```

建议后端结构：

```json
{
  "id": "user-id",
  "name": "张三",
  "deptId": "dept-id",
  "status": 1,
  "createTime": "2024/01/01 10:00:00",
  "remark": "说明",
  "permissions": []
}
```

### GET `/system/user/list`

查询参数：

| 参数 | 说明 |
| --- | --- |
| `page` | 当前页，从 1 开始。 |
| `pageSize` | 每页条数。 |
| `name` | 用户名称模糊查询。 |
| `id` | ID 模糊查询。 |
| `status` | `0` 或 `1`。 |
| `remark` | 备注模糊查询。 |
| `startTime` | 创建时间开始。 |
| `endTime` | 创建时间结束。 |
| `deptId` | 左侧部门树筛选。 |

响应：

```json
{
  "code": 0,
  "data": {
    "items": [
      {
        "id": "user-id",
        "name": "张三",
        "deptId": "dept-id",
        "status": 1,
        "createTime": "2024/01/01 10:00:00",
        "remark": "说明"
      }
    ],
    "total": 1
  },
  "error": null,
  "message": "ok"
}
```

### POST `/system/user`

表单请求：

```json
{
  "name": "张三",
  "deptId": "dept-id",
  "status": 1,
  "remark": "说明"
}
```

不确定：是否包含 `permissions`。当前表单 schema 没有这个字段。

### PUT `/system/user/{id}`

编辑请求同创建。状态切换可能只传：

```json
{
  "status": 0
}
```

### DELETE `/system/user/{id}`

删除用户。

### 缺失但真实项目可能需要

当前没有：

- 用户详情接口。
- 重置密码接口。
- 绑定角色接口。
- 用户账号 username/mobile/email 字段。

如果做真实 RBAC，这些应该补齐。

## 系统角色接口

前端文件：

- `playground/src/api/system/role.ts`
- `playground/src/views/system/role/**`
- mock：`apps/backend-mock/api/system/role/list.ts`

### 数据结构 `SystemRole`

```ts
interface SystemRole {
  id: string;
  name: string;
  permissions: string[];
  remark?: string;
  status: 0 | 1;
}
```

实际列表还用到 `createTime`。

### GET `/system/role/list`

查询参数：

| 参数 | 说明 |
| --- | --- |
| `page` | 当前页，从 1 开始。 |
| `pageSize` | 每页条数。 |
| `name` | 角色名称模糊查询。 |
| `id` | ID 模糊查询。 |
| `status` | `0` 或 `1`。 |
| `remark` | 备注模糊查询。 |
| `startTime` | 创建时间开始。 |
| `endTime` | 创建时间结束。 |

响应：

```json
{
  "code": 0,
  "data": {
    "items": [
      {
        "id": "role-id",
        "name": "管理员",
        "status": 1,
        "createTime": "2024/01/01 10:00:00",
        "permissions": ["201", "20101"],
        "remark": "说明"
      }
    ],
    "total": 1
  },
  "error": null,
  "message": "ok"
}
```

### POST `/system/role`

请求：

```json
{
  "name": "管理员",
  "status": 1,
  "remark": "说明",
  "permissions": ["201", "20101"]
}
```

`permissions` 是菜单树节点 ID 列表，不是 `authCode`。

### PUT `/system/role/{id}`

编辑请求同创建。状态切换可能只传：

```json
{
  "status": 0
}
```

### DELETE `/system/role/{id}`

删除角色。

## 系统菜单接口

前端文件：

- `playground/src/api/system/menu.ts`
- `playground/src/views/system/menu/**`
- mock：`apps/backend-mock/api/system/menu/**`

### 枚举

菜单类型：

```ts
type MenuType = 'catalog' | 'menu' | 'embedded' | 'link' | 'button';
```

徽标类型：

```ts
type BadgeType = 'dot' | 'normal';
```

徽标颜色：

```ts
type BadgeVariant =
  | 'default'
  | 'destructive'
  | 'primary'
  | 'success'
  | 'warning';
```

状态：

```ts
type Status = 0 | 1;
```

### 数据结构 `SystemMenu`

```ts
interface SystemMenu {
  id: string;
  pid: string;
  name: string;
  path: string;
  type: 'catalog' | 'menu' | 'embedded' | 'link' | 'button';
  status: 0 | 1;
  authCode?: string;
  component?: string;
  redirect?: string;
  children?: SystemMenu[];
  meta?: {
    title?: string;
    icon?: string;
    activeIcon?: string;
    activePath?: string;
    affixTab?: boolean;
    affixTabOrder?: number;
    badge?: string;
    badgeType?: 'dot' | 'normal';
    badgeVariants?: string;
    hideChildrenInMenu?: boolean;
    hideInBreadcrumb?: boolean;
    hideInMenu?: boolean;
    hideInTab?: boolean;
    iframeSrc?: string;
    keepAlive?: boolean;
    link?: string;
    maxNumOfOpenTab?: number;
    noBasicLayout?: boolean;
    openInNewWindow?: boolean;
    order?: number;
    query?: Record<string, any>;
  };
}
```

### GET `/system/menu/list`

用途：

- 菜单管理列表。
- 角色授权树。
- 菜单父级选择。
- 部分示例 ApiSelect。

响应：

```json
{
  "code": 0,
  "data": [
    {
      "id": "2",
      "pid": "0",
      "name": "System",
      "path": "/system",
      "type": "catalog",
      "status": 1,
      "meta": {
        "title": "system.title",
        "icon": "carbon:settings",
        "order": 9997,
        "badge": "new",
        "badgeType": "normal",
        "badgeVariants": "primary"
      },
      "children": [
        {
          "id": "201",
          "pid": "2",
          "name": "SystemMenu",
          "path": "/system/menu",
          "type": "menu",
          "status": 1,
          "authCode": "System:Menu:List",
          "component": "/system/menu/list",
          "meta": {
            "title": "system.menu.title",
            "icon": "carbon:menu"
          }
        }
      ]
    }
  ],
  "error": null,
  "message": "ok"
}
```

### GET `/system/menu/name-exists`

查询参数：

| 参数 | 说明 |
| --- | --- |
| `name` | 菜单路由名。 |
| `id` | 编辑时排除当前菜单。 |

响应：

```json
{
  "code": 0,
  "data": true,
  "error": null,
  "message": "ok"
}
```

### GET `/system/menu/path-exists`

查询参数：

| 参数 | 说明 |
| --- | --- |
| `path` | 菜单路径。 |
| `id` | 编辑时排除当前菜单。 |

响应同 name-exists。

### POST `/system/menu`

请求示例：页面菜单

```json
{
  "type": "menu",
  "name": "SystemUser",
  "pid": "2",
  "path": "/system/user",
  "component": "/system/user/list",
  "authCode": "System:User:List",
  "status": 1,
  "meta": {
    "title": "system.user.title",
    "icon": "mdi:user",
    "keepAlive": true,
    "hideInMenu": false
  }
}
```

请求示例：按钮权限

```json
{
  "type": "button",
  "name": "SystemUserDelete",
  "pid": "201",
  "authCode": "System:User:Delete",
  "status": 1,
  "meta": {
    "title": "common.delete"
  }
}
```

请求示例：外链

```json
{
  "type": "link",
  "name": "ExternalDocs",
  "pid": "9",
  "status": 1,
  "meta": {
    "title": "Docs",
    "link": "https://example.com",
    "icon": "carbon:book"
  }
}
```

请求示例：iframe

```json
{
  "type": "embedded",
  "name": "EmbeddedDocs",
  "pid": "9",
  "path": "/docs",
  "component": "IFrameView",
  "status": 1,
  "meta": {
    "title": "Docs",
    "iframeSrc": "https://example.com",
    "icon": "carbon:book"
  }
}
```

### PUT `/system/menu/{id}`

请求同创建。

### DELETE `/system/menu/{id}`

删除菜单。后端必须校验是否存在子菜单、角色绑定、路由引用。

## 时区接口

前端文件：

- `playground/src/api/core/timezone.ts`
- `playground/src/timezone-init.ts`

### GET `/timezone/getTimezoneOptions`

无需登录：mock 当前没校验 token。

响应：

```json
{
  "code": 0,
  "data": [
    {
      "label": "Asia/Shanghai (GMT+8)",
      "value": "Asia/Shanghai"
    }
  ],
  "error": null,
  "message": "ok"
}
```

### GET `/timezone/getTimezone`

需登录。

响应：

```json
{
  "code": 0,
  "data": "Asia/Shanghai",
  "error": null,
  "message": "ok"
}
```

可返回 `null` 或 `undefined`。

### POST `/timezone/setTimezone`

请求：

```json
{
  "timezone": "Asia/Shanghai"
}
```

成功响应：

```json
{
  "code": 0,
  "data": {},
  "error": null,
  "message": "ok"
}
```

非法时区：HTTP `400`。

## 示例接口

这些接口用于演示组件，不建议纳入第一阶段业务表设计。

### GET `/table/list`

用途：VXE 表格远程分页/排序示例。

查询参数：

| 参数 | 说明 |
| --- | --- |
| `page` | 当前页。 |
| `pageSize` | 每页条数，mock 最大 100。 |
| `sortBy` | 排序字段。 |
| `sortOrder` | `desc` 或其他。 |
| 其他 | 示例表单会透传 `category/productName/price/color/start/end`，mock 未过滤。 |

响应字段示例：

```json
{
  "id": "uuid",
  "imageUrl": "https://...",
  "imageUrl2": "https://...",
  "open": true,
  "status": "success",
  "productName": "Product",
  "price": "99.00",
  "currency": "CNY",
  "quantity": 10,
  "available": true,
  "category": "Dept",
  "releaseDate": "2024-01-01T00:00:00.000Z",
  "rating": 4.5,
  "description": "Text",
  "weight": 1.2,
  "color": "red",
  "inProduction": true,
  "tags": ["tag"]
}
```

### POST `/upload`

用途：上传组件演示。

请求：`multipart/form-data`，字段名 `file`。

响应：

```json
{
  "code": 0,
  "data": {
    "url": "https://unpkg.com/@vbenjs/static-source@0.1.7/source/logo-v1.webp"
  },
  "error": null,
  "message": "ok"
}
```

### GET `/status?status=401`

用途：模拟状态码。

返回对应 HTTP status，body：

```json
{
  "code": -1,
  "data": null,
  "error": null,
  "message": "401"
}
```

### GET `/demo/bigint`

用途：演示 `json-bigint`。

后端返回 JSON 中超大数字，前端会解析为字符串。真实后端更建议直接把 Long/Snowflake ID 序列化为字符串。

## 状态枚举汇总

| 枚举 | 值 | 说明 |
| --- | --- | --- |
| 通用状态 | `1` | 启用 |
| 通用状态 | `0` | 禁用 |
| 菜单类型 | `catalog` | 目录 |
| 菜单类型 | `menu` | 页面 |
| 菜单类型 | `embedded` | iframe |
| 菜单类型 | `link` | 外链 |
| 菜单类型 | `button` | 按钮权限 |
| 徽标类型 | `dot` | 点状徽标 |
| 徽标类型 | `normal` | 文本徽标 |
| 徽标颜色 | `default/destructive/primary/success/warning` | UI 展示 |
| 表格示例状态 | `success/error/warning` | demo-only |
| 权限模式 | `frontend/backend/mixed` | 路由生成策略 |
| 登录过期模式 | `page/modal` | 401 后跳页或弹窗 |

## 推荐表结构草案

这是从前端契约反推的最低限度，不是最终业务建模。

### `sys_user`

| 字段 | 建议类型 | 说明 |
| --- | --- | --- |
| `id` | varchar/bigint as string | 主键 |
| `username` | varchar | 登录名 |
| `password_hash` | varchar | 密码摘要 |
| `real_name` | varchar | 显示名 |
| `avatar` | varchar | 头像 |
| `dept_id` | varchar | 部门 |
| `status` | tinyint | 0/1 |
| `home_path` | varchar | 登录首页 |
| `remark` | varchar | 备注 |
| `created_at` | datetime | 创建时间 |

### `sys_role`

| 字段 | 建议类型 | 说明 |
| --- | --- | --- |
| `id` | varchar/bigint as string | 主键 |
| `name` | varchar | 角色名 |
| `code` | varchar | 角色标识，如 `admin` |
| `status` | tinyint | 0/1 |
| `remark` | varchar | 备注 |
| `created_at` | datetime | 创建时间 |

### `sys_menu`

| 字段 | 建议类型 | 说明 |
| --- | --- | --- |
| `id` | varchar/bigint as string | 主键 |
| `pid` | varchar | 父级 |
| `name` | varchar | 路由名，唯一 |
| `path` | varchar | 路由路径 |
| `type` | varchar | 菜单类型 |
| `component` | varchar | 前端组件路径 |
| `redirect` | varchar | 重定向 |
| `auth_code` | varchar | 权限码 |
| `status` | tinyint | 0/1 |
| `meta_json` | json/text | meta 扩展字段 |
| `order_no` | int | 可冗余自 meta.order |

### 关系表

| 表 | 说明 |
| --- | --- |
| `sys_user_role` | 用户-角色 |
| `sys_role_menu` | 角色-菜单/按钮节点 |
| `sys_dept` | 部门树 |

## 不确定项

- `/auth/refresh` 是否采用 cookie refresh token 或 body refresh token，当前 mock 只用 cookie `jwt`。
- 系统用户接口是否应支持角色分配，当前页面没做。
- 菜单按钮权限最终用菜单 ID 还是 `authCode`，当前角色表单用菜单 ID，按钮权限判断用 `accessCodes` 字符串。
- 真实后端是否要支持后端动态路由。如果不支持，可保持 `frontend` 权限模式，只实现 `/auth/codes` 和用户角色。
