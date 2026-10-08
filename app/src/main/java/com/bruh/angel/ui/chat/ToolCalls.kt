package com.bruh.angel.ui.chat

import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bruh.angel.R
import com.bruh.angel.model.ChatItem
import com.bruh.angel.model.ToolNames
import com.bruh.angel.ui.theme.AngelTheme

/** Exit codes the agent loop reserves for non-execution outcomes (see ChatViewModel). */
private const val EXIT_DENIED = 126
private const val EXIT_CANCELLED = 130
private const val EXIT_KILLED = 137

enum class ToolStatus { RUNNING, OK, FAILED, DENIED, CANCELLED }

/** Presentation of a persisted [ChatItem.ToolCall]: tool name, bare command and status. */
data class ToolView(val tool: String, val command: String, val status: ToolStatus, val call: ChatItem.ToolCall) {
    val toolLabel: String get() = ToolNames.label(tool)

    /** Short tag used in list rows. */
    val toolTag: String get() = when (tool) {
        ToolNames.ANDROID -> "android"
        ToolNames.LINUX -> "linux"
        ToolNames.SCREEN -> "screen"
        else -> if (tool.startsWith("mcp__")) "mcp" else tool
    }

    val statusLabel: String get() = when (status) {
        ToolStatus.RUNNING -> "Running"
        ToolStatus.OK -> "Done"
        ToolStatus.FAILED -> "Exit ${call.exitCode}"
        ToolStatus.DENIED -> "Not run"
        ToolStatus.CANCELLED -> if (call.exitCode == EXIT_KILLED) "Killed" else "Stopped"
    }
}

/** The stored command is "<tool>: <command>" (or just "read_screen"); split it back for display. */
fun ChatItem.ToolCall.view(): ToolView {
    val stored = command
    val mcpName = Regex("mcp__[a-z0-9-]+__[A-Za-z0-9_-]+").find(stored)?.takeIf { it.range.first == 0 && (stored.length == it.value.length || stored[it.value.length] == ':') }?.value
    val tool = mcpName ?: ToolNames.all.firstOrNull { stored == it || stored.startsWith("$it:") } ?: "manual"
    val bare = when {
        stored == tool -> ""
        stored.startsWith("$tool:") -> stored.substring(tool.length + 1).trimStart()
        else -> stored
    }
    val status = when {
        running -> ToolStatus.RUNNING
        exitCode == 0 -> ToolStatus.OK
        exitCode == EXIT_DENIED -> ToolStatus.DENIED
        exitCode == EXIT_CANCELLED || exitCode == EXIT_KILLED -> ToolStatus.CANCELLED
        else -> ToolStatus.FAILED
    }
    return ToolView(tool, if (tool == ToolNames.SCREEN && bare.isEmpty()) "read screen" else bare, status, this)
}

@Composable
fun ToolStatus.color(): Color {
    val colors = AngelTheme.colors
    return when (this) {
        ToolStatus.RUNNING -> colors.warning
        ToolStatus.OK -> colors.success
        ToolStatus.FAILED -> colors.danger
        ToolStatus.DENIED -> MaterialTheme.colorScheme.onSurfaceVariant
        ToolStatus.CANCELLED -> MaterialTheme.colorScheme.onSurfaceVariant
    }
}

fun toolIcon(tool: String): Int = when (tool) {
    ToolNames.ANDROID -> R.drawable.ic_android
    ToolNames.LINUX -> R.drawable.ic_terminal
    ToolNames.SCREEN -> R.drawable.ic_visibility
    else -> R.drawable.ic_code
}

/** Status glyph: spinner while running, otherwise a check / cross / dash icon in the status color. */
@Composable
fun ToolStatusGlyph(status: ToolStatus, modifier: Modifier = Modifier, size: Dp = 18.dp) {
    val color = status.color()
    when (status) {
        ToolStatus.RUNNING -> CircularProgressIndicator(modifier = modifier.size(size), strokeWidth = 2.dp, color = color)
        else -> Icon(
            painter = painterResource(
                when (status) {
                    ToolStatus.OK -> R.drawable.ic_check_circle
                    ToolStatus.FAILED -> R.drawable.ic_cancel
                    ToolStatus.DENIED -> R.drawable.ic_block
                    else -> R.drawable.ic_block
                }
            ),
            contentDescription = when (status) {
                ToolStatus.OK -> "Succeeded"
                ToolStatus.FAILED -> "Failed"
                ToolStatus.DENIED -> "Not run"
                else -> "Stopped"
            },
            tint = color,
            modifier = modifier.size(size)
        )
    }
}
