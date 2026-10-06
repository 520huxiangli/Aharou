package com.aharou.feature.git.domain

import androidx.annotation.StringRes
import com.aharou.R

/**
 * 把 git 命令的原始失败输出匹配成用户友好的提示。
 *
 * git 的 stderr 是面向命令行的英文技术文案，直接 toast 不友好。这里对常见失败场景做模式匹配，
 * 命中则返回对应文案的资源 id，未命中则返回 null（由调用方回退原始输出，保留排查信息）。
 * 匹配基于 git 稳定输出前缀，不依赖完整字符串相等，兼容路径、分支名等动态片段。
 */
object GitErrorMessage {

    /**
     * @param raw git 原始 stdout+stderr 文本（即 [GitCommandFailureException.output]）。
     * @return 命中场景的文案资源 id；无匹配时返回 null。
     */
    @StringRes
    fun friendlyRes(raw: String): Int? {
        if (raw.isBlank()) return null
        // 删除当前所在分支
        if (raw.contains("Cannot delete branch") && raw.contains("checked out"))
            return R.string.git_error_delete_checked_out_branch
        // 删除未合并的分支
        if (raw.contains("is not fully merged"))
            return R.string.git_error_branch_not_merged
        // 切换到不存在的分支/引用
        if (raw.contains("invalid reference"))
            return R.string.git_error_invalid_reference
        // 切换时本地改动会被覆盖
        if (raw.contains("local changes") && raw.contains("would be overwritten"))
            return R.string.git_error_local_changes_overwritten
        // 远程仓库不可用 / 无权限
        if (raw.contains("does not appear to be a git repository") ||
            raw.contains("Could not read from remote repository"))
            return R.string.git_error_remote_unavailable
        // 克隆目录非空
        if (raw.contains("already exists and is not an empty directory"))
            return R.string.git_error_clone_dir_not_empty
        // 鉴权失败（用户名/密码/token 错误或未配置）
        if (raw.contains("Authentication failed") ||
            raw.contains("Invalid username or token") ||
            raw.contains("Permission denied") ||
            raw.contains("Password authentication is not supported"))
            return R.string.git_error_auth_failed
        // 仓库不存在或无权限访问
        if (raw.contains("Repository not found") || raw.contains("repository") && raw.contains("not found"))
            return R.string.git_error_repo_not_found
        // 拉取时无上游跟踪信息
        if (raw.contains("no tracking information"))
            return R.string.git_error_no_tracking_branch
        // 推送无配置目标
        if (raw.contains("No configured push destination"))
            return R.string.git_error_no_push_destination
        // 推送被拒（非快进 / 远程有更新）
        if (raw.contains("non-fast-forward") || raw.contains("rejected") && raw.contains("fetch first") ||
            raw.contains("Updates were rejected because the remote contains"))
            return R.string.git_error_push_rejected
        // 未配置署名（提交时）
        if (raw.contains("Please tell me who you are") || raw.contains("Author identity unknown"))
            return R.string.git_error_identity_missing
        // 合并冲突
        if (raw.contains("CONFLICT") || raw.contains("Automatic merge failed"))
            return R.string.git_error_merge_conflict
        // 合并/拉取时本地有未提交改动
        if (raw.contains("Your local changes to the following files would be overwritten by merge"))
            return R.string.git_error_local_changes_merge
        // 储藏相关
        if (raw.contains("No local changes to save"))
            return R.string.git_error_no_local_changes_stash
        return null
    }

    /** 鉴权类失败（用户名/密码/令牌缺失或错误）→ 可引导去「凭据与署名」页填写。 */
    fun isAuthFailure(raw: String): Boolean = listOf(
        "Authentication failed",
        "Invalid username or token",
        "Permission denied",
        "Password authentication is not supported",
        "could not read Username",
        "could not read Password",
        "terminal prompts disabled",
        "not authorized"
    ).any { raw.contains(it) }

    /** 署名缺失（提交时）→ 可引导去填写用户名+邮箱。 */
    fun isIdentityFailure(raw: String): Boolean =
        raw.contains("Please tell me who you are") || raw.contains("Author identity unknown")
}
