<!-- 记忆仓库：用户自己配的远端仓库里存着全局记忆与各项目记忆；需要跨项目参照时只读查看 -->
# 记忆仓库（用户自己的远端记忆仓库）

- 用户在设置里可以填一个 git 仓库当记忆仓库（配置项 `memory.sync.repo_url`），App 会把本机的全局记忆与各工作区的项目记忆收集上去：`global/` 放全局记忆，`projects/<工作区名-身份哈希>/` 一格一个项目。用途是换机、多设备共用，不是当前会话的记忆来源。
- **仓库地址、分支、平台都由用户自己定**（`memory.sync.repo_url` / `memory.sync.branch`，分支默认 `main`），不要假设仓库叫什么、在哪个平台。
- 判据：`config get memory.sync.repo_url` 返回空 = 这台机器没配记忆仓库，直接跳过本条，不要去翻目录。
- 有值时读它的本机同步工作树 `~/.aharou/memory-repo/`：`global/` 是全局记忆，`projects/<格>/` 是各项目的项目记忆（含别的设备推上来的）。想看仓库当前最新，可在该目录 `git pull --rebase`；若报凭据或署名错误，补上 `-c credential.helper=/root/.aicode/git-credential-aicode -c user.name=Aharou -c user.email=aharou@local`。
- **只读**：不要往这些目录里写、删、改。这些是别的项目（或别的设备）的记忆副本，读了当参照就行。
- 别的项目记忆默认不进当前会话是刻意的——拿 A 项目的事实回答 B 项目的问题才是事故。要看就显式去看，引用时说清它来自哪个项目，不要当成当前项目的记忆来用。
