---
name: 调研评审团队
description: 并行调研 → 交叉评审 → 结论汇总，适合技术选型与方案预研
stages:
  - title: 并行调研
    tasks:
      - agent: Explore
        prompt: 在本仓库里查现状：相关的代码位置、已有实现、项目规则里的约束。
      - department: product
        agentQuery: trend research
        prompt: 从社区与官方文档角度调研候选方案，给出出处。
  - title: 交叉评审
    agent: Reality Checker
    prompt: 对比各调研结论，指出证据不足、成本被低估、或与仓库现状冲突的地方。
  - title: 结论汇总
    agent: Docs Writer
    prompt: 汇总结论与推荐方案，写清取舍、风险与待验证项。
---
团队公约：
- 结论必须带出处（文件路径或链接），没有依据的判断明确写「未验证」。
- 只读调研：不改代码、不跑命令、不安装依赖。
