package com.bruh.angel.ui.project

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bruh.angel.R
import com.bruh.angel.linuxenv.LinuxDistro
import com.bruh.angel.ui.chat.ChatViewModel
import com.bruh.angel.ui.components.Callout
import com.bruh.angel.ui.components.Hint
import com.bruh.angel.ui.theme.mono
import kotlinx.coroutines.launch

/**
 * Picks a directory inside the Linux userland of [distro]. Browses via the agent shell
 * ([ChatViewModel.listDirectory]); directories are tappable, files are shown but dimmed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DirectoryBrowserSheet(
    vm: ChatViewModel,
    distro: LinuxDistro,
    start: String,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    var path by remember { mutableStateOf(start.ifBlank { "/root" }) }
    var entries by remember { mutableStateOf<List<ChatViewModel.DirEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var newFolder by remember { mutableStateOf(false) }

    fun open(target: String) {
        path = target
    }

    LaunchedEffect(path) {
        loading = true; error = null
        vm.listDirectory(path, distro)
            .onSuccess { entries = it }
            .onFailure { error = it.message ?: "Could not open folder"; entries = emptyList() }
        loading = false
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        sheetMaxWidth = 640.dp,
        containerColor = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Choose a folder", style = MaterialTheme.typography.titleLarge)
            Text(path, style = MaterialTheme.typography.bodySmall.mono(), color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { open("/root") }) { Text("/root") }
                OutlinedButton(onClick = { open("/sdcard") }) { Text("/sdcard") }
                OutlinedButton(onClick = { newFolder = true }) { Text("New folder") }
            }

            error?.let { Callout(it, danger = true) }

            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp), contentPadding = PaddingValues(vertical = 4.dp)) {
                if (path != "/") item(key = "..") {
                    Row(
                        Modifier.fillMaxWidth().clickable { open(path.trimEnd('/').substringBeforeLast('/').ifEmpty { "/" }) }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(painterResource(R.drawable.ic_arrow_upward), null, Modifier.size(20.dp))
                        Spacer(Modifier.width(12.dp))
                        Text("..", style = MaterialTheme.typography.bodyLarge)
                    }
                }
                items(entries, key = { it.name }) { e ->
                    Row(
                        Modifier.fillMaxWidth()
                            .then(if (e.isDir) Modifier.clickable { open(path.trimEnd('/') + "/" + e.name) } else Modifier)
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            painterResource(if (e.isDir) R.drawable.ic_folder_open else R.drawable.ic_code), null,
                            Modifier.size(20.dp),
                            tint = if (e.isDir) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            e.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            color = if (e.isDir) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (loading) item(key = "loading") {
                    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    }
                } else if (entries.isEmpty() && error == null) item(key = "empty") {
                    Hint("This folder is empty.", Modifier.padding(16.dp))
                }
            }

            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("Cancel") }
                Button(onClick = { onPick(path) }, modifier = Modifier.weight(1f)) { Text("Use this folder") }
            }
            Spacer(Modifier.size(4.dp))
        }
    }

    if (newFolder) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { newFolder = false },
            title = { Text("New folder") },
            text = {
                OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true,
                    placeholder = { Text("Folder name") })
            },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = {
                    val parent = path
                    val folder = name
                    newFolder = false
                    scope.launch {
                        vm.makeDirectory(parent, folder, distro)
                            .onSuccess { created -> open(created) }
                            .onFailure { vm.notice.value = it.message ?: "Could not create folder" }
                    }
                }) { Text("Create") }
            },
            dismissButton = { TextButton(onClick = { newFolder = false }) { Text("Cancel") } }
        )
    }
}
