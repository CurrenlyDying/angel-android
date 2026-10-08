package com.bruh.angel.mcp

import com.bruh.angel.linuxenv.LinuxDistro
import com.bruh.angel.model.ToolPolicy

/** Builds the shell scripts and commands that install and start servers. Untrusted strings are validated and quoted. */
object McpRecipes {
    private const val NODE_VERSION = "v24.21.0"
    private const val NODE_SHA256 = "724282c3b43aec998aa9527380465b45d229e021b58035f5f4f63095eabfe5d5"
    private val NPM_NAME = Regex("(@[a-z0-9~-][a-z0-9._~-]*/)?[a-z0-9~-][a-z0-9._~-]*")
    private val PYPI_NAME = Regex("[A-Za-z0-9][A-Za-z0-9._-]*")
    private val VERSION = Regex("[0-9A-Za-z][0-9A-Za-z.+_-]*")

    fun dir(id: String) = "/root/.angel/mcp/$id"

    /** POSIX single-quote escaping. */
    fun quote(value: String) = "'" + value.replace("'", "'\\''") + "'"

    fun validate(pkg: McpPackage): String? = when {
        pkg.version.length > 64 || !VERSION.matches(pkg.version) -> "Unsupported version '${pkg.version.take(40)}'"
        pkg.identifier.length > 214 -> "Package name too long"
        pkg.type == McpPackageType.NPM && !NPM_NAME.matches(pkg.identifier) -> "Unsupported npm package name"
        pkg.type == McpPackageType.PYPI && !PYPI_NAME.matches(pkg.identifier) -> "Unsupported PyPI package name"
        else -> null
    }

    /** Installs missing system packages, then the server itself into [dir]. Streams progress lines to stdout. */
    fun installScript(id: String, pkg: McpPackage, distro: LinuxDistro): String {
        require(validate(pkg) == null)
        val node = pkg.type == McpPackageType.NPM
        val dir = dir(id)
        val checks = buildList {
            if (node && distro.aptBased) {
                add("command -v node >/dev/null 2>&1 && command -v npm >/dev/null 2>&1 || fetchnode=1")
                add("[ \"\$fetchnode\" != 1 ] || command -v wget >/dev/null 2>&1 || need=\"\$need wget\"")
            } else if (node) add("command -v node >/dev/null 2>&1 && command -v npm >/dev/null 2>&1 || need=\"\$need nodejs npm\"")
            else if (distro.aptBased) add("python3 -c 'import venv, ensurepip' >/dev/null 2>&1 || need=\"\$need python3 python3-venv python3-pip\"")
            else add("python3 -c 'import venv, ensurepip' >/dev/null 2>&1 || need=\"\$need python3 py3-pip\"")
            pkg.system.filter { it.matches(Regex("[a-z0-9][a-z0-9+.-]*")) }.forEach {
                add("command -v $it >/dev/null 2>&1 || need=\"\$need $it\"")
            }
        }.joinToString("\n")
        val installPackages = if (distro.aptBased) {
            "export DEBIAN_FRONTEND=noninteractive\n" +
                "[ -z \"\$(dpkg --audit 2>&1)\" ] || dpkg --configure -a || apt-get -o APT::Sandbox::User=root -o DPkg::Lock::Timeout=180 -f install -y -qq\n" +
                "apt-get -o APT::Sandbox::User=root -o DPkg::Lock::Timeout=180 update -qq\n" +
                "apt-get -o APT::Sandbox::User=root -o DPkg::Lock::Timeout=180 install -y -qq --no-install-recommends ca-certificates \$need"
        } else {
            "apk add --no-cache ca-certificates \$need"
        }
        // Debian-family `npm` drags in ~370 packages, which takes 20+ minutes under proot; the official tarball takes about two.
        val fetchNode = if (node && distro.aptBased) """
            if [ "${'$'}fetchnode" = 1 ]; then
              echo "==> Downloading Node.js $NODE_VERSION"
              if wget -q -O /tmp/node.tgz https://nodejs.org/dist/$NODE_VERSION/node-$NODE_VERSION-linux-arm64.tar.gz && echo "$NODE_SHA256  /tmp/node.tgz" | sha256sum -c - >/dev/null 2>&1; then
                rm -rf /opt/node
                mkdir -p /opt/node
                tar -xzf /tmp/node.tgz -C /opt/node --strip-components=1
                for b in node npm npx; do ln -sf /opt/node/bin/${'$'}b /usr/local/bin/${'$'}b; done
                rm -f /tmp/node.tgz
              else
                echo "==> Download failed, using the distro's packages instead"
                rm -f /tmp/node.tgz
                need="nodejs npm"
                $installPackages
              fi
            fi
        """.trimIndent() else ":"
        val install = if (node) """
            [ -f package.json ] || npm init -y >/dev/null
            npm install --no-audit --no-fund --omit=dev --loglevel=http ${quote(pkg.identifier + "@" + pkg.version)}
            node -e ${quote("const p=require('./node_modules/'+process.argv[1]+'/package.json');const b=p.bin;const base=p.name.split('/').pop();" +
                "const n=typeof b==='string'?base:(b&&b[base]?base:Object.keys(b||{})[0]);if(!n){process.exit(3)}console.log(n)")} ${quote(pkg.identifier)} > entry.txt
        """.trimIndent() else """
            python3 -m venv venv
            ./venv/bin/pip install --no-cache-dir --disable-pip-version-check ${quote(pkg.identifier + "==" + pkg.version)}
            ./venv/bin/python -c ${quote("import importlib.metadata as m,sys;n=sys.argv[1];e=[x.name for x in m.distribution(n).entry_points if x.group=='console_scripts'];print(n if n in e else e[0])")} ${quote(pkg.identifier)} > entry.txt
        """.trimIndent()
        return """
            set -e
            exec 9>/tmp/.angel-packages.lock
            command -v flock >/dev/null 2>&1 && flock 9
            echo "==> Checking system packages"
            need=""
            fetchnode=0
            $checks
            if [ -n "${'$'}need" ]; then
              echo "==> Installing:${'$'}need"
              $installPackages
            fi
            $fetchNode
            command -v flock >/dev/null 2>&1 && flock -u 9
            rm -rf ${quote(dir)}
            mkdir -p ${quote(dir)}
            cd ${quote(dir)}
            echo "==> Installing ${pkg.identifier} ${pkg.version}"
            $install
            echo "==> Installed (starts as: ${'$'}(cat entry.txt))"
        """.trimIndent().replace("\n            ", "\n").trim()
    }

