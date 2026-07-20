# Payment Web Platform 开发规则

本文件作用于整个仓库。任何开发者或 AI 代理在改代码前都必须先建立当前项目上下文。

## 强制阅读路由

1. 所有任务先读 `docs/ai-context/README.md`。
2. 修改 `frontend/admin/**` 前，必须读：
   - `docs/ai-context/vben/README.md`
   - `docs/ai-context/frontend/README.md`
3. 修改 `backend/**` 前，必须读：
   - `docs/ai-context/backend/README.md`
   - 对应业务领域文档；权限相关还要读 `docs/ai-context/permission/`
4. 修改前后端接口、字段、登录、菜单或权限码时，还必须读 `docs/ai-contract/identity-admin-api-contract.md`。
5. 跨端任务同时阅读前端、后端和契约文档。
6. `frontend/portal` 初始化前先建立 Nuxt 4 专属上下文；Vben 规则不能直接套用到 Portal。

完整流程见 `docs/ai-context/development-workflow.md`。

## 事实与证据

- 分开判断目标事实和实现事实：已批准的产品需求、ADR 与契约决定“应该建设成什么”；当前源码、测试和配置决定“现在真实运行成什么”。两者冲突必须登记偏差并收敛，不能让已有错误实现自动覆盖项目决策。
- 框架行为以同版本官方文档和当前源码共同核验；Playground 与开源项目只提供参考模式，不构成项目决策。
- 当前 Admin 基线是 Vben `5.7.0`、Vue `3.5.38`、`antdv-next` `1.3.5`。
- Playground 只用于提取模式。复制前必须确认依赖、接口、权限和 UI 组件与 `web-antdv-next` 一致。
- 不确定的内容明确标为“待确认”，不允许编造框架行为。

## 前端硬规则

- 当前产品应用是 `frontend/admin/apps/web-antdv-next`；UI 控件使用 `antdv-next`，不是 `ant-design-vue`。
- 动态菜单 `meta.title` 必须是已有语言包 key，例如 `system.title`；禁止把 `System Management`、`系统管理`这类展示文案写入路由数据。
- 后端页面 `component` 必须是相对 `src/views`、去掉 `.vue` 的路径，例如 `/system/user/list`；特殊布局只允许前端 `layoutMap` 已注册的名称。
- 新组件先检查 Vben 组件文档、Playground 示例和应用适配器。业务层不得绕开既有 `VbenForm`、`VbenVxeTable`、权限按钮适配而另建平行体系。
- 新文案同步维护 `zh-CN` 与 `en-US`；新权限按钮使用统一语义权限码。
- `packages` 是共享框架层。应用专属代码留在应用内，除非已经证明需要跨应用复用。

## 后端硬规则

- `backend/applications` 只放可启动组合根，`backend/modules` 放业务上下文及其所属适配器。
- Core 不能依赖 Spring MVC、MyBatis、Redis 或 Sa-Token；依赖方向必须指向业务核心。
- 权限默认拒绝；租户和 membership 从可信会话获取；前端隐藏按钮不能代替后端鉴权。
- 数据库迁移只增不改。所有已提交或已在任一环境执行的 Flyway 迁移都不得回写；后续数据或结构修正必须新增迁移。
- 支付、余额、账本、退款、代付、提现和调账改动必须同时检查资金规则、幂等、状态机、审计和数据范围。
- API Long ID 返回字符串，错误响应包含 `traceId`，接口字段以契约文档为准。

## 验证与交付

- 前端至少执行受影响应用 typecheck 和相关测试；跨路由/组件改动应构建并做浏览器验证。
- 后端至少执行受影响 Maven 模块测试；跨模块、数据库或 API 改动执行 `clean verify`。
- 修改接口、目录、约定或架构时，同一任务更新 `docs/ai-context`。
- 不擅自提交、推送、重置或覆盖工作区已有修改。
