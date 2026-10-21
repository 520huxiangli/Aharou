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

    // ── 仓库与工作区状态 ───────────────────────────────────────

    @Test
    fun notARepository_returnsInitHint() {
        val raw = "fatal: not a git repository (or any of the parent directories): .git"
        assertEquals(R.string.git_error_not_a_repository, GitErrorMessage.friendlyRes(raw))
    }

    @Test
    fun dubiousOwnership_returnsOwnershipHint() {
        val raw = "fatal: detected dubious ownership in repository at '/storage/emulated/0/repo'\n" +
            "To add an exception for this directory, call:\n\n" +
            "\tgit config --global --add safe.directory /storage/emulated/0/repo"
        assertEquals(R.string.git_error_repo_ownership, GitErrorMessage.friendlyRes(raw))
    }

    @Test
    fun indexLockExists_returnsLockedHint() {
        val raw = "fatal: Unable to create '/storage/emulated/0/repo/.git/index.lock': File exists.\n\n" +
            "Another git process seems to be running in this repository, e.g.\n" +
            "an editor opened by 'git commit'. Please make sure all processes\n" +
            "are terminated then try again."
        assertEquals(R.string.git_error_repo_locked, GitErrorMessage.friendlyRes(raw))
    }

    @Test
    fun cannotLockRef_returnsLockedHint() {
        val raw = "error: cannot lock ref 'refs/heads/main': is at 3f1c2ab but expected 9d4e5f6"
        assertEquals(R.string.git_error_repo_locked, GitErrorMessage.friendlyRes(raw))
    }

    @Test
    fun corruptIndex_returnsRebuildHint() {
        val raw = "error: bad index file sha1 signature\nfatal: index file corrupt"
        assertEquals(R.string.git_error_index_corrupt, GitErrorMessage.friendlyRes(raw))
    }

    @Test
    fun unableToCreateFile_returnsWriteFailedHint() {
        val raw = "error: unable to create file app/src/main/java/com/aharou/Main.kt: Permission denied"
        assertEquals(R.string.git_error_write_failed, GitErrorMessage.friendlyRes(raw))
    }

    @Test
    fun noSpaceLeftOnDevice_returnsWriteFailedHint() {
        val raw = "fatal: write error: No space left on device"
        assertEquals(R.string.git_error_write_failed, GitErrorMessage.friendlyRes(raw))
    }

    @Test
    fun readOnlyFileSystem_returnsWriteFailedHint() {
        val raw = "error: unable to write file .git/objects/ab/cdef0123: Read-only file system"
        assertEquals(R.string.git_error_write_failed, GitErrorMessage.friendlyRes(raw))
    }

    // ── 分支 / 标签 ────────────────────────────────────────────

    @Test
    fun createDuplicateBranch_returnsExistsHint() {
        val raw = "fatal: a branch named 'feat/login' already exists"
        assertEquals(R.string.git_error_branch_already_exists, GitErrorMessage.friendlyRes(raw))
    }

    @Test
    fun invalidBranchName_returnsNamingHint() {
        val raw = "fatal: 'feat login' is not a valid branch name"
        assertEquals(R.string.git_error_invalid_branch_name, GitErrorMessage.friendlyRes(raw))
    }

    @Test
    fun createDuplicateTag_returnsExistsHint() {
        val raw = "fatal: tag 'v1.0.0' already exists"
        assertEquals(R.string.git_error_tag_already_exists, GitErrorMessage.friendlyRes(raw))
    }

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
    fun pathspecDidNotMatch_returnsPathHint() {
        val raw = "error: pathspec 'missing.txt' did not match any file(s) known to git"
        assertEquals(R.string.git_error_pathspec_not_found, GitErrorMessage.friendlyRes(raw))
    }

    @Test
    fun untrackedWouldBeOverwritten_returnsMoveOrRemoveHint() {
        val raw = "error: The following untracked working tree files would be overwritten by checkout:\n" +
            "\tnotes.txt\nPlease move or remove them before you switch branches."
        assertEquals(R.string.git_error_untracked_overwritten, GitErrorMessage.friendlyRes(raw))
    }

    @Test
    fun localChangesWouldBeOverwritten_returnsStashHint() {
        val raw = "error: Your local changes to the following files would be overwritten by checkout:\n" +
            "\tapp/src/Main.kt\nPlease commit your changes or stash them before you switch branches."
        assertEquals(R.string.git_error_local_changes_overwritten, GitErrorMessage.friendlyRes(raw))
    }

    @Test
    fun detachedHead_returnsSwitchBranchHint() {
        val raw = "fatal: You are not currently on a branch.\n" +
            "To push the history leading to the current (detached HEAD)\n" +
            "state now, use\n\n    git push origin HEAD:<name-of-remote-branch>"
        assertEquals(R.string.git_error_detached_head, GitErrorMessage.friendlyRes(raw))
    }

    // ── 合并 ──────────────────────────────────────────────────

    @Test
    fun mergeConflict_returnsResolveHint() {
        val raw = "Auto-merging app/src/Main.kt\n" +
            "CONFLICT (content): Merge conflict in app/src/Main.kt\n" +
            "Automatic merge failed; fix conflicts and then commit the result."
        assertEquals(R.string.git_error_merge_conflict, GitErrorMessage.friendlyRes(raw))
    }

    @Test
    fun unmergedFiles_returnsFinishMergeHint() {
        val raw = "error: Committing is not possible because you have unmerged files.\n" +
            "hint: Fix them up in the work tree, and then use 'git add/rm <file>'\n" +
            "hint: as appropriate to mark resolution and make a commit."
        assertEquals(R.string.git_error_merge_unresolved, GitErrorMessage.friendlyRes(raw))
    }

    @Test
    fun unrelatedHistories_returnsHistoryHint() {
        val raw = "fatal: refusing to merge unrelated histories"
        assertEquals(R.string.git_error_unrelated_histories, GitErrorMessage.friendlyRes(raw))
    }

    @Test
    fun notSomethingWeCanMerge_returnsInvalidTargetHint() {
        val raw = "merge: feat/deleted - not something we can merge"
        assertEquals(R.string.git_error_not_mergeable_ref, GitErrorMessage.friendlyRes(raw))
    }

    @Test
    fun fastForwardImpossible_returnsRegularMergeHint() {
        val raw = "fatal: Not possible to fast-forward, aborting."
        assertEquals(R.string.git_error_fast_forward_impossible, GitErrorMessage.friendlyRes(raw))
    }

    @Test
    fun localChangesBlockMerge_returnsMergeStashHint() {
        val raw = "error: Your local changes to the following files would be overwritten by merge:\n" +
            "\tapp/src/Main.kt\nPlease commit your changes or stash them before you merge.\nAborting"
        assertEquals(R.string.git_error_local_changes_merge, GitErrorMessage.friendlyRes(raw))
    }

    // ── 提交 ──────────────────────────────────────────────────

    @Test
    fun nothingToCommit_returnsNoChangesHint() {
        val raw = "On branch main\nnothing to commit, working tree clean"
        assertEquals(R.string.git_error_nothing_to_commit, GitErrorMessage.friendlyRes(raw))
    }

    @Test
    fun emptyCommitMessage_returnsMessageHint() {
        val raw = "Aborting commit due to empty commit message."
        assertEquals(R.string.git_error_empty_commit_message, GitErrorMessage.friendlyRes(raw))
    }

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

    // ── 拉取 / 推送 ────────────────────────────────────────────

    @Test
    fun noTrackingInformation_returnsPullHint() {
        val raw = "There is no tracking information for the current branch.\n" +
            "Please specify which branch you want to rebase against."
        assertEquals(R.string.git_error_no_tracking_branch, GitErrorMessage.friendlyRes(raw))
    }

    @Test
    fun noUpstreamBranch_returnsSetUpstreamHint() {
        val raw = "fatal: The current branch feat/login has no upstream branch.\n" +
            "To push the current branch and set the remote as upstream, use\n\n" +
            "    git push --set-upstream origin feat/login"
        assertEquals(R.string.git_error_no_upstream, GitErrorMessage.friendlyRes(raw))
    }

    @Test
    fun pullWithoutBranch_returnsBranchHint() {
        val raw = "You asked to pull from the remote 'origin', but did not specify\n" +
            "a branch. Because this is not the default configured remote\n" +
            "for your current branch, you must specify a branch on the command line."
        assertEquals(R.string.git_error_pull_branch_unspecified, GitErrorMessage.friendlyRes(raw))
    }

    @Test
    fun noConfiguredPushDestination_returnsRemoteHint() {
        val raw = "fatal: No configured push destination.\n" +
            "Either specify the URL from the command-line or configure a remote repository using\n" +
            "\n    git remote add <name> <url>\n\nand then push using the remote name"
        assertEquals(R.string.git_error_no_push_destination, GitErrorMessage.friendlyRes(raw))
    }

    @Test
    fun divergentBranches_returnsPullFirstHint() {
        val raw = "hint: You have divergent branches and need to specify how to reconcile them.\n" +
            "hint: You can do so by running one of the following commands sometime before\n" +
            "hint: your next pull:"
        assertEquals(R.string.git_error_divergent_branches, GitErrorMessage.friendlyRes(raw))
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

    @Test
    fun protectedBranchHookDeclined_returnsProtectionHint() {
        val raw = "remote: error: GH006: Protected branch update failed for refs/heads/main.\n" +
            "remote: error: Required status check \"ci\" is expected.\n" +
            "To https://github.com/user/repo.git\n" +
            " ! [remote rejected] main -> main (protected branch hook declined)\n" +
            "error: failed to push some refs to 'https://github.com/user/repo.git'"
        assertEquals(R.string.git_error_remote_hook_declined, GitErrorMessage.friendlyRes(raw))
    }

    @Test
    fun preReceiveHookDeclined_returnsProtectionHint() {
        val raw = "remote: pre-receive hook declined\n" +
            " ! [remote rejected] feat/big -> feat/big (pre-receive hook declined)"
        assertEquals(R.string.git_error_remote_hook_declined, GitErrorMessage.friendlyRes(raw))
    }

    @Test
    fun deleteMissingRemoteBranch_returnsRemoteRefHint() {
        val raw = "error: unable to delete 'feat/old': remote ref does not exist\n" +
            "error: failed to push some refs to 'https://github.com/user/repo.git'"
        assertEquals(R.string.git_error_remote_branch_missing, GitErrorMessage.friendlyRes(raw))
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
    fun invalidUsernameOrPassword_returnsCredentialHint() {
        val raw = "remote: Invalid username or password."
        assertEquals(R.string.git_error_auth_failed, GitErrorMessage.friendlyRes(raw))
    }

    @Test
    fun accessDenied_returnsCredentialHint() {
        val raw = "remote: HTTP Basic: Access denied. If a password was provided for Git authentication"
        assertEquals(R.string.git_error_auth_failed, GitErrorMessage.friendlyRes(raw))
    }

    @Test
    fun permissionDenied_returnsCredentialHint() {
        // SSH 公钥被拒：真实场景通常紧跟 "Could not read from remote repository"，但该文案会先命中
        // 更靠前的「远程不可用」分支（friendlyRes 按顺序匹配），故此处仅保留 Permission denied 本身。
        val raw = "git@github.com: Permission denied (publickey)."
        assertEquals(R.string.git_error_auth_failed, GitErrorMessage.friendlyRes(raw))
    }

    @Test
    fun passwordAuthenticationNotSupported_returnsCredentialHint() {
        val raw = "remote: Password authentication is not supported for GitHub."
        assertEquals(R.string.git_error_auth_failed, GitErrorMessage.friendlyRes(raw))
    }

    @Test
    fun terminalPromptsDisabled_returnsCredentialsMissingHint() {
        val raw = "fatal: could not read Username for 'https://github.com': terminal prompts disabled"
        assertEquals(R.string.git_error_credentials_missing, GitErrorMessage.friendlyRes(raw))
    }

    @Test
    fun httpErrorStatus_returnsPermissionHint() {
        val raw = "fatal: unable to access 'https://github.com/user/repo.git/': " +
            "The requested URL returned error: 403"
        assertEquals(R.string.git_error_http_status, GitErrorMessage.friendlyRes(raw))
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

    // ── 网络 / TLS ─────────────────────────────────────────────

    @Test
    fun couldNotResolveHost_returnsDnsHint() {
        val raw = "fatal: unable to access 'https://github.com/user/repo.git/': " +
            "Could not resolve host: github.com"
        assertEquals(R.string.git_error_dns_failed, GitErrorMessage.friendlyRes(raw))
    }

    @Test
    fun certificateVerificationFailed_returnsTlsHint() {
        val raw = "fatal: unable to access 'https://github.com/user/repo.git/': " +
            "server certificate verification failed. CAfile: none CRLfile: none"
        assertEquals(R.string.git_error_tls_failed, GitErrorMessage.friendlyRes(raw))
    }

    @Test
    fun gnutlsHandshake_returnsTlsHint() {
        val raw = "fatal: unable to access 'https://git.example.com/team/repo.git/': " +
            "gnutls_handshake() failed: The TLS connection was non-properly terminated."
        assertEquals(R.string.git_error_tls_failed, GitErrorMessage.friendlyRes(raw))
    }

    @Test
    fun connectionTimedOut_returnsNetworkHint() {
        val raw = "fatal: unable to access 'https://github.com/user/repo.git/': " +
            "Failed to connect to github.com port 443 after 21046 ms: Connection timed out"
        assertEquals(R.string.git_error_connection_failed, GitErrorMessage.friendlyRes(raw))
    }

    @Test
    fun remoteEndHungUp_returnsRetryHint() {
        val raw = "fatal: the remote end hung up unexpectedly\n" +
            "error: failed to push some refs to 'https://github.com/user/repo.git'"
        assertEquals(R.string.git_error_connection_interrupted, GitErrorMessage.friendlyRes(raw))
    }

    @Test
    fun earlyEof_returnsRetryHint() {
        val raw = "fatal: early EOF\nfatal: the remote end hung up unexpectedly\nfatal: index-pack failed"
        assertEquals(R.string.git_error_connection_interrupted, GitErrorMessage.friendlyRes(raw))
    }

    @Test
    fun unableToAccessGeneric_returnsAccessHint() {
        val raw = "fatal: unable to access 'https://git.example.com/team/repo.git/': " +
            "Empty reply from server"
        assertEquals(R.string.git_error_unable_to_access, GitErrorMessage.friendlyRes(raw))
    }

    // ── 克隆 / LFS ────────────────────────────────────────────

    @Test
    fun cloneIntoNonEmptyDir_returnsEmptyDirHint() {
        val raw = "fatal: destination path 'repo' already exists and is not an empty directory."
        assertEquals(R.string.git_error_clone_dir_not_empty, GitErrorMessage.friendlyRes(raw))
    }

    @Test
    fun gitLfsUnavailable_returnsLfsHint() {
        val raw = "This repository is configured for Git LFS but 'git-lfs' was not found on your path. " +
            "If you no longer wish to use Git LFS, remove this hook by deleting the 'pre-push' file " +
            "in the hooks directory (set by 'core.hookspath'; usually '.git/hooks')."
        assertEquals(R.string.git_error_lfs_required, GitErrorMessage.friendlyRes(raw))
    }

    // ── 储藏 ──────────────────────────────────────────────────

    @Test
    fun noLocalChangesToStash_returnsStashHint() {
        val raw = "No local changes to save"
        assertEquals(R.string.git_error_no_local_changes_stash, GitErrorMessage.friendlyRes(raw))
    }

    @Test
    fun noStashEntries_returnsEmptyStashHint() {
        val raw = "No stash entries found."
        assertEquals(R.string.git_error_no_stash_entries, GitErrorMessage.friendlyRes(raw))
    }

    // ── 顺序敏感（同一输出同时命中两条规则时，必须命中更具体的那条）────

    @Test
    fun untrackedAndLocalChanges_bothMatch_untrackedWins() {
        // 两段错误都是 git 的原样输出，拼在一起构成「同一份输出命中两条规则」的场景：
        // 「未跟踪文件会被覆盖」必须排在「本地改动会被覆盖」之前，否则用户拿到的建议是
        // 「提交或储藏你的改动」，而实际要做的是移动/删除未跟踪文件。
        val raw = "error: Your local changes to the following files would be overwritten by checkout:\n" +
            "\tapp/src/Main.kt\n" +
            "error: The following untracked working tree files would be overwritten by checkout:\n" +
            "\tnotes.txt\nPlease move or remove them before you switch branches."
        assertEquals(R.string.git_error_untracked_overwritten, GitErrorMessage.friendlyRes(raw))
    }

    @Test
    fun writePermissionAndAuth_bothMatch_writeFailureWins() {
        // `fatal: could not create work tree dir 'repo': Permission denied` 里同时有写入失败的判据
        // 和鉴权规则的 `Permission denied`：必须命中写入失败，否则用户会被引导去查账号令牌，
        // 而真正的原因是目标目录不可写。
        val raw = "fatal: could not create work tree dir 'repo': Permission denied"
        assertEquals(R.string.git_error_write_failed, GitErrorMessage.friendlyRes(raw))
    }

    // ── 未命中 / 空输入 ─────────────────────────────────────────

    @Test
    fun noMatch_returnsNull() {
        val raw = "fatal: bad config line 1 in file .git/config"
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
