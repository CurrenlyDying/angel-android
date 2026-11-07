package com.bruh.angel.model

import android.content.Context
import com.bruh.angel.linuxenv.LinuxDistro
import com.bruh.angel.mcp.McpKind
import com.bruh.angel.mcp.McpRuntime
import com.bruh.angel.mcp.McpServerConfig

/**
 * The built-in skills that ship with the app. Each one's instructions live in
 * `assets/skills/<id>/SKILL.md`; its optional setup script (CLI tools installed into the distro) and
 * MCP connector bundle are defined here. User state (enabled / set-up / installed connector ids) is
 * persisted separately in [ProviderSettingsStore]; bodies are re-read from assets on every load so
 * app updates can revise them.
 */
object SkillCatalog {
    const val REVERSE_ENGINEERING = "reverse-engineering"

    /**
     * Finishes any apt install that a previous (killed) run left half-done. Without this, every
     * `apt-get install` aborts with "dpkg was interrupted". Mirrors the guard in McpRecipes.installScript.
     */
    private const val APT_REPAIR =
        "[ -z \"\$(dpkg --audit 2>&1)\" ] || dpkg --configure -a || apt-get -o APT::Sandbox::User=root -o DPkg::Lock::Timeout=180 -f install -y -qq"

    private data class Builtin(
        val id: String,
        val assetPath: String,
        /** CLI tools installed into [LinuxDistro], or null when the skill needs none. */
        val setup: ((LinuxDistro) -> String)?,
        /** MCP servers the skill brings (built for the target distro so install scripts match it). */
        val connectors: (Set<String>, LinuxDistro) -> List<McpServerConfig>
    )

    private val builtins = listOf(
        Builtin(REVERSE_ENGINEERING, "skills/$REVERSE_ENGINEERING/SKILL.md", ::reverseEngineeringSetup, ::reverseEngineeringConnectors)
    )

    val ids: List<String> get() = builtins.map { it.id }

    /** Loads the built-in skills, reading each body from assets. Skills whose asset is missing are skipped. */
    fun load(context: Context): List<Skill> = builtins.mapNotNull { b ->
        val text = runCatching { context.assets.open(b.assetPath).bufferedReader().use { it.readText() } }.getOrNull()
            ?: return@mapNotNull null
        Skill.parseSkillMd(text)?.copy(id = b.id, builtin = true, source = "builtin")
    }

    /** True when the skill installs anything (CLI tools or MCP connectors) that a setup pass handles. */
    fun needsSetup(id: String, distro: LinuxDistro): Boolean =
        setupScript(id, distro) != null || connectors(id, emptySet(), distro).isNotEmpty()

    /** Setup script for [id] in [distro], or null when the skill installs no CLI tools. */
    fun setupScript(id: String, distro: LinuxDistro): String? =
        builtins.firstOrNull { it.id == id }?.setup?.invoke(distro)

    /** MCP connector configs the skill brings, with ids avoiding [taken], built for [distro]. */
    fun connectors(id: String, taken: Set<String>, distro: LinuxDistro): List<McpServerConfig> =
        builtins.firstOrNull { it.id == id }?.connectors?.invoke(taken, distro) ?: emptyList()

    fun isSetUp(skill: Skill, distro: LinuxDistro, servers: List<McpServerConfig>): Boolean =
        distro.id in skill.setupDistros && connectors(skill.id, emptySet(), distro).all { recipe ->
            servers.any { it.id in skill.connectorIds && it.installedDistro == distro.id &&
                it.name == recipe.name && it.command == recipe.command }
        }

    /** Reuse this skill's server on retries, without moving servers installed in another distro. */
    fun reusableConnector(skill: Skill, recipe: McpServerConfig, distro: LinuxDistro, servers: List<McpServerConfig>): McpServerConfig? =
        servers.firstOrNull { it.id in skill.connectorIds && it.name == recipe.name && it.command == recipe.command &&
            (it.installedDistro == distro.id || (it.installedDistro.isEmpty() && it.installScript == recipe.installScript)) }

    // ---- reverse-engineering ----------------------------------------------------------------

