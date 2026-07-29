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
| 文档/规则 | `git diff --check`，检查相对链接与源码路径，运行 `python3 -B -I scripts/check_sensitive_artifacts.py --repository-root <repo> --base-commit <trusted-base-SHA> --commit <target-SHA>` 扫描完整 `base..target` DAG 每条父边的新增/修改/类型变化 blob；涉及权限决策时运行 `python3 -B -I scripts/check-doc-decisions.py` |
| 项目级 Agent skill | `python3 -B -I -m unittest discover -s scripts/tests -p 'test_*.py'`、`python3 -B -I scripts/check_project_skills.py`，并运行其关联的文档/规则门禁 |
| Payment modernization 产物 | `python3 -B -I scripts/check_modernization_artifacts.py --repository-root <target-repository> --commit <full-target-SHA> --trusted-policy-commit <protected-base-SHA>`；CI 权威检查必须读取 Git 对象，canonical root 只允许 `README.md` 与 closed JSON bundle，每个 closed bundle 必须带两份独立签名 PASS；Queue Item 必须使用精确 schema v2、整段 `initialStateHistory` 唯一的 evaluated key、类型严格 canonical JSON immutable root 和状态一致的 `resolution`；每个非当前历史 key 必须有 `queueHistoryEvidence` 保留可重算 target/manifests、对应 Queue 状态与双签 Review，旧伪摘要不兼容；build/test closed 项的不可变 `originExecution` failure commit 必须是 evaluated target 的严格祖先；每次 Queue 新增/变化只能由绑定直接父树的单父、纯 JSON envelope commit 激活；positional draft 预检不能替代 |

文档治理脚本的 Python 依赖以版本和 wheel SHA-256 完整固定在 `scripts/requirements-documentation.txt`。CI 先建立可信 repository/toolchain capture，再使用 `--require-hashes --no-deps --only-binary=:all: --no-compile --target` 从该清单准备独立的 dependency root；准备完成后立即封存目录路径、树结构、文件身份、元数据与内容摘要。受控 checker 和测试统一经 guard 的 Python runner 以 `-B -I -S` 启动，再只把已封存 root 显式加入 `sys.path`；system/user site、`.pth`、`sitecustomize` 和 `usercustomize` 都不进入 import 边界。`-B` 从解释器层禁止 bytecode 写入；policy 的 `judgePaths` 封闭登记全部 `scripts/**/*.py`。不要依赖机器全局恰好存在的 YAML/Markdown 解析库。

Queue 历史协议改动必须运行真实临时 Git 仓库回归，不能只测 helper。最少覆盖：真实保留证据的合法 closed bootstrap；伪摘要、缺失 Review、签名 Queue digest/key/target 绑定错误和 target tree 漂移；引导 transcript 与真实父链中的 `A -> B -> A` key 重放；status/resolution 不一致；嵌套值 `1 -> true` 与 `2 -> 2.0` 的类型改写；合法线性多提交；merge 不能直接激活状态、分叉父状态只能经后续单父重新签名版本调和；`A -> tampered B -> restored C` 仍报告 B 的完整 SHA。

权限跨文档决策使用 `<!-- decision-status id=<ID> status=<pending|accepted|superseded> ref=<none|repo-relative-path> -->` 标记。属性必须且只能各出现一次。`pending` 必须使用 `ref=none`；`accepted` 或 `superseded` 必须引用 `docs/adr/NNNN-slug.md`，该 ADR 必须且只能声明一次 `Status: accepted.` 和匹配的 `Decision-ID: <ID>`。修改任一决策状态时，必须同步全部登记文档并运行一致性检查。

