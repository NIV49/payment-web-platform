# Current Delivery Status

> 更新时间：2026-08-24
> 整理任务起点：`main` / `f12aa5b3c344008050ab24d9403a5fee11c488fd`
> 源码状态：Candidate / Production NO-GO
> 验证规则：PASS/FAIL 必须绑定当次精确 commit SHA，不在本页静态冒充当前 HEAD 证据

本文是新开发任务的第一交接入口。它只描述当前交付顺序和已经验证的事实；目标架构、接口细节和全局生产缺口仍分别以 ADR、API Contract 和 [当前偏差与待治理项](./known-deviations.md) 为准。

## 1. 版本边界

- 2026-08-12 整理任务开始时，本地和 `origin` 都只保留 `main`，其起点均为上方 SHA；Vben `upstream/*` 是第三方远端跟踪引用，不属于本项目开发分支。
- IAM-003、系统字典、三后台管理页面与 ADR-0012 已进入 Candidate 源码基线，不再是只存在于历史未提交工作树的实现。
- 它们仍不是 production closed：每次代码、规则、Judge、迁移或文档变化后，都必须对新的精确 commit 重跑正式 repository gate 并由作者之外的 reviewer 复审；旧 commit 的证据不得继承。

## 2. 当前已实现的本地 Candidate

