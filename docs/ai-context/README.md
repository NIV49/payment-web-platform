# AI 开发上下文入口

> 状态：当前仓库事实基线
> 基线日期：2026-07-17
> 适用对象：开发人员、代码审查者和 AI 编码代理

这个目录不是背景资料归档，而是每次改代码前的必读入口。目标是让实现建立在当前源码、版本匹配的官方文档和明确的项目决策上，避免凭印象复制示例。

## 信息优先级

出现冲突时，按以下顺序判断：

1. 当前工作区源码、测试和实际配置；
2. 与当前版本匹配的官方文档；
3. 本目录记录的项目决策与接口契约；
4. Playground 示例；
5. 经验或推测。

Playground 是模式参考，不是产品需求，也不是可以整目录复制到业务应用的第二套实现。无法从前三项确认的结论必须标为“待确认”，不能假装已经确定。

## 按改动范围阅读

| 改动范围 | 开始编码前必须阅读 |
| --- | --- |
| 任意改动 | 本文、仓库根目录 `AGENTS.md` |
| `frontend/admin/**` | [Vben 5.7.0 基线](./vben/README.md)、[Admin 前端工程上下文](./frontend/README.md) |
| Vben 路由、菜单、标题、权限、组件 | 上述两份文档，以及对应的 Vben 官方页面 |
| `backend/**` | [后端工程上下文](./backend/README.md)、相关领域文档 |
| 前后端联调或 DTO 变化 | 前后端文档、[Identity Admin API 契约](../ai-contract/identity-admin-api-contract.md) |
| 权限、租户、数据范围 | [权限设计目录](./permission/)、产品需求基线 |
| 数据库或 Flyway | 后端文档、[数据库设计](./permission/06-database-design.md)、[迁移计划](./permission/09-migration-plan.md) |
| `frontend/portal/**` | 当前只有占位目录；初始化前先新增 Nuxt 4 monorepo 专属上下文，不套用 Vben 约定 |

完整的开发前置与完成标准见 [开发工作流](./development-workflow.md)。

## 文档地图

### 项目与框架

- [仓库总览](./project-structure.md)：顶层目录、工程边界、Git 上游和跨端依赖。
- [Vben 5.7.0 基线](./vben/README.md)：官方文档导航、运行机制和不可违反的约定。
- [Admin 前端工程上下文](./frontend/README.md)：monorepo 目录、依赖边界、启动链路、组件和测试。
- [后端工程上下文](./backend/README.md)：Maven 模块、认证授权、持久化、缓存、接口和运行方式。
- [当前偏差与待治理项](./known-deviations.md)：已经发现但本次未改业务代码的问题。
- [开发工作流](./development-workflow.md)：每次开发的阅读、实现、验证和文档更新门禁。

### 业务与契约

- [目标架构](../new-payment-system-target-architecture.md)
- [权限重构产品需求](../permission-refactor-product-requirements.md)
- [权限设计目录](./permission/)
- [Playground 分析目录](./playground/)
- [Identity Admin API 契约](../ai-contract/identity-admin-api-contract.md)

## 维护规则

- 文档中的事实必须附源码路径、配置项或官方链接。
- 代码行为改变时，同一任务内更新对应上下文；不能让文档长期描述旧实现。
- 新增顶层应用、领域模块、共享包或基础设施时，更新本索引和对应目录图。
- Vben 升级时先更新版本基线，再判断旧约定是否仍成立。
- 已执行的 Flyway 迁移只增不改；历史种子错误通过新迁移修正。