该轻量治理 CI 对所有 PR 和 `main` 推送运行。它固定 `linux/amd64` 执行平台、Python 3.13.14 容器的单平台 manifest 摘要、Action 完整提交和依赖 wheel 哈希；容器 root filesystem 保持只读，先丢弃全部 Linux capabilities，再只恢复 checkout 写入 runner-owned workspace 所需的 `DAC_OVERRIDE`，并且只把 `/tmp` 挂载为可执行临时文件系统。checkout 前的独立 preflight 必须实际创建并删除 workspace probe，避免 capability 配置在 guard 启动前就让 job 失效。仓库脚本只在 `env -i`、`--noprofile --norc` 的最小 shell 中运行。workflow 在独立准备 step 中 capture Python 与 repository，并在安装锁定依赖和执行 PR 可修改代码前确认隔离模式下的每个 Python runtime import root 与 system site 均不可写；准备完成后复验工具身份和 import 边界。之后所有会执行 PR 控制 Python 的 verify、archive、snapshot checker 与 test 阶段必须位于同一个不中断的最小 shell，并只捕获一次 dependency fingerprint，禁止后续 step 对已被前序 checker 改写的 dependency root 重新授信。治理测试内部需要派生 scanner/checker 进程时也必须复用 fixture 的 `ci_python`，不得直接调用系统 Python 而绕过 `-B -I -S` 和 sealed dependency root。该受控 shell 开始前冻结 canonical workspace、absolute git-dir、work-tree、common-dir、object directory、`.git` entry、完整有效的 local/worktree config 及其 include 链，以及 `info/exclude` 和有效 `core.excludesFile` 的内容指纹。受控命令在启用 `errexit` 的子 shell 中首错即停，父 shell捕获其原始状态后无条件运行最终 repository verifier；两者同时失败时仍返回受控命令的原始非零状态。脚本后的 Git 命令统一经 `scripts/ci_repository_guard.sh` 显式传入 `-C`、`--git-dir` 和 `--work-tree`，禁用 system/global config、replace refs 与 commit-graph 读取；本地配置、exclude 控制面、仓库身份、Python/dependency 身份或受控脚本后的仓库状态变化一律 fail closed。

`CI_SAFE_PATH` 只定义初始发现范围，不再充当工具身份凭据。Python 第一次执行前，guard 以 Bash 内建和固定 native PATH 中的 `stat`、`readlink`、SHA-256 工具捕获其原始解析路径、完整符号链接链、canonical path、文件身份、必要元数据与内容摘要；该结论不依赖被测 Python 的输出。原生身份成立后，guard 才允许 Python 记录完整工具链指纹及只读 `sys.flags`/`sys.path` runtime boundary。`env`、`git`、`python3`、`find`、`grep`、`tar`、`mktemp`、`mkdir`、`rm` 和三项 native trust primitive 均只通过只读绝对路径调用；每次 verifier 先重新取得 Python 原生身份，再允许 Python 校验其余工具和 import 边界。PATH resolution、符号链接、内容、元数据、runtime flags、import path 或 dependency root 任一漂移均 fail closed。固定容器契约与本地合成测试职责分离：workflow 测试静态核对固定 runner 的绝对 shell、PATH、镜像和平台；本地 fixture 使用宿主实际解析出的绝对工具构造独立最小环境，不宣称模拟容器文件系统。fixture 明确要求 Git `>= 2.31.0`，关闭 system/global config、hooks 及 commit/tag signing，避免宿主 Git 配置影响测试结论。

