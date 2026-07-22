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
