# ContiNew Admin 权限参考分析

## 0. 定位

POM 版本为 `4.2.0-SNAPSHOT`，父 Starter `2.15.0`。本地目录没有独立 `.git`，无法确认提交哈希。

核心价值是结构、扩展点、上下文、缓存和 DTO/DO 分层。

## 1. 用户登录与 Token 生命周期

1. **入口文件**：`AuthController`、`AuthServiceImpl`、`LoginHandlerFactory`、`AbstractLoginHandler`、`SaTokenConfiguration`。
2. **调用链**：`POST /auth/login -> client 校验 -> LoginHandler -> AccountLoginHandler -> UserService -> RoleService -> UserContext -> StpUtil.login`；请求经 `SaExtensionInterceptor` 恢复上下文并校验租户。
3. **核心表结构**：`sys_user`、`sys_client`、`sys_user_role`、`sys_role`、`sys_role_menu`、`sys_menu`。
4. **核心类职责**：Handler 策略支持账号/邮箱/手机号/社交登录；Sa-Token 管会话；UserContext 保存角色、权限、租户和密码过期信息。
5. **缓存设计**：UserContext 存 SaSession；Token extra 保存 IP、浏览器、时间；验证码和密码错误计数存 Redis。
6. **扩展点**：LoginHandler、客户端策略、并发登录、超时、替人和最大登录数。
7. **优点**：登录策略分离；会话参数按客户端配置；上下文职责清晰。
8. **缺点**：权限仍是登录快照；异步加载增加调试复杂度；MFA 和激活流程未覆盖目标需求。
9. **是否适合当前项目**：采用 Sa-Token 和 Handler 模式，但凭证/MFA 可继续交给成熟 IdP；Sa-Token 不作为业务权限真相源。

## 2. 用户、角色、菜单关系

1. **入口文件**：`UserController`、`RoleController`、`MenuController`。
2. **调用链**：用户全量替换角色 -> `UserRoleService`；角色全量替换菜单 -> `RoleMenuService`；MenuMapper 加载权限码。
3. **核心表结构**：`sys_user`、`sys_role`、`sys_menu`、`sys_user_role`、`sys_role_menu`、`sys_role_dept`。
4. **核心类职责**：ServiceImpl 编排业务，Mapper 负责访问，Req/Resp/DO 分离。
5. **缓存设计**：角色菜单用 JetCache；用户昵称等读模型使用双级缓存；在线 UserContext 定向更新。
6. **扩展点**：BaseController/BaseService、CRUD 注解、RoleMenuApi、租户插件。
7. **优点**：分层清晰；系统内置角色和用户有保护；关系替换先检查差异。
8. **缺点**：User 仍直接持有 deptId；租户插件通过给表增加 tenant_id 实现，不等于支付主体关系模型。
9. **是否适合当前项目**：代码结构可采用；领域上改成 User + TenantMembership，业务主体与租户分开。

## 3. 权限码生成与加载

1. **入口文件**：`RoleServiceImpl.listPermissionByUserId`、`MenuMapper.selectPermissionByUserId`、`SaTokenPermissionImpl`。
2. **调用链**：`user_role -> role_menu -> menu.permission -> UserContext.permissions -> StpInterface`。
3. **核心表结构**：权限码保存在 `sys_menu.permission`；角色菜单为复合主键。
4. **核心类职责**：RoleService 判断超级管理员；SaTokenPermissionImpl 向框架提供权限和角色集合。
5. **缓存设计**：权限集合存在 SaSession UserContext；角色菜单查询有 JetCache。
6. **扩展点**：CRUD API 权限前缀扫描；`@SaCheckPermission`。
7. **优点**：Sa-Token 只消费业务加载结果；接口层注解直观；权限异常统一处理。
8. **缺点**：权限仍绑定菜单；UserContext 只保留角色级 dataScope 和全量权限集合，缺少“哪个角色授予哪个动作”的直接映射。
9. **是否适合当前项目**：采用 StpInterface 适配；目标 UserContext 只放身份、版本和低敏摘要，完整 Grant 由 Loader 加载。

## 4. API 与按钮权限校验

1. **入口文件**：各 Controller 的 `@SaCheckPermission`，例如 `RoleController.updatePermission`。
2. **调用链**：注解 -> Sa-Token -> `SaTokenPermissionImpl.getPermissionList` -> UserContext permissions。
3. **核心表结构**：按钮为 `sys_menu.type=3`，权限存 `permission`。
4. **核心类职责**：Sa-Token 处理注解；GlobalSaTokenExceptionHandler 统一权限异常。
5. **缓存设计**：鉴权读取 SaSession 上下文。
6. **扩展点**：CRUD Controller 自动权限前缀、NextDoc 权限展示。
7. **优点**：样板代码少；异常和文档整合良好。
8. **缺点**：通用 CRUD 自动化可能隐藏高风险接口的差异；按钮权限仍不能代替资源实例授权。
9. **是否适合当前项目**：普通管理接口可用；资金和跨租户资源必须调用显式 AuthorizationService，不只检查字符串。

## 5. 数据权限 SQL 处理

