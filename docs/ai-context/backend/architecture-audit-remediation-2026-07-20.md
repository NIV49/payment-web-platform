# 后端架构审计整改记录（2026-07-20）

> 输入：`backend-architecture-audit-2026-07-20.md`
>
> 结论：基础 IAM 原型可以继续演进；真实资金生产门禁仍不通过。
>
> 规则：本表只记录已落代码和测试的事实；“已设计”不等于“已实现”。

## 1. 技术基线纠偏

| 项目 | 审计前实现 | 已接受目标 | 当前实现 |
| --- | --- | --- | --- |
| Java | 17 | 25 LTS | 25，Compiler/Enforcer/CI 同步锁定 |
| Spring Boot | 3.x/4.x 决策摇摆 | 4.1 | 4.1.0 |
| 持久化 | MyBatis | jOOQ | jOOQ 3.21.5；MyBatis 运行时、Mapper 和配置删除 |
| PostgreSQL | 16 | 18 | 18.4，Compose/Testcontainers/CI 同 major 且 pin digest |
| Schema 模型 | 手写 Row/Map | Flyway -> jOOQ | PG18 执行完整迁移后生成并提交；CI 拒绝 drift |
| Redis 协议实现 | 浮动 Redis 7 | 明确产品边界 | Valkey 7.2.13，精确 digest；只承载会话、限流、缓存 |

截至该审计完成时，前端工程事实基线也已统一：`.node-version` 与产品构建镜像固定 Node.js 24.16.0，`engines.node` 只接受 `>=24.11.0 <25`；`packageManager`/`engines.pnpm` 固定 pnpm 11.7.0；当时工作区版本为 Vben 5.7.0，锁文件解析 Vue 3.5.38 和产品 UI `antdv-next` 1.3.6。产品 Docker 构建只包含 `web-antdv-next`，Playground 仍保留为本地参考但不进入生产镜像。当前前端版本事实以 `docs/ai-context/frontend/README.md` 为准。

## 2. 审计问题关闭矩阵

