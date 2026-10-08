package com.bruh.angel.ui.chat

import com.bruh.angel.linuxenv.LinuxDistro
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
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.bruh.angel.R
import com.bruh.angel.agent.RequestedTool
import com.bruh.angel.model.Provider
import com.bruh.angel.model.ToolNames
import com.bruh.angel.ui.components.Callout
import com.bruh.angel.ui.components.Hint
import com.bruh.angel.ui.components.InfoRow
import com.bruh.angel.ui.theme.mono

/**
 * Approval prompt for a model-requested tool call (policy "Ask"). The command is editable before
 * it runs. Dismissing the sheet denies the request.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApprovalSheet(tool: RequestedTool, provider: Provider, distro: LinuxDistro, onDecision: (ChatViewModel.Approval) -> Unit, mcpLabel: Pair<String, String>? = null) {
    var command by remember(tool) { mutableStateOf(tool.command) }
    var always by remember(tool) { mutableStateOf(false) }
    val isScreen = tool.name == ToolNames.SCREEN
    val isMcp = mcpLabel != null
    val deny = { onDecision(ChatViewModel.Approval(false, command, false)) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = deny,
        sheetState = sheetState,
        sheetMaxWidth = 640.dp,
        containerColor = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .navigationBarsPadding()
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(toolIcon(tool.name)), contentDescription = null, modifier = Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        when {
                            isMcp -> "Angel wants to use ${mcpLabel!!.second}"
                            isScreen -> "Angel wants to read the screen"
                            else -> "Angel wants to run a ${ToolNames.label(tool.name)} command"
                        },
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        when {
                            isMcp -> "A tool from the ${mcpLabel!!.first} MCP server. What it can reach depends on that server."
                            else -> when (tool.name) {
                            ToolNames.ANDROID -> "Runs as the ADB shell user (uid 2000). It can read and change anything ADB can."
                            ToolNames.LINUX -> "Runs inside ${distro.label} (proot). Not a sandbox; phone storage is at /sdcard."
                            else -> "Reads the text and layout of whatever is on screen right now."
                            }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (isScreen) {
                if (command.isNotBlank()) Text("Filter: $command", style = MaterialTheme.typography.bodyMedium.mono())
            } else {
                OutlinedTextField(
                    value = command,
                    onValueChange = { command = it },
                    label = { Text(if (isMcp) "Arguments (JSON)" else "Command") },
                    supportingText = { Text("You can edit it before it runs.") },
                    textStyle = MaterialTheme.typography.bodyMedium.mono(),
                    minLines = 2,
                    maxLines = 8,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Hint(
                if (provider.isLocal) "The result stays on this phone (on-device model)."
                else "The result is sent to ${provider.label}."
            )

            InfoRow(
                title = if (isMcp) "Always allow ${mcpLabel!!.first}" else "Always allow ${ToolNames.label(tool.name)}",
                supporting = if (isMcp) "Every tool of this server runs without asking. Change anytime in MCP servers." else "Future requests run without asking. Change anytime in Settings.",
                trailing = { Switch(checked = always, onCheckedChange = { always = it }) },
                onClick = { always = !always }
            )
            if (always) Callout("Allowed tools run model-requested actions without confirmation.", danger = tool.name == ToolNames.ANDROID)

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = deny, modifier = Modifier.weight(1f)) { Text("Deny") }
                Button(
                    enabled = isScreen || isMcp || command.isNotBlank(),
                    onClick = { onDecision(ChatViewModel.Approval(true, command, always)) },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(painterResource(R.drawable.ic_play), contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Run")
                }
            }
            Spacer(Modifier.size(8.dp))
        }
    }
}
