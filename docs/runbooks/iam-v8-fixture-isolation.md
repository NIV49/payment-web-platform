# IAM V8 fixture 隔离迁移手册

## 目的与边界

`V8__isolate_local_identity_fixture.sql` 只负责移除 V2/V3 错误进入生产迁移链的固定开发身份。它不是通用数据清理脚本，也不会把“库里存在真实数据”当成可删除依据。

V8 的安全不变量：

- 只删除 tenant `1`、user `100`、membership `1000`、role `2000`、固定 grant/dimension 和固定 menu/role-menu 组成的精确 fixture；
- 保留 14 条必需 Permission Catalog，同时允许并保留扩展权限；
- 保留无关租户、用户、审计事件、Outbox 事件及 relay state；
- 预留 ID/自然键碰撞、fixture 任一行被修改、tenant `1` 有额外依赖关系，或必需权限缺失/被修改时整笔回滚；
- 不提供 down migration。成功执行后的回退只能恢复备份，或发布新的前向修复迁移。

## 执行判定

| 数据状态 | V8 行为 | 操作决定 |
| --- | --- | --- |
| 全新库，只有迁移内固定 fixture | 删除 fixture，保留权限目录 | 可继续 |
| 固定 fixture + 其他租户/用户/审计/Outbox/扩展权限 | 只删除固定 fixture，无关数据保留 | 克隆库演练通过后继续 |
| 固定 fixture 已被完整人工移除 | no-op | 可继续 |
| 预留 ID 或自然键被真实主体占用 | 事务失败 | 编写专用前向迁移 |
| fixture 被登录、编辑、扩展关系或产生 tenant `1` 历史事件 | 事务失败 | 先分类数据归属，再编写专用前向迁移 |
| 14 条必需权限缺失或字段被修改 | 事务失败 | 先修复目录合同，再迁移 |

“失败”是保护机制，不是需要绕过的错误。

## 上线前

1. 安排维护窗口并停止所有写入 IAM、审计和 Outbox 的应用/任务。
2. 完成可验证的数据库备份，并实际验证备份能恢复到隔离环境。
3. 克隆生产库到隔离的 PostgreSQL 18 实例；不得拿生产库直接试跑。
4. 在克隆库执行 `flyway validate`，确认 V1–V7 checksum 未被修改。
5. 记录迁移前计数和关键业务样本：所有非 tenant `1` 主体、扩展权限、审计、Outbox、relay state，以及 sequence 当前值。
6. 在克隆库执行与发布物完全相同的 V8，再执行应用 `clean verify` 对应的迁移集成测试。

## 迁移后验收

至少验证：

- 预留 fixture 的 Tenant/User/Membership/Credential/Role/Grant/Dimension/Menu/RoleMenu 已全部消失；
- 14 条必需 Permission Catalog 仍满足完整字段合同，扩展权限数量和内容不变；
- 无关租户、用户、审计、Outbox、relay state 的计数和抽样内容不变；
- `iam_id_seq` 仍高于所有使用它的表的最大 ID；
- Flyway history 中 V8 只出现一次成功记录；
- 默认/生产 profile 不会创建 `admin`，只有 `local` profile 才运行本地 bootstrap。

验收通过后，才允许在同样停止写入、同样备份和同样发布物条件下执行生产迁移。

## V8 拒绝时

1. 保持应用写入停止，保存 PostgreSQL/Flyway 完整错误和迁移前快照。
2. 不要修改已发布的 V8，不要执行 `flyway repair` 把失败伪装成成功，也不要手工删除冲突数据后盲目重跑。
3. 按错误分类预留 footprint：真实主体碰撞、fixture 已被使用、额外 tenant `1` 关系、历史审计/Outbox、或 Permission Catalog 漂移。
4. 为该数据库状态设计新的、可审计的前向迁移；在生产克隆库覆盖成功、拒绝和恢复路径。
5. 只有评审通过的新迁移才能进入下一维护窗口。

Flyway/PostgreSQL 会回滚失败的事务型 V8；如果迁移已成功提交后才发现业务问题，使用已演练的备份恢复或新的前向迁移，不能伪造回滚历史。

## 本地 profile

本地 bootstrap 在 V8 完成后运行，并使用相同的预留 footprint：

- 首次创建 `admin` 后会写入配置密码的 BCrypt hash；
- 重启不会重置密码、成功登录时间或 credential row version；
- 可以保留不占用预留键、也不附着到 fixture 管理员/角色的额外本地用户、角色、grant 和菜单；
- 更换 `PAYMENT_BOOTSTRAP_PASSWORD`、预留键碰撞、固定关系缺失或被扩展时会拒绝启动，避免把权限拼接给错误主体。
