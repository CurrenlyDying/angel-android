package com.bruh.angel.ui.components

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@Composable
fun ThirdPartyNoticesDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val paragraphs = remember(context) {
        context.assets.open("THIRD_PARTY_NOTICES.txt").bufferedReader().use { it.readText() }
            .split("\n\n")
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Third-party licenses") },
        text = {
            SelectionContainer {
                LazyColumn(Modifier.heightIn(max = 480.dp)) {
                    items(paragraphs) { Text(it + "\n", style = MaterialTheme.typography.bodySmall) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}
