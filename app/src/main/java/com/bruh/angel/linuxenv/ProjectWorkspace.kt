package com.bruh.angel.linuxenv

/**
 * Shell snippets for a project's Git workspace: installing git on demand, cloning a repo, and the
 * read/mutating git commands the Changes screen runs. All untrusted values are single-quoted.
 */
object ProjectWorkspace {
    /** POSIX single-quote escaping. */
    fun quote(value: String) = "'" + value.replace("'", "'\\''") + "'"

    private val REPO = Regex("https://github\\.com/[A-Za-z0-9_.-]{1,39}/[A-Za-z0-9_.-]{1,100}(\\.git)?/?")

    /** Null when [url] is an acceptable GitHub HTTPS repo URL, otherwise a user-facing reason. */
    fun validateRepoUrl(url: String): String? = when {
        url.isBlank() -> "Enter a GitHub URL"
        url.length > 300 -> "URL is too long"
        !REPO.matches(url.trim()) -> "Use a URL like https://github.com/owner/repo"
        else -> null
    }

    private fun ensureGit(aptBased: Boolean): String = if (aptBased)
        "command -v git >/dev/null 2>&1 || { echo \"==> Installing git\"; export DEBIAN_FRONTEND=noninteractive; " +
            "apt-get -o APT::Sandbox::User=root update -qq; apt-get -o APT::Sandbox::User=root install -y -qq --no-install-recommends ca-certificates git; }"
    else
        "command -v git >/dev/null 2>&1 || { echo \"==> Installing git\"; apk add --no-cache git ca-certificates; }"

    /** Installs git if missing, then clones [repoUrl] into [workdir] (which must be empty or absent). */
    fun cloneScript(repoUrl: String, workdir: String, aptBased: Boolean): String {
        val url = repoUrl.trim()
        val parent = workdir.trimEnd('/').substringBeforeLast('/').ifEmpty { "/" }
        return """
            set -e
            ${ensureGit(aptBased)}
            mkdir -p ${quote(parent)}
            echo "==> Cloning $url"
            git clone --depth 1 ${quote(url)} ${quote(workdir)}
            echo "==> Done"
        """.trimIndent().replace("\n            ", "\n").trim()
    }

    fun ensureGitScript(aptBased: Boolean) = "set -e\n${ensureGit(aptBased)}\ngit --version"

    // ---- git commands (run with the project working directory as cwd) ------------------------

    // NUL-delimited paths preserve whitespace, newlines, Unicode and literal " -> " in names.
    const val STATUS = "git status --porcelain=v1 -z --untracked-files=all 2>&1"

    fun diff(path: String, staged: Boolean, untracked: Boolean, originalPath: String? = null): String = when {
        untracked -> "git --no-pager diff --no-color --no-index -- /dev/null ${quote(path)} 2>&1 || true"
        staged -> "git --no-pager diff --no-color --staged -- ${paths(path, originalPath)} 2>&1"
        else -> "git --no-pager diff --no-color -- ${quote(path)} 2>&1"
    }

    fun stage(path: String) = "git add -- ${quote(path)} 2>&1"
    private fun paths(path: String, originalPath: String?) = listOfNotNull(path, originalPath).distinct().joinToString(" ") { quote(it) }

    fun unstage(path: String, originalPath: String? = null): String {
        val paths = paths(path, originalPath)
        // A newly initialized repository has no HEAD to restore from. Remove only index entries.
        return "if git rev-parse --verify HEAD >/dev/null 2>&1; then git restore --staged -- $paths; " +
            "else git rm --cached -r -f -- $paths; fi 2>&1"
    }
    fun discard(path: String, untracked: Boolean) =
        if (untracked) "rm -rf -- ${quote(path)} 2>&1" else "git restore -- ${quote(path)} 2>&1"
    fun commit(message: String) =
        "git -c user.email=angel@localhost -c user.name=Angel commit -m ${quote(message)} 2>&1"

    /**
     * Parses `git status --porcelain=v1 -z` into one entry per changed path per area (a path can appear
     * both staged and unstaged). [StatusEntry.untracked] marks new files git is not tracking yet.
     */
    data class StatusEntry(val path: String, val staged: Boolean, val untracked: Boolean, val code: String, val originalPath: String? = null)

    fun parseStatus(output: String): List<StatusEntry> {
        require(output.isEmpty() || output.endsWith('\u0000')) {
            "Git status output was incomplete. Use the terminal to inspect this repository."
        }
        val out = mutableListOf<StatusEntry>()
        val records = output.split('\u0000')
        var index = 0
        while (index < records.lastIndex) {
            val raw = records[index++]
            if (raw.length < 4 || raw[2] != ' ') continue
            val x = raw[0]
            val y = raw[1]
            val path = raw.substring(3)
            // In -z output the destination comes first, followed by a separate source record.
            val originalPath = if (x == 'R' || x == 'C' || y == 'R' || y == 'C') records.getOrNull(index++) else null
            if (path.isEmpty()) continue
            if (x == '?' && y == '?') { out += StatusEntry(path, staged = false, untracked = true, code = "??"); continue }
            if (x != ' ' && x != '?') out += StatusEntry(path, staged = true, untracked = false, code = "$x ", originalPath = originalPath.takeIf { x == 'R' })
            if (y != ' ' && y != '?') out += StatusEntry(path, staged = false, untracked = false, code = " $y")
        }
        return out
    }
}
