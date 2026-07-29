# Payment Platform Judge Charter

> 状态：Judge 治理流程已确认
> 适用项目：`payment-web-platform`
> 第一阶段范围：权限底座
> 长期范围：支付平台全部领域

<!-- decision-status id=IAM-GLOBAL-USER-MULTI-TENANT status=pending ref=none -->

本文件确认的是 Judge 的治理流程，不会使未定版业务规则自动生效。规则只有在产品基线或 accepted ADR 中获批后，才能进入 approved Rulebook 和自动 PASS；全局 User 的 TenantMembership 基数、工作空间选择以及三后台是否采用独立 session realm/Token audience 仍是待决项。

## 1. 目的

在启动多 Agent 迁移和实现循环前，先建立独立、客观、可重复执行的裁判系统（Judge）。

Judge 必须回答：

```text
目标代码是否满足已确认业务规则、安全与资金不变量、接口契约和工程门禁？
```

Agent 声称“完成”不构成完成。只有指定版本通过 Judge，任务才能关闭。

## 2. 基本原则

1. 老系统代码只是现状证据，不是目标真理，也不是唯一规格。
2. Judge 独立于业务实现 Agent；业务实现 Agent 不得通过修改 Judge 让自身通过。只有人类明确授权的治理维护任务可以修改 Judge/Rulebook，且必须接受独立复审。
3. 普通任务由机器自动裁决；人工只处理规则新增、规则冲突和无法机器判断的例外。
4. 工作按可独立验收的能力切片拆分，不按文件数量拆分。
5. 所有失败进入结构化机器队列，修复后必须由独立 Judge 复验。
6. 重复问题必须沉淀为版本化规则和永久回归测试。
7. 审查和验证必须针对不可变 commit，不得针对变化中的共享工作区。
8. 第一阶段只实现权限 Judge，但框架必须能扩展到支付、账本、渠道、回调和清结算。

## 3. 事实优先级

发生冲突时，按以下顺序裁决：

```text
已确认业务规则
> 安全与资金不变量
> ADR / API / 数据契约
> Judge 测试
> 当前目标代码
> 老系统代码与历史行为
```

约束：

- 目标代码不能反向修改规则来自证正确。
- 老系统行为违反已确认规则时，不保持兼容。
- 无法判断的旧行为进入待裁决队列，Agent 不得自行猜测。
- 人工裁决一次后，结论必须进入 Rulebook，并生成永久回归测试。

## 4. Judge 的六层结构

| 层级 | 职责 |
| --- | --- |
| 规则裁判 | 校验已确认业务规则、ADR、权限模型和禁止事项 |
| 契约裁判 | 校验 API、事件、数据库字段、错误码和前后端契约 |
| 行为裁判 | 对合法旧行为执行新旧黑盒差异测试 |
| 不变量裁判 | 校验权限、租户、状态、余额、幂等及其他领域不变量 |
| 对抗裁判 | 覆盖越权、空值、极值、重复、乱序、并发和依赖故障 |
| 工程裁判 | 校验编译、测试、迁移、配置、部署和可观测性门禁 |

新旧结果一致不代表自动通过；结果仍必须符合 Rulebook。

## 5. 第一阶段权限 Judge

第一阶段不实现支付业务。Judge 覆盖：

- 运维端、商户端、代理商端的租户内授权、组织和数据边界默认隔离；
- 三端登录入口、接口和缓存必须阻止缺少当前后台授权工作区 ACTIVE TenantMembership 或显式后台授权的跨后台访问；
- 授权工作区 Tenant 与资源归属 Tenant 必须分开：代理商在自身工作区执行 `RELATED_PARTY_READ` 不要求加入商户 Tenant，而要求显式 Grant、可信代理关系和真实资源归属证据，见 [ADR-0001](adr/0001-separate-authorization-workspace-from-resource-owner-tenant.md)；
- 全局 User 是否允许关联多个 TenantMembership、登录后的工作空间选择，以及 session realm/Token audience 是否分离，保持 `IAM-GLOBAL-USER-MULTI-TENANT` 待决，Judge 不得把候选方案当作已批准规则；
- 用户、部门、角色、菜单、权限码和数据范围闭环；
- 部门只负责组织归属和数据范围，不直接分配菜单或权限；
- 菜单、权限码和完整授权始终通过角色分配；
- 未声明或证据不完整时默认拒绝；
- 租户、主体及资源归属由服务端可信上下文提供；
- 撤权、禁用、改密和会话版本变化后旧会话立即失效；
- 前后端 API、动态菜单、按钮权限及错误契约一致；
- 数据库迁移、权限缓存和并发更新保持一致；
- 空数据范围不得解释为全局范围；
- 批量操作不得只校验第一项；
- 前端隐藏按钮不得替代后端授权。

