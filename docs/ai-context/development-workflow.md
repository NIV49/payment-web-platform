# 开发工作流与完成门禁

## 1. 开发前

1. 用 `git status --short` 确认工作区已有修改，避免覆盖他人或上一次任务的内容。
2. 判断改动属于前端、后端、接口契约、数据库、权限或跨端中的哪一类。
3. 按 [上下文入口](./README.md) 阅读对应文档。
4. 在源码中找到至少一个当前版本的同类实现；Vben 功能优先查看 `playground` 与 `packages` 的实现链，而不是只看页面文件。
5. 涉及框架行为时阅读对应版本的官方文档，并以当前源码核验。
6. 在编码前写清输入、输出、权限码、错误语义、数据所有者和测试边界。

## 2. 实现中

### 前端

- 业务页面放在 `frontend/admin/apps/web-antdv-next/src/views`，应用专属 API、路由、语言包和适配器留在应用内。
- 只有两个以上应用确实复用且接口稳定时，才考虑修改 `packages`；不能为了少写几行就污染框架公共包。
- 页面优先复用 Vben 的 `Page`、`VbenForm`、`VbenVxeTable`、`VbenModal`、`VbenDrawer` 等深层组件，底层控件使用 `antdv-next`，并通过应用适配器注册。
- 先确认语言包里是否已有 key。菜单和路由标题使用 i18n key，不写展示文案。
- 后端动态路由的 `component` 必须满足 `views` glob 映射规则；不能用 Java 类名、Vue 组件名或随意路径。
- 新增菜单路由页时同步维护前端 `MENU_PAGE_COMPONENTS`、后端 `payment.menu.allowed-page-components`、双语 key 和契约测试。
- 中英文业务语言包必须保持相同 key 集合；相关 locale contract test 不能用 fallback 掩盖缺失 key。
- 按钮隐藏只是交互反馈，后端仍必须用同一个稳定权限码授权。

### 后端

- `applications` 只放可启动、可部署的组合根；业务规则进入 `modules/<bounded-context>/core`。
- Core 只依赖领域模型和端口；PostgreSQL、Redis、Sa-Token、Spring MVC 留在适配器或启动层。
- 租户、membership 和操作人来自可信会话，不能信任请求传入的租户上下文。
- 权限默认拒绝；高风险资金动作还必须经过数据范围、MFA/step-up、审批和审计规则。
- 授权工作区 tenant 与业务资源归属 tenant 必须分开命名；跨归属租户只读要求受控 READ/VIEW action、RELATED_PARTY_READ 元数据、显式商户/客户范围和可信关系证据，缺一即拒绝。
- Flyway 文件一旦应用就不可编辑；修正数据或结构必须新增版本迁移。
- Long ID 对前端返回字符串；分页保持 `{ items, total }`；统一响应保持 `{ code, data, error, message, traceId }`。

### 前后端契约

- 请求或响应字段变化先改契约，再同时修改消费者和提供者。
- `/menu/all` 是运行时路由 DTO，`/system/menu/list` 是管理 DTO；字段相似不代表语义相同。
- `meta.title` 是翻译 key；`menu_name` 是管理侧名称。两者不能混为一个展示字段。
- `component` 是受前端视图清单约束的跨端协议，写入数据库前必须验证。
- 菜单类型决定 route 形状：embedded/link 固定 `IFrameView` 且仍需要内部 path，不能照搬 Playground 表单中隐藏 link path 的缺口。
- 未登记的 `/api/**` method/path 必须默认拒绝；新增 Controller endpoint 时同步更新 `AdminApiPermissionPolicy` 和权限测试。
- 已知资源不存在和 Spring 静态资源未命中都返回 404 envelope；不能让 `NoResourceFoundException` 落入 500 兜底。

## 3. 最低验证矩阵

在改动目录执行对应命令；不能把“启动成功”当作完整验证。

