# 权限能力对比与当前项目决策

## 1. 总体矩阵

| 能力 | RuoYi-Vue | ContiNew Admin | 当前项目决策 |
| --- | --- | --- | --- |
| 登录认证 | Spring Security + JWT + Redis LoginUser | Sa-Token + LoginHandler + SaSession | 采用 Sa-Token 作为会话和鉴权适配层；凭证/MFA 可接 IdP |
| 用户身份 | User 直接属于部门 | User 直接属于部门和 tenant_id | Global User 与 TenantMembership 分离 |
| 多租户 | 当前源码无原生租户模型 | tenant_id 插件 + 租户套餐 | 租户是安全空间，不直接等于商户；主体关系独立建模 |
| 用户角色 | sys_user_role | sys_user_role | MembershipRole，角色必须属于同一租户 |
| 菜单权限 | 菜单/按钮绑定 perms | 菜单/按钮绑定 permission | Permission 独立；Menu 只做展示绑定 |
| API 权限 | `@PreAuthorize @ss` | `@SaCheckPermission` | 普通接口用 Sa-Token 注解；资源/资金接口再走 AuthorizationService |
| 数据权限 | AOP 拼接 SQL；按当前权限筛角色 | Starter 插件 + Provider | 参考 Provider/Interceptor 分层；采用结构化 Grant Predicate |
| 数据范围 | 全部/自定义部门/本部门/下级/本人 | 同类五种范围 | Department、Merchant、Market、Channel、Customer、Owner 多维组合 |
| 动作与范围绑定 | 角色持有菜单权限和单一 data_scope，Aspect 按动作筛角色 | Provider 暴露所有 RoleContext；具体合并逻辑在外部 Starter | 明确建模 RoleGrant(permission + scopes + constraints) |
| 部门树 | ancestors + `find_in_set` | ancestors + Starter | PostgreSQL closure/递归查询；只作为组织维度 |
| 缓存 | Redis LoginUser 权限快照 | SaSession + JetCache/Redis | 第一阶段只用 Redis；版本化 Key，事务后失效 |
| 权限失效 | 角色更新扫描在线 token；部分路径存在遗漏风险 | 定向更新受影响在线 UserContext | permissionVersion + sessionVersion + Outbox 事件 |
| 超级管理员 | userId=1 / roleId=1 全部绕过 | super_admin / tenant_admin 绕过 | 不允许绕过资金权限；超管仅管理 IAM |
| DTO/VO | Entity/DTO 边界较弱 | Req/Resp/DO 清晰 | 采用 ContiNew 分层，不暴露 Entity |
| 异常 | ServiceException + AjaxResult | 统一异常 Handler | 统一错误码；未认证、未授权、范围拒绝、版本冲突分开 |
| 数据库迁移 | 初始化 SQL | Liquibase MySQL/PostgreSQL | PostgreSQL + Flyway/Liquibase 选一；expand/contract |
| 审计 | 操作日志注解 | 日志 Starter | 权限决策与高风险操作专用审计，不记录秘密和完整敏感数据 |

## 2. 各主题选择

### 2.1 登录与 Token

选择 ContiNew 的 Handler 和 Sa-Token 会话方式，不把完整商户/市场/渠道范围编码进 Token。Token 只定位服务端 Session；Session 保存：

```text
userId
membershipId
tenantId
sessionVersion
permissionVersion
MFA/step-up 状态摘要
```

### 2.2 权限目录

权限码格式统一为：

```text
resource:action
```

例如：

```text
order:view
historical_order:view
historical_order:export
payout:approve
withdrawal:approve
ledger:adjust
mfa_reset:approve
```

权限码不从 URL 动态推导，也不依赖菜单标题。

### 2.3 数据权限

拒绝把一个 `role.data_scope` 应用于该角色所有动作。目标结构：

```text
Role
  -> RoleGrant(permissionCode, riskLevel, constraints)
       -> Scope(DEPARTMENT, ...)
       -> Scope(MERCHANT, ...)
       -> Scope(MARKET, ...)
       -> Scope(CHANNEL, ...)
```

评估同一 Grant 内的维度使用 AND；同一维度多个 target 使用 OR；多个 Grant 之间使用 OR，但不能先把所有动作和所有范围分别求并集。

### 2.4 超级管理员

目标没有 `*:*:*` 对资金操作的全局效果。

- 平台超管可以维护 IAM 和系统配置；
- `payout:approve`、`withdrawal:approve`、`ledger:adjust` 等必须显式授予；
- 高风险动作需要 step-up MFA；
- 申请人和审批人职责分离；
- break-glass 使用单独身份、短时授权和强审计，不复用普通超管。

## 3. 明确不采用的模式

1. 前端传 `tenantId/merchantId/userId` 并作为可信授权上下文；
2. 菜单隐藏等价后端权限；
3. 用户 ID 或角色 ID 固定为超级管理员；
4. `${params.dataScope}` 拼接任意 SQL；
5. Redis pattern 扫描全部在线 Token 刷新权限；
6. 将代理商—商户关系建成租户父子关系；
7. 将市场、商户、渠道全部压进部门树；
8. 将完整范围放入长生命周期 JWT；
9. 因“框架自带”就信任外部数据权限插件的多角色合并逻辑。

## 4. 决策结论

```text
RuoYi 的业务闭环
+ ContiNew 的工程结构
+ RoleGrant 原子授权
+ 支付业务的租户/关系/资金门禁
= 目标权限系统
```

这不是二选一，而是对两者同时做减法。
