---
name: 功能交付团队
description: 拆解 → 并行实现 → 代码审查 → 集中验证，适合中小型功能开发
stages:
  - title: 需求拆解
    agent: Senior Project Manager
    prompt: 把本次目标拆成 2~4 个可并行执行的子任务，标明每个子任务要动的文件范围与验收点。只拆不解，不改代码。
  - title: 并行实现
    tasks:
      - agent: Mobile App Builder
        prompt: 实现界面与交互部分，按上游拆解的范围改，不扩大范围。
      - agent: API Platform Engineer
        prompt: 实现数据、接口与状态部分，按上游拆解的范围改，不扩大范围。
  - title: 代码审查
    agent: Code Reviewer
    prompt: 审查上游阶段的改动，按严重度列出问题与位置。只指出问题，不自己动手改。
  - title: 集中验证
    agent: Reality Checker
    prompt: 汇总前几阶段列出的改动，给出一份一次性验证清单（编译命令、要跑的测试、手动检查点），并明确指出哪些改动尚未验证。
---
团队公约：
- 只改任务范围内的文件；确实需要动范围外的文件，先用 messageParent 说明原因，等上一层放行，不要擅自扩大范围。
- 不要自己运行全量构建或单测：同一个工程只能串行编译，统一留到「集中验证」阶段，由主对话发起并先征得用户同意。
- 缺信息先问，不猜；改完不自行提交 git。
