# RuoYi-Vue 权限参考分析

## 0. 定位

源码版本：`RuoYi-Vue 3.9.2`，Git 提交 `41720e624c5a668c7d3777835e4c87095a7a1dfd`。

核心价值是业务链路完整；不把它视为目标技术架构。

## 1. 用户登录与 Token 生命周期

1. **入口文件**：`SysLoginController.login`、`SysLoginService.login`、`JwtAuthenticationTokenFilter`。
2. **调用链**：`POST /login -> AuthenticationManager -> UserDetailsServiceImpl -> SysUserService -> SysPermissionService -> TokenService.createToken -> Redis`；请求时 `JWT -> uuid -> login_tokens:{uuid} -> LoginUser -> SecurityContext`。
3. **核心表结构**：`sys_user`、`sys_user_role`、`sys_role`、`sys_role_menu`、`sys_menu`；登录会话不落业务表。
4. **核心类职责**：`SysLoginService` 校验验证码和密码；`TokenService` 生成 JWT、保存会话和滑动续期；`LoginUser` 保存用户、角色和权限快照。
5. **缓存设计**：JWT 只携带 UUID 和用户名，完整 `LoginUser` 存 `login_tokens:{uuid}`；不足 20 分钟时续期。
6. **扩展点**：Spring `UserDetailsService`、`AuthenticationManager`、登录前置校验和异步登录日志。
7. **优点**：会话可服务端撤销；Token 不携带完整权限；链路简单、容易跟踪。
8. **缺点**：自管 JWT 密钥和生命周期；登录权限是快照；Redis 故障直接影响认证；缺少 MFA/工作空间模型。
9. **是否适合当前项目**：业务链路可参考；认证实现不采用，目标使用 Sa-Token 会话适配，并预留 IdP/MFA。

## 2. 用户、角色、菜单关系

1. **入口文件**：`SysUserController`、`SysRoleController`、`SysMenuController`。
2. **调用链**：用户绑定 `sys_user_role`；角色绑定 `sys_role_menu`；菜单 `perms` 经 `SysMenuMapper` 加载为权限集合。
3. **核心表结构**：`sys_user`、`sys_role`、`sys_menu`、`sys_user_role`、`sys_role_menu`、`sys_role_dept`、`sys_dept`。
4. **核心类职责**：`SysUserServiceImpl` 管用户关系；`SysRoleServiceImpl` 在事务中替换角色菜单/部门；`SysMenuServiceImpl` 构建路由和权限。
5. **缓存设计**：用户、角色、权限集合进入在线 `LoginUser`；菜单路由每次从数据库构建。
6. **扩展点**：角色菜单和角色部门关系表；菜单类型 M/C/F 区分目录、页面和按钮。
7. **优点**：链路直观；关联表明确；事务替换关系易理解。
8. **缺点**：菜单同时承担 UI、API 权限目录，耦合过重；无 TenantMembership；用户直接绑定部门和角色。
9. **是否适合当前项目**：保留关系链，但拆分全局 User、TenantMembership、RoleGrant、Permission 和 MenuPresentation。

## 3. 权限码生成与加载

1. **入口文件**：`SysPermissionService.getMenuPermission`、`SysMenuMapper.selectMenuPermsByUserId/RoleId`。
2. **调用链**：`user -> roles -> role_menu -> sys_menu.perms -> Set<String>`；超级管理员返回 `*:*:*`。
3. **核心表结构**：权限码存于 `sys_menu.perms varchar(100)`。
4. **核心类职责**：`SysPermissionService` 按启用角色加载权限，并把每个角色权限写回 `SysRole.permissions`。
5. **缓存设计**：最终集合缓存于 `LoginUser.permissions`。
6. **扩展点**：字符串格式通常为 `module:resource:action`；Spring EL 统一调用 `@ss`。
7. **优点**：权限码从数据库集中加载；角色仍保留自己的权限集合，为数据权限按动作过滤提供证据。
8. **缺点**：权限元数据依附菜单；无风险级别、step-up、审批和适用租户类型。
9. **是否适合当前项目**：保留稳定权限码；权限目录必须独立建模，菜单只引用权限码。

## 4. API 与按钮权限校验

1. **入口文件**：后端 `@PreAuthorize("@ss.hasPermi(...)")`；前端 `v-hasPermi`、`v-hasRole`。
2. **调用链**：后端注解 -> `PermissionService.hasPermi` -> `LoginUser.permissions`；前端指令 -> Vuex permissions -> 移除 DOM。
3. **核心表结构**：按钮也是 `sys_menu.menu_type='F'`，权限码位于 `perms`。
4. **核心类职责**：`PermissionService` 处理单个/任一权限与角色；前端指令只控制展示。
5. **缓存设计**：前后端都使用登录返回的权限快照。
6. **扩展点**：Spring Method Security；路由元数据 `permissions` / `roles`。
7. **优点**：后端鉴权没有依赖前端隐藏按钮；权限表达一致。
8. **缺点**：字符串易拼错；注解遗漏会导致仅认证即可访问；缺少统一权限目录编译校验。
9. **是否适合当前项目**：采用注解/Guard，但默认拒绝；前端只消费结果，资金操作还需资源授权和 step-up。

## 5. 数据权限 SQL 处理