每个测试前后还拒绝 shallow 仓库、`.git/info/grafts`、`GIT_GRAFT_FILE`、`GIT_SHALLOW_FILE` 以及有效、损坏或松散/打包形式的 `refs/replace/*`。它校验原始 commit/tree object、真实 index tree，拒绝 assume-unchanged/skip-worktree 标志，并用从目标 tree 新建的临时 index 强制逐内容比较工作树；untracked 检查使用不加载任何 exclude 规则的 `git ls-files --others`，所以 tracked `.gitignore`、`info/exclude`、默认或显式 `core.excludesFile` 都不能隐藏持久文件。隔离快照的 archive 只接受捕获的完整 commit SHA，并在临时 bare Git 元数据目录中读取捕获的对象库；system/user attributes 被显式禁用，临时目录不承载 `info/attributes` 或工作树 attributes，只有目标 tree 中已提交的 `.gitattributes`（包括 `export-ignore`）能决定 archive 路径集合。archive manifest 同时保存 commit tree 的 mode、object type 和 OID，并用受限 `git cat-file --batch` 把每个归档普通文件与原始 Git blob 逐字节比较，因此 `export-subst` 不能改写 checker、测试或其他归档执行字节后再由第二份同样被转换的 archive 自证。快照 checker 经同一 `-B -I -S` runner 和已封存 dependency root 重跑；完整治理测试也在同一个受控 shell、同一份捕获 commit 的一次性 archive 内执行，使测试及其子进程的 bytecode 等文件副作用随快照销毁，同时原 checkout 仍由最终 repository verifier 独立检查。敏感扫描器拒绝不完整或被 graft 改写的提交图以及可重定向仓库、对象和图状态的 Git 环境变量，严格解析 Git 单行元数据，核对 raw commit parent header，遍历可信 base 到目标 SHA 的完整可达提交 DAG 及每条真实父边，扫描新增、修改或类型变化的每个不可变 blob，覆盖中间提交和合并分支，并显式忽略 `.gitattributes` patch 与 submodule ignore 配置对覆盖范围的影响；JSON/HAR、XML 与 YAML 一并结构化检查。敏感 JSON Schema 只解析可证明的同文档 JSON Pointer `$ref`；外部、歧义、循环、嵌套 `$id` 资源作用域、`$dynamicRef`/`$dynamicAnchor` 或超预算引用默认拒绝，共享目标按文档资源根和对象身份记忆化。敏感 YAML Schema 同样只解析当前 YAML document root 内的 JSON Pointer `$ref`；外部 URI、命名 fragment anchor、缺失或歧义目标、循环、嵌套 `$id` 资源和动态引用语义均 fail closed。JSON/YAML 的单 schema、schema 数组及 schema map applicator 必须满足各自 carrier 形状后才能取得 schema 字段名豁免；标准 value keyword 及明确承载 default/example/value 的 `x-*` 扩展都会扫描，`enum/examples` 只有数组形状才能取得豁免。敏感结构化值容器的 mapping key 同样作为候选敏感值扫描；当同一成员的 value 已独立构成不安全标量证据时不重复报告，任何诊断均不得回显 key 原文。descriptor 下 vendor `x-*-examples` 的 mapping 形态与 scalar/list 形态保持敏感 schema 上下文，并只扫描命名 example 的实际 value 载体；`x-*-value` 的 mapping/list/scalar 形态继续作为直接值载体。YAML merge/alias 传播有效 descriptor，schema alias DAG 按节点身份记忆化并计入共享结构预算，集合形状规则与 JSON 一致。JSON 字符串中的嵌套 JSON 共享深度、节点和字节预算；HAR 标准路径的 `request.postData` 与 `response.content` 必须先是 object carrier，其 `text` 才按 JSON media type 和严格 Base64 声明解析，array/scalar/null carrier 一律 fail closed，敏感键覆盖受控的 camelCase 词组。XML 检查 `key|name`、`param-name`、`property-name` descriptor 及对应值，覆盖 namespace 同名属性、注释、CDATA 和处理指令；敏感元素的 `value/data/default/text/content` 属性逐项检查，隐藏 fragment 对无命名空间属性与默认命名空间子元素沿用普通 XML 配对语义，且隐藏敏感元素会把敏感上下文传给后代的值属性；隐藏内容同样受共享节点/字节预算约束，DTD/实体声明一律拒绝，且安全值不能遮蔽同节点的不安全文本。immutable evidence reader 为每次 Git 调用建立最小环境并设置 `core.commitGraph=false` 与 `core.useReplaceRefs=false`；普通 commit-graph 或 split commit-graph 可以作为仓库缓存存在，但不能参与 commit parent、ancestry、tree 或 blob 证据解析，结果必须与 raw commit object 一致。modernization checker 必须显式接收外部 `--trusted-policy-commit`，不会回退到父提交；Rule approval 与 Queue state activation commit 的完整路径差异强制 `--ignore-submodules=none`，其中 Queue 激活还必须是单父、纯 canonical JSON envelope 且绑定该直接父 commit。非 UTF-8、NUL/control、二进制、symlink/gitlink、危险 Git mode、超限或无法安全解析的结构化变更默认拒绝。

所有在精确 `safe.directory` 下读取 checkout、index 或真实对象库的 Git 子进程都必须清空 `core.fsmonitor`，禁止 textconv/ext-diff，并同时设置空 `GIT_ALLOW_PROTOCOL`、`GIT_NO_LAZY_FETCH=1` 与 `GIT_TERMINAL_PROMPT=0`。空协议白名单是 Git 2.31 fixture 也能识别的传输阻断层；`GIT_NO_LAZY_FETCH` 作为新版 Git 的直接阻断层，不能单独承担旧版兼容边界。异所有者回归测试必须先行为探测普通访问、模拟拒绝和精确命令级例外三种能力，不得依赖版本号或本地化诊断文本。

archive 与 tar 的原始状态分别从 `PIPESTATUS` 捕获，archive 的非零状态优先于 tar，外层仍无条件运行最终 repository verifier。解包成功后，guard 从同一捕获 commit 重新生成 archive manifest，并与 commit tree 的 mode/type/OID、原始 blob、实际解包路径/类型/内容、policy `judgePaths`、固定运行入口及完整 `scripts/tests/test_*.py` 集合逐项绑定；普通 symlink 的 target 也必须等于 mode `120000` 的原始 blob，而所有治理入口和测试路径必须是 regular file，不能通过 symlink 把执行流带出快照。合法 `export-ignore` 排除非门禁文件不受影响；任何根目录或嵌套 `.gitattributes` 排除门禁、checker 或测试都会 fail closed，任何 `export-subst` 引起的归档字节变化也会因不等于原始 blob 而 fail closed。