- 三套独立前端应用和三套独立后端组合根；三账号域、Cookie、Session realm、OIDC client 与缓存命名空间继续隔离。
- PLATFORM、MERCHANT、AGENT 都提供当前 Session Tenant 内的 User/Role Management；普通 CRUD 不接受客户端 Tenant 或账号域切换。
- PLATFORM 提供跨域 User 目录及受限系统管理员治理；local/test profile 支持精确绑定的跨域本地密码恢复。
- 系统字典以 PostgreSQL 为事实源、Redis revision cache 为加速层；PLATFORM 管理字典，MERCHANT/AGENT 只通过批量读取接口消费，不展示字典菜单。
- PLATFORM Role/Menu 页面已实现 ADR-0012 Candidate：PLATFORM 目标保持同租户管理，MERCHANT/AGENT 目标使用独立只读目录；切换目标会清空旧数据并拒绝迟到或上下文不匹配的响应。
- `BELONG_SYSTEM` 只按固定 `1/2/3 -> PLATFORM/MERCHANT/AGENT` 映射提供 color/order；合法域和双语 label 仍由应用固定语义决定。
- MCH-001 已完成一次本地真实浏览器功能验收：MERCHANT 从“商户资料”提交后进入 `PENDING_REVIEW`，PLATFORM 从“商户列表”依次执行通过、禁用和启用，MERCHANT 与 PLATFORM 页面同步显示 `ACTIVE -> DISABLED -> ACTIVE`；页面时间按本地化格式展示，生命周期原因显示双语 i18n 文案，MERCHANT 商户资料页先前的加载死锁未再出现，AGENT 菜单中没有 Merchant 入口。
- MCH-002 Candidate implementation 已完成本地保留数据升级和真实页面验收：V33 数据卷前向升级到 V36 且 Merchant 数据保留；V34 前置检查拒绝重复审计版本，V36 建立审计版本唯一约束；PLATFORM 商户列表提供详情、编辑、市场/分类筛选、ACTIVE/DISABLED Switch 和其他状态 Tag，所有 Select 可清除；编辑 BRA 市场和三项业务分类后，禁用/启用及 MERCHANT 只读资料页同步正确，AGENT 直达 Merchant 路由被拒绝，当前验收流程控制台无新 error/warn。
- MCH-003 mutable Candidate 已实现方案 2：PLATFORM 选择已有 ACTIVE、未绑定的 MERCHANT Tenant 代建申请，不创建 IAM 主体。新增和编辑复用同一个 23 字段、5 个受保护图片字段的表单全页；详情与审核使用独立隐藏路由并共用完整只读资料组件，返回图标位于 Vben `Page` 标题内，审核页只在存在待审创建或资料修改时于标题右侧显示一个打开通过/驳回及受限原因弹窗的“审核”动作。列表用服务端 `reviewPending` 隐藏已完成且无后续待审工作的审核入口；前三项有效操作内联、第四项起进入点击溢出，市场列居中且只显示“巴西/菲律宾”等本地化名称；新写商户类型不再接受 `DIRECT`，历史 `DIRECT` 统一按平台商户展示并仅保留回执重放兼容。
- V40 以前向迁移把六个 Merchant BUTTON 标题从历史 `merchant.*` key 收敛为 `merchant.permission.*`；仅在成功版本 39 且 V40 pending 时激活的 callback 会锁住 IAM menu writer 并验证 V39 输入，V41 以同样锁验证 V40 精确结果。本地 bootstrap、双语语言包和菜单契约使用同一集合，发布必须先部署双 key 前端、再停止 menu writer 后执行 V40/V41，且 V40 后不能回滚到不认识新 key 的前端；V32/V34/V37/V38/V39/V40 均未回写。最终状态仍以本次提交后的完整门禁和独立复审为准。
- V42/V43 为 amendment 建立五项不可变证件引用及追加式守卫：编辑可逐项保留当前同类型证件或替换为本操作者临时上传，零替换和部分替换都合法，审核读取精确待审引用且批准只提升替换项。生产迁移必须在 V42 前停止旧 Merchant create/amend/review writer，并保持停写直到 V43 与兼容后端启用；旧二进制不是可写回滚目标。
- MCH-003 前端在 Node.js 24 下通过全量 128 个文件/975 个测试、五项 typecheck、31 项 production-safety 和三应用 `build:all`/三制品检查。`registrationCountry` 只允许选择已分配 ISO alpha-2 国家；编辑改变注册国家或法人证件类型时，页面分别强制注册号或法人证件号进入 `REPLACE`，恢复原上下文后才允许 `RETAIN`。本轮真实页面验证确认三项列表操作全部直显且无省略、市场单元格居中并只显示“巴西”、详情和审核资料一致、审核弹窗不提交即可选择通过/驳回及原因；页面内计时显示新增三段表单约 335ms 挂载完成，编辑 23 字段约 460ms 完成回填，操作窗口没有新增业务 error/warn。页面卸载会使迟到的附件预览和提交响应失效；提交期间会冻结 Tenant、三段表单、替换开关和五个上传控件，临时附件在服务端完成绑定前不会被删除或替换。本轮作者外独立复审在冻结后执行，旧 reviewer 计数不作为本轮结论。
- Unified backend `clean verify`: PASS. 95 XML reports / 678 tests / 0 failures / 0 errors. 其中 IAM 黑盒 10/10；三组合根及 blackbox 的 `verify -am` 也为 17/17 reactor `BUILD SUCCESS`。`LocalIdentityFixtureBootstrapIntegrationTest` 在 JDK 25 + PostgreSQL 18 下 61/61 通过；自动化证明 `local`/`iam002-local` 共用候选谱系，候选序号只接受精确 `2..999999`，连续消费 candidate1..9 后生成 candidate10、重复重启幂等且九个 Merchant 聚合不变，前导零、`1`、`1000000` 和非数字谱系均失败关闭且零修复。真实 HTTP 链路由 Merchant `10802` 消费 Tenant `4000`；同一持久卷重启后原 Merchant 摘要不变，eligible 唯一返回无 Department、Membership、Role 或 Merchant 的替代候选 Tenant `10805`（`local-merchant-candidate-2`），本地运行态保持 healthy。较早的 blackbox/Valkey 基础设施超时只作为失败尝试保留，不替代本次全绿结果。
- 作者之外的前端 review 发现的问题已在本版本修复：`registrationNumberMasked` 响应必须满足脱敏形状才可进入页面，PLATFORM 商户列表的并发查询通过 latest-query 队列保证旧响应不覆盖最新筛选结果；创建审核进入同一全页，注册国家拒绝未分配代码，受保护编号随其上下文变化强制替换；迟到响应和提交中附件交互的竞态由卸载失效与提交态冻结关闭。当前代次仍须在冻结后重新接受作者之外的独立复审，不能沿用旧代次的无 Critical/Required 结论；正式结论仍以绑定精确不可变 SHA 的签名独立复审为准。
- 该验收使用 `local`/`iam002-local` 密码模式。仅这两个本地 profile 的密码登录成功会把 Session `STEP_UP_AT` 设为当前 UTC 时间，供最近 10 分钟内的本地敏感操作验收；这是本地重认证夹具，不是 LoA 2。生产 OIDC 初始登录不写 `stepUpAt`，敏感操作仍必须完成独立 OIDC step-up。
- `docs/` 已作为 Tolaria active vault 使用，仓库 Markdown 是唯一事实源；产品生命周期与系统管理图文说明和 AI 工程上下文通过同一 commit 绑定。

