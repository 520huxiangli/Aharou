package com.aharou.feature.git.domain

import com.aharou.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * git 失败输出的友好文案映射（[GitErrorMessage.friendlyRes]）。
 * 每个模式匹配分支一个用例，入参尽量贴近真实 git stderr 文案，
 * 未命中时返回 null（由调用方回退原始输出，保留排查信息）。
 */
class GitErrorMessageTest {

    // ── 删除/切换分支 ───────────────────────────────────────────

    @Test
    fun cannotDeleteBranch_checkedOut_returnsSwitchHint() {
        val raw = "error: Cannot delete branch 'main' checked out at '/root/workspace'"
        assertEquals(R.string.git_error_delete_checked_out_branch, GitErrorMessage.friendlyRes(raw))
    }

    @Test
    fun branchNotFullyMerged_returnsSafeDeleteHint() {
        val raw = "error: The branch 'feat/old' is not fully merged.\n" +
            "If you are sure you want to delete it, run 'git branch -D feat/old'."
        assertEquals(R.string.git_error_branch_not_merged, GitErrorMessage.friendlyRes(raw))
    }

    @Test
    fun invalidReference_returnsNotFoundHint() {
        val raw = "fatal: invalid reference: nope"
        assertEquals(R.string.git_error_invalid_reference, GitErrorMessage.friendlyRes(raw))
    }

    @Test
    fun localChangesWouldBeOverwritten_returnsStashHint() {
        val raw = "error: Your local changes to the following files would be overwritten by checkout:\n" +
            "\tapp/src/Main.kt\nPlease commit your changes or stash them before you switch branches."
        assertEquals(R.string.git_error_local_changes_overwritten, GitErrorMessage.friendlyRes(raw))
    }

    // ── 远程不可用 / 无权限 ─────────────────────────────────────

    @Test
    fun notGitRepository_returnsRemoteUnavailable() {
        val raw = "fatal: 'origin' does not appear to be a git repository"
        assertEquals(R.string.git_error_remote_unavailable, GitErrorMessage.friendlyRes(raw))
    }

    @Test
    fun couldNotReadFromRemote_returnsRemoteUnavailable() {
        val raw = "fatal: Could not read from remote repository.\n\nPlease make sure you have the correct access rights and the repository exists."
        assertEquals(R.string.git_error_remote_unavailable, GitErrorMessage.friendlyRes(raw))
    }

    // ── 鉴权失败 ────────────────────────────────────────────────

    @Test
    fun authenticationFailed_returnsCredentialHint() {
        val raw = "fatal: Authentication failed for 'https://github.com/user/repo.git/'"
        assertEquals(R.string.git_error_auth_failed, GitErrorMessage.friendlyRes(raw))
    }

    @Test
    fun invalidUsernameOrToken_returnsCredentialHint() {
        val raw = "fatal: Invalid username or token."
        assertEquals(R.string.git_error_auth_failed, GitErrorMessage.friendlyRes(raw))
    }

    @Test
    fun permissionDenied_returnsCredentialHint() {
        // 注意：真实场景通常紧跟 "Could not read from remote repository"，但该文案会先命中
        // 更靠前的「远程不可用」分支（friendlyRes 按顺序匹配），故此处仅保留 Permission denied 本身。
        val raw = "git@github.com: Permission denied (publickey)."
        assertEquals(R.string.git_error_auth_failed, GitErrorMessage.friendlyRes(raw))
    }

    @Test
    fun passwordAuthenticationNotSupported_returnsCredentialHint() {
        val raw = "remote: Password authentication is not supported for GitHub."
        assertEquals(R.string.git_error_auth_failed, GitErrorMessage.friendlyRes(raw))
    }

    // ── 仓库不存在 ──────────────────────────────────────────────

    @Test
    fun repositoryNotFound_returnsRepoMissingHint() {
        val raw = "remote: Repository not found.\nfatal: repository 'https://github.com/user/nope.git/' not found"
        assertEquals(R.string.git_error_repo_not_found, GitErrorMessage.friendlyRes(raw))
    }

    @Test
    fun repositoryAndNotFoundCombined_returnsRepoMissingHint() {
        val raw = "fatal: repository 'https://git.example.com/team/deleted.git' not found"
        assertEquals(R.string.git_error_repo_not_found, GitErrorMessage.friendlyRes(raw))
    }

    // ── 拉取 / 推送 ─────────────────────────────────────────────

    @Test
    fun noTrackingInformation_returnsPullHint() {
        val raw = "There is no tracking information for the current branch.\n" +
            "Please specify which branch you want to rebase against."
        assertEquals(R.string.git_error_no_tracking_branch, GitErrorMessage.friendlyRes(raw))
    }

    @Test
    fun noConfiguredPushDestination_returnsRemoteHint() {
        val raw = "fatal: No configured push destination.\n" +
            "Either specify the URL from the command-line or configure a remote repository using\n" +
            "\n    git remote add <name> <url>\n\nand then push using the remote name"
        assertEquals(R.string.git_error_no_push_destination, GitErrorMessage.friendlyRes(raw))
    }

    @Test
    fun nonFastForward_returnsPullFirstHint() {
        val raw = "To https://github.com/user/repo.git\n" +
            " ! [rejected]        main -> main (non-fast-forward)\n" +
            "error: failed to push some refs to 'https://github.com/user/repo.git'"
        assertEquals(R.string.git_error_push_rejected, GitErrorMessage.friendlyRes(raw))
    }

    @Test
    fun rejectedFetchFirst_returnsPullFirstHint() {
        val raw = " ! [rejected]        main -> main (fetch first)"
        assertEquals(R.string.git_error_push_rejected, GitErrorMessage.friendlyRes(raw))
    }

    @Test
    fun updatesRejectedRemoteContains_returnsPullFirstHint() {
        val raw = "hint: Updates were rejected because the remote contains work that you do not\n" +
            "hint: have locally. This is usually caused by another repository pushing\n" +
            "hint: to the same ref."
        assertEquals(R.string.git_error_push_rejected, GitErrorMessage.friendlyRes(raw))
    }

    // ── 提交署名 ────────────────────────────────────────────────

    @Test
    fun pleaseTellMeWhoYouAre_returnsIdentityHint() {
        val raw = "*** Please tell me who you are.\n\nRun\n\n  git config --global user.email \"you@example.com\"\n  git config --global user.name \"Your Name\""
        assertEquals(R.string.git_error_identity_missing, GitErrorMessage.friendlyRes(raw))
    }

    @Test
    fun authorIdentityUnknown_returnsIdentityHint() {
        val raw = "fatal: Author identity unknown"
        assertEquals(R.string.git_error_identity_missing, GitErrorMessage.friendlyRes(raw))
    }

    // ── 未命中 / 空输入 ─────────────────────────────────────────

    @Test
    fun noMatch_returnsNull() {
        val raw = "error: pathspec 'missing.txt' did not match any file(s) known to git"
        assertNull(GitErrorMessage.friendlyRes(raw))
    }

    @Test
    fun emptyInput_returnsNull() {
        assertNull(GitErrorMessage.friendlyRes(""))
    }

    @Test
    fun blankInput_returnsNull() {
        assertNull(GitErrorMessage.friendlyRes("  \n\t "))
    }
}