1. **入口文件**：`DataPermissionMapper`、`@DataPermission`、`DefaultDataPermissionUserDataProvider`、`MybatisPlusConfiguration`。
2. **调用链**：Mapper 方法注解 -> ContiNew Starter 数据权限插件 -> UserDataProvider 提供 user/dept/roles -> 插件改写 SQL。
3. **核心表结构**：`sys_role.data_scope`、`sys_role_dept`、`sys_dept.ancestors`。
4. **核心类职责**：Provider 只提供上下文；具体 SQL 解析器属于外部 Starter，当前源码不可见。
5. **缓存设计**：MyBatis JSqlParser 使用 5 秒、1024 条本地解析缓存；角色范围来自 UserContext。
6. **扩展点**：Mapper 基类、表别名、DataPermissionUserDataProvider。
7. **优点**：SQL 改写与业务 Service 解耦；默认覆盖 list/deleteById；扩展接口清晰。
8. **缺点**：外部插件黑盒；Provider 把所有角色数据范围一起交出，当前源码看不到它是否按具体权限码隔离角色；删除按 ID 自动过滤的可验证性依赖 Starter。
9. **是否适合当前项目**：参考 Provider/Interceptor 分层，不直接依赖黑盒；资金查询采用显式资源类型和结构化谓词。

> 不确定：外部 `continew-starter-extension-datapermission` 如何合并多角色、如何防止跨角色动作/范围拼接；本地没有 Starter 源码。

## 6. 部门树权限

1. **入口文件**：`DeptServiceImpl`、`RoleDeptServiceImpl`、`DataScopeEnum`。
2. **调用链**：角色保存 `dataScope + deptIds` -> UserContext RoleContext -> DataPermission Provider。
3. **核心表结构**：`sys_dept(parent_id, ancestors)`、`sys_role_dept`。
4. **核心类职责**：部门 Service 维护路径；角色 Service 在范围变化时更新在线上下文。
5. **缓存设计**：部门范围本身未见独立缓存；上下文在 SaSession。
6. **扩展点**：ALL、DEPT_AND_CHILD、DEPT、SELF、CUSTOM。
7. **优点**：枚举集中；PostgreSQL DDL 提供更长 ancestors 字段和索引基础。
8. **缺点**：仍是部门中心模型，无法表达代理关系快照、商户、市场和渠道的组合。
9. **是否适合当前项目**：部门作为一个维度保留，不能泛化成所有业务数据范围。

## 7. Redis / JetCache 中存储的权限相关数据

1. **入口文件**：`CacheConstants`、`UserContextHolder`、`MenuServiceImpl`、`AccountLoginHandler`。
2. **调用链**：Sa-Token Session 保存 UserContext；JetCache 保存 `ROLE_MENU:{roleId}`；Redis 保存验证码和密码错误次数。
3. **核心表结构**：无一一对应缓存表。
4. **核心类职责**：UserContextHolder 管 ThreadLocal + SaSession；JetCache 管查询缓存；RedisUtils 管短期安全状态。
5. **缓存设计**：角色菜单、用户昵称、字典、配置分别命名；部分缓存为本地 + Redis 双级。
6. **扩展点**：CacheInvalidate/CacheUpdate/CacheRefresh、Redis pattern 删除。
7. **优点**：按用途分区；上下文定向更新；缓存注解一致。
8. **缺点**：双级缓存增加失效复杂度；pattern 删除仍可能放大；未看到权限版本作为所有授权缓存的统一边界。
9. **是否适合当前项目**：第一期只用 Redis；权限缓存使用版本 Key，不上本地二级缓存，先保证撤权确定性。

## 8. 权限修改后的缓存失效

1. **入口文件**：`RoleServiceImpl.updatePermission/update/updateUserContext`、`UserServiceImpl.update/updateRole/updateContext`。
2. **调用链**：关系变化 -> 定位受影响 userId -> 读取在线 SaSession -> 重载 roles/permissions -> 更新 UserContext；角色菜单缓存定向失效。
3. **核心表结构**：`sys_user_role`、`sys_role_menu`、`sys_role_dept`。
4. **核心类职责**：RoleService 和 UserService 负责业务变更后的在线上下文同步。
5. **缓存设计**：`ROLE_MENU:{roleId}` 定向删除；菜单本身变化时 pattern 删除所有角色菜单缓存。
6. **扩展点**：可以替换成事务后事件 + permissionVersion。
7. **优点**：比扫描全部 Token 更准确；禁用用户直接踢下线。
8. **缺点**：更新多个用户是同步循环；关系范围变更不在 IAM 内时容易漏失效；跨实例事件一致性依赖 Sa-Token/Redis 实现。
9. **是否适合当前项目**：采用“受影响主体定向刷新”，升级为 DB 权限版本 + Outbox 事件；资金权限服务端每次校验版本。

## 9. 超级管理员绕过

1. **入口文件**：`UserContext.isSuperAdmin`、`DefaultDataPermissionUserDataProvider.isFilter`、`RoleServiceImpl.listPermissionByUserId`。
2. **调用链**：角色编码 `super_admin` -> 返回 `*:*:*` -> 数据权限插件不再过滤；路由按角色 ID 1 返回全部菜单。
3. **核心表结构**：`sys_role.code`、`is_system`；常量 `SUPER_ADMIN_ROLE_ID=1`。
4. **核心类职责**：系统角色保护防止修改/分配；上下文判断是否绕过。
5. **缓存设计**：超管角色和权限进入 SaSession。
6. **扩展点**：RoleCodeEnum 区分超级管理员和租户管理员。
7. **优点**：内置角色不可被普通接口修改；租户管理员与平台超管有区分。
8. **缺点**：两类管理员都可能绕过数据过滤；不满足资金操作职责分离。
9. **是否适合当前项目**：仅保留 IAM 管理能力；不得通过角色名/ID获得资金通配权限。

## 10. 总结

ContiNew 最值得提取的是 LoginHandler、上下文、DTO/DO、缓存失效和扩展接口。数据权限插件必须重新证明多角色和支付维度语义后才能采用。
