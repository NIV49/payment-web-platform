# 数据范围与查询拦截设计

## 1. 目标

数据范围必须同时满足：

- 默认拒绝；
- 授权工作区身份永远来自可信 Session；
- 资源归属租户 predicate 不缺失；跨归属租户时关系 predicate 不缺失；
- 不信任前端 tenant、relationship 或 scope；
- 不跨 Grant 扁平化维度；
- 不通过可控 `${}` 拼接 SQL；
- 支持部门、商户、市场、渠道、客户和历史关系；
- 单条详情和列表查询使用同一授权语义。

## 2. 两阶段授权

### 2.1 粗粒度动作检查

Sa-Token 检查是否存在候选 permissionCode，用于快速拒绝无权限请求。

### 2.2 资源/查询范围检查

AuthorizationService 加载完整 RoleGrant：

```text
membership -> membership_role -> role_grant -> grant_dimension -> grant_target
```

对详情接口，直接用资源授权视图计算 Allow/Deny。

对列表接口，生成结构化 `DataScopePlan`：

```text
DataScopePlan {
  authorizationWorkspaceTenantId,
  permissionCode,
  permissionVersion,
  crossTenantMode,
  grantPredicates[]
}
```

`authorizationWorkspaceTenantId` 表示 Grant 属于哪个安全工作区，不等同于每条业务资源的 `resource_owner_tenant_id`。当前 `DataScopePlan` 尚未扩展到跨归属租户查询；在完成关系 Provider 与真实订单 Mapper 之前必须 fail closed。

## 3. GrantPredicate

每个 Grant 保持独立：

```text
GrantPredicate A:
  merchant IN (M1)
  market IN (PK)

GrantPredicate B:
  merchant IN (M2)
  market IN (BR, TH)
```

同租户 SQL 语义：

```text
resource_owner_tenant = authorization_workspace_tenant
AND ((A.merchant AND A.market) OR (B.merchant AND B.market))
```

跨资源归属租户只读 SQL 语义：

```text
permission.cross_tenant_mode = RELATED_PARTY_READ
AND trusted_relationship(resource_owner_tenant, merchant)
AND ((A.merchant AND A.market) OR (B.merchant AND B.market))
```

不能用关系条件替代 Grant 条件，也不能只凭 `TENANT_ALL` 跨租户。

## 4. Mapper 集成

推荐显式标注资源列映射，而不是拦截所有 SQL 猜字段：

```java
@EnforceDataScope(
    permission = "order:view",
    tenantColumn = "o.tenant_id",
    merchantColumn = "o.merchant_id",
    marketColumn = "o.market_code",
    channelColumn = "o.channel_id"
)
Page<OrderRow> selectOrderPage(...);
```

列名必须来自编译期注解或服务端白名单，不接受请求参数。

第一版参考实现提供应用层 `PermissionDataScopeInterceptor`，输出结构化 Plan；真正 MyBatis/JSqlParser 适配器必须在订单表和查询形态确定后单独实现并做真实 PostgreSQL 测试。

这是有意的边界：一个“看起来通用”的 SQL 插件如果无法证明 UNION、子查询、CTE、别名、UPDATE/DELETE 和分页行为，不能进入支付系统。

## 5. 各维度解析

| 维度 | 解析方式 | 注意事项 |
| --- | --- | --- |
| TENANT | 校验 Grant 属于 Session 的授权工作区；同租户资源再要求 owner tenant 相等 | 跨归属租户不能靠 TENANT_ALL 放行 |
| RESOURCE_OWNER_TENANT | 业务资源表或授权视图 | 必须来自服务端，不接收请求 tenant |
| RELATIONSHIP | Party/Relationship Provider 或历史关系快照 | 只补充 RELATED_PARTY_READ Grant，不能生成权限 |
| OWNER/SELF | `owner_membership_id` | 不使用全局 userId 代替 membershipId |
| DEPARTMENT | dept_id 或 closure table | 部门必须属于同租户 |
| CUSTOMER | SalesCustomerRelation Provider | 离职/解绑立即失效，历史业绩另算 |
| MERCHANT | 指定 ID 或 AgentMerchantRelation | 当前与历史关系语义分开 |
| MARKET | market_code | 市场代码由服务端资源提供 |
| CHANNEL | channel_id/account_id | 资金接口通常必须匹配具体渠道维度 |

## 6. 历史订单

历史订单查询不能只对 Merchant 表应用当前关系。目标订单读模型至少包含：

```text
tenant_id
merchant_id
agent_id_at_trade
agent_merchant_relation_id
relation_effective_at_trade
market_code
```

`RELATION_AT_EVENT` 由订单快照 Provider 验证；响应应用专用脱敏 Profile。

## 7. 详情接口

禁止：

```text
selectById(id) -> 返回后再判断 tenant/merchant
```

优先：

```text
select trusted authorization view by id
-> obtain resourceOwnerTenant + merchant/customer + market + relationship snapshot
-> authorize
-> select masked detail by id and the same verified scope tuple
```

同租户查询强制 owner tenant + scope；跨归属租户只读查询强制显式 metadata + relationship + scope。无权和不存在统一返回，避免枚举。

## 8. 缓存

DataScopePlan 可以按：

```text
tenantId + membershipId + permissionCode + permissionVersion
```

短期缓存。关系型范围必须额外包含 relationshipVersion，或由关系变更同时递增 permissionVersion。

时间型 Grant 的下一 `valid_from/valid_until` 边界必须进入快照；边界到达即强制回源，不能让固定 TTL 延长或延迟授权。

资金操作不缓存最终 Allow 结果；只缓存可验证的 Grant 数据。

## 9. 测试矩阵

必须覆盖：

1. tenant 不一致且无显式 metadata 时拒绝；
2. 只有关系证据、没有 RELATED_PARTY_READ metadata 时拒绝；
3. 只有 metadata、没有可信关系证据时拒绝；
4. RELATED_PARTY_READ + 显式商户/客户范围 + 关系证据同时满足才允许普通只读；
5. FUND 即使关系匹配也拒绝跨资源归属租户；
6. 空 Grant 拒绝；
7. 空 SPECIFIED 拒绝；
8. 多角色不跨 Grant 拼接；
9. 本部门和下级；
10. 当前代理关系；
11. 解除后的历史订单可见，新订单不可见；
12. 市场匹配但商户不匹配拒绝；
13. 商户匹配但渠道不匹配拒绝；
14. 超管对 FUND 权限无隐式绕过；
15. 权限版本变化后旧 Plan 不再命中；
16. UNION/CTE/子查询无法安全改写时 fail closed。

## 10. Verdict

**有条件通过。** Core 已实现 `SAME_TENANT_ONLY` 默认值、`RELATED_PARTY_READ` 显式 permission metadata 和关系证据端口，数据库也以约束禁止 FUND 跨租户。但 Admin API 尚未接入 Party/Relationship 适配器，`DataScopePlan` 也未生成真实跨租户订单 SQL，因此当前运行时仍默认拒绝。真实订单 Mapper、关系版本和 PostgreSQL 查询测试完成前，不得声称跨租户数据权限已投产。