1. **入口文件**：`@DataScope`、`DataScopeAspect`、各 Service 查询方法、Mapper XML `${params.dataScope}`。
2. **调用链**：`@PreAuthorize` 把当前权限码写入 `PermissionContextHolder` -> `@DataScope` 按含该权限的角色计算范围 -> 写入 `BaseEntity.params` -> XML 拼接 SQL。
3. **核心表结构**：`sys_role.data_scope`、`sys_role_dept`、`sys_dept.ancestors`。
4. **核心类职责**：`DataScopeAspect` 支持全部、自定义部门、本部门、本部门及下级、本人。
5. **缓存设计**：使用 `LoginUser.user.roles` 内的角色和权限快照；自定义部门实时查表。
6. **扩展点**：`userAlias/deptAlias/userField/deptField/permission`。
7. **优点**：当前动作只使用真正拥有该权限的角色范围，避免无关角色借出全量范围；空匹配默认查询不到数据。
8. **缺点**：`${}` 原始 SQL 拼接；只支持用户/部门；依赖第一个参数是 `BaseEntity`；别名和字段配置脆弱。
9. **是否适合当前项目**：保留“按完整角色授权计算”的思想；拒绝复制字符串 SQL，改为结构化 Grant Predicate。

## 6. 部门树权限

1. **入口文件**：`SysDeptServiceImpl`、`DataScopeAspect`。
2. **调用链**：部门写入 `ancestors` -> `DEPT_AND_CHILD` 使用 `find_in_set(userDeptId, ancestors)`。
3. **核心表结构**：`sys_dept(parent_id, ancestors)`、`sys_role_dept`。
4. **核心类职责**：部门 Service 维护树；Aspect 生成部门谓词。
5. **缓存设计**：未发现专用部门树权限缓存。
6. **扩展点**：自定义部门范围和 include children。
7. **优点**：实现成本低，业务人员容易理解。
8. **缺点**：逗号路径和 `find_in_set` 对 PostgreSQL 不适用、索引利用差；部门不应承载商户/市场/渠道语义。
9. **是否适合当前项目**：部门维度保留，但 PostgreSQL 使用显式 ancestry/closure 查询；业务范围单独建模。

## 7. Redis 中存储的权限相关数据

1. **入口文件**：`CacheConstants`、`RedisCache`、`TokenService`、`SysPasswordService`。
2. **调用链**：登录/验证码/密码错误/限流等组件按前缀读写 Redis。
3. **核心表结构**：无 Redis 对应业务表。
4. **核心类职责**：`login_tokens:` 保存完整登录上下文；`captcha_codes:` 保存验证码；`pwd_err_cnt:` 保存错误次数。
5. **缓存设计**：TTL + 滑动续期；配置和字典也有独立前缀。
6. **扩展点**：`RedisCache` 封装对象、集合和 pattern keys。
7. **优点**：Key 分类清晰；服务端可以强退。
8. **缺点**：权限刷新通过扫描 `login_tokens:*`，用户规模大时不可接受；Key 版本未隔离租户和权限版本。
9. **是否适合当前项目**：采用 Redis，但使用 `tenant + membership + permissionVersion` 定向失效，禁止全库扫描。

## 8. 权限变更后的缓存失效

1. **入口文件**：`SysRoleController.edit`、`TokenService.refreshPermissionByRoleId`、`SysLoginController.getInfo`。
2. **调用链**：角色菜单变更 -> 扫描在线 Token -> 找到含角色的 LoginUser -> 重载权限；`getInfo` 也会比较后刷新。
3. **核心表结构**：`sys_user_role`、`sys_role_menu`。
4. **核心类职责**：Controller 显式触发刷新；TokenService 更新 Redis 会话。
5. **缓存设计**：修改角色功能权限时刷新；当前源码的数据范围/状态/用户角色变更路径没有同样完整的一致失效证据。
6. **扩展点**：可以抽成领域事件 `PermissionChanged`。
7. **优点**：意识到在线权限快照必须更新。
8. **缺点**：pattern 扫描；刷新逻辑散落 Controller；事务提交前后时序不明确；遗漏路径容易形成旧权限。
9. **是否适合当前项目**：改为事务提交后发布权限版本事件；资金权限同时撤销/拒绝旧版本会话。

## 9. 超级管理员绕过

1. **入口文件**：`SecurityUtils.isAdmin`、`SysUser.isAdmin`、`SysRole.isAdmin`、`SysPermissionService`。
2. **调用链**：`userId == 1` -> `*:*:*` 和 `admin` -> DataScopeAspect 不过滤。
3. **核心表结构**：初始化 `sys_user.id=1`、`sys_role.id=1`。
4. **核心类职责**：工具方法和实体方法共同实现固定 ID 绕过。
5. **缓存设计**：超级管理员权限也进入 LoginUser，但数据过滤直接绕过。
6. **扩展点**：无显式风险等级或操作限制。
7. **优点**：运维简单、问题排查方便。
8. **缺点**：固定数据库 ID 是隐式后门语义；一旦账号被盗可操作全部数据；无法满足资金职责分离。
9. **是否适合当前项目**：不采用。平台超管仅可管理 IAM；资金动作必须显式授权、step-up、审批和审计。

## 10. 总结

RuoYi-Vue 最值得提取的是完整链路和“按当前权限过滤角色数据范围”。最不应复制的是固定 ID 超管与 `${params.dataScope}`。
