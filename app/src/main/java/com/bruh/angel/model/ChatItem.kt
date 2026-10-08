// model: shared data types used across ui, agent, shizuku and linuxenv.
package com.bruh.angel.model

sealed interface ChatItem {
    data class UserMsg(val text: String) : ChatItem
    data class AgentMsg(val text: String) : ChatItem
    data class ToolCall(
        val command: String,
        val output: String,
        val exitCode: Int,
        val running: Boolean = false
    ) : ChatItem
}