    /**
     * Installs the on-device RE toolchain from the distro's package manager (no unpinned downloads):
     * radare2 + binwalk + file everywhere, and apktool + jadx where the repos carry them (Kali does;
     * plain Debian/Ubuntu/Alpine may not, which is fine — the skill still works with what installed).
     */
    private fun reverseEngineeringSetup(distro: LinuxDistro): String = if (distro.aptBased) """
        set -e
        export DEBIAN_FRONTEND=noninteractive
        $APT_REPAIR
        echo "==> Updating package lists"
        apt-get -o APT::Sandbox::User=root -o DPkg::Lock::Timeout=180 update -qq
        echo "==> Installing core tools (radare2, binwalk, file)"
        apt-get -o APT::Sandbox::User=root -o DPkg::Lock::Timeout=180 install -y -qq --no-install-recommends radare2 binwalk file unzip
        echo "==> Installing APK tools (apktool, jadx)"
        apt-get -o APT::Sandbox::User=root -o DPkg::Lock::Timeout=180 install -y -qq --no-install-recommends apktool jadx || echo "==> apktool/jadx are not in this distro's repos (Kali has them) — skipping"
        echo "==> Done"
    """.trimIndent() else """
        set -e
        echo "==> Installing core tools (radare2, binwalk, file)"
        apk add --no-cache radare2 binwalk file unzip
        echo "==> apktool/jadx are not packaged for Alpine; use a Debian/Ubuntu/Kali project for APK decompilation"
        echo "==> Done"
    """.trimIndent()

    internal fun connectorBuildDependencies(distro: LinuxDistro): String = if (distro.aptBased)
            "if ! command -v r2pm >/dev/null 2>&1 || ! command -v git >/dev/null 2>&1 || " +
                "! command -v cc >/dev/null 2>&1 || ! command -v make >/dev/null 2>&1 || " +
                "! command -v pkg-config >/dev/null 2>&1 || ! pkg-config --exists r_core || [ ! -f /usr/include/stdio.h ]; then " +
                "echo \"==> Installing radare2 connector build dependencies\"; export DEBIAN_FRONTEND=noninteractive; " +
                "apt-get -o APT::Sandbox::User=root -o DPkg::Lock::Timeout=180 update -qq; " +
                "apt-get -o APT::Sandbox::User=root -o DPkg::Lock::Timeout=180 install -y -qq --no-install-recommends " +
                "radare2 git build-essential pkg-config libradare2-dev; fi"
        else
            "if ! command -v r2pm >/dev/null 2>&1 || ! command -v git >/dev/null 2>&1 || " +
                "! command -v cc >/dev/null 2>&1 || ! command -v make >/dev/null 2>&1 || " +
                "! command -v pkg-config >/dev/null 2>&1 || ! pkg-config --exists r_core || [ ! -f /usr/include/stdio.h ]; then " +
                "echo \"==> Installing radare2 connector build dependencies\"; " +
                "apk add --no-cache radare2 radare2-dev git build-base pkgconf; fi"

    /** Pinned r2mcp release compatible with the distro's radare2 development API. */
    private fun reverseEngineeringConnectors(taken: Set<String>, distro: LinuxDistro): List<McpServerConfig> {
        val front = if (distro.aptBased) "export DEBIAN_FRONTEND=noninteractive" else ":"
        val repair = if (distro.aptBased) APT_REPAIR else ":"
        val install = """
            set -e
            $front
            $repair
            ${connectorBuildDependencies(distro)}
            echo "==> Building r2mcp 1.8.0 (optional; radare2 CLI works regardless)"
            src="${'$'}HOME/.angel/mcp/radare2-mcp-1.8.0"
            if [ ! -d "${'$'}src/.git" ]; then
                git clone --depth 1 --branch 1.8.0 https://github.com/radareorg/radare2-mcp.git "${'$'}src"
            fi
            [ "${'$'}(git -C "${'$'}src" rev-parse HEAD)" = ad8e484a769b42c50737a6a97dbbaac5061f5ee0 ] || {
                echo "The r2mcp source does not match the pinned release." >&2
                exit 1
            }
            cd "${'$'}src"
            ./configure
            make
            make user-install
            r2pm -r r2mcp -h >/dev/null
            echo "==> Done"
        """.trimIndent()
        return listOf(
            McpServerConfig(
                id = McpServerConfig.slug("radare2", taken),
                name = "radare2",
                kind = McpKind.CUSTOM,
                runtime = McpRuntime.LINUX,
                installScript = install,
                command = "r2pm -r r2mcp",
                description = "radare2 reverse-engineering tools (open/analyze binaries, disassemble, read memory) over MCP.",
                policy = ToolPolicy.ASK
            )
        )
    }
}
