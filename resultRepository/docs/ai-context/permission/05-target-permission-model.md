# 目标权限领域模型

## 1. 核心原则

### 1.1 默认拒绝

没有完整证据时返回 Deny。空范围不是全租户，而是无权限。

### 1.2 完整授权项

允许执行一个动作，必须存在一条 Grant 同时满足动作、数据范围和限制：

```text
Allow(request)
= exists grant in effectiveGrants
  where grant.permission == request.permission
    and grant.tenant == request.tenant
    and every required dimension is covered by this grant
    and grant.constraints are satisfied
```

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
| Tenant | 安全隔离空间 | 代理商/商户租户彼此独立 |
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

## 3. 数据范围维度

| 维度 | 示例 | 数据来源 |
| --- | --- | --- |
| TENANT | 当前租户全部 | 服务端 TenantContext |
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
- `TENANT_ALL` 仍不能跨 Tenant。

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
membership belongs to agent tenant
AND grant has order:view
AND scope covers merchant
AND AgentMerchantRelation is ACTIVE
AND order.merchantId matches
```

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
