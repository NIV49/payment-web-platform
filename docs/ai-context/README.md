# AI 开发上下文入口

> 状态：当前仓库事实基线
> 基线日期：2026-08-12
> 适用对象：开发人员、代码审查者和 AI 编码代理

这个目录不是背景资料归档，而是每次改代码前的必读入口。产品人员从 [文档首页](../README.md) 和 [产品生命周期](../product/lifecycle.md) 进入；开发人员和 Codex 从本文与 Current Delivery Status 进入。两条路径读取同一仓库 Markdown，不维护第二份 Tolaria 文档。

## 两类事实与冲突处理

项目不能用一条“源码永远优先”的规则同时回答“现在是什么”和“应该建设成什么”。必须区分：

1. **目标事实**：已批准的产品需求、ADR、架构决策和版本化接口契约，决定系统应该建设成什么；
2. **实现事实**：当前工作区源码、测试和实际配置，决定系统现在真实运行成什么；
3. **框架事实**：与当前版本匹配的官方文档，决定框架和依赖的正确使用方式；
4. **参考材料**：Playground 和开源项目只提供模式证据，不能替代项目决策；
5. **经验或推测**：只能用于提出待确认项，不能直接成为实现依据。

目标事实与实现事实冲突时，必须登记为架构偏差并停止扩大错误实现；源码不能因为已经存在就自动推翻 ADR，文档也不能把尚未实现的目标描述成当前能力。Playground 是模式参考，不是产品需求，也不是可以整目录复制到业务应用的第二套实现。

## 按改动范围阅读

| 改动范围 | 开始编码前必须阅读 |
| --- | --- |
| 任意改动 | [Current Delivery Status](./current-status.md)、本文、仓库根目录 `AGENTS.md` |
| 系统管理菜单、字段、动作或只读边界 | [系统管理产品说明](../product/system-management.md) 与对应前后端上下文/契约 |
| `frontend/admin/**` | [Vben 5.7.0 基线](./vben/README.md)、[Admin 前端工程上下文](./frontend/README.md) |
| Vben 路由、菜单、标题、权限、组件 | 上述两份文档，以及对应的 Vben 官方页面 |
| `backend/**` | [后端工程上下文](./backend/README.md)、相关领域文档 |
| 前后端联调或 DTO 变化 | 前后端文档、[Identity Admin API 契约](../ai-contract/identity-admin-api-contract.md) |
| 权限、租户、数据范围 | [权限设计目录](./permission/)、产品需求基线 |
| 数据库或 Flyway | 后端文档、[数据库设计](./permission/06-database-design.md)、[迁移计划](./permission/09-migration-plan.md) |
| Merchant 主体、入驻、审核、状态、资料、市场、amendment 或证件 | [Merchant Lifecycle 上下文](./merchant/README.md)、[商户管理](../product/merchant-management.md)、[ADR-0013](../adr/0013-separate-merchant-business-lifecycle-from-identity-tenancy.md)、[ADR-0014](../adr/0014-platform-maintained-merchant-profile-and-operating-markets.md)、[ADR-0015](../adr/0015-platform-assisted-merchant-onboarding-and-reviewed-amendments.md)、[Merchant Lifecycle API Contract](../ai-contract/merchant-lifecycle-api-contract.md) |
| `frontend/portal/**` | 当前只有占位目录；初始化前先新增 Nuxt 4 monorepo 专属上下文，不套用 Vben 约定 |
| 迁移、重构、Judge、多 Agent 能力切片 | [Judge Charter](../judge-charter.md)；需要执行现代化流程时加载 [payment-modernization skill](../../.agents/skills/payment-modernization/SKILL.md) 及任务相关 reference |

完整的开发前置与完成标准见 [开发工作流](./development-workflow.md)。

## 文档地图

### 项目与框架

- [Current Delivery Status](./current-status.md)：当前分支/工作树边界、已完成验证、唯一交付任务和下一实现切片。新任务先读本文。
- [文档首页](../README.md)：Tolaria/GitHub 共用入口和产品/开发两条阅读路径。
- [产品生命周期](../product/lifecycle.md)：产品阶段、里程碑与 GO/NO-GO。
- [系统管理](../product/system-management.md)：三后台能力矩阵、交互、只读边界和真实页面截图。
- [商户管理](../product/merchant-management.md)：MCH-001 状态机与 MCH-002 PLATFORM 资料/市场交互目标。
- [Vben 5.7.0 基线](./vben/README.md)：官方文档导航、运行机制和不可违反的约定。
- [Admin 前端工程上下文](./frontend/README.md)：monorepo 目录、依赖边界、启动链路、组件和测试。
- [后端工程上下文](./backend/README.md)：Maven 模块、认证授权、持久化、缓存、接口和运行方式。
- [当前偏差与待治理项](./known-deviations.md)：已经发现但本次未改业务代码的问题。
- [开发工作流](./development-workflow.md)：每次开发的阅读、实现、验证和文档更新门禁。
- [Judge Charter](../judge-charter.md)：不可变快照、独立复审、Rulebook、机器队列和退出门禁。
- [payment-modernization skill](../../.agents/skills/payment-modernization/SKILL.md)：Reimagine/Transform 路由及 Judge 治理工作流；不能覆盖待确认产品决策。

### 业务与契约

- Payment 旧系统参考：[代收链路](./payment/01-collection-flow.md)、[代付链路](./payment/02-payout-flow.md)、[渠道路由链路](./payment/03-channel-routing-flow.md)；仅用于业务理解、风险识别和迁移对照，不表示新平台已经实现 Payment 能力。
- [目标架构](../new-payment-system-target-architecture.md)
- [权限重构产品需求](../permission-refactor-product-requirements.md)
- [权限设计目录](./permission/)
- [Identity Admin API 契约](../ai-contract/identity-admin-api-contract.md)
- [System Dictionary API 契约](../ai-contract/system-dictionary-api-contract.md)
- [Merchant Lifecycle 工程上下文](./merchant/README.md)
- [Merchant Lifecycle API 契约](../ai-contract/merchant-lifecycle-api-contract.md)
- [ADR-0013 Merchant 与 Identity Tenant 生命周期分离](../adr/0013-separate-merchant-business-lifecycle-from-identity-tenancy.md)
- [ADR-0014 PLATFORM Merchant 资料维护与经营市场](../adr/0014-platform-maintained-merchant-profile-and-operating-markets.md)
- [ADR-0015 PLATFORM 代提交入驻、受审 amendment 与受保护证件](../adr/0015-platform-assisted-merchant-onboarding-and-reviewed-amendments.md)
- [ADR-0010 三端统一用户/角色治理边界](../adr/0010-centralize-tenant-administrator-provisioning-with-delegated-user-governance.md)
- [ADR-0011 系统字典与跨域只读消费](../adr/0011-centralize-system-dictionaries-with-cross-domain-read-only-access.md)
- [ADR-0012 PLATFORM 跨域 Role/Menu 只读目录](../adr/0012-expose-platform-cross-domain-role-and-menu-directories.md)

## 维护规则

- 文档中的事实必须附源码路径、配置项或官方链接。
- 代码行为改变时，同一任务内更新对应上下文；不能让文档长期描述旧实现。
- 系统管理的路由、菜单可见性、字段、动作或权限边界改变时，同步更新产品图文页；当前阶段变化时同步更新产品生命周期和 Current Delivery Status。
- 新增顶层应用、领域模块、共享包或基础设施时，更新本索引和对应目录图。
- Vben 升级时先更新版本基线，再判断旧约定是否仍成立。
- 已执行的 Flyway 迁移只增不改；历史种子错误通过新迁移修正。
