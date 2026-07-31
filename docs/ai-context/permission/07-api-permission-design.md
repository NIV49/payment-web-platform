# 权限 API 与鉴权设计

## 1. API 原则

- Controller 使用显式 Request/Response，不暴露 Entity；
- 身份、membershipId、tenantId 从服务端会话上下文获取；
- URL 中 tenantId 只用于资源定位，必须与上下文一致；
- 角色保存采用全量语义并带 `expectedVersion`；
- 空数组表示明确清空，`null`/缺失表示请求无效；
- 权限错误不泄露目标资源是否存在；
- 批量操作逐项鉴权并返回逐项结果；
- 资金 API 不仅检查权限码，还要检查资源范围、step-up 和职责分离。

## 2. Sa-Token 的边界

Sa-Token 负责：

- 当前原型的 Cookie 登录态和 Session 生命周期；
- Session 定位；
- 踢下线和会话撤销；
- 保存经过服务端验证的 step-up 摘要（当前只有布尔原型，不能用于资金生产）。

业务 AuthorizationService 负责：

- 租户成员有效性；
- RoleGrant 加载；
- 商户/市场/渠道/关系范围；
- 资金附加约束；
- 决策审计。

不能把 `@SaCheckPermission("payout:approve")` 当成完整资金授权。

长期身份真相源由外部 OIDC IdP 承担，Sa-Token 只保留为应用 Session adapter；两者不能并列成为账号、MFA 和凭证状态的双真相源。接入 IdP 前，本地 credential 仅是开发原型和未来受控 break-glass 的候选，不是默认生产方案。

## 3. 目标接口

### 3.1 当前会话

```http
GET /api/v1/auth/me
```

返回：

```json
{
  "code": "OK",
  "data": {
    "userId": 10001,
    "membershipId": 20001,
    "tenantId": 30001,
    "permissionVersion": 7,
    "sessionVersion": 3,
    "permissions": ["order:view", "historical_order:view"]
  },
  "traceId": "..."
}
```

不返回完整商户、市场、渠道列表，避免 Token/响应成为长期授权真相。

### 3.2 全量覆盖成员角色

```http
PUT /api/v1/iam/memberships/{membershipId}/roles
```

```json
{
  "roleIds": [41001, 41002],
  "expectedVersion": 6,
  "reason": "岗位调整"
}
```

权限：`user:assign-role`。

校验：

- 目标 Membership 属于当前租户；
- 所有 Role 属于当前租户且有效；
- 操作者有权授予每个 Role；
- 不能删除最后一个可登录的最高 IAM 管理员；备用管理员必须 User/Membership/Credential/Role 全部 ACTIVE，且凭证符合统一 BCrypt 格式与 cost 10..14，非空但非法的 hash 不计数；
- 不能普通流程自提权；
- 操作者只能委派自己当前拥有且允许普通分配的角色能力；禁用的 system role 不产生超级管理员绕过；
- 版本冲突返回 `IAM_VERSION_CONFLICT`；
- 同事务更新关系、版本、审计和 Outbox。

### 3.3 保存角色完整授权

```http
PUT /api/v1/iam/roles/{roleId}/grants
```

```json
{
  "expectedVersion": 9,
  "grants": [
    {
      "grantKey": "payout-pk-merchant-88001",
      "permissionCode": "payout:approve",
      "dimensions": [
        {"code": "MERCHANT", "mode": "SPECIFIED", "targets": ["88001"]},
        {"code": "MARKET", "mode": "SPECIFIED", "targets": ["PK"]}
      ]
    }
  ],
  "reason": "PK 代付审批职责"
}
```

权限码格式遵循现有 `resource:action` 两段约束，因此本项目使用 `role:grant-update`。第一阶段还同时要求 `role:view`、ACTIVE system role 操作者与非 system 目标角色；FUND Grant、targets、有效期和批准流程尚未开放。

`riskLevel`、`requiresStepUp`、`requiresApproval` 和 `requiredDimensions` 不属于请求字段，必须从服务端 Permission Catalog 读取并校验。`grantKey` 只用于角色内的幂等定位；同一权限的多组相关范围必须使用不同 key，不能把商户和市场 target 扁平合并。

`requiresApproval=true` 必须绑定由审批工作流服务端签发并重新读取验证的证据。客户端提交的 initiator/approver ID 不可信；在 workflowId、状态/版本、资源指纹、金额币种、审批人、有效期和防重放规则落地前，此类 Grant 一律拒绝，不能回退成普通授权。

### 3.4 授权预检

```http
POST /internal/v1/authorization/check
```

仅供可信内部服务，不能由浏览器决定最终资金授权。

```json
{
  "permissionCode": "payout:approve",
  "resource": {
    "resourceType": "PAYOUT_ORDER",
    "resourceId": "P202607160001"
  }
}
```

服务端根据 resourceId 加载 tenant/merchant/market/channel/owner，忽略客户端提交的同类字段。

## 4. 错误契约

| Code | HTTP | 语义 |
| --- | --- | --- |
| AUTH_UNAUTHENTICATED | 401 | 未登录或 Session 无效 |
| AUTH_SESSION_STALE | 401 | sessionVersion 过期 |
| AUTH_TENANT_MISMATCH | 403 | 工作空间与资源租户不一致 |
| AUTH_PERMISSION_DENIED | 403 | 没有动作授权 |
| AUTH_SCOPE_DENIED | 403 | 数据范围不覆盖资源 |
| AUTH_STEP_UP_REQUIRED | 403 | 需要 MFA step-up |
| AUTH_SEPARATION_OF_DUTY | 403 | 申请人与审批人冲突 |
| IAM_VERSION_CONFLICT | 409 | 基于旧版本覆盖 |
| IAM_ROLE_NOT_ASSIGNABLE | 422 | 无权授予目标角色 |
| IAM_LAST_ADMIN_PROTECTED | 422 | 不能移除最后管理员 |

错误响应不返回“目标商户存在但不属于你”等可枚举细节。

## 5. 后端使用方式

普通接口：

```java
@SaCheckPermission("merchant:view")
public MerchantResp getMerchant(...) { ... }
```

资源接口：

```java
authorizationService.require(
    AuthorizationRequest.forResource("order:view", orderAuthorizationView)
);
```

资金接口：

```java
fundAuthorizationService.require(
    "payout:approve",
    payoutAuthorizationView,
    SeparationOfDuty.notSameOperator(payout.getApplicantId())
);
```

## 6. 前端

- 前端可以用权限码隐藏按钮，但不能传入或合并数据范围；
- 遇到 `AUTH_STEP_UP_REQUIRED` 进入 MFA step-up；
- 遇到版本冲突重新加载完整角色/Grant；
- 不在 LocalStorage 保存长期 Token 或完整授权范围；
- 导出和查看使用不同权限码。

## 7. 兼容计划

目标是新系统，无需兼容 RuoYi/ContiNew API。若迁移旧后台：

1. 保留旧登录入口只做适配；
2. 新增 `/api/v1` 权限接口；
3. 前端用 adapter 转换旧菜单结构；
4. 旧权限码建立映射表，不在运行时猜测；
5. 影子比对拒绝/允许差异；
6. 切换后废弃旧权限接口，不双写资金授权。

## 8. API Gate

Verdict：**有条件通过**。资金资源加载器、错误码目录、step-up 时效和审批规则未定版前，不允许接入真实资金写接口。
