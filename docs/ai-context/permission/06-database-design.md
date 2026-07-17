# 权限数据库设计

## 1. 结论

数据库目标为 PostgreSQL。DDL 位于：

```text
backend/applications/identity-authorization/src/main/resources/db/migration/V1__permission_schema.sql
```

Verdict：**有条件通过作为新库基线，不允许直接在生产执行。**

条件：确认 ID 生成器、租户成员多归属规则、权限目录、市场编码、数据保存周期和迁移工具。

## 2. 表所有权

| 表 | 所有者 | 用途 |
| --- | --- | --- |
| iam_user | Identity & Organization | 全局身份映射 |
| iam_tenant | Identity & Organization | 安全隔离空间 |
| iam_department | Identity & Organization | 租户组织树 |
| iam_membership | Identity & Organization | 用户在租户中的身份和版本 |
| iam_role | Authorization | 租户角色 |
| iam_membership_role | Authorization | 成员多角色 |
| iam_permission | Authorization | 权限目录和风险元数据 |
| iam_role_grant | Authorization | 角色完整授权项 |
| iam_grant_dimension | Authorization | Grant 维度和范围模式 |
| iam_grant_target | Authorization | 指定目标集合 |
| iam_menu | Presentation | 菜单/按钮展示 |
| iam_role_menu | Presentation | 角色菜单展示关系 |
| iam_audit_event | Audit | 权限、会话和高风险操作审计 |
| iam_permission_change_outbox | Authorization | 权限版本和缓存失效的事务事件 |

代理商、商户、销售、渠道和市场由各自业务模块拥有；IAM 通过 ID/Code 和 Provider 校验，不复制业务关系真相。

## 3. 关键约束

### 3.1 TenantMembership

- 唯一键 `(tenant_id, user_id)`；
- `department_id` 通过 `(tenant_id, department_id)` 组合外键保证同租户，Service 仍需校验业务状态；
- `permission_version` 在授权变化时单调递增；
- `session_version` 在禁用、改密、MFA 重置和强制退出时递增；
- 禁用状态不能继续签发或使用业务 Session。

### 3.2 RoleGrant

- 唯一键 `(role_id, permission_id, grant_key)`；同一角色、同一权限可以有多个原子 Grant；
- `grant_key` 是角色内稳定的授权 tuple 标识，用于幂等更新和审计，不承载权限语义；
- 一条 Grant 内不同维度按 AND，维度内多个 Target 按 OR，多条 Grant 按 OR；需要保持相关性的范围必须拆成多条 Grant，禁止合并成错误的笛卡尔积；
- Role 与 Permission 不能通过菜单间接推导；
- `risk_level` 以 Permission 为真相源，Grant 不允许降低风险；
- FUND Permission 必须 `requires_step_up=true`；
- 角色、Grant 和 Membership 必须属于同一租户安全域。
- MembershipRole、RoleGrant、RoleMenu、部门父子和菜单父子均使用带 `tenant_id` 的组合外键，数据库拒绝跨租户关系写入。

### 3.3 GrantDimension

- 唯一键 `(grant_id, dimension_code)`；
- `SPECIFIED` 必须至少存在一个 `iam_grant_target`；
- `SELF/TENANT_ALL/DEPARTMENT` 等模式不允许带任意 Target；
- Target 不使用前端自由文本直接写入，必须由对应业务 Provider 校验存在和归属。

## 4. 索引与查询场景

| 索引 | 查询场景 | 代价 |
| --- | --- | --- |
| membership `(tenant_id,user_id)` unique | 登录工作空间定位 | 成员写入增加唯一性检查 |
| membership `(tenant_id,status)` | 租户成员列表 | 低频成员写入可接受 |
| membership_role `(membership_id,role_id)` unique | 加载有效角色 | 关系替换写放大 |
| role_grant `(role_id,permission_id,grant_key)` unique | 幂等定位一组原子范围 | 同权限多 tuple 会增加 Grant 行数 |
| role_grant `(role_id,permission_id)` | 按角色和权限加载全部 tuple | 额外写索引 |
| grant_dimension `(grant_id,dimension_code)` unique | 组装授权范围 | 低写高读合适 |
| grant_target `(dimension_id,target_ref)` unique | SPECIFIED 目标匹配 | 大范围角色可能产生较多行 |
| audit `(tenant_id,occurred_at desc)` | 租户审计时间线 | 审计高写成本，需要分区门槛 |
| audit `(trace_id)` | 事故追踪 | 额外写索引 |

不预先给 `iam_audit_event` 分区。达到真实行数、保留周期和查询证据后按月分区。

## 5. 数据类型

- 主键使用 `BIGINT`，由应用分布式 ID 生成；
- 时间使用 `TIMESTAMPTZ`，统一 UTC 存储；
- 状态和枚举使用可读 `VARCHAR` + CHECK，避免裸数字漂移；
- 审计前后值使用受控 `JSONB`，必须脱敏；
- OIDC 身份使用 `(idp_issuer, idp_subject)` 唯一，不能假设 `sub` 在不同 Issuer 间全局唯一；
- 权限表不存金额，因此没有资金精度字段；
- 不使用物理删除回收权限历史，业务表通过状态和审计表达变更。

## 6. 历史兼容与迁移

新系统是新库，不直接 ALTER 两套参考系统表。旧数据迁移采用：

1. 导入 User 与租户映射；
2. 建立 TenantMembership；
3. 导入部门与角色；
4. 把旧 `role + menu permission + data_scope` 展开成 RoleGrant；
5. 部门自定义范围展开为 GrantTarget；
6. 商户、市场和渠道范围初始为空，默认 Deny；
7. 业务 Owner 补齐并批准范围；
8. 影子比对新旧授权结果；
9. 强制重新登录后灰度。

## 7. 回滚

- 新系统未承接流量前可以删除新库重建；
- 灰度后回滚只停止新会话和新授权写入，不把已产生的审计丢弃；
- 权限变更使用事件和版本，旧应用只在兼容窗口读取旧模型；
- 不通过反向修改旧系统权限表实现回滚；
- 迁移脚本必须可重跑，并记录 source_id -> target_id 映射。

## 8. 数据库门禁

以下任一未满足，不得上线：

- 缺少跨租户自动化测试；
- `SPECIFIED` 空集合被解释为全量；
- FUND 权限允许通配绕过；
- 角色全量覆盖没有版本控制；
- 唯一索引前未检查迁移重复数据；
- 审计记录包含密码、Token、MFA Secret 或完整敏感数据；
- 数据范围查询可以丢失 tenant predicate；
- 没有迁移验证、回滚和权限差异报表。

## 9. 不确定项

> 不确定：最终采用 Flyway 还是 Liquibase；当前文件使用 Flyway 风格命名，但 SQL 本身不依赖 Flyway 专有语法。

> 不确定：ID 是否采用雪花、数据库 sequence 或其他全局生成器。

> 不确定：用户多租户工作空间是否开放；Schema 支持，但产品交互尚未确认。
