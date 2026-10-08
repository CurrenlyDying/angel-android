package com.bruh.angel.ui.mcp

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.bruh.angel.R
import com.bruh.angel.linuxenv.LinuxDistro
import com.bruh.angel.mcp.McpCatalogEntry
import com.bruh.angel.mcp.McpInput
import com.bruh.angel.mcp.McpNet
import com.bruh.angel.mcp.McpPackage
import com.bruh.angel.mcp.McpRecipes
import com.bruh.angel.mcp.McpRuntime
import com.bruh.angel.ui.components.Callout
import com.bruh.angel.ui.components.Hint
import com.bruh.angel.ui.components.Pill
import com.bruh.angel.ui.theme.mono

/** What the user chose to do with a browsed server. */
sealed interface InstallChoice {
    data class Local(val pkg: McpPackage, val values: Map<String, String>) : InstallChoice
    data class Remote(val url: String, val headers: Map<String, String>) : InstallChoice
}

/** Registry header templates like "Bearer {token}" prefill "Bearer "; keep the prefix when only the token was typed. */
internal fun headerValue(input: McpInput, typed: String): String {
    val prefix = input.default
    val value = typed.trim()
    if (prefix.isBlank()) return value
    if (value == prefix.trim()) return ""
    return if (prefix.endsWith(" ") && !value.startsWith(prefix.trim())) prefix + value else value
}

@Composable
private fun InputField(input: McpInput, value: String, onChange: (String) -> Unit) {
    var reveal by rememberSaveable(input.key) { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(input.label + if (input.required) " *" else "") },
        supportingText = if (input.description.isNotBlank()) ({ Text(input.description.take(200)) }) else null,
        singleLine = input.kind != McpInput.Kind.ARG || input.secret,
        visualTransformation = if (input.secret && !reveal) PasswordVisualTransformation() else VisualTransformation.None,
        trailingIcon = if (input.secret) ({
            IconButton(onClick = { reveal = !reveal }) {
                Icon(painterResource(R.drawable.ic_visibility), contentDescription = if (reveal) "Hide" else "Show")
            }
        }) else null,
        modifier = Modifier.fillMaxWidth()
    )
}