## 6. 并行工作单元

并行单位是“可独立验收的能力切片”。每个切片必须包含：

- 明确输入、输出和不变量；
- API、数据库、事件及前端契约；
- 生产代码与相关测试；
- 独立 Judge 验收条件；
- 依赖项和唯一负责人；
- 不依赖其他 Agent 的临时实现。

权限阶段初始依赖链：

```text
身份命名空间
  -> 登录与三后台授权/会话边界
  -> 部门组织树
  -> 角色与权限码
  -> 菜单和动态路由
  -> 数据范围
  -> 撤权与缓存失效
  -> 三端集成验收
```

无依赖切片可以并行。同一状态机、表或契约只能由单一所有者修改，或按依赖顺序串行执行。

## 7. 机器队列

统一流转：

```text
规则缺口
  -> 实现任务
  -> 编译/测试失败
  -> 审查发现
  -> 修复任务
  -> 回归验证
  -> 关闭
```

每个队列项必须包含：

- 唯一问题指纹；
- 目标 commit、能力切片和绑定规则/Judge 内容摘要的 `evaluatedVersionKey`；
- 带类型的失败来源：规则失败记录 `ruleId`，Judge、构建、测试或审查失败记录对应类型和稳定 `checkId`；
- 被违反的 `ruleId` 或带类型的 `checkId`，不得为非规则失败伪造领域规则；
- 显式 `queueItemSchemaVersion=2` 和从 `open`、`failedReviewRounds: 0` 开始的不可变 `initialStateHistory`；
- 与状态一致并进入签名 `queueDigest` 的显式 `resolution`：处理中为 `unresolved`，人工裁决为 `deferred`，关闭时为 `fixed|flaky|rejected`；
- 可复现输入、控制流和证据；
- `BLOCKER` 或 `SHOULD_FIX`；
- 依赖项、负责人和状态；
- 修复后必须执行的验证集合。

队列规则：

- 编译、测试和审查失败自动产生队列项；
- `failureSource.type=rule` 的 `ruleId` 必须同时解析到 evaluated policy 登记的唯一 Rule Card，以及当前 Capability Slice 实际携带的同一 Rule Card；未登记规则、重复 ID 和跨切片规则一律 fail closed；
- `findingId` 在整个 bundle 内跨 reviewer 唯一；每个未关闭的有效审查 finding（包括 `SHOULD_FIX`）必须且只能对应一个 `failureSource.type=review`、`checkId=review:<findingId>` 的队列项，且严重级别、状态、触发条件、控制流、证据、影响和验证方法完全一致；每个 review 来源队列项也必须反向对应真实 finding，禁止孤儿或重复映射；
- `failureSource.type=judge` 的 `checkId` 必须同时解析到 evaluated Judge registry，并解析到至少一条命令完全一致、签名有效且绑定当前 `queueDigest` 的 Review execution；
- `failureSource.type=build|test` 除稳定 `checkId` 外必须包含 `originExecution`：同一 `checkId`、最初失败命令、失败 commit、非零退出码和结果摘要。该对象进入不可变 Queue root 与双签 `queueDigest`；closed 项的失败 commit 必须是当前 evaluated commit 的严格祖先，失败与成功验证指向同一 commit 时不得关闭。后续 Review 验证必须沿用原始命令，后续 `evaluatedVersionKey` 不得改写最初命令、退出码或结果摘要；
- build/test `checkId` 不得冒充 Judge registry ID，且必须解析到至少一条签名有效、命令与 `originExecution` 一致并绑定当前 `queueDigest` 的 Review execution；v2 尚无独立 typed build/test gate registry，所以自由文本退出条件、验证命令或未签名 CI label 都不构成可信 gate；
- `open|implementing|reviewing` 只能使用 `resolution=unresolved`，`human-decision` 只能使用 `deferred`，closed 必须显式使用 `fixed|flaky|rejected`；`flaky` 仅适用于 build/test。`flaky` 与 `rejected` 是非修复裁决，不得伪装为 `fixed`，review 来源 Queue 的 resolution 还必须与签名 finding 完全一致；
- judge/build/test Queue 来源的 `checkId` 在 bundle 内全局唯一；两名 reviewer 可以独立签署同一 checkId，但对应 command 必须完全一致；
- Queue Item 必须使用精确字段集合，`queueItemSchemaVersion` 必须是 JSON 整数 `2`，不能用数值相等的 `2.0`；不可变 root、跨 bundle 同指纹状态、分叉父状态和调和状态一律按类型严格 canonical JSON 比较，嵌套 `evidence` / `dependencies` 中的整数 `1`、布尔值 `true` 和数值 `1.0` 不得视为同一值；
- 同根因问题先去重，再派发修复；
- 修复 Agent 无权直接关闭任务；
- 独立 Judge 通过后才能关闭；
- 同一问题连续三轮未解决，停止自动循环并交给人工。