| 改动 | 最低验证 |
| --- | --- |
| Admin 前端业务代码 | `pnpm -F @vben/web-antdv-next run typecheck`，相关 Vitest，必要时 `pnpm build:antdv-next` |
| Admin 生产配置或部署脚本 | `pnpm run test:production-safety`，`pnpm build:antdv-next` |
| Vben 公共包 | 受影响包 typecheck、相关单测、Admin 构建 |
| Playground | `pnpm -F @vben/playground run typecheck`，必要时 Playwright |
| 后端 Core | `./mvnw -s maven-settings.xml -pl modules/identity/core -am test` |
| 后端适配器/API | `./mvnw -s maven-settings.xml clean verify`，必要时 Testcontainers 集成测试 |
| Flyway | 新库迁移、已有本地库升级、回滚/兼容性检查 |
| 跨端契约 | 前端契约测试、后端集成测试、真实浏览器联调 |
| 文档/规则 | `git diff --check`，检查相对链接与源码路径，运行 `python3 -I scripts/check_sensitive_artifacts.py --repository-root <repo> --base-commit <trusted-base-SHA> --commit <target-SHA>` 扫描不可变 diff 中全部新增/修改文本；涉及权限决策时运行 `python3 -I scripts/check-doc-decisions.py` |
| 项目级 Agent skill | `python3 -I -m unittest discover -s scripts/tests -p 'test_*.py'`、`python3 -I scripts/check_project_skills.py`，并运行其关联的文档/规则门禁 |
| Payment modernization 产物 | `python3 -I scripts/check_modernization_artifacts.py --repository-root <target-repository> --commit <full-target-SHA> --trusted-policy-commit <protected-base-SHA>`；CI 权威检查必须读取 Git 对象，canonical root 只允许 `README.md` 与 closed JSON bundle，每个 closed bundle 必须带两份独立签名 PASS；positional draft 预检不能替代 |

文档治理脚本的 Python 依赖以版本和本地 SHA-256 完整固定在 `scripts/requirements-documentation.txt`，CI 使用 `--require-hashes --no-deps --only-binary=:all:` 安装。所有门禁和测试以 `python3 -I` 隔离执行，policy 的 `judgePaths` 封闭登记全部 `scripts/**/*.py`，避免同目录伪造标准库模块。不要依赖机器全局恰好存在的 YAML/Markdown 解析库。

权限跨文档决策使用 `<!-- decision-status id=<ID> status=<pending|accepted|superseded> ref=<none|repo-relative-path> -->` 标记。属性必须且只能各出现一次。`pending` 必须使用 `ref=none`；`accepted` 或 `superseded` 必须引用 `docs/adr/NNNN-slug.md`，该 ADR 必须且只能声明一次 `Status: accepted.` 和匹配的 `Decision-ID: <ID>`。修改任一决策状态时，必须同步全部登记文档并运行一致性检查。

该轻量治理 CI 对所有 PR 和 `main` 推送运行。它固定 `linux/amd64` 执行平台、Python 3.13.14 容器的单平台 manifest 摘要、Action 完整提交和依赖 wheel 哈希，并在运行 PR 可修改的测试前先执行四道门禁；测试后校验 `HEAD`、tracked worktree 与 untracked 文件均未漂移，再从禁用 replace objects 的提交 `git archive` 重建隔离快照。快照脚本以 `-I` 重跑，敏感扫描器从可信 base 到目标 SHA 的 Git 对象 diff 定位所有新增/修改路径，并扫描每个目标 blob 的完整内容，不信任可被 `.gitattributes` 改写的 patch 行；modernization checker 必须显式接收外部 `--trusted-policy-commit`，不会回退到父提交。非 UTF-8、NUL/control、二进制、危险 Git mode 或超限变更默认拒绝。

当前 trusted reviewer registry 有意保持为空，所以任何 `approved` Rule Card 都会 fail closed，直到独立的人工作业完成 reviewer key bootstrap。含 `sourceSnapshots` 的历史证据还要求运行环境能只读访问项目登记的 legacy workspace；GitHub 托管 Ubuntu runner 不具备该本地路径时会阻断，不能跳过验证，需改用受控的 self-hosted evidence runner 或后续获批的不可变证据 attestation 方案。

仓库内脚本不能成为自己的最终信任根。必须按 [`docs/governance/codeowners-bootstrap.md`](../governance/codeowners-bootstrap.md) 先在目标分支落地 CODEOWNERS，再由仓库管理员启用并通过平台 API 核验 ruleset。首次 CODEOWNERS 提交不能自我保护；仓库测试只验证 policy、Judge、全部 workflow 和治理路径的覆盖关系，不证明平台配置已生效。

## 4. 完成定义

- 实现符合当前源码的架构边界，而不是只让页面暂时可用。
- 没有新增硬编码菜单标题、重复 DTO、绕过权限或未验证的 component 路径。
- 相关测试已运行并记录结果；不能运行的测试说明原因和风险。
- 新增约定、目录、接口、迁移或已知限制已同步到 `docs/ai-context`。
- 工作区只包含任务范围内修改，未擅自提交或覆盖用户已有改动。
