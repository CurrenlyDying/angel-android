package com.bruh.angel.ui.models

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bruh.angel.R
import com.bruh.angel.local.HfFile
import com.bruh.angel.local.LocalEngine
import com.bruh.angel.local.LocalModel
import com.bruh.angel.local.LocalModelStore
import com.bruh.angel.local.formatBytes
import com.bruh.angel.ui.components.HealthDot
import com.bruh.angel.ui.components.Hint
import com.bruh.angel.ui.components.InfoRow
import com.bruh.angel.ui.components.Pill
import com.bruh.angel.ui.components.SectionCard
import com.bruh.angel.ui.components.angelTopBarColors
import com.bruh.angel.ui.theme.AngelTheme
import com.bruh.angel.ui.theme.mono
import java.text.NumberFormat

/** Search terms that find popular instruction-tuned GGUF builds; repos change too often to hard-code. */
private val SuggestedSearches = listOf("Qwen3", "Llama 3.2 3B Instruct", "Gemma 3", "Phi-4 mini", "SmolLM3", "Mistral 7B Instruct")

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun LocalModelsScreen(
    activeModelId: String?,
    onUse: (String) -> Unit,
    onBack: () -> Unit,
    vm: LocalModelsViewModel = viewModel()
) {
    val models by vm.models.collectAsStateWithLifecycle()
    val engine by vm.engine.collectAsStateWithLifecycle()
    val params by vm.params.collectAsStateWithLifecycle()
    val token by vm.hfToken.collectAsStateWithLifecycle()
    val mobileData by vm.allowMobileData.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val repos by vm.repos.collectAsStateWithLifecycle()
    val repo by vm.repo.collectAsStateWithLifecycle()
    val files by vm.files.collectAsStateWithLifecycle()
    val searching by vm.searching.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val progress by vm.progress.collectAsStateWithLifecycle()
    val systemInfo by vm.systemInfo.collectAsStateWithLifecycle()

    var confirmDownload by remember { mutableStateOf<HfFile?>(null) }
    var confirmDelete by remember { mutableStateOf<LocalModel?>(null) }
    var tokenDraft by remember(token) { mutableStateOf(token) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::import) }

    BackHandler(enabled = repo != null) { vm.closeRepo() }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text("On-device models") },
                colors = angelTopBarColors(),
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "Back") }
                }
            )
        }
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(
                Modifier
                    .widthIn(max = 720.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Hint(
                    "GGUF models run entirely on this phone with llama.cpp; nothing you type leaves the device. " +
                        "Small instruction-tuned models (1–4B parameters, Q4_K_M) are the best fit."
                )
                if (message.isNotBlank()) Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)

                // ---- Engine + installed ----
                SectionCard(title = "Your models", icon = R.drawable.ic_memory, subtitle = engineSubtitle(engine), trailing = {
                    if (engine is LocalEngine.State.Ready) TextButton(onClick = vm::unload) { Text("Unload") }
                }) {
                    if (engine is LocalEngine.State.Loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                    (engine as? LocalEngine.State.Failed)?.let { Text(it.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    if (models.isEmpty()) Hint("No models yet. Download one from Hugging Face below or add a .gguf file you already have.")
                    models.sortedByDescending { it.added }.forEach { model ->
                        ModelRow(
                            model = model,
                            active = model.id == activeModelId,
                            loaded = (engine as? LocalEngine.State.Ready)?.modelId == model.id,
                            progress = progress[model.id],
                            fit = vm.fit(model.size),
                            onUse = { onUse(model.id) },
                            onCancel = { vm.cancelDownload(model) },
                            onDelete = { confirmDelete = model }
                        )
                    }
                    OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }) {
                        Icon(painterResource(R.drawable.ic_folder_open), contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Add a .gguf file from this phone")
                    }
                    Hint("Added files are used in place (not copied), so they take no extra space.")
                    systemInfo?.let {
                        Text("llama.cpp v0.5.0 · ${it.take(220)}", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                }

                // ---- Hugging Face ----
                SectionCard(title = "Download from Hugging Face", icon = R.drawable.ic_download) {
                    if (repo == null) {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { vm.query.value = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            placeholder = { Text("Search models, or paste owner/repo") },
                            leadingIcon = { Icon(painterResource(R.drawable.ic_search), contentDescription = null) },
                            trailingIcon = {
                                TextButton(enabled = !searching && query.isNotBlank(), onClick = { vm.search() }) { Text(if (searching) "…" else "Search") }
                            },
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { vm.search() }),
                            shape = MaterialTheme.shapes.medium
                        )
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            SuggestedSearches.forEach { s -> AssistChip(onClick = { vm.search(s) }, label = { Text(s) }) }
                        }
                        if (searching) LinearProgressIndicator(Modifier.fillMaxWidth())
                        repos.forEach { r ->
                            InfoRow(
                                title = r.id,
                                supporting = "${NumberFormat.getIntegerInstance().format(r.downloads)} downloads · ${r.likes} likes",
                                trailing = { Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null, tint = MaterialTheme.colorScheme.outline) },
                                onClick = { vm.openRepo(r.id) }
                            )
                        }
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = vm::closeRepo) { Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "Back to results") }
                            Text(repo.orEmpty(), style = MaterialTheme.typography.titleSmall.mono(), modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Hint("Pick one file. Q4_K_M is the usual balance of quality and size; Q8_0 is better but twice as big. Phone RAM: ${formatBytes(vm.totalRam)}.")
                        if (searching) LinearProgressIndicator(Modifier.fillMaxWidth())
                        files.forEach { file ->
                            val fit = vm.fit(file.size)
                            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(file.name, style = MaterialTheme.typography.bodyMedium.mono(), maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                        file.quant?.let { Pill(it, mono = true) }
                                        Pill(formatBytes(file.size))
                                        Pill(fit.label, color = fitColor(fit))
                                        if (file.split) Pill("multi-part (unsupported)", color = MaterialTheme.colorScheme.error)
                                    }
                                }
                                Spacer(Modifier.width(8.dp))
                                Button(
                                    enabled = !file.split && fit != LocalModelsViewModel.Fit.TOO_BIG,
                                    onClick = { confirmDownload = file }
                                ) { Text("Get") }
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                    InfoRow(
                        title = "Allow downloads over mobile data",
                        trailing = { Switch(checked = mobileData, onCheckedChange = { vm.allowMobileData.value = it }) },
                        onClick = { vm.allowMobileData.value = !mobileData }
                    )
                    OutlinedTextField(
                        value = tokenDraft,
                        onValueChange = { tokenDraft = it },
                        label = { Text("Hugging Face token (optional, for gated models)") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        trailingIcon = if (tokenDraft != token) {
                            { TextButton(onClick = { vm.saveToken(tokenDraft) }) { Text("Save") } }
                        } else null,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // ---- Inference settings ----
                SectionCard(title = "Inference settings", icon = R.drawable.ic_tune, subtitle = "Context size and threads apply when the model is next loaded.") {
                    Text("Context size", style = MaterialTheme.typography.titleSmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(2048, 4096, 8192, 16384).forEach { n ->
                            FilterChip(selected = params.contextSize == n, onClick = { vm.updateParams(params.copy(contextSize = n)) },
                                label = { Text("${n / 1024}K") })
                        }
                    }
                    Hint("More tokens remember more of the conversation but use more RAM.")
                    Text("CPU threads", style = MaterialTheme.typography.titleSmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(0, 2, 4, 6, 8).forEach { n ->
                            FilterChip(selected = params.threads == n, onClick = { vm.updateParams(params.copy(threads = n)) },
                                label = { Text(if (n == 0) "Auto (${params.copy(threads = 0).resolvedThreads()})" else "$n") })
                        }
                    }
                    Text("Max reply length", style = MaterialTheme.typography.titleSmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(512, 1024, 2048, 4096).forEach { n ->
                            FilterChip(selected = params.maxTokens == n, onClick = { vm.updateParams(params.copy(maxTokens = n)) },
                                label = { Text("$n") })
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Temperature", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        Pill("%.2f".format(params.temperature), mono = true)
                    }
                    Slider(
                        value = params.temperature,
                        onValueChange = { vm.params.value = params.copy(temperature = (it * 20).toInt() / 20f) },
                        onValueChangeFinished = { vm.updateParams(vm.params.value) },
                        valueRange = 0f..1.5f
                    )
                    Hint("Lower is more focused and better for tool use.")
                    InfoRow(
                        title = "Let reasoning models think first",
                        supporting = "Slower, sometimes smarter.",
                        trailing = { Switch(checked = params.thinking, onCheckedChange = { vm.updateParams(params.copy(thinking = it)) }) },
                        onClick = { vm.updateParams(params.copy(thinking = !params.thinking)) }
                    )
                }
                Spacer(Modifier.size(8.dp))
            }
        }
    }

    confirmDownload?.let { file ->
        val fit = vm.fit(file.size)
        AlertDialog(
            onDismissRequest = { confirmDownload = null },
            title = { Text("Download ${file.quant ?: "model"}?") },
            text = {
                Text(
                    "${file.name}\n\n${formatBytes(file.size)} from huggingface.co (${repo}). RAM fit: ${fit.label}.\n" +
                        (if (mobileData) "May use mobile data." else "Wi-Fi only.") +
                        " The file is checked against Hugging Face's SHA-256 before use. Check the model's license on its Hugging Face page."
                )
            },
            confirmButton = { TextButton(onClick = { vm.download(file); confirmDownload = null }) { Text("Download") } },
            dismissButton = { TextButton(onClick = { confirmDownload = null }) { Text("Cancel") } }
        )
    }
    confirmDelete?.let { model ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Remove ${model.name}?") },
            text = {
                Text(if (model.source == LocalModel.Source.LINKED) "Angel forgets this file; the file itself stays on your phone."
                else "Deletes the downloaded file (${formatBytes(model.size)}).")
            },
            confirmButton = { TextButton(onClick = { vm.delete(model); confirmDelete = null }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } }
        )
    }
}

private fun engineSubtitle(engine: LocalEngine.State): String = when (engine) {
    LocalEngine.State.Idle -> "Nothing loaded. The selected model loads on your first message."
    is LocalEngine.State.Loading -> "Loading ${engine.name}…"
    is LocalEngine.State.Ready -> "Loaded: ${engine.name} · ${LocalEngine.describe(engine.info)}"
    is LocalEngine.State.Failed -> "Last load failed"
}

@Composable
private fun fitColor(fit: LocalModelsViewModel.Fit): Color = when (fit) {
    LocalModelsViewModel.Fit.GOOD -> AngelTheme.colors.success
    LocalModelsViewModel.Fit.TIGHT -> AngelTheme.colors.warning
    else -> AngelTheme.colors.danger
}

@Composable
private fun ModelRow(
    model: LocalModel,
    active: Boolean,
    loaded: Boolean,
    progress: com.bruh.angel.local.HuggingFace.Progress?,
    fit: LocalModelsViewModel.Fit,
    onUse: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit
) {
    Surface(
        Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = if (active) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f) else MaterialTheme.colorScheme.surfaceContainer
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                HealthDot(ok = when { !model.ready -> null; active -> true; else -> null }, pulsing = loaded && !active)
                Spacer(Modifier.width(10.dp))
                Text(model.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (active) Pill("In use", color = MaterialTheme.colorScheme.primary, container = MaterialTheme.colorScheme.primaryContainer)
                else if (loaded) Pill("Loaded")
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                LocalModelStore.quantOf(model.name)?.let { Pill(it, mono = true) }
                if (model.size > 0) Pill(formatBytes(model.size))
                Pill(if (model.source == LocalModel.Source.LINKED) "file on phone" else model.repo ?: "downloaded")
                if (model.ready) Pill(fit.label, color = fitColor(fit))
            }
            when (model.status) {
                LocalModel.Status.DOWNLOADING -> {
                    val total = progress?.total?.takeIf { it > 0 } ?: model.size
                    val done = progress?.downloaded ?: 0
                    if (total > 0) LinearProgressIndicator(progress = { (done.toFloat() / total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                    else LinearProgressIndicator(Modifier.fillMaxWidth())
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Downloading ${formatBytes(done)} of ${formatBytes(total)}", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                        TextButton(onClick = onCancel) { Text("Cancel") }
                    }
                }
                LocalModel.Status.VERIFYING -> {
                    Text("Verifying checksum…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                LocalModel.Status.FAILED -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(model.error ?: "Failed", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = onDelete) { Text("Remove") }
                }
                LocalModel.Status.READY -> Row(verticalAlignment = Alignment.CenterVertically) {
                    if (!active) Button(onClick = onUse) { Text("Use") }
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = onDelete) { Icon(painterResource(R.drawable.ic_delete), contentDescription = "Remove", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
    }
}
