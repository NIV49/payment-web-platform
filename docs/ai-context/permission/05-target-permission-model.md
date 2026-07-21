# 目标权限领域模型

## 1. 核心原则

### 1.1 默认拒绝

没有完整证据时返回 Deny。空范围不是全租户，而是无权限。

### 1.2 完整授权项

允许执行一个动作，必须存在一条 Grant 同时满足动作、数据范围和限制：

```text
Allow(request)
= snapshot.tenant == subject.authorizationWorkspaceTenant
  and exists grant in snapshot.effectiveGrants
    where grant.permission == request.permission
      and every required dimension is covered by this grant
      and grant.constraints are satisfied
      and (
        resource.ownerTenant == subject.authorizationWorkspaceTenant
        or (
          grant.crossTenantMode == RELATED_PARTY_READ
          and grant.permission.action in {READ, VIEW}
          and grant has explicit CUSTOMER or MERCHANT scope
          and trusted RelationshipProvider confirms the relationship
        )
      )
```

业务关系只能补充已有 Grant，不能创造 Permission。`SAME_TENANT_ONLY` 是权限目录默认值；没有显式元数据、没有关系适配器、关系证据缺失或范围不完整时一律 Deny。

禁止：

```text
permissions = union(all role permissions)
scopes = union(all role scopes)
allow = permission exists AND resource in scopes
```

上述错误算法会创造从未被明确授予的权限组合。

## 2. 领域对象

| 对象 | 职责 | 关键不变量 |
| --- | --- | --- |
| User | 全局自然人身份，与 IdP subject 对应 | 不直接拥有租户权限 |
| Tenant / AuthorizationWorkspace | 权限来源的安全隔离空间 | 不是 Merchant，也不必等于资源归属租户 |
| ResourceOwnerTenant | 业务资源的归属租户 | 只由服务端资源事实提供，不能取请求 tenant |
| BusinessRelationshipEvidence | Party/Relationship 上下文提供的可信关系事实 | 只能补充显式 Grant，不能生成权限 |
| TenantMembership | User 在 Tenant 中的工作身份 | 同租户单部门；状态和版本控制会话 |
| Department | 租户内部组织树 | 只表达组织，不表达代理关系 |
| Role | 租户内可复用授权集合 | 不继承；不能跨租户分配 |
| Permission | 稳定资源动作及风险元数据 | 独立于菜单和 URL |
| RoleGrant | Role 对某 Permission 的完整授权 | 动作、范围、限制原子绑定 |
| GrantDimension | Grant 在某个数据维度的覆盖方式 | 维度间 AND |
| GrantTarget | SPECIFIED 范围的具体目标 | 目标类型必须与维度一致 |
| MembershipRole | Membership 与 Role 关系 | 全量覆盖带版本控制 |
| Menu | 前端导航和按钮展示 | 不是后端授权真相源 |
| Session | Sa-Token 会话摘要 | 包含 session/permission version |
| AuthorizationDecision | 单次授权证据 | 记录 matchedGrant/reason/traceId |

同一角色可以对同一 Permission 持有多条 RoleGrant。每条 Grant 用稳定的 `grantKey` 区分一组原子范围；这不是重复数据，而是为了保留商户、市场、渠道之间的相关 tuple，避免把多个维度错误展开成笛卡尔积。

会话有效性不是只比较 Membership 版本。每次从会话恢复主体时必须以同一 tenant + membership + user 组合核对：

```text
Tenant.status = ACTIVE
AND User.status = ACTIVE
AND Credential.status = ACTIVE
AND Membership.status = ACTIVE
AND Membership.sessionVersion = session.sessionVersion
```

任一条件失败都统一失效会话并返回 401，不能退化成 500，也不能让其他租户 Membership 的存在替代当前组合。租户内 Membership 更新无权修改全局 User/Credential；全局身份禁用属于独立管理用例并应撤销该 User 的全部会话。

## 3. 数据范围维度

| 维度 | 示例 | 数据来源 |
| --- | --- | --- |
| TENANT | 当前授权工作区全部 | 服务端可信 AuthorizationSubject |
| OWNER | 本人创建/负责 | 业务资源 ownerMembershipId |
| DEPARTMENT | 本部门/下级/指定部门 | IAM 部门树 |
| CUSTOMER | 分配的代理商或商户 | SalesCustomerRelation |
| MERCHANT | 指定或关系覆盖的商户 | Merchant / AgentMerchantRelation |
| MARKET | PK/BR/TH 等市场 | Product/Party 配置 |
| CHANNEL | 指定渠道账户或渠道 | Channel 模块 |

第一期不强制每个权限都有所有维度。PermissionDefinition 声明该动作所需的维度集合，例如：

```text
order:view             -> TENANT + MERCHANT + MARKET
historical_order:view  -> TENANT + MERCHANT_RELATION_SNAPSHOT
payout:approve         -> TENANT + MERCHANT + MARKET + FUND_CONSTRAINT
channel:config:update  -> TENANT + MARKET + CHANNEL
```

## 4. ScopeMode

```text
TENANT_ALL
SELF
DEPARTMENT
DEPARTMENT_AND_CHILDREN
ASSIGNED
SPECIFIED
RELATION_CURRENT
RELATION_AT_EVENT
```

- `ASSIGNED` 由销售/客户关系 Provider 判断；
- `RELATION_CURRENT` 用于代理商查看当前名下商户；
- `RELATION_AT_EVENT` 用于历史订单，必须使用订单快照，不根据当前关系回算；
- `SPECIFIED` 读取 GrantTarget；
- `TENANT_ALL` 只描述授权工作区内的范围，不能单独放开其他资源归属租户；
- 跨资源归属租户还必须由 Permission 的 `RELATED_PARTY_READ` 元数据、受控 `READ/VIEW` action 和可信关系证据共同放行；未知或写 action 默认拒绝；
- `FUND` 权限在领域模型和数据库约束中固定为 `SAME_TENANT_ONLY`。

