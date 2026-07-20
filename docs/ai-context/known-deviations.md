# 当前偏差与待治理项

> 本文件记录已经由源码确认的问题及治理状态。它不是愿望清单；未解决项必须在后续任务中有迁移、测试和验收。

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

## 已解决：Admin API 权限映射尚未默认拒绝

`AdminApiPermissionPolicy` 现按精确 method/path 形状登记接口；只有登录是公开接口，用户信息、权限码、菜单和退出是 session-only，系统 CRUD 映射稳定权限码。未知路径、未知方法和相似前缀默认返回 403。后续接口必须先登记策略和测试；长期仍可演进为注解 + 启动期扫描，减少手工注册表维护成本。

## 已解决：前端初始化产生无命名空间 LocalStorage

Vben `PreferenceManager` 构造阶段原本会创建无 prefix 的 `StorageManager`，不仅产生浏览器警告，若初始化前误调用 `clear()` 还会影响同源全部 LocalStorage。构造阶段现使用内存驱动，`initPreferences(namespace)` 后才切换到有命名空间的 LocalStorage，并有单测防止回归。

## 已解决：不存在的非 API 资源被包装成 500

Spring MVC 的 `NoResourceFoundException` 原先落入兜底异常处理，导致不存在资源返回 `INTERNAL_ERROR`。现在统一返回 40401 `RESOURCE_NOT_FOUND`，并由集成测试覆盖。

## P1：完整数据范围引擎尚未接入 Admin CRUD

Core 已有 RoleGrant、数据范围 planner、结构化 SQL predicate 和缓存模型，但 Admin 管理请求当前只检查权限码集合。后续市场、商户、渠道和资金操作不能照搬当前 URL + code 检查，必须进入完整授权项和目标资源判断。

## P2：Portal 尚未初始化

`frontend/portal` 只有 `.gitkeep`。在创建 Nuxt 4 大型 pnpm monorepo 前，需要先确定：按国家拆 app 的命名、共享 layers/packages、运行时配置、i18n、支付收银台安全边界、官网与收银台的部署关系。不能复制 Admin 的 Vben package 层次作为默认答案。