## 8. Rulebook

每条规则必须具有稳定 `ruleId`，并记录：

- 适用范围；
- 正例和反例；
- 验证方法；
- 证据来源；
- 引入版本和状态。

规则治理：

1. Agent 可以提交 `candidate`，也可以在独立 commit B 中把 payload 的请求状态写为 `approved`；该字段本身不产生有效批准，缺少可信 envelope 时门禁必须失败。
2. 请求批准的规则必须由两名独立审查者确认；两份 Ed25519 签名结果都必须绑定同一 commit B、同一 `evaluatedVersionKey`、同一 Rulebook/Judge 内容摘要和同一 Rule payload digest，并且是快照有效的 PASS。review purpose 必须为 `rule-approval`，被批准的 subject 也必须进入签名内容。
3. 规则生效时必须同时增加回归测试。
4. Rulebook 变化触发受影响能力切片重新验收。
5. 禁止修改或删除规则来绕过失败。
6. 规则废弃必须保留原因和决策记录。

双签完成后，由单父 commit C 只提交 canonical artifact root 下的 regular JSON detached approval envelope；C 的完整树差异不得夹带 Rule、Judge、workflow、业务或其他路径。完整 `approvalCommit`、两名独立审查者的 `approvedBy`、两条可解析的 `approvalReviewRefs`、Judge 成功执行证明和签名 Review Results 都必须绑定 B。只有 C 上的仓库级门禁通过后，规则才是 effective approved。B 签名后禁止 squash、rebase 或 amend。删除 envelope、删除规则、把 effective approved 降回 candidate，或更换 reviewer trust registry，均必须 fail closed；当前尚未定义可信 retirement/key-rotation 协议。

Bundle 必须显式声明 `lifecycleStatus`。`draft` 只允许 positional 本地预检；canonical artifact root 只接收 `closed`，并要求恰好两名 reviewer ID 和 key ID 均不同的有效签名 PASS。closed slice 至少声明一个可在 evaluated Judge registry 解析的 check ID，两名 reviewer 都必须签署命令、目标 SHA、`exitCode: 0` 与结果摘要；即使没有 approved Rule 也不能省略。签名覆盖严格 finding schema、Judge 执行结果和整个 `queueItems` 的 `queueDigest`。Queue 按 fingerprint 跨完整 merge DAG、相对真实直接父提交 append-only 回放，不得删除、改写不变字段或跳过状态；一个 fingerprint 分歧不得屏蔽其他一致项的校验，分叉父状态必须由后续单父 commit 实际改变签名 Queue 状态并绑定新 evaluated version，纯代码后继不能冒充调和。每次 `reviewing -> implementing` 递增签名内的 `failedReviewRounds`，第三次失败必须进入 `human-decision`，获人类授权后重新实施时归零。历史 JSON、policy 或签名错误即使随后删除也继续 fail closed。