## 3. Candidate 验证能力

本仓库已建立以下 Candidate 验证面，但是否 PASS 以精确目标 commit 的外部门禁记录为准：

- backend 使用 Java 25 执行完整 Maven `clean verify`，覆盖模块单测、PostgreSQL/Flyway 集成和三组合根黑盒边界；
- frontend/admin 使用固定 Node.js 24.16.0 执行全量 Vitest、五包 typecheck、`build:all` 与三制品隔离检查；
- repository gate 执行 IAM-003 decision checker、文档决策/同步检查、敏感制品扫描及其对抗回归；
- PLATFORM 真实浏览器回归必须覆盖 User、Role、Menu、Department、Dictionary 页面，以及 Role/Menu 的 MERCHANT/AGENT target 查询和只读动作收敛；
- 独立 Candidate review 必须在开始与结束都证明目标 SHA 和工作树未漂移。

代码或治理文件继续变化后，以上验证必须重跑，不能继承旧结论。

## 4. Candidate 收口条件

任何新产品能力进入实现前，必须对当时精确 Candidate commit 完成：

1. 文档、Rulebook、Judge、代码和迁移一致，且无临时产物、秘密或不属于本 Candidate 的修改；
2. IAM-003 decision checker、backend `clean verify`、frontend unit/build/production-safety、三制品检查和需要的真实浏览器回归通过；
3. 正式 modernization/sensitive/doc-code repository gate 对同一不可变 SHA 通过；
4. 作者之外的 reviewer 在同一 SHA 上证明 snapshot valid 且无 Critical/Required。

完成以上步骤前，任何文档都不得把当前能力写成已进入生产或正式 closed。

## 5. 当前 Merchant Candidate

MCH-002 is now a mutable Candidate implementation, not only an approved target. [ADR-0014](../adr/0014-platform-maintained-merchant-profile-and-operating-markets.md) governs PLATFORM profile maintenance, multiple ISO alpha-3 operating markets, the old-page business labels Merchant Type / Legal Person Name / Authentication Type (without business email), protected `merchant:update`, append-only V34/V35/V36 and the revised list/detail interaction. Source, focused tests, preserved-volume migration and real-browser evidence exist in the current worktree. It remains Production NO-GO until the worktree is frozen into an exact commit, all formal gates pass on that SHA, and an author-independent immutable review closes without Critical/Required findings.

MCH-003 is an accepted target under [ADR-0015](../adr/0015-platform-assisted-merchant-onboarding-and-reviewed-amendments.md) and Merchant Contract section 12, with a mutable Candidate implementation and integrated local evidence now present. The former `implementation pending` label is retained only as historical decision text and does not describe the current worktree. Frontend full gates, three backend composition roots plus blackbox, persisted-volume compatibility and the real-browser create/review/edit workflow are recorded above. MCH-001/MCH-002/MCH-003 checkers, documentation decision tests and `git diff --check` also passed. Unified backend `clean verify`: PASS. 95 XML reports / 678 tests / 0 failures / 0 errors. The Candidate remains Production NO-GO because the working tree is not yet an immutable reviewed candidate SHA and exact-SHA repository gates plus signed author-independent review remain outstanding.

MCH-001 商户主体与入驻生命周期是当前已实现基线。[ADR-0013](../adr/0013-separate-merchant-business-lifecycle-from-identity-tenancy.md)、[Merchant Lifecycle API Contract](../ai-contract/merchant-lifecycle-api-contract.md)、[商户管理产品说明](../product/merchant-management.md) 和 [Merchant 工程上下文](./merchant/README.md) 固定其历史边界；本版本完成 Candidate 代码与上述本地真实浏览器验收，正式结论仍由同一不可变 SHA 的门禁和作者之外签名复审决定。

MCH-001 只包含：

