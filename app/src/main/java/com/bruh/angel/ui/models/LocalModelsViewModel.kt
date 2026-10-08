package com.bruh.angel.ui.models

import android.app.ActivityManager
import android.app.Application
import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bruh.angel.local.HfFile
import com.bruh.angel.local.HfRepo
import com.bruh.angel.local.HuggingFace
import com.bruh.angel.local.LocalEngine
import com.bruh.angel.local.LocalModel
import com.bruh.angel.local.LocalModelStore
import com.bruh.angel.local.LocalParams
import com.bruh.angel.model.ProviderSettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** State for the Local models screen: installed models, Hugging Face browser, downloads, settings. */
class LocalModelsViewModel(application: Application) : AndroidViewModel(application) {
    private val settingsStore = ProviderSettingsStore(application)
    private val modelStore = LocalModelStore.get(application)

    val models: StateFlow<List<LocalModel>> = modelStore.models
    val engine: StateFlow<LocalEngine.State> = LocalEngine.state

    val params = MutableStateFlow(LocalParams())
    val hfToken = MutableStateFlow("")
    val allowMobileData = MutableStateFlow(false)

    val query = MutableStateFlow("")
    private val _repos = MutableStateFlow<List<HfRepo>>(emptyList())
    val repos = _repos.asStateFlow()
    private val _repo = MutableStateFlow<String?>(null)
    val repo = _repo.asStateFlow()
    private val _files = MutableStateFlow<List<HfFile>>(emptyList())
    val files = _files.asStateFlow()
    private val _searching = MutableStateFlow(false)
    val searching = _searching.asStateFlow()
    val message = MutableStateFlow("")
    private val _progress = MutableStateFlow<Map<String, HuggingFace.Progress>>(emptyMap())
    val progress = _progress.asStateFlow()
    private val _systemInfo = MutableStateFlow<String?>(null)
    val systemInfo = _systemInfo.asStateFlow()

    /** Total RAM, used to say whether a model will fit. */
    val totalRam: Long = ActivityManager.MemoryInfo().also {
        (application.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(it)
    }.totalMem

    init {
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    params.value = settingsStore.loadLocalParams()
                    hfToken.value = settingsStore.hfToken()
                }
            }
            _systemInfo.value = withContext(Dispatchers.IO) { LocalEngine.systemInfo(application) }
        }
        // Poll DownloadManager while downloads are running; verify finished ones.
        viewModelScope.launch {
            while (isActive) {
                val active = models.value.filter {
                    it.status == LocalModel.Status.DOWNLOADING || it.status == LocalModel.Status.VERIFYING
                }
                if (active.isNotEmpty()) {
                    _progress.value = withContext(Dispatchers.IO) {
                        active.mapNotNull { m -> HuggingFace.progress(application, m.downloadId)?.let { m.id to it } }.toMap()
                    }
                    runCatching { HuggingFace.reconcile(application) }
                }
                delay(1000)
            }
        }
    }

    enum class Fit(val label: String) { GOOD("Fits well"), TIGHT("Tight"), RISKY("Very tight"), TOO_BIG("Too large") }

    fun fit(size: Long): Fit = when {
        size <= 0 -> Fit.GOOD
        size <= totalRam * 0.35 -> Fit.GOOD
        size <= totalRam * 0.55 -> Fit.TIGHT
        size <= totalRam * 0.7 -> Fit.RISKY
        else -> Fit.TOO_BIG
    }

    private fun token() = hfToken.value.takeIf { it.isNotBlank() }

    fun search(text: String = query.value) {
        val q = text.trim()
        if (q.isEmpty() || _searching.value) return
        query.value = q
        // A pasted repo id or URL opens the repository directly.
        HuggingFace.normalizeRepo(q)?.takeIf { q.contains('/') }?.let { openRepo(it); return }
        _searching.value = true
        message.value = ""
        viewModelScope.launch {
            try {
                _repos.value = HuggingFace.search(q, token())
                _repo.value = null
                if (_repos.value.isEmpty()) message.value = "No GGUF models found for \"$q\"."
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message.value = e.message ?: "Search failed"
            } finally {
                _searching.value = false
            }
        }
    }

    fun openRepo(id: String) {
        if (_searching.value) return
        _searching.value = true
        message.value = ""
        viewModelScope.launch {
            try {
                val list = HuggingFace.files(id, token())
                _repo.value = id
                _files.value = list
                if (list.isEmpty()) message.value = "$id has no GGUF files."
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message.value = e.message ?: "Could not list files"
            } finally {
                _searching.value = false
            }
        }
    }

    fun closeRepo() {
        _repo.value = null
        _files.value = emptyList()
    }

    fun download(file: HfFile) {
        val id = _repo.value ?: return
        viewModelScope.launch {
            try {
                val model = HuggingFace.startDownload(getApplication(), id, file, token(), allowMobileData.value)
                message.value = "Downloading ${model.name} (${com.bruh.angel.local.formatBytes(file.size)}). " +
                    "It continues in the background; you'll get a notification."
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message.value = e.message ?: "Download failed to start"
            }
        }
    }

    fun cancelDownload(model: LocalModel) {
        HuggingFace.cancelDownload(getApplication(), model)
    }

    fun delete(model: LocalModel) {
        viewModelScope.launch {
            if (LocalEngine.isLoaded(model.id)) LocalEngine.unload()
            if (model.status == LocalModel.Status.DOWNLOADING && model.downloadId >= 0) {
                (getApplication<Application>().getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).remove(model.downloadId)
            }
            withContext(Dispatchers.IO) { modelStore.remove(model.id) }
        }
    }

    fun import(uri: Uri) {
        viewModelScope.launch {
            try {
                val model = HuggingFace.importDocument(getApplication(), uri)
                message.value = "Added ${model.name}. It's used in place, so keep the file where it is."
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message.value = e.message ?: "Could not add the file"
            }
        }
    }

    fun updateParams(value: LocalParams) {
        params.value = value
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { settingsStore.saveLocalParams(value) } }
                .onFailure { message.value = it.message ?: "Could not save settings" }
        }
    }

    fun saveToken(value: String) {
        hfToken.value = value.trim()
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { settingsStore.setHfToken(value) } }
                .onSuccess { message.value = if (value.isBlank()) "Hugging Face token removed." else "Hugging Face token saved (encrypted)." }
                .onFailure { message.value = it.message ?: "Could not save the token" }
        }
    }

    fun unload() {
        viewModelScope.launch { LocalEngine.unload() }
    }
}

