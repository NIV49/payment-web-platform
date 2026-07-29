# CODEOWNERS 与分支保护引导

`CODEOWNERS` 只对它已经存在于目标分支之后创建或更新的拉取请求生效。首次把
`.github/CODEOWNERS` 加入仓库的提交，不能依赖该文件自我批准或自我保护。

引导顺序必须由仓库管理员在仓库外完成并保留审计记录：

1. 两名独立审查者按完整提交 SHA 审查首次治理提交；实现作者不能计入两人。
2. 管理员合并该提交后，确认默认分支已经包含 `.github/CODEOWNERS`。
3. 管理员启用默认分支 ruleset：要求 Code Owner review、必需的 documentation
   check、dismiss stale approvals、禁止 force push 和分支删除，并限制 bypass 为应急管理员。
4. 管理员通过 GitHub API 或组织侧只读作业核验实际 ruleset；仓库内文档和 CI
   不能充当平台配置已经生效的证据。
5. 上述外部核验完成之前，权限、支付规则、Judge、工作流和治理脚本不得视为受保护。

以后每次修改 policy 的 `rulebookPaths`、`judgePaths` 或新增 workflow，都必须确认
最终匹配的 Code Owner 仍为 `@NIV49`；仓库测试只验证覆盖关系，平台侧 ruleset
仍必须独立核验。

## 外部保护状态

- `needsHumanDecision: true`
- 仓库内只能证明 CODEOWNERS、workflow、`.gitattributes` 与治理脚本的静态覆盖；
  当前 ruleset 是否启用、是否命中默认分支以及是否允许 bypass，必须由仓库管理员
  在 GitHub 控制面核验，不能由本仓库声明为已生效。

关闭该人工决策前，至少保留以下可复核的外部证据：

1. GitHub API 返回的 repository ID、默认分支、ruleset ID/名称、`active` enforcement
   状态、采集时间和只读查询主体；
2. ruleset target/conditions 确实覆盖默认分支；
3. Code Owner review、documentation required status check、dismiss stale approvals、
   禁止 force push、禁止分支删除的完整规则值；
4. bypass actor 的主体、权限和应急用途清单；
5. 一个测试 PR 的平台侧审计记录，证明缺少 Code Owner approval 或必需 check 时
   合并实际被阻断。
