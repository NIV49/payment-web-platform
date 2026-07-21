# 权限系统现状与目标边界

## 0. 结论

本次工作不是选择一个开源后台直接二开，而是提取两套系统中已经被验证的模式，重新建立适合支付平台的权限内核。

结论为：**有条件通过进入设计与原型阶段，不允许把原型直接用于真实资金流量。**

条件：

1. 权限判断必须以服务端租户成员上下文为准；
2. 动作和数据范围必须来自同一条完整授权，禁止跨角色拼接；
3. 资金操作不能被超级管理员通配权限自动绕过；
4. 商户、市场、渠道、代理关系等范围不能只依赖前端参数；
5. 权限、关系和会话撤销必须具备版本化失效机制；
6. 最终接入真实业务前，需要 PostgreSQL 集成测试、并发测试、越权矩阵和生产门禁。

## 1. 输入与证据状态

| 输入 | 状态 | 说明 |
| --- | --- | --- |
| `docs/new-payment-system-target-architecture.md` | 产品/架构基线 | 资金核心、租户、事件、部署和迁移方向 |
| `docs/permission-refactor-product-requirements.md` | 产品需求基线 | 租户、代理商、多角色、历史订单、MFA 和审计规则 |
| RuoYi-Vue 分析时快照（现已移除） | 已完成源码取证 | 实际是 RuoYi-Vue 3.9.2，不是 RuoYi-Vue-Plus |
| ContiNew Admin 分析时快照（现已移除） | 已完成源码取证 | POM 为 4.2.0-SNAPSHOT；快照没有独立 Git 元数据，无法确认源码提交 |
| `backend/` 与 `docs/ai-context/permission/` | 当前正式输出 | 自有设计与实现，不保留两套开源源码副本 |

### 1.1 重要偏差

用户原始输入把第一套系统描述为 “RuoYi-Vue-Plus / Sa-Token”，但本地源码的事实是：

- 分析时 `RuoYi-Vue/pom.xml` 声明 `com.ruoyi:ruoyi:3.9.2`；
- 分析时 `ruoyi-common/pom.xml` 引入 Spring Security 和 `jjwt`；
- `JwtAuthenticationTokenFilter` 与 `TokenService` 实现 JWT + Redis 会话。

因此参考文档统一使用 **RuoYi-Vue** 名称。不得把 RuoYi-Vue-Plus 的能力写成当前源码事实。

## 2. 当前业务约束

目标权限判断回答：

```text
谁（User）
+ 以哪个租户成员身份（TenantMembership）
+ 通过哪一条角色授权（RoleGrant）
+ 对什么资源执行什么动作（PermissionCode）
+ 覆盖哪些数据（ScopeDimensions）
+ 是否满足资金附加条件（Step-up / Approval / SoD）
= Allow / Deny
```

### 2.1 已确认规则

- 平台、代理商、直连商户、间连商户具有独立租户边界；
- 代理关系不是租户父子关系；
- 销售关系和代理关系只是数据候选范围，不自动授予动作权限；
- 多角色不能把角色 A 的动作和角色 B 的数据范围拼接；
- 历史订单权限依赖交易时关系快照；
- 代理关系解除后只保留授权范围内的历史只读访问；
- 菜单和按钮只是前端展示结果，后端权限码才是安全边界；
- 用户、角色、关系、密码、MFA 变化后旧权限必须及时失效；
- 资金权限必须独立、可审计，不能归入普通订单管理。

### 2.2 未决项

<!-- decision-status id=IAM-GLOBAL-USER-MULTI-TENANT status=pending ref=none -->

> 不确定：一个全局用户是否允许加入多个租户，以及登录时如何选择工作空间；产品文档要求技术评审确认。

> 不确定：权限撤销的最终 SLA；架构候选为普通权限 60 秒内、资金权限立即生效。

> 不确定：市场、渠道和资金账户是否全部成为第一期数据范围维度。

> 不确定：平台强制解除代理关系是否启用，以及对应的审批角色。

> 不确定：首期资金权限目录和职责分离矩阵尚未定版。

## 3. 参考系统的明确用途

### 3.1 RuoYi-Vue

用于学习完整业务链路：

```text
用户 -> 用户角色 -> 角色菜单 -> 菜单权限码
     -> Spring Method Security
     -> 当前动作对应角色的数据范围
     -> Mapper 查询过滤
```

不采用：JWT 自研会话、固定 ID 超级管理员、原始 SQL 字符串拼接、单一部门数据范围。

### 3.2 ContiNew Admin

用于学习工程模式：

- LoginHandler 策略；
- Sa-Token 会话适配；
- UserContext / RoleContext；
- Request / Response / DO 分层；
- DataPermissionUserDataProvider 扩展点；
- JetCache 与在线上下文刷新；
- 系统内置角色保护；
- Liquibase 多数据库迁移组织。

不直接采用：角色级单值 `data_scope`、外部 Starter 黑盒 SQL 改写、超级/租户管理员全量绕过。

## 4. 目标交付边界

本次生成：

- 九份权限分析与设计文档；
- PostgreSQL 新建表 DDL；
- Java 领域实体与持久化行模型；
- Mapper 接口；
- 授权 Service；
- 版本化权限加载器；
- Sa-Token 会话桥接；
- 结构化数据范围拦截器；
- 缓存端口和 Redis Key 策略；
- 关键越权场景单元测试。

本次不生成：

- 真实密码、MFA 和 IdP 实现；
- 可直接执行的生产数据库迁移；
- 对真实订单表的通用 SQL 字符串注入；
- 平台、代理商和商户的最终预置角色；
- 资金审批工作流；
- 生产 Redis、PostgreSQL 或 Sa-Token 配置。

## 5. 威胁模型摘要

| 威胁 | 典型攻击 | 目标控制 |
| --- | --- | --- |
| 身份伪造 | 伪造 tenantId / merchantId | 服务端会话上下文、资源归属校验 |
| 权限篡改 | 前端传入角色或数据范围 | 请求 DTO 不接受可信授权字段 |
| 跨角色拼接 | A 角色有动作、B 角色有全量范围 | 单条 Grant 原子评估 |
| 跨租户访问 | 按主键读取其他租户资源 | tenantId 强制进入资源加载和查询谓词 |
| 超管越权 | 通配符执行调账/提现审批 | 资金动作禁止通配绕过，要求显式 Grant |
| 撤权延迟 | 旧 Token 继续持有权限 | permissionVersion/sessionVersion |
| SQL 注入 | `${dataScope}` 或可控列名 | 白名单列映射、结构化谓词、绑定参数 |
| 历史归属漂移 | 当前代理商看到旧代理订单 | order relation snapshot |
| 抵赖 | 高风险操作无法追责 | 授权决策与操作审计、traceId |

## 6. Finance Review

Verdict：**有条件通过**。

- 本次不直接动账，但设计决定谁能查看和操作资金，属于高风险路径；
- 所有资金操作权限必须显式授权、MFA step-up、职责分离和完整审计；
- 超级管理员不能等价于资金操作员；
- 未完成资金权限目录和审批矩阵前，只允许生成框架与测试，不允许接入余额、账本、提现、代付、退款或调账写链路。