/** Confirmation sheet shown before anything is installed or connected. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InstallSheet(
    entry: McpCatalogEntry,
    distro: LinuxDistro,
    distroInstalled: Boolean,
    shizukuConnected: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (InstallChoice) -> Unit
) {
    val pkg = entry.pkg
    val remote = entry.remote
    var useRemote by rememberSaveable(entry.id) { mutableStateOf(pkg == null) }
    val values = remember(entry.id) { mutableStateMapOf<String, String>().apply {
        (pkg?.inputs.orEmpty() + remote?.inputs.orEmpty()).forEach { put(it.key, it.default) }
    } }
    val inputs = if (useRemote) remote?.inputs.orEmpty() else pkg?.inputs.orEmpty()
    val missing = inputs.any { it.required && values[it.key].isNullOrBlank() }
    val canRun = useRemote || (distroInstalled && shizukuConnected)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        sheetMaxWidth = 640.dp, containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding().imePadding(),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Column {
                Text(entry.name, style = MaterialTheme.typography.titleLarge)
                Text("by ${entry.publisher}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (entry.description.isNotBlank()) Text(entry.description, style = MaterialTheme.typography.bodyMedium)

            if (pkg != null && remote != null) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    SegmentedButton(selected = !useRemote, onClick = { useRemote = false }, shape = SegmentedButtonDefaults.itemShape(0, 2),
                        label = { Text("Install on phone") })
                    SegmentedButton(selected = useRemote, onClick = { useRemote = true }, shape = SegmentedButtonDefaults.itemShape(1, 2),
                        label = { Text("Use hosted") })
                }
            }

            if (useRemote && remote != null) {
                Hint("Angel connects to ${remote.url}. Nothing is installed; your requests go to that server.")
            } else if (pkg != null) {
                Hint("Installs ${pkg.identifier} ${pkg.version} (${pkg.type.label}) into ${distro.label}" +
                    (if (entry.approxMb > 0) ", about ${entry.approxMb} MB" else "") +
                    ". It needs Node.js or Python, which Angel adds if missing. This can take several minutes on a phone.")
                if (!entry.curated) Callout("Community servers are third-party code. It runs inside Linux with access to files there and to /sdcard. Install only what you trust.")
                else if (entry.needsNetwork) Hint("This server makes network requests on behalf of the model.")
                if (!shizukuConnected) Callout("Connect Shizuku in Settings first.", danger = true)
                else if (!distroInstalled) Callout("${distro.label} is not installed yet. Set it up in Settings, then come back.", danger = true)
            }

            inputs.forEach { input -> InputField(input, values[input.key].orEmpty()) { values[input.key] = it } }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("Cancel") }
                Button(
                    enabled = canRun && !missing,
                    onClick = {
                        if (useRemote && remote != null) {
                            onConfirm(InstallChoice.Remote(remote.url, remote.inputs.filter { it.kind == McpInput.Kind.HEADER }
                                .associate { it.key to headerValue(it, values[it.key].orEmpty()) }.filterValues { it.isNotBlank() }))
                        } else if (pkg != null) onConfirm(InstallChoice.Local(pkg, values.toMap()))
                    },
                    modifier = Modifier.weight(1f)
                ) { Text(if (useRemote) "Connect" else "Install") }
            }
            Spacer(Modifier.size(8.dp))
        }
    }
}

/** "Connect to a server that is already running" form. */
@Composable
fun RemoteDialog(onDismiss: () -> Unit, onAdd: (name: String, url: String, headers: Map<String, String>) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var url by rememberSaveable { mutableStateOf("") }
    var token by rememberSaveable { mutableStateOf("") }
    var reveal by rememberSaveable { mutableStateOf(false) }
    var extra by rememberSaveable { mutableStateOf("") }
    val normalized = McpNet.normalize(url)
    val problem = if (url.isBlank()) null else McpNet.check(normalized)
    val headers = parseHeaders(extra, token)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Connect to a hosted server") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Hint("For an MCP server that is already running: on your network, in the cloud, or tunnelled to this phone.")
                OutlinedTextField(url, { url = it.trim() }, label = { Text("Address") }, singleLine = true,
                    placeholder = { Text("192.168.1.20:8000/mcp or https://host/mcp") },
                    isError = problem != null,
                    supportingText = { Text(problem ?: "Streamable HTTP and the older SSE transport both work.") },
                    modifier = Modifier.fillMaxWidth())
                OutlinedTextField(name, { name = it.take(60) }, label = { Text("Name (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(token, { token = it.trim() }, label = { Text("Bearer token (optional)") }, singleLine = true,
                    visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = { IconButton(onClick = { reveal = !reveal }) { Icon(painterResource(R.drawable.ic_visibility), contentDescription = if (reveal) "Hide" else "Show") } },
                    modifier = Modifier.fillMaxWidth())
                OutlinedTextField(extra, { extra = it }, label = { Text("Other headers (optional)") }, minLines = 2, maxLines = 4,
                    placeholder = { Text("X-Api-Key: value") }, textStyle = MaterialTheme.typography.bodySmall.mono(),
                    modifier = Modifier.fillMaxWidth())
                Hint("Plain http is accepted for private addresses only (192.168.x.x, 10.x, localhost, .local, Tailscale). Public servers must use https.")
            }
        },
        confirmButton = {
            TextButton(enabled = url.isNotBlank() && problem == null, onClick = { onAdd(name.trim(), normalized, headers) }) { Text("Connect") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/** Parses "Name: value" lines; a bearer [token] becomes the Authorization header unless set explicitly. */
internal fun parseHeaders(text: String, token: String): Map<String, String> {
    val out = LinkedHashMap<String, String>()
    if (token.isNotBlank()) out["Authorization"] = "Bearer $token"
    text.lineSequence().forEach { line ->
        val i = line.indexOf(':')
        if (i <= 0) return@forEach
        val key = line.substring(0, i).trim()
        val value = line.substring(i + 1).trim()
        if (key.matches(Regex("[A-Za-z0-9-]{1,64}")) && value.isNotEmpty()) out[key] = value
    }
    return out
}

/** Parses KEY=VALUE lines. */
internal fun parseEnv(text: String): Map<String, String> {
    val out = LinkedHashMap<String, String>()
    text.lineSequence().forEach { line ->
        val i = line.indexOf('=')
        if (i <= 0) return@forEach
        val key = line.substring(0, i).trim()
        if (key.matches(Regex("[A-Za-z_][A-Za-z0-9_]{0,63}"))) out[key] = line.substring(i + 1)
    }
    return out
}

/** "Add your own server" form: any command that speaks MCP on stdio, plus an optional install script. */
@Composable
fun CustomDialog(distro: LinuxDistro, onDismiss: () -> Unit, onAdd: (CustomServer) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var runtime by rememberSaveable { mutableStateOf(McpRuntime.LINUX.id) }
    var command by rememberSaveable { mutableStateOf("") }
    var install by rememberSaveable { mutableStateOf("") }
    var env by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add a custom server") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(name, { name = it.take(60) }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    McpRuntime.entries.forEachIndexed { i, r ->
                        SegmentedButton(selected = runtime == r.id, onClick = { runtime = r.id },
                            shape = SegmentedButtonDefaults.itemShape(i, McpRuntime.entries.size), label = { Text(r.label) })
                    }
                }
                OutlinedTextField(command, { command = it }, label = { Text("Start command") }, minLines = 2, maxLines = 5,
                    placeholder = { Text("npx -y some-mcp-server --flag") }, textStyle = MaterialTheme.typography.bodySmall.mono(),
                    supportingText = { Text("Must speak MCP over stdin/stdout. Runs as a shell command in ${if (runtime == "linux") distro.label else "the Android shell"}.") },
                    modifier = Modifier.fillMaxWidth())
                if (runtime == McpRuntime.LINUX.id) OutlinedTextField(install, { install = it }, label = { Text("Install script (optional)") },
                    minLines = 2, maxLines = 6, placeholder = { Text("apk add --no-cache git") }, textStyle = MaterialTheme.typography.bodySmall.mono(),
                    supportingText = { Text("Runs once, as root in ${distro.label}.") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(env, { env = it }, label = { Text("Environment (optional)") }, minLines = 2, maxLines = 5,
                    placeholder = { Text("API_KEY=secret") }, textStyle = MaterialTheme.typography.bodySmall.mono(), modifier = Modifier.fillMaxWidth())
                Callout("Custom servers run arbitrary commands. Only add commands you understand and trust.")
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank() && command.isNotBlank(), onClick = {
                onAdd(CustomServer(name.trim(), command, install, McpRuntime.fromId(runtime), parseEnv(env)))
            }) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

data class CustomServer(val name: String, val command: String, val install: String, val runtime: McpRuntime, val env: Map<String, String>)

/** Edit credentials of an installed server. */
@Composable
fun SecretsDialog(
    env: Map<String, String>, headers: Map<String, String>, onDismiss: () -> Unit,
    onSave: (Map<String, String>, Map<String, String>) -> Unit
) {
    var envText by rememberSaveable { mutableStateOf(env.entries.joinToString("\n") { "${it.key}=${it.value}" }) }
    var headerText by rememberSaveable { mutableStateOf(headers.entries.joinToString("\n") { "${it.key}: ${it.value}" }) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Credentials") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(envText, { envText = it }, label = { Text("Environment (KEY=VALUE)") }, minLines = 2, maxLines = 6,
                    textStyle = MaterialTheme.typography.bodySmall.mono(), modifier = Modifier.fillMaxWidth())
                if (headers.isNotEmpty() || env.isEmpty()) OutlinedTextField(headerText, { headerText = it }, label = { Text("Headers (Name: value)") },
                    minLines = 2, maxLines = 6, textStyle = MaterialTheme.typography.bodySmall.mono(), modifier = Modifier.fillMaxWidth())
                Hint("Stored encrypted on this phone. The server restarts when you save.")
            }
        },
        confirmButton = { TextButton(onClick = { onSave(parseEnv(envText), parseHeaders(headerText, "")) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
