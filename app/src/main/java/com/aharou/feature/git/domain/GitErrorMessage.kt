package com.aharou.feature.git.domain

import androidx.annotation.StringRes
import com.aharou.R

/**
 * 把 git 命令的原始失败输出匹配成用户友好的提示。
 *
 * git 的 stderr 是面向命令行的英文技术文案，直接 toast 不友好。这里对常见失败场景做模式匹配，
 * 命中则返回对应文案的资源 id，未命中则返回 null（由调用方回退原始输出，保留排查信息）。
 * 匹配基于 git 稳定输出前缀，不依赖完整字符串相等，兼容路径、分支名等动态片段。
 *
 * 顺序即优先级：越具体的判据越靠前。两处关键顺序在对应分支上方有注释说明原因。
 */
object GitErrorMessage {

    /**
     * @param raw git 原始 stdout+stderr 文本（即 [GitCommandFailureException.output]）。
     * @return 命中场景的文案资源 id；无匹配时返回 null。
     */
    @StringRes
    fun friendlyRes(raw: String): Int? {
        if (raw.isBlank()) return null

        // ── 仓库与工作区状态 ───────────────────────────────────────
        // 当前目录不是仓库
        if (raw.contains("not a git repository"))
            return R.string.git_error_not_a_repository
        // 仓库目录所有者异常（外部存储、多用户环境、换设备后常见）
        if (raw.contains("detected dubious ownership"))
            return R.string.git_error_repo_ownership
        // 索引/引用被锁：并发 git 操作未结束或残留 .lock 文件
        if (raw.contains("Another git process seems to be running") ||
            raw.contains("cannot lock ref") ||
            raw.contains("Unable to create") && raw.contains(".lock"))
            return R.string.git_error_repo_locked
        // 索引损坏
        if (raw.contains("index file corrupt") || raw.contains("index file smaller than expected"))
            return R.string.git_error_index_corrupt
        // 文件写入失败：必须排在「鉴权失败」之前。这类失败的真实输出是
        // `fatal: could not create work tree dir 'repo': Permission denied`，同样带 `Permission denied`；
        // 若让鉴权规则先匹配，用户会被引导去查账号令牌，而真正的原因是存储目录不可写或空间不足。
        if (raw.contains("unable to create file") || raw.contains("unable to write file") ||
            raw.contains("unable to write objects") || raw.contains("unable to unlink") ||
            raw.contains("could not create work tree dir") || raw.contains("cannot create directory") ||
            raw.contains("No space left on device") || raw.contains("Read-only file system"))
            return R.string.git_error_write_failed

        // ── 分支 / 标签 ───────────────────────────────────────────
        // 删除当前所在分支
        if (raw.contains("Cannot delete branch") && raw.contains("checked out"))
            return R.string.git_error_delete_checked_out_branch
        // 删除未合并的分支
        if (raw.contains("is not fully merged"))
            return R.string.git_error_branch_not_merged
        // 新建分支重名
        if (raw.contains("branch named") && raw.contains("already exists"))
            return R.string.git_error_branch_already_exists
        // 分支名不合法
        if (raw.contains("not a valid branch name"))
            return R.string.git_error_invalid_branch_name
        // 新建标签重名
        if (raw.contains("tag '") && raw.contains("already exists"))
            return R.string.git_error_tag_already_exists

        // ── 检出 / 切换 ───────────────────────────────────────────
        // 切换到不存在的分支/引用
        if (raw.contains("invalid reference"))
            return R.string.git_error_invalid_reference
        // 指定的路径不存在或未被跟踪
        if (raw.contains("pathspec") && raw.contains("did not match"))
            return R.string.git_error_pathspec_not_found
        // 未跟踪文件会被覆盖：必须排在「本地改动会被覆盖」之前。checkout/merge/pull 的未跟踪冲突
        // 文案里同时出现 `would be overwritten`，先匹配宽泛的本地改动规则会给出「请提交你的改动」，
        // 而用户其实需要的是移动或删除这些未跟踪文件。
        if (raw.contains("untracked working tree files would be overwritten") ||
            raw.contains("Please move or remove them before"))
            return R.string.git_error_untracked_overwritten
        // 切换时本地改动会被覆盖（只匹配 checkout 措辞；`would be overwritten by merge` 归下面的合并规则，
        // 否则合并场景会被这条规则先吃掉，用户看到「切换分支」的错误引导）
        if (raw.contains("would be overwritten by checkout") ||
            raw.contains("Please commit your changes or stash them before you switch branches"))
            return R.string.git_error_local_changes_overwritten
        // 当前处于分离头指针
        if (raw.contains("You are not currently on a branch"))
            return R.string.git_error_detached_head

        // ── 合并 ─────────────────────────────────────────────────
        // 合并冲突
        if (raw.contains("CONFLICT") || raw.contains("Automatic merge failed"))
            return R.string.git_error_merge_conflict
        // 上一次合并尚未收尾（冲突未解决）
        if (raw.contains("You have not concluded your merge") ||
            raw.contains("unmerged files") ||
            raw.contains("Exiting because of an unresolved conflict"))
            return R.string.git_error_merge_unresolved
        // 无共同历史
        if (raw.contains("refusing to merge unrelated histories"))
            return R.string.git_error_unrelated_histories
        // 目标不是可合并的引用
        if (raw.contains("not something we can merge"))
            return R.string.git_error_not_mergeable_ref
        // 无法快进
        if (raw.contains("Not possible to fast-forward"))
            return R.string.git_error_fast_forward_impossible
        // 合并/拉取时本地有未提交改动
        if (raw.contains("Your local changes to the following files would be overwritten by merge"))
            return R.string.git_error_local_changes_merge

        // ── 提交 ─────────────────────────────────────────────────
        // 未配置署名
        if (raw.contains("Please tell me who you are") || raw.contains("Author identity unknown"))
            return R.string.git_error_identity_missing
        // 无改动可提交
        if (raw.contains("nothing to commit") || raw.contains("no changes added to commit"))
            return R.string.git_error_nothing_to_commit
        // 提交信息为空
        if (raw.contains("Aborting commit due to empty commit message"))
            return R.string.git_error_empty_commit_message

        // ── 拉取 / 推送 ───────────────────────────────────────────
        // 拉取时无上游跟踪信息
        if (raw.contains("no tracking information"))
            return R.string.git_error_no_tracking_branch
        // 当前分支无上游
        if (raw.contains("has no upstream branch"))
            return R.string.git_error_no_upstream
        // 拉取未指定分支
        if (raw.contains("You asked to pull from the remote") || raw.contains("but did not specify a branch"))
            return R.string.git_error_pull_branch_unspecified
        // 推送无配置目标
        if (raw.contains("No configured push destination"))
            return R.string.git_error_no_push_destination
        // 本地与远程分叉，需要先决定合并策略
        if (raw.contains("You have divergent branches"))
            return R.string.git_error_divergent_branches
        // 推送被拒（非快进 / 远程有更新）
        if (raw.contains("non-fast-forward") || raw.contains("rejected") && raw.contains("fetch first") ||
            raw.contains("Updates were rejected because the remote contains"))
            return R.string.git_error_push_rejected
        // 远程钩子 / 分支保护拒绝
        if (raw.contains("remote rejected") || raw.contains("hook declined") ||
            raw.contains("pre-receive hook declined") || raw.contains("protected branch"))
            return R.string.git_error_remote_hook_declined
        // 远程分支不存在
        if (raw.contains("remote ref does not exist") || raw.contains("couldn't find remote ref"))
            return R.string.git_error_remote_branch_missing

        // ── 远程仓库与鉴权 ────────────────────────────────────────
        // 远程仓库不可用 / 无权限
        if (raw.contains("does not appear to be a git repository") ||
            raw.contains("Could not read from remote repository"))
            return R.string.git_error_remote_unavailable
        // 无法交互式索要凭据（进程里没有终端，git 直接放弃询问）
        if (raw.contains("could not read Username") || raw.contains("could not read Password") ||
            raw.contains("terminal prompts disabled"))
            return R.string.git_error_credentials_missing
        // 鉴权失败（用户名/密码/token 错误或未配置）
        if (raw.contains("Authentication failed") ||
            raw.contains("Invalid username or ") ||
            raw.contains("Permission denied") ||
            raw.contains("Password authentication is not supported") ||
            raw.contains("Access denied") ||
            raw.contains("HTTP Basic"))
            return R.string.git_error_auth_failed
        // 服务端返回的 HTTP 错误状态
        if (raw.contains("The requested URL returned error"))
            return R.string.git_error_http_status
        // 仓库不存在或无权限访问（只认 git 的 `repository '...' not found` 形态，
        // 宽泛的 `repository` + `not found` 会吃掉 Git LFS 的 "'git-lfs' was not found" 之类无关输出）
        if (raw.contains("Repository not found") ||
            raw.contains("repository '") && raw.contains("not found"))
            return R.string.git_error_repo_not_found

        // ── 网络 / TLS ────────────────────────────────────────────
        // DNS 解析失败
        if (raw.contains("Could not resolve host"))
            return R.string.git_error_dns_failed
        // TLS / 证书：排在 `unable to access` 之前，否则会被这条通用前缀先吃掉
        if (raw.contains("server certificate verification failed") ||
            raw.contains("SSL certificate problem") ||
            raw.contains("gnutls_handshake"))
            return R.string.git_error_tls_failed
        // 连接失败 / 超时
        if (raw.contains("Failed to connect") || raw.contains("Connection timed out") ||
            raw.contains("Connection refused") || raw.contains("Operation timed out") ||
            raw.contains("Connection reset") || raw.contains("RPC failed"))
            return R.string.git_error_connection_failed
        // 连接中途断开
        if (raw.contains("remote end hung up unexpectedly") || raw.contains("early EOF"))
            return R.string.git_error_connection_interrupted
        // 远程访问失败的通用包装（更具体的原因都在上面）
        if (raw.contains("unable to access"))
            return R.string.git_error_unable_to_access

        // ── 初始化 / 克隆 ─────────────────────────────────────────
        // 克隆目录非空
        if (raw.contains("already exists and is not an empty directory"))
            return R.string.git_error_clone_dir_not_empty
        // 仓库依赖 Git LFS，但本机没有可用的 git-lfs
        if (raw.contains("smudge filter lfs") || raw.contains("git-lfs' was not found") ||
            raw.contains("Git LFS is not installed") ||
            raw.contains("configured for Git LFS"))
            return R.string.git_error_lfs_required

        // ── 储藏 ─────────────────────────────────────────────────
        // 没有可储藏的改动
        if (raw.contains("No local changes to save"))
            return R.string.git_error_no_local_changes_stash
        // 储藏列表为空
        if (raw.contains("No stash entries found"))
            return R.string.git_error_no_stash_entries
        return null
    }

    /** 鉴权类失败（用户名/密码/令牌缺失或错误）→ 可引导去「凭据与署名」页填写。 */
    fun isAuthFailure(raw: String): Boolean = listOf(
        "Authentication failed",
        "Invalid username or",
        "Permission denied",
        "Password authentication is not supported",
        "could not read Username",
        "could not read Password",
        "terminal prompts disabled",
        "Access denied",
        "not authorized"
    ).any { raw.contains(it) }

    /** 署名缺失（提交时）→ 可引导去填写用户名+邮箱。 */
    fun isIdentityFailure(raw: String): Boolean =
        raw.contains("Please tell me who you are") || raw.contains("Author identity unknown")
}