Queue Item v2 的 `initialStateHistory` 是首次持久状态的签名引导日志：第一项固定为 `open` 和零失败轮次，每一个状态都必须使用在整段 transcript 内唯一的 `evaluatedVersionKey`，不能只检查相邻项，`A -> B -> A` 属于版本重放。每个历史 key 的可验证含义是“该次状态变化接受独立 Judge 时绑定的精确 evaluated snapshot”，必须能由保留的 `taskIdentityKey`、目标 commit 和 evaluated Rulebook/Judge digest 按既定公式重算，并与签名 Review 证据一致；它不是任意 nonce、计数器或展示标签。每个非当前 key 必须在 bundle 的 `queueHistoryEvidence` 中精确保留历史 target、重算后的 Rulebook/Judge manifest、与日志状态一致且不可改根因的 Queue snapshot，以及恰好两份绑定该 snapshot、Queue digest 和成功 Judge 执行的可信独立签名 PASS Review。保留 target 必须按日志顺序形成严格祖先链；merge 不能直接充当状态转换，只能由后续单父、重新 evaluated 的 reconciliation 承接。只有 sole key 就是当前 key 的单状态日志可省略该字段；已有任意 64 hex 伪摘要不予白名单或兼容豁免。最后一项必须等于该 fingerprint 在仓库历史中首次出现的状态。由于 canonical root 不能保存 draft/open BLOCKER，一个已经修复的 BLOCKER 可以在首个 closed bundle 中携带完整的 `open -> implementing -> reviewing -> closed` 引导日志；这不是绕过 closed 约束，因为日志整体进入 `queueDigest` 并由两名 reviewer 签名，首个持久状态仍是已复验的 `closed`，未解决 BLOCKER 仍然禁止。首次出现后 `initialStateHistory` 属于不可变 root，普通状态迁移继续相对真实 Git 父提交回放并要求新 evaluated version 和新双签；状态实际变化时不得复用该 fingerprint 在已回放历史中出现过的 key，未变化的透传状态保留原 key。仓库回放必须遍历相关路径的完整 Git 父链；即使 C 恢复 A 的最终树，`A -> tampered B -> restored C` 仍必须在错误中报告 B 的完整 commit SHA。缺少显式版本、引导日志或所需保留历史证据的旧 Queue 数据一律 fail closed；Review canonical JSON 字段和既有 Queue digest namespace 不变。

每个签名 Queue fingerprint 的首次出现或后续变化只能由单父 envelope commit 激活：该 commit 的完整 tree delta 只能包含 `.agents/payment-modernization/artifacts/` 下的 regular `*.json`，且承载该变化的 bundle 必须令 `evaluatedSnapshot.targetCommitSha` 精确等于直接父 commit。这样两名 reviewer 绑定的是实际 gate tree；Queue bootstrap/transition 不得同时夹带源码，旧 evaluated target 也不得跨过未审中间树继续激活。

Reviewer 公钥、角色、稳定 target repository ID、Rulebook/Judge 路径由 `.agents/payment-modernization-policy.json` 固定。PR 以受保护基准分支 SHA、`main` push 以上一次 main SHA 作为外部 policy anchor；bundle 不能用自己先登记的公钥自签。当前 registry 为空，因此规则批准有意保持不可用，直到独立人工流程完成 key bootstrap。Rulebook 以长度分帧、按路径排序的实际内容 SHA-256 摘要作为身份；人工标签只用于展示。

## 9. 运行隔离与幂等

- 每个实现任务使用独立 Git worktree 和分支。
- 审查任务只读取指定 commit，不读取活动工作区。
- 业务实现 Agent 不得修改 Judge、Rulebook 或其他切片；治理维护任务只有在获得人类明确授权时才能修改 Judge/Rulebook。
- 审查 Agent 只读，不得修改代码。
- 只有集成循环负责合并、完整构建和全量回归。
- 并行 Agent 只运行切片级快速验证。
- 实现前任务身份使用 namespaced canonical JSON，而不是歧义字符串拼接：

```text
taskIdentityKey = sha256(canonical-json({
  namespace: "payment-modernization-task-v2",
  turnId, sliceId, path, targetRepositoryId, targetBaseSha,
  sourceSnapshots, nonGitEvidence,
  baselineRulebookPaths, baselineRulebookDigest,
  baselineJudgePaths, baselineJudgeDigest,
  actors, inputs, outputs, ruleIds, dependencies, ownedPaths,
  forbiddenChanges, entryCriteria, exitCriteria, judgeCommands
}))
```

`taskIdentityKey` 不包含尚未产生的输出 commit、host-specific runtime path 或 display-only manifest label；同一任务身份禁止重复实现或合并。输出 commit 产生后，先从目标 commit 重新计算 evaluated Rulebook/Judge 摘要，再得到评估版本：

```text
evaluatedVersionKey = sha256(canonical-json({
  namespace: "payment-modernization-evaluated-version-v2",
  taskIdentityKey, targetCommitSha,
  evaluatedRulebookDigest, evaluatedJudgeDigest
}))
```

每个独立审查的幂等键为：

```text
reviewIdempotencyKey = sha256(canonical-json({
  namespace: "payment-modernization-review-v2",
  evaluatedVersionKey, reviewerId, reviewerRole
}))
```