    /** The stdio start command for a package installed by [installScript]. */
    fun runCommand(id: String, pkg: McpPackage, values: Map<String, String>): String {
        val args = buildList {
            addAll(pkg.fixedArgs)
            pkg.inputs.filter { it.kind == McpInput.Kind.ARG }.forEach { input ->
                val value = values[input.key]?.trim().orEmpty().ifEmpty { input.default }
                if (value.isEmpty()) return@forEach
                if (input.flag.isNotEmpty()) { add(input.flag); add(value) }
                else value.split(Regex("\\s+")).filter { it.isNotEmpty() }.forEach { add(it) }
            }
        }.joinToString(" ") { quote(it) }
        val bin = if (pkg.type == McpPackageType.NPM) "./node_modules/.bin/" else "./venv/bin/"
        return "cd ${quote(dir(id))} && exec ${bin}\"\$(cat entry.txt)\" $args".trim()
    }

    /** KEY=VALUE pairs for the process (ENV inputs, falling back to defaults). */
    fun env(pkg: McpPackage, values: Map<String, String>): Map<String, String> =
        pkg.inputs.filter { it.kind == McpInput.Kind.ENV }.mapNotNull { input ->
            val value = values[input.key]?.trim().orEmpty().ifEmpty { input.default }
            if (value.isEmpty()) null else input.key to value
        }.toMap()

    /** Reverse of [source]: "npm|name|1.2.3" -> package, or null for hand-written servers. */
    fun packageFromSource(source: String): McpPackage? {
        val parts = source.split('|')
        if (parts.size != 3) return null
        val type = when (parts[0]) { "npm" -> McpPackageType.NPM; "pypi" -> McpPackageType.PYPI; else -> return null }
        return McpPackage(type, parts[1], parts[2]).takeIf { validate(it) == null }
    }

    fun source(pkg: McpPackage) = "${if (pkg.type == McpPackageType.NPM) "npm" else "pypi"}|${pkg.identifier}|${pkg.version}"

    fun removeScript(id: String) = "rm -rf ${quote(dir(id))}"
}

/** Builds the stored configuration for each way of adding a server. */
object McpSetup {
    /** A server installed from a catalog or registry package and run over stdio inside Linux. */
    fun fromPackage(entry: McpCatalogEntry, pkg: McpPackage, values: Map<String, String>, taken: Set<String>): McpServerConfig {
        val id = McpServerConfig.slug(if (entry.curated) entry.id else entry.name, taken)
        return McpServerConfig(
            id = id, name = entry.name.take(60), kind = McpKind.CATALOG, runtime = McpRuntime.LINUX,
            command = McpRecipes.runCommand(id, pkg, values), env = McpRecipes.env(pkg, values),
            description = entry.description.take(400), source = McpRecipes.source(pkg),
            policy = ToolPolicy.ASK
        )
    }

    /** A server that is already running somewhere, reached over HTTP(S). */
    fun remote(name: String, url: String, headers: Map<String, String>, taken: Set<String>, description: String = ""): McpServerConfig {
        val clean = McpNet.normalize(url)
        return McpServerConfig(
            id = McpServerConfig.slug(name.ifBlank { defaultName(clean) }, taken), name = name.ifBlank { defaultName(clean) }.take(60),
            kind = McpKind.REMOTE, url = clean, headers = headers.filterValues { it.isNotBlank() }, description = description.take(400)
        )
    }

    /** A hand-written stdio server: any command, optionally with an install script. */
    fun custom(
        name: String, command: String, installScript: String, runtime: McpRuntime, env: Map<String, String>, taken: Set<String>
    ) = McpServerConfig(
        id = McpServerConfig.slug(name, taken), name = name.take(60), kind = McpKind.CUSTOM, runtime = runtime,
        command = command.trim(), installScript = installScript.trim(), env = env
    )

    /** Host plus any explicit port, so two servers on one machine do not share a name. */
    fun defaultName(url: String): String {
        val uri = runCatching { java.net.URI(url) }.getOrNull() ?: return "remote"
        val host = uri.host.orEmpty().ifEmpty { return "remote" }
        return if (uri.port > 0) "$host:${uri.port}" else host
    }

}
