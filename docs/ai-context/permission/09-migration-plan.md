# 权限系统迁移与实施计划

## 1. 原则

- 不把开源项目表直接复制为新系统表；
- 不大爆炸切换；
- 默认拒绝优先于错误放行；
- 新旧权限先影子比对，再灰度；
- 权限切换与资金系统切换分开；
- 每批迁移有 Owner、停止条件和回滚开关。

## 2. 阶段 0：规则定版

必须先确认：

- 是否允许全局 User 加入多个 Tenant； 答：运维用户可跨 Tenant，商户/代理用户默认只属于自己的 Tenant，跨 Tenant 必须显式授权；
- 平台、代理商、直连/间连商户预置角色；
- 第一批权限码；
- FUND 权限和职责分离矩阵；
- 市场/商户/渠道是否一期范围维度；
- 历史订单脱敏与导出规则；
- 普通撤权和资金撤权 SLA；
- break-glass 流程。

验收：产品、安全、资金、架构共同签字。

## 3. 阶段 1：身份与租户底座

建设：

- User、Tenant、Membership、Department；
- Sa-Token Session Adapter；
- sessionVersion、permissionVersion；
- MFA/IdP 接口边界；
- 安全审计。

验证：跨租户自动化测试、禁用/改密/MFA 重置会话撤销。

## 4. 阶段 2：角色与完整授权

建设：

- Role、Permission、RoleGrant；
- GrantDimension/Target；
- 成员角色全量覆盖和 optimistic version；
- 菜单展示绑定；
- 权限缓存和事务后失效。

验证：多角色动作/范围不拼接、最后管理员保护、自提权拒绝。

## 5. 阶段 3：业务范围 Provider

按顺序接入：

1. Department；
2. Merchant；
3. Market；
4. SalesCustomerRelation；
5. AgentMerchantRelation；
6. Channel；
7. Historical Relation Snapshot。

每个 Provider 都需要详情授权和列表谓词测试。

## 6. 阶段 4：旧权限映射

建立显式映射：

```text
legacy permission code -> target permission code
legacy role -> target role
legacy data scope -> target grant dimensions
legacy user -> user + tenant membership
```

旧 `ALL` 数据范围不能直接映射为支付系统全商户/全市场。需要业务 Owner 审批。

## 7. 阶段 5：影子运行

请求同时计算旧/新权限，但仍由旧系统作最终决定。记录：

```text
oldDecision
newDecision
reasonCode
permissionCode
tenantId
resourceFingerprint
```

差异分类：

- 新拒绝、旧允许：检查是否修复旧越权或造成误拒；
- 新允许、旧拒绝：默认 P0，必须解释；
- 范围不同：逐条确认关系和历史快照；
- 缓存延迟：验证版本传播。

影子日志必须脱敏。

## 8. 阶段 6：灰度

推荐顺序：

```text
内部只读用户
-> 平台普通管理
-> 代理商只读订单
-> 商户普通配置
-> 敏感数据查看/导出
-> 资金只读
-> 资金写操作（最后）
```

资金写操作单独通过 production merge gate 和发布演练。

## 9. 回滚

- 停止给新会话启用目标权限引擎；
- 已迁移会话强制退出并回到旧入口；
- 保留新系统审计和差异数据；
- 不回滚已经发生的资金业务事实；
- 若新权限曾允许不应允许的动作，立即撤权、冻结相关会话并启动安全事件调查；
- Schema 采用向前兼容，不在同次发布删除旧字段。

## 10. 发布门禁

任何一项未满足都不切换：

- Permission 目录未定版；
- 多角色拼接测试失败；
- tenant predicate 可被绕过；
- 角色全量覆盖无版本控制；
- 权限变更无法及时撤销在线会话；
- 历史订单无关系快照；
- 导出无范围、大小、审计和脱敏；
- FUND 权限可以由超级管理员通配获得；
- 没有影子比对和差异 Owner；
- 没有回滚、告警和事件响应手册。

## 11. 当前原型的定位

`backend` 当前用于验证：

- RoleGrant 原子授权；
- 多维范围；
- 多角色不拼接；
- 版本化缓存；
- Sa-Token 会话桥接边界；
- DataScopePlan 保留 Grant 元组。

它不是完整应用，不包含生产数据库连接、Controller、MFA、审批工作流和真实订单 Mapper。