`DimensionScope` 的允许矩阵已经定版；未列出的组合一律 fail closed：

| Dimension | Allowed ScopeMode |
| --- | --- |
| TENANT | `TENANT_ALL` |
| OWNER | `SELF` |
| DEPARTMENT | `SELF`、`DEPARTMENT`、`DEPARTMENT_AND_CHILDREN`、`SPECIFIED` |
| CUSTOMER | `ASSIGNED`、`SPECIFIED` |
| MERCHANT | `ASSIGNED`、`SPECIFIED`、`RELATION_CURRENT`、`RELATION_AT_EVENT` |
| MARKET | `SPECIFIED` |
| CHANNEL | `SPECIFIED` |

Core 构造 `DimensionScope` 时执行该矩阵，V10 在 `iam_grant_dimension` 使用同一数据库 CHECK。历史非法行会阻断 V10，禁止迁移代码猜测改成哪个权限。

## 5. 多角色合并语义

假设：

```text
角色 A: payout:approve, merchant=M1, market=PK
角色 B: report:view, tenant=ALL
```

结果：

- 可以审批 M1/PK 的代付；
- 可以查看全租户报表；
- 不能审批全租户代付。

查询列表时应保留 Grant 元组：

```sql
WHERE tenant_id = :tenantId
  AND (
       (merchant_id = :m1 AND market_code = :pk)
       OR
       (merchant_id = :m2 AND market_code IN (:br, :th))
  )
```

不能扁平化为：

```sql
merchant_id IN (:m1, :m2)
AND market_code IN (:pk, :br, :th)
```

后者会凭空生成 `M1/BR` 等组合。

## 6. 资金操作权限

Permission 具有风险级别：

```text
NORMAL
SENSITIVE
FUND
```

FUND 权限最低约束：

- 显式 Grant，不接受通配权限；
- 会话权限版本必须是最新；
- MFA step-up 在有效时间窗内；
- 操作者租户和资源租户一致；
- 资源 merchant/market/channel 全部匹配；
- 申请人不能审批自己的申请；
- 记录授权决策与业务操作审计；
- 批量操作逐项授权，不能只授权批次外壳。

建议权限：

```text
payout:view
payout:submit
payout:approve
withdrawal:view
withdrawal:submit
withdrawal:approve
refund:approve
reversal:approve
ledger:adjust-request
ledger:adjust-approve
balance:freeze
balance:unfreeze
```

## 7. 代理商和历史订单

当前订单访问：

```text
membership belongs to agent authorization workspace
AND order.resourceOwnerTenant may be the related merchant tenant
AND permission metadata is RELATED_PARTY_READ
AND grant has order:view with explicit merchant/customer scope
AND trusted AgentMerchantRelation is ACTIVE
AND order.merchantId and market match the same grant tuple
```

当前应用尚未接入 Party/Relationship 适配器，因此运行时仍 fail closed；这份模型只建立安全扩展边界，不代表现有 Admin API 已经开放跨租户订单访问。

历史订单访问：

```text
grant has historical_order:view
AND order.agentMerchantRelationId belongs to the agent
AND order.createdAt was inside relation effective interval
AND response uses historical-order masking profile
```

后续退款、拒付、冲正和佣金变化继承原订单关系快照。

## 8. 超级管理员与 break-glass

平台超管不具备隐式资金权限。目标区分：

| 身份 | 能力 |
| --- | --- |
| IAM_PLATFORM_ADMIN | 管理租户、成员、角色、普通权限；不能动资金 |
| SECURITY_ADMIN | 管 MFA、会话和安全事件；不能动资金 |
| FUND_OPERATOR | 显式资金操作范围 |
| FUND_APPROVER | 显式资金审批范围，受职责分离限制 |
| BREAK_GLASS | 短时、双人批准、强审计、默认禁用 |

## 9. 缓存与撤权

数据库保存：

```text
membership.permission_version
membership.session_version
```

缓存 Key：

```text
iam:grant:{tenantId}:{membershipId}:v{permissionVersion}
iam:session:{membershipId}:v{sessionVersion}
```

变更事务：

```text
更新关系/Grant
-> permission_version + 1
-> 写 outbox PermissionChanged
-> 事务提交
-> 删除旧缓存/通知节点
```

GrantSnapshot 同时携带当前成员全部角色 Grant 的最近 `valid_from/valid_until` 边界。只要该 temporal boundary 存在，快照就不得进入或命中 Redis，每次授权都必须以数据库 `statement_timestamp()` 重新加载；不能用应用节点 Clock 比较数据库绝对时间。无时间边界的 cache-hit 在返回前再次复核 permissionVersion，复核前已提交的撤权必须使旧命中失效。

`requiresApproval=true` 的 Grant 在没有服务端验证的 workflowId、审批状态/版本、资源指纹、金额币种、审批人和过期时间前始终 fail closed。客户端传来的 `initiatorMembershipId` 不是审批证据，也不能通过换一个 ID 绕过职责分离。数据范围规划同样排除这类未获可信审批证据的 Grant。

资金接口必须校验当前数据库/强一致版本或使用极短版本缓存；普通读接口可以接受受控的秒级版本缓存。

## 10. 授权决策输出

```text
AuthorizationDecision {
  allowed
  reasonCode
  matchedGrantId
  membershipId
  tenantId
  permissionCode
  permissionVersion
  resourceFingerprint
  traceId
}
```

Deny 原因必须区分：未登录、租户不匹配、无动作、范围不覆盖、step-up 缺失、职责冲突、权限版本过期。