1. `Merchant` 业务主体，与 Identity `Tenant` 分离并建立永久一对一绑定；首次提交在同一事务创建 Merchant 并绑定可信 Session Tenant；
2. 无 DRAFT 的 `PENDING_REVIEW / REVIEW_REJECTED / ACTIVE / DISABLED / TERMINATED` 精确状态机；MERCHANT 提交/驳回后重提，PLATFORM 审核和治理；
3. PLATFORM 商户列表/详情/审核与 MERCHANT 自身档案/申请页面；
4. 服务端 `merchantCode`、受保护 `registrationNumber`、独立审计、乐观锁、幂等提交和严格的 PLATFORM/MERCHANT 服务端数据范围。

`MerchantMarket`/Market、Agent/AgentRelation、费率、限额、结算账户、资金账户、接入配置和 Payment 均不进入 MCH-001；它们必须分别通过后续 Capability Slice。

candidate `MCH-001` Rule Card、确定性 Judge、文档/代码 ownership 门禁和受质疑的 production-pre 计划已经进入版本历史。业务实现从工作树干净、通过精确 SHA repository gate 与作者之外复审的治理基线开始；任何 finding 修复都会形成新 SHA 并使旧证据作废。accepted 规格、治理基线、本地测试或 Candidate 实现都不代表整个项目达到 Production GO。

当前实现事实：后端新增独立 Merchant Core、PostgreSQL adapter、HTTP adapter 与 append-only V32/V33；V33 将注册号密钥轮换限定为独立数据库 capability Role 和离线运维连接，三套 Web 组合根不注册轮换服务。PLATFORM 注册列表、详情、审核和生命周期控制面，MERCHANT 注册当前 Session Tenant 的申请/重提，AGENT 不注册 Merchant HTTP；前端新增 PLATFORM 商户列表、独立详情/审核全页与 MERCHANT 商户资料页，AGENT 制品不含 Merchant 页面。详情和审核共用完整只读资料版式，审核页只增加一个弹窗式审核动作；新增和编辑继续共用完整表单全页。注册号只以服务端密文、keyed fingerprint 和脱敏值处理；权限证明、幂等、乐观锁、审计和状态机均由后端事务强制。本地浏览器验收覆盖提交、通过、禁用、启用、两端状态同步、时间格式、原因 i18n、商户资料页可用和 AGENT 菜单缺席；独立前端 review 的脱敏字段 fail-closed 校验与 latest-query 队列 finding 已修复。这些是 Candidate 功能证据，不替代精确 SHA 的正式 gate 或签名独立复审证据。

## 6. 暂缓的全局生产阻断项

[当前偏差与待治理项](./known-deviations.md) 仍记录 ADR-0012 专用不可委派权限、finite `valid_until` 写入 TOCTOU、RoleGrant cutover、结构化审计、Keycloak/SMTP/备份恢复、Outbox relay、关系数据权限以及 Payment/Ledger 可执行规格等独立 NO-GO 项。MCH-001 生产部署还必须先由 cluster-superuser 运行 capability Role bootstrap，再由 direct `NOSUPERUSER NOCREATEROLE` Flyway login 通过 preflight 并执行迁移；三个 Web runtime 必须是非超级、非对象所有者且无 rotation Role 访问，离线 ops 只能获得 direct `SET TRUE, INHERIT FALSE, ADMIN FALSE` membership 并使用独立非 Web data source。本地超级用户夹具不能作为生产证据。它们仍是生产上线阻断，但不再占用 MCH-001 的本地 Candidate 功能验收顺序。

## 7. 新任务必读顺序

1. 本文；
2. [文档首页](../README.md)、[AI 开发上下文入口](./README.md) 与仓库根目录 `AGENTS.md`；
3. 按改动范围读取 frontend/backend/permission 上下文；
4. 涉及身份、菜单、权限或字典时读取 Identity/System Dictionary Contract 与 ADR-0010/0011/0012；
5. 涉及 Merchant 时读取 Merchant 产品说明、ADR-0013、ADR-0014、ADR-0015、Merchant Lifecycle Contract 和 Merchant 工程上下文；
6. 改迁移、Rulebook、Judge 或执行正式门禁前读取 [Judge Charter](../judge-charter.md)。
