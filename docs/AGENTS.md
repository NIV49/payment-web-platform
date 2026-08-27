# Documentation Rules

本目录同时服务两类读者：接手任务的开发者/Codex，以及理解产品阶段和交互的产品人员。仓库内 Markdown 是唯一事实源；Tolaria 只用于浏览、搜索、关系图和 Mermaid 渲染，不维护第二份文档副本。

## 写作边界

1. `README.md` 是双入口首页；`product/` 解释产品能力和生命周期；`ai-context/` 记录当前工程事实；`ai-contract/` 固定跨端协议；`adr/` 保留已接受决策及其原因。
2. 当前实现、批准目标和历史原因必须分开写。代码未实现的目标不得写成当前能力，当前代码也不能静默推翻 accepted ADR 或契约。
3. 新的导航、产品和状态页使用简短 frontmatter，至少包含 `type`、`status` 和 `audience`；正文链接必须使用 GitHub 可解析的普通 Markdown，相互关系可在 frontmatter 中补充 Tolaria wikilink。
4. 不创建一次性分析、框架摘抄或临时 runbook。仍有效的规则合并到长期拥有者文档；短期执行状态进入 `ai-context/current-status.md`；架构原因进入 ADR。
5. 已被 `.agents/payment-modernization-policy.json` 登记的 Rulebook 路径不能作为普通清理删除。路径退役必须先设计治理迁移、重建 trust anchor，并验证历史产物仍可重算。

## 与代码同步

- 路由、菜单可见性、字段、筛选、动作、只读边界或权限行为变化时，同一改动更新 `product/system-management.md` 和对应工程/契约文档。
- 当前阶段、唯一下一任务、GO/NO-GO 或验证结论变化时，更新 `product/lifecycle.md` 与 `ai-context/current-status.md`。
- API、DTO、错误码、权限码或兼容性变化时，更新对应 `ai-contract`。
- Merchant 主体、状态机、申请/审核接口、权限、菜单或页面变化时，同一改动更新 Merchant Contract、Merchant 工程上下文、商户管理产品页和 Current Delivery Status。
- 部署单元、模块所有权、依赖方向或固定工具链变化时，更新 frontend/backend 上下文。
- 当前交付事实改变后，应优先修正文档；一笔提交中的代码和文档共同构成新事实，不能要求每个开发中间态都先维持旧文档成立。
- 文档和代码同步由 `scripts/check_doc_code_sync.py` 在不可变 `base..target` 上检查；人工描述不能替代该门禁。

## 图文规范

- 截图只使用本地合成数据，禁止凭据、Token、生产标识或个人信息。
- 固定使用 `assets/<feature>/` 下的稳定文件名；交互变化后原位重拍，不新增带日期的重复图片。
- 截图前必须等待真实页面内容出现并检查非空、无加载遮罩、无布局溢出。
- 流程、状态和所有权优先使用 Mermaid；截图用于证明用户实际看到的页面，不替代权限和接口契约。