- 相同 `reviewIdempotencyKey` 禁止重复审查；不同 `reviewerId` 的审查必须能够针对同一 `evaluatedVersionKey` 分别完成，不能被版本级去重误杀。
- Rulebook 和 Judge 摘要必须由指定 commit 中长度分帧、按路径排序的路径与实际 bytes 计算，不能用可变标签代替。baseline 与 evaluated manifests 分别绑定 `targetBaseSha` 和 `targetCommitSha`，不能混用。
- 工作区不干净、目标 SHA 不一致、`startCommitSha`、`endCommitSha`、`targetCommitSha` 三者不完全相等，或规则/Judge 摘要漂移时，本轮结果作废。

## 10. 测试数据与 Oracle

Judge 使用三类测试资产：

1. 规则样本：根据已确认规则构造正例与反例。
2. 历史样本：从老系统提取并脱敏的真实请求、响应、异常和事故案例。
3. 生成样本：自动生成空值、极值、重复、乱序、并发和越权组合。

安全约束：

- Judge 禁止连接生产执行写操作；
- 老系统源码、配置、数据库转储、日志、Trace 和 Payload 样本均视为不可信且可能含敏感信息；
- 历史数据必须在引用前脱敏，使用合成同形值或 `${ENV_VAR}` 占位符；
- 密钥、Token、密码、连接串、个人信息和生产标识不得进入分析、规格、队列、评审、提示词、测试或其他仓库产物；
- 所有证据派生产物提交前必须通过仓库批准的 `python3 -I scripts/check_sensitive_artifacts.py --repository-root <repo> --base-commit <trusted-base-SHA> --commit <target-SHA>`；扫描器拒绝 shallow/graft 图和 Git 仓库/图路由环境覆盖，严格解析单行元数据，遍历 `base..target` 的完整可达提交 DAG 与每条 raw parent 真边，检查所有新增、修改和类型变化的不可变 blob，因此“中间提交加入、目标提交删除”以及合并分支历史也不能逃逸。它不依赖目录名、可被 `.gitattributes` 改写的 patch 或 submodule ignore 配置；symlink/gitlink、二进制、非 UTF-8、超限，以及不安全或无法可靠解析的 YAML/JSON/XML 变更 fail closed，JSON/XML descriptor 结构必须检查，XML namespace 同名属性不得互相覆盖，安全 `value` 属性不得遮蔽不安全文本，且错误不得回显候选敏感值；
- 时间、随机、汇率和外部渠道必须使用固定时钟及受控模拟器；
- Judge 使用外部可观察契约，不依赖目标实现的私有函数；
- 每个历史事故和已确认缺陷都必须成为永久回归样本。

## 11. Judge 红队试点

第一个试点切片是“三后台访问边界与会话安全”。必须故意植入并检出：

- 缺少当前后台授权工作区 ACTIVE TenantMembership 或显式后台授权的主体仍能登录或访问该后台；
- 把资源归属商户 Tenant 错当授权工作区，要求合法代理商加入商户 Tenant 后才能执行 `RELATED_PARTY_READ`；
- 商户账号通过切换 `tenantId` 访问其他主体；
- 跨后台复用 Cookie、Token 或缓存后绕过目标后台的 Membership 与权限校验；
- 禁用、撤权、改密后旧会话继续访问；
- 未授权接口、未知路由和空数据范围；
- 前端隐藏按钮但直接请求后端接口；
- 并发登录、撤权和缓存版本竞争。

只有同时满足以下条件，才能启动大规模多事件循环：

```text
故意植入的缺陷全部检出
+ 正常实现没有误报
+ 失败自动生成去重队列
+ 修复后可以独立复验关闭
+ Rulebook 变化触发受影响测试
+ 普通流程无需人工逐项裁决
```

## 12. 退出条件

能力切片满足以下全部条件才算完成：

```text
规则覆盖完整
+ 契约测试通过
+ 领域不变量通过
+ 对抗矩阵通过
+ 无未处理 BLOCKER
+ 所有偏差均有明确裁决
= Judge PASS
```

Judge 未通过时，不得以进度、Agent 共识、人工感觉或“旧系统也是如此”为理由关闭任务。

## 13. 当前明确不做

- 第一阶段不实现支付、账本、渠道、回调或清结算业务；
- 不把老系统测试原样搬入新系统；
- 不按文件数量制造表面并行；
- 不允许实现者自行修改裁判标准；
- 不让多个 Agent 同时修改同一状态机、表或契约；
- 不在 Judge 红队试点通过前启动大规模自动迁移。
