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

<!-- decision-status id=IAM-GLOBAL-USER-MULTI-TENANT status=accepted ref=docs/adr/0008-isolate-three-backoffice-account-domains-and-sessions.md -->

- [已确认，IAM-GLOBAL-USER-MULTI-TENANT] [ADR-0008](../../adr/0008-isolate-three-backoffice-account-domains-and-sessions.md) 要求三后台应用 User 与会话跨域隔离；同域多 Membership 保留，可信服务端入口无法唯一解析工作区时失败关闭。迁移必须先报告跨域或无法归属的 User，由人工拆分账号后重试，不能复制凭据或改写审计归属来猜测；
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

验证：多角色动作/范围不拼接、RELATED_PARTY_READ 不能包装写 action、非法 BCrypt hash 不能充当备用管理员、最后管理员保护、自提权拒绝。

当前管理权限目录采用 V14 细粒度扩展、V15 N-1 兼容、V16 精确目录守卫的前向链；V17 在其后只增不改地加入 Role/Menu/Department 墓碑和 live-only 唯一索引。已执行迁移不可回写：V16 发现固定管理权限元数据漂移时停在 V15 且不修数据；V17 不物理清理业务行。N-1 兼容期内 RoleGrant/角色 configuration 全量替换必须由默认关闭的部署闸门禁止；只有旧客户端依赖清零并完成发布验证后才可打开。

V18 只增不改地加入 PLATFORM/MERCHANT/AGENT 账号域约束。执行前运行 `psql "$PAYMENT_DB_URL" -v ON_ERROR_STOP=1 -f backend/scripts/iam001-account-domain-preflight.sql`；结果必须为空。报告中的跨域或无 Membership 主体必须由人工拆分账号并保留审计映射，禁止迁移脚本复制凭证或猜测归属。

V19 新增三个服务端专用入口 Permission，并按 Tenant `account_domain` 给每个未删除历史角色回填唯一 `system-backoffice-access`、`TENANT/TENANT_ALL` Grant；迁移同步递增角色和成员权限版本，并写审计与 Outbox。两条新建角色事务也必须创建该 Grant；18 项租户授权编辑器不返回或替换它，缺失、错误域或畸形 Grant 一律只读失败。ACTIVE Membership 不能代替 `backoffice:{platform|merchant|agent}-access` RoleGrant。

V20 是 V18/V19 之后的前向兼容修复。V17 允许普通 Permission 使用 `system-backoffice-access` key，V19 的三列唯一键不能阻止它与 portal Permission 并存；V20 将这些历史普通 Grant 确定性改名为 `legacy-backoffice-access-{grantId}`，保留状态、有效期、维度与目标，推进受影响角色/成员版本并写审计和 Outbox。任何 portal 数量、账号域、维度/目标形态异常或目标 key 冲突都会原子阻断；读取侧对迁移后再次出现的保留 key 冲突保持只读失败。

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

当前管理权限迁移采用明确的 expand/contract：V14 建立细粒度目录，V15 前向补齐所有旧 manage Grant 的等价现代 Grant，并暂时恢复旧 Permission 供滚动兼容；V16 只读式精确核验当前 21 条管理 Permission 元数据，发现漂移即阻断升级，不回写已执行的 V14/V15。旧码不再绑定当前 endpoint、按钮或新授权。现代 RoleGrant PUT 在生产默认关闭；只有 N/N-1 二进制与真实数据库兼容测试、旧实例和旧调用方清零、生产审批完成后，部署方才可显式打开 cutover 开关并允许全量保存退役该角色的兼容影子。最终仍需新的 contract 迁移停用旧码；禁止修改已执行的 V14/V15。

V17 墓碑写入属于前向状态：旧二进制会把墓碑误当普通 DISABLED 行，因此一旦新版本开始软删除，不能通过回滚应用版本继续对同库写入。发布计划必须把“停止写入并前向修复”或“恢复发布前整库快照”作为回退路径，并分别验证 live-only unique index 和历史行保留。

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

`backend/applications/platform-admin-api` 已是可启动的本地管理应用，当前已经验证：

- RoleGrant 原子授权；
- 多维范围；
- 多角色不拼接；
- 版本化缓存；
- Sa-Token 会话对 Tenant/User/Credential/Membership 四态和版本的 fail-closed 校验；
- 普通角色分配的委派上限、自提权拒绝和基于统一 BCrypt 可登录规则的最后管理员保护；
- Admin HTTP PEP 接入完整授权服务；
- Java 25 / Spring Boot 4.1 / jOOQ / PostgreSQL 18 构建与生成门禁；
- 生产 Flyway 与 local fixture 隔离；
- V14/V15 管理权限 expand 迁移保留复杂历史 Grant 的范围、有效期和 target，并推进 role/membership 版本、审计和 Outbox；
- V16 固定管理权限目录失败关闭守卫，以及默认关闭的 RoleGrant N-1 切换闸门；
- V17 墓碑、系统预置来源标记和 live-only 唯一索引；`system_managed` 是 local bootstrap 所有权事实，不是业务不可编辑标记；软删除后列表/选择器/有效授权均排除墓碑；
- DataScopePlan 保留 Grant 元组并排除无可信审批证据的 Grant。

它仍不是生产身份或支付权限系统：外部 IdP、MFA 时效、可信审批工作流、超出首阶段 18 项 TENANT/TENANT_ALL 目录的通用 RoleGrant 管理、关系 Provider、真实订单 Mapper、Outbox relay、生产 provisioning/observability 和资金业务规格尚未完成。任何真实资金写路径仍被发布门禁阻断。
