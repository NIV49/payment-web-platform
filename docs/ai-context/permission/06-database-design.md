# 权限数据库设计

## 1. 结论

数据库目标为 PostgreSQL。DDL 位于：

```text
backend/modules/identity/persistence-postgres/src/main/resources/db/migration/V1__permission_schema.sql
...
backend/modules/identity/persistence-postgres/src/main/resources/db/migration/V17__add_administration_tombstones.sql
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
| iam_permission_change_outbox | Authorization | append-only 权限版本和缓存失效事件事实 |
| iam_permission_change_relay_state | Authorization Infrastructure | polling relay 的租约、重试和发布状态 |

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
- FUND Permission 必须 `requires_step_up=true` 且 `cross_tenant_mode=SAME_TENANT_ONLY`；
- `RELATED_PARTY_READ` 是显式 opt-in，只能搭配 `read/view` action，且仍需可信关系 Provider 和商户/客户范围；V12 CHECK 同时约束历史行和后续直接 SQL；
- 角色、Grant 和 Membership 必须属于同一授权工作区。
- MembershipRole、RoleGrant、RoleMenu、部门父子和菜单父子均使用带 `tenant_id` 的组合外键，数据库拒绝跨租户关系写入。

### 3.3 GrantDimension

- 唯一键 `(grant_id, dimension_code)`；
- `SPECIFIED` 必须至少存在一个 `iam_grant_target`；
- `SELF/TENANT_ALL/DEPARTMENT` 等模式不允许带任意 Target；
- Target 不使用前端自由文本直接写入，必须由对应业务 Provider 校验存在和归属。

Dimension/mode 允许矩阵：

| dimension_code | allowed scope_mode |
| --- | --- |
| TENANT | `TENANT_ALL` |
| OWNER | `SELF` |
| DEPARTMENT | `SELF`、`DEPARTMENT`、`DEPARTMENT_AND_CHILDREN`、`SPECIFIED` |
| CUSTOMER | `ASSIGNED`、`SPECIFIED` |
| MERCHANT | `ASSIGNED`、`SPECIFIED`、`RELATION_CURRENT`、`RELATION_AT_EVENT` |
| MARKET | `SPECIFIED` |
| CHANNEL | `SPECIFIED` |

Core `DimensionScope` 与 V10 `ck_iam_grant_dimension_mode_compatibility` 同时执行该矩阵，其他组合全部拒绝。V10 先增加 `NOT VALID` CHECK 再验证既有行；发现历史非法组合时迁移失败，不静默收窄、扩大或转换授权。

### 3.4 Menu

- V9 在 tenant 内对 `lower(coalesce(route_name, menu_name))` 建唯一索引，route name 大小写不敏感；
- V9 对非空 `route_path` 建 partial unique index，BUTTON 等空 path 不参与；
- 创建索引前显式扫描历史重复，发现冲突直接拒绝迁移，不猜测删除或合并；
- 仓储在 tenant 锁事务中按相同语义预检，数据库唯一索引处理最终并发竞态；
- ACTIVE 菜单必须挂 ACTIVE 直接父节点；禁用或逻辑删除祖先前检查完整后代树，存在 ACTIVE 深层后代时拒绝。

### 3.5 Outbox

- `iam_permission_change_outbox` 是 append-only 事件事实，数据库 trigger 拒绝 UPDATE/DELETE；
- 事件包含 UUID eventId、aggregateVersion、schemaVersion、partitionKey 和 traceId；
- 新事件 INSERT 后由 trigger 在同一事务初始化 `iam_permission_change_relay_state=PENDING`；
- relay 只更新 state 表中的 lease、attempts、lastError 和 publishedAt；
- 当前只有 schema 与写入约束，没有 relay 进程，因此不能声称事件已经发布；
- Payment/Ledger 必须复用这个形态，不能复制 V1 中状态与事件混表的旧设计。

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
| role `(tenant_id,role_name) WHERE deleted_at IS NULL` unique | live 角色名称唯一，允许墓碑后重建同名角色 | 查询必须始终带 live predicate |
| menu `(tenant_id,lower(effective route name)) WHERE deleted_at IS NULL` unique | live Vben route name 稳定解析 | 改名增加唯一性检查，历史重复需先人工分类 |
| menu `(tenant_id,route_path) WHERE route_path IS NOT NULL AND deleted_at IS NULL` unique | live tenant 路由唯一定位 | 非空 path 写入增加唯一性检查 |
| audit `(tenant_id,occurred_at desc)` | 租户审计时间线 | 审计高写成本，需要分区门槛 |
| audit `(trace_id)` | 事故追踪 | 额外写索引 |
| outbox `(event_id)` unique | 消息幂等 | 每事件一次唯一性检查 |
| relay `(status,available_at,event_record_id)` partial | polling 批次领取 | mutable state 写放大，与事件事实隔离 |

不预先给 `iam_audit_event` 分区。达到真实行数、保留周期和查询证据后按月分区。

## 5. 数据类型

- 主键使用 `BIGINT`，由应用分布式 ID 生成；
- 时间使用 `TIMESTAMPTZ`，统一 UTC 存储；
- 状态和枚举使用可读 `VARCHAR` + CHECK，避免裸数字漂移；
- 审计前后值使用受控 `JSONB`，必须脱敏；
- OIDC 身份使用 `(idp_issuer, idp_subject)` 唯一，不能假设 `sub` 在不同 Issuer 间全局唯一；
- 权限表不存金额，因此没有资金精度字段；
- 不使用物理删除回收权限历史。Membership 删除以 `TERMINATED` 表达；Role、Menu、Department 使用 `deleted_at` 墓碑并保留状态、关系和审计。运行时所有管理列表、选择器和有效授权 join 都必须显式排除墓碑，不能只依赖 `status=DISABLED`。

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

升级既有库时，V9/V10 都采取拒绝式迁移：重复菜单路由和非法 dimension/mode 组合必须先由业务负责人分类并通过新的前向修复迁移处理，不能在约束迁移里自动选边或改权。V17 增加 Role/Menu/Department 墓碑和 local system-managed 标记，并把 live 角色/菜单唯一性改成 `WHERE deleted_at IS NULL` 的 partial index；迁移不删除或猜测归并历史业务行。

## 7. 回滚

- 新系统未承接流量前可以删除新库重建；
- 灰度后回滚只停止新会话和新授权写入，不把已产生的审计丢弃；
- 权限变更使用事件和版本，旧应用只在兼容窗口读取旧模型；
- 一旦 V17 后发生墓碑写入，旧二进制因不理解 `deleted_at` 不属于可写回滚路径；故障恢复只能停止新写、保留审计并前向修复或恢复整库快照；
- 不通过反向修改旧系统权限表实现回滚；
- 迁移脚本必须可重跑，并记录 source_id -> target_id 映射。

## 8. 数据库门禁

以下任一未满足，不得上线：

- 缺少跨租户自动化测试；
- `SPECIFIED` 空集合被解释为全量；
- FUND 权限允许通配绕过；
- 角色全量覆盖没有版本控制；
- 唯一索引前未检查迁移重复数据；
- dimension/mode 不在批准矩阵，或迁移试图自动猜测修复历史授权；
- 审计记录包含密码、Token、MFA Secret 或完整敏感数据；
- 数据范围查询可以丢失 tenant predicate；
- 没有迁移验证、回滚和权限差异报表。

## 9. 不确定项

当前迁移工具已定为 Flyway，已执行版本禁止改 checksum，只能追加前向迁移。

> 不确定：长期公开 ID 是否采用 UUIDv7、雪花或其他生成器；当前 IAM 仍使用经 V5 修复的共享 sequence。

用户多工作区在 Schema 和登录 API 中已开放：单一活动 Membership 可省略 tenantId，多个活动 Membership 必须显式选择 tenantId。