YAML boolean schema 只接受标准 bool tag 且词法必须能按锁定的 PyYAML 语义构造成真正的 boolean；伪造后缀 tag 或非法显式 bool 词法不能取得 schema 豁免，本地引用命中此类节点同样 fail closed。JSON schema 的 `required` 只有字符串数组 carrier 才能作为 schema 证据。JSON schema map 和命名 example map 的成员名按不透明名称处理，不能因名为 `default` 等 value keyword 触发误报；但 `properties` 等 schema map 中的敏感成员名必须继续传递给其子 schema，所以嵌套敏感属性的 `default/example` 仍会扫描，普通嵌套属性的非敏感默认值不会被扩大解释。引用验证上下文与敏感 default/example 上下文分离，因此穿过 `properties`、`schemas`、`definitions`、`$defs` 的嵌套 `$ref` 仍会被解析扫描。HAR root、`log`、每个 `entries[]`、`request`、`response`、`request.postData` 和 `response.content` 都必须是 object，只有 `log.entries` 必须是 array；任一中间 array/mapping/scalar/null 畸形 carrier 都在上下文丢失前 fail closed，不能借 Base64 JSON body 绕过标准 HAR 路径扫描。

XML 隐藏通道还必须拒绝违反 XML Namespaces 保留前缀、保留 URI 或前缀 undeclaration 约束的 namespace 声明；字符引用形成的隐藏标签按结构化后代文本聚合 descriptor/value，并保持现有 expanded namespace 边界以及无命名空间 attribute 与默认命名空间 child 的配对契约。隐藏标签的 qualified name 按 XML 规则大小写敏感匹配，大小写不同或前缀不同的伪 closing 不能提前清除 scope。隐藏敏感元素的最近敏感祖先沿 scope stack 传播，后代元素的 `value/data/default/text/content` 属性不能因外层安全占位文本而逃逸；这一隐藏非标准 markup 路径有意 fail closed，即使后代属性看似普通元数据也不放宽，避免重新引入不可判定的嵌套 secret carrier。显式元素、comment/CDATA/PI carrier、隐藏标签和 assignment 共用一个全局节点预算；任一阶段超限都必须 fail closed，诊断不得回显候选值。

当前 trusted reviewer registry 有意保持为空，所以任何 `approved` Rule Card 都会 fail closed，直到独立的人工作业完成 reviewer key bootstrap。含 `sourceSnapshots` 的历史证据还要求运行环境能只读访问项目登记的 legacy workspace；GitHub 托管 Ubuntu runner 不具备该本地路径时会阻断，不能跳过验证，需改用受控的 self-hosted evidence runner 或后续获批的不可变证据 attestation 方案。

仓库内脚本不能成为自己的最终信任根。必须按 [`docs/governance/codeowners-bootstrap.md`](../governance/codeowners-bootstrap.md) 先在目标分支落地 CODEOWNERS，再由仓库管理员启用并通过平台 API 核验 ruleset。首次 CODEOWNERS 提交不能自我保护；仓库测试只验证 policy、Judge、全部 workflow 和治理路径的覆盖关系，不证明平台配置已生效。CODEOWNERS 本地验证按 GitHub 语义剥离行内注释，只接受整段 `**`，拒绝重复 `/` 以及 GitHub 不支持的 `!`、`[]`、反斜杠和嵌入式 `**` 模式；尾 `/` 目录模式只匹配目录后代，不得匹配同名普通文件；末尾 `/**/` 的 globstar 按 GitHub 兼容的 gitignore 语义匹配其目录内零层或多层的直接后代，但不匹配目录本身，也不得绕过前导 `/` 的仓库根锚定。最终 owner 仍以最后一条匹配规则为准并允许 ownerless 清空。

## 4. 完成定义

- 实现符合当前源码的架构边界，而不是只让页面暂时可用。
- 没有新增硬编码菜单标题、重复 DTO、绕过权限或未验证的 component 路径。
- 相关测试已运行并记录结果；不能运行的测试说明原因和风险。
- 新增约定、目录、接口、迁移或已知限制已同步到 `docs/ai-context`。
- 工作区只包含任务范围内修改，未擅自提交或覆盖用户已有改动。