| 审计项 | 状态 | 已落地证据 | 剩余边界 |
| --- | --- | --- | --- |
| P0-1 共享序列被 V3 回拨 | 已关闭 | V5 前向修复同时比较 sequence 当前值与所有共享表最大 ID；V2→latest PG18 升级测试 | 长期每表 identity/UUIDv7 随新域设计 |
| P0-2 跨租户产品模型与硬拒绝冲突 | Core/存储边界已关闭，业务接入未完成 | `resource owner tenant` 与授权工作区分离；仅受控 `READ/VIEW` action 可配置 `RELATED_PARTY_READ`，并且还要有显式商户/客户范围和可信关系证据；Core 的 PermissionDefinition/PermissionGrant/授权服务三层拒绝非只读 action，V12 CHECK 阻断直接 SQL 与历史错配；FUND 永远同租户 | Party/Relationship adapter、历史关系快照和订单查询尚未实现，因此运行时继续 fail closed |
| P0-3 完整授权内核未接 HTTP | Admin 已关闭 | `AdminSecurityInterceptor -> AdminAuthorizationEnforcer -> DefaultAuthorizationService -> versioned GrantSnapshot`；未知 method/path 默认拒绝 | 支付详情/列表尚无真实 endpoint；不能把 Admin PEP 宣称为支付数据权限完成 |
| P0-4 角色分配可越权、最后管理员可被删 | 主体/版本并发路径已关闭，RoleGrant PUT 时间竞态已关闭 | system/non-assignable/disabled role 拒绝；禁止自提权和越过操作者委派上限；最后可登录的活动 system admin 保护只计算符合统一 BCrypt 格式/成本策略的凭证；`PUT user` 统一要求 update/disable/assign-role；可信 Session 生成包含 userId、membershipId、permissionVersion、sessionVersion 的 `AdministrationActor`，写事务锁定 tenant + actor tuple 后复核主体与两个版本；RoleGrant PUT 锁后用数据库时间重验两项权限 | 其他管理写接口仍有 finite `valid_until` 边界；双人 break-glass provisioning 尚未实现 |
| P0-5 支付实施规格/旧系统证据缺失 | 未关闭 | 目标架构只保留为约束和评审输入 | 状态机、账本、金额精度、API/事件/渠道契约、旧新映射、迁移/回滚演练未定版；禁止开始真实资金链 |
| P1 身份/租户语义混乱 | 原型边界已关闭 | UserCreate 与 MembershipUpdate 拆分；租户内 PUT 不修改全局 User/Credential；多 Membership 登录要求选 workspace；新建身份固定为 `PENDING_ACTIVATION`、Credential 固定为 `DISABLED` 且无 password hash，列表/API 独立返回 `identityStatus` | 外部 OIDC IdP、邀请/密码设置/激活/重置、MFA 和全局身份管理 API 未实现；当前没有可用的生产激活流程 |
| P1 Session 只查 Membership | 已关闭 | Sa-Token 安全属性由 Boot auto-configuration 在 ApplicationContext 创建期绑定；Session 查询精确匹配 tenant + membership + user，并检查 Tenant/User/Credential/Membership 四态、受 V13 约束的可验证 BCrypt hash 与 sessionVersion；Core 登录也在调用 verifier 前执行同一 `LoginCredentialPolicy`；授权加载另核 permissionVersion；主体/版本失效均返回 401 `SESSION_INVALID` | 生产 Session/Redis 故障演练未完成 |
| P1 登录限流可被轮换账号或并发绕过 | 已关闭当前本地边界 | Redis Lua 在密码校验前原子预留 client 全局桶与 client/username 桶；15 分钟窗口分别限制 30/5；两个 key 共用 client digest hash tag，在 Redis Cluster 中同槽执行；真实 Valkey 并发回归覆盖原子性、账号轮换与成功释放 | 分布式攻击、代理地址基数、可观测性和生产故障演练仍未完成 |
| P1 Grant 缓存越过时间边界/并发读 | 已关闭（保守策略） | Snapshot 保存最近 future `valid_from/valid_until`；Loader 与 Redis adapter 双层保证 temporal boundary 快照不被接受或写入缓存，每个请求都以单条 PostgreSQL 查询的 MVCC 视图和 `statement_timestamp()` 为判定点，彻底排除 DB/应用时钟偏差；无时间边界的 cache-hit 返回前再次读取 permissionVersion，版本变化时丢弃旧命中并有界重试一次；4096 明细硬上限 | cache-hit 的最终版本复核是读路径线性化点：此前已提交撤权必须生效，此后提交属于已进入处理的请求；Admin 写仍由事务锁后 actor/version 复核。分布式变更通知与 relay 未完成 |
| Required 管理写授权跨事务时间窗 | **RoleGrant PUT 已关闭；其他管理写仍阻断** | RoleGrant PUT 在 tenant/actor/目标/system role 锁后以 `statement_timestamp()` 重验 `role:view` 与 `role:grant-update`，双连接测试覆盖过期和缺权限且证明零写入 | User、Role、Menu、Department 仍可能在等待锁期间跨过 finite `valid_until`；过渡期这些写权限不得设置有限有效期，正式方案仍需同等事务内重验或可信事务授权凭据 |
| P1 客户端伪造审批人即可放行 | 已关闭为 fail-closed | `requiresApproval` 在没有可信 workflow evidence 时不授权，也不进入 DataScopePlan | 真正审批工作流、资源指纹、金额币种、过期/防重放未实现 |
| P1 固定生产 fixture | 已关闭默认路径 | V8 只删除精确预留 footprint；无关真实 IAM/审计/Outbox/扩展权限保留；碰撞、修改或 tenant 1 额外依赖时事务回滚；local profile 后置 bootstrap | 命中预留 footprint 冲突的历史库按 runbook 编写前向迁移 |
| P1 可变 Outbox 不兼容 Debezium | Schema 关闭，运行闭环未完成 | V7 append-only event facts + 独立 relay state，禁止 update/delete 事实行 | relay、Kafka/Inbox、告警、重放 Runbook 未实现 |
| P1 构建不可复现 | 基线已关闭 | Maven Wrapper、Enforcer、Java25 CI、PG18 jOOQ drift gate、精确容器 digest | SBOM、SCA/CVE、许可证自动门禁、应用运行镜像尚未补齐 |
| P1 生产配置默认放行 | 配置与 Schema 门禁已关闭 | 默认 DB/Redis/origin 必填、生产 Flyway 执行默认关闭、Secure Cookie 默认 true；Web 进程仍在接流量前只读校验当前二进制全部 versioned migration，pending/missing/failed/future/checksum/description/type 漂移全部拒绝启动，异常只暴露脱敏原因码；forwarded headers 默认 `NONE`；local 单独回环地址与开发值且不推断 baseline | 独立迁移 Job/CD 编排、expand/contract 与 N/N-1 双版本兼容门禁、Secret manager、反向代理和真实部署验证未完成；当前不承诺滚动回滚，仅可信边界代理剥离外部 Forwarded/X-Forwarded-* 后才可显式启用 |
| P1 部门并发/树约束 | 当前 Admin 路径已加固 | tenant lock；活动父部门/活动 Membership 不变量；递归子树依赖检查；防自身/后代移动；列表返回 rowVersion，PUT/DELETE 原子比较 expectedVersion | 更高并发规模需压测；冲突只支持拒绝并重载，不做自动合并 |
| P1 菜单路由与树完整性 | 已关闭当前 Admin 路径 | tenant 锁事务内预检；V9 对 tenant 内 canonical route name（trim 后空值回退 menu name，再小写）和 canonical route path（小写、去尾斜杠，根 `/` 例外）建唯一索引，历史重复直接拒绝迁移；ACTIVE 菜单要求 ACTIVE parent；禁用/删除有 ACTIVE 深层后代的祖先返回 409；列表返回 rowVersion，PUT/DELETE 原子比较 expectedVersion；真实 PG18 repository 测试覆盖同/跨租户与深层树 | 更高并发规模仍需压测；菜单 authCode 目录校验仍未完成 |
| P1 动态菜单越界展示与持久化 XSS | 已关闭当前菜单边界 | `/menu/all` 只返回 ACTIVE DIRECTORY/PAGE/EMBEDDED/LINK，排除 BUTTON；直接授权节点只有在其同租户 ACTIVE 祖先链完整时才会返回，所需祖先自动补齐；`iframeSrc` 仅 EMBEDDED、`link` 仅 LINK 可用，且都必须是带 host 的绝对 `http/https` URL，其他菜单类型持有这些字段直接拒绝 | 外部站点本身仍是不可信边界；上线前还需 CSP/嵌入白名单与浏览器安全评审 |
| P1 路由与输入边界 | 已关闭当前入口 | 所有 mutating `/api/**` 请求体上限 256 KiB，包括无 `Content-Length` 流；登录和 Admin DTO 字段有长度/格式约束；route/redirect 拒绝 `//` protocol-relative 值；meta 限制容器项、key/string 长度、深度和总值数；分页 offset 用 long 计算并在超过 int 上限时返回 400；roleIds ≤ 256、menuIds ≤ 2048；部门/菜单树每租户最多 2000 节点、最深 32 层，查询、组树和写入边界 fail closed | 后续新增批量入口必须复用同一上限策略 |
| P1 Grant 维度/模式可错配 | 已关闭模型与存储边界 | Core `DimensionScope` fail closed；V10 使用同一矩阵增加 DB CHECK，历史非法行在 validate 阶段阻断迁移而不自动改权；真实 PG18 测试证明非法组合触发 SQLSTATE 23514 且不落库 | Relationship/Party Provider 和真实支付查询仍未接入 |
| P1 审计与 trace | 部分完成 | 请求 trace 贯穿成功 IAM 写 audit；Outbox 有 trace 字段 | before/after、拒绝/登录失败、OpenTelemetry、MDC、指标告警尚未完成 |
| P1 前端 lifecycle 与 CI 门禁 | 已关闭当前工程边界 | 删除 lifecycle `preinstall` 中的 `npx only-allow`；生产安全测试禁止 lifecycle 脚本使用 `npx`/`pnpm dlx`；根 GitHub Actions 对前端变更执行 lint、产品 app typecheck、unit、production-safety 和 product build | 依赖安全/SBOM/SCA 门禁仍未完成 |

