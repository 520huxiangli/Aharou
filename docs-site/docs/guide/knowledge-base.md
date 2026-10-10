# 共享知识库

共享知识库是一份放在公开仓库里的 Markdown 资料。Aharou 会在启动时把它同步到手机本地，AI 回答「这个怎么用 / 有没有讲过」这类问题时，先在里面查一遍再答。

## 它是怎么工作的

- 源头是一个 Git 仓库，默认指向 `520huxiangli/aharou-kb`（主仓在 `cnb.cool/huxiangli/aharou-kb`，GitHub 上是它的镜像）。仓库里按主题分目录放 Markdown 文件。
- 每次打开 Aharou，应用在后台把仓库里的文档同步到本地私有目录。同步失败不影响使用，会沿用上一次同步到的内容。
- AI 需要查资料时调用 `knowledge_search`，按关键词在本地副本里检索，返回命中的标题、路径和一段上下文。

## 在设置里管理

**设置 → AI 配置 → 共享知识库**：

- 「同步全部」把每个源的最新文档拉到本机；每个源也有一行，右侧可单独同步。
- 每行显示已同步篇数与仓库地址；自己加的源还能移除。
- 底部填仓库地址（`owner/repo` 或完整链接）就能加一个新源，支持 GitHub / Gitee / GitLab 以及自建 Gitea 系实例。

## 怎么确认它同步上了

- 在容器里看 `~/.aharou/knowledge/`，应该能看到按源分目录的 Markdown 文件。
- 直接问 AI：「在知识库里查一下 xxx」，它会走 `knowledge_search`。
- 日志里搜 `KnowledgeRepository`，能看到每次同步写入与移除的篇数。

## 换一个内置知识库

出场自带的源清单来自仓库里的 `data/knowledge.json`（内置兜底在 `assets/knowledge.json`）：

```json
{
  "sources": {
    "aharou-kb": {
      "name": { "zh": "Aharou 共享知识库", "en": "Aharou Knowledge Base" },
      "host": "github",
      "repo": "520huxiangli/aharou-kb",
      "branch": "master",
      "path": ""
    }
  }
}
```

- `repo` 是 `owner/仓库名`，`branch` 是分支，`path` 用来只同步仓库里的某个子目录（留空表示整仓）。
- `host` 按托管平台填 `github` / `gitee` / `gitlab`，自建实例填域名。
- 改完推到仓库即可，不必发新版本——App 每次启动都会对一次这个清单。

## 出错了怎么办

- **同步不动**：多半是网络或加速节点不通，换个网络再启动一次；本地旧副本还在，AI 仍能查到。
- **查到的是旧内容**：同步发生在启动时，刚推上去的改动要等下一次启动。
- **查不到**：确认关键词确实出现在文档正文里。检索是关键词匹配（中文按子串），不是语义搜索，换个更贴近原文的词再试。