## 3. 当前生产结论

基础栈和 IAM Admin 原型不再存在“文档说 Java 25/jOOQ、代码却跑 Java 17/MyBatis”的双重事实；实现、CI、Compose、生成模型和开发文档已经统一。

但生产结论仍是 **NO-GO**。以下闭环缺一不可：

1. User、Role、Menu、Department 管理写授权 finite `valid_until` 的锁等待 TOCTOU（RoleGrant PUT 已关闭）；
2. RoleGrant 生产 cutover 的 N-1 清零、双版本验证与审批；
3. 审计 before/after、拒绝事件和登录失败事件；
4. 外部 IdP、MFA、邀请/激活/重置与可信审批工作流；
5. 商户/市场/渠道/客户/历史关系数据权限 Provider 及真实业务查询集成；
6. Outbox relay、投递/重放/告警和灾难恢复演练；
7. Payment/Ledger 状态机、金额精度、幂等、调账/对账、事件和迁移/回滚的可执行规格。

任何余额、账本、代付、提现、退款、冲正或调账写接口在上述门禁通过前都不得接入。

## 4. 回归证据要求

每次触碰本页所列能力，至少执行：

```bash
# JDK 25
cd backend
./mvnw -s maven-settings.xml clean verify

# Admin 前端
cd frontend/admin
pnpm -F @vben/web-antdv-next run typecheck
pnpm run lint
pnpm run test:unit
pnpm run test:production-safety
pnpm run build:antdv-next
```

数据库变更还必须在真实 PostgreSQL 18 上同时验证 fresh schema、受支持的升级路径和明确的拒绝/回滚路径。测试数量不是授权依据；必须检查断言是否覆盖租户隔离、状态失效、并发和历史数据兼容。

`clean verify` 会执行 `FlywaySchemaReadinessGuardIntegrationTest`：动态 latest、本地先迁移后校验、生产禁用 Flyway 时旧库拒绝，以及 checksum/failed/missing/future success/future failed 拒绝都在真实 PostgreSQL 18 上验证。每条拒绝路径断言稳定原因码，且异常不携带 JDBC URL、用户名、密码或 Flyway 原始错误。
