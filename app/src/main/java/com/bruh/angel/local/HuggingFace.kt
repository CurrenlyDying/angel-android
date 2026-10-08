package com.bruh.angel.local

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

data class HfRepo(val id: String, val downloads: Long, val likes: Long)

data class HfFile(val path: String, val size: Long, val sha256: String?) {
    val name get() = path.substringAfterLast('/')
    val quant get() = LocalModelStore.quantOf(name)
    /** Multi-part GGUFs ("-00001-of-00003.gguf") need every part; not supported yet. */
    val split get() = Regex("-\\d{5}-of-\\d{5}\\.gguf$", RegexOption.IGNORE_CASE).containsMatchIn(path)
}

/** Hugging Face Hub client for finding and downloading GGUF models. */
object HuggingFace {
    private const val HUB = "https://huggingface.co"
    private val REPO = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,95}/[A-Za-z0-9][A-Za-z0-9._-]{0,95}$")

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(false) // we resolve redirects ourselves; tokens never follow to other hosts
            .build()
    }

    fun isRepoId(id: String) = REPO.matches(id.trim())

    /** Accepts "owner/name" or a huggingface.co URL. */
    fun normalizeRepo(input: String): String? {
        val text = input.trim().removePrefix("https://").removePrefix("http://").removePrefix("huggingface.co/").removePrefix("hf.co/")
        val repo = text.split('/').take(2).joinToString("/")
        return repo.takeIf { isRepoId(it) }
    }

    private fun get(url: HttpUrl, token: String?): String {
        val request = Request.Builder().url(url).apply {
            if (!token.isNullOrBlank()) header("Authorization", "Bearer ${token.trim()}")
        }.build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException(when (response.code) {
                    401, 403 -> "Hugging Face denied access (HTTP ${response.code}). Gated models need a token and accepting the license on huggingface.co."
                    404 -> "Not found on Hugging Face (HTTP 404)."
                    429 -> "Hugging Face rate limit reached; try again shortly."
                    else -> "Hugging Face HTTP ${response.code}"
                })
            }
            return response.body?.string().orEmpty()
        }
    }

    suspend fun search(query: String, token: String?): List<HfRepo> = withContext(Dispatchers.IO) {
        val url = "$HUB/api/models".toHttpUrl().newBuilder()
            .addQueryParameter("search", query.trim())
            .addQueryParameter("filter", "gguf")
            .addQueryParameter("sort", "downloads")
            .addQueryParameter("direction", "-1")
            .addQueryParameter("limit", "40")
            .build()
        parseRepos(get(url, token))
    }

    suspend fun files(repo: String, token: String?): List<HfFile> = withContext(Dispatchers.IO) {
        require(isRepoId(repo)) { "Invalid repository id" }
        val url = HUB.toHttpUrl().newBuilder().addPathSegment("api").addPathSegment("models")
            .addPathSegments(repo).addPathSegment("tree").addPathSegment("main")
            .addQueryParameter("recursive", "true").build()
        parseFiles(get(url, token))
    }

    fun parseRepos(json: String): List<HfRepo> {
        val array = JSONArray(json)
        return (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("id", o.optString("modelId"))
            if (!isRepoId(id)) null else HfRepo(id, o.optLong("downloads"), o.optLong("likes"))
        }
    }

    /** GGUF model files only (vision projectors "mmproj" are not chat models). Sorted by size. */
    fun parseFiles(json: String): List<HfFile> {
        val array = JSONArray(json)
        return (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            val path = o.optString("path")
            if (o.optString("type") != "file" || !path.endsWith(".gguf", ignoreCase = true)) return@mapNotNull null
            if (path.contains("mmproj", ignoreCase = true) || path.split('/').any { it == ".." }) return@mapNotNull null
            val lfs = o.optJSONObject("lfs")
            val sha = lfs?.optString("oid")?.removePrefix("sha256:")?.takeIf { it.matches(Regex("[0-9a-f]{64}")) }
            HfFile(path, lfs?.optLong("size")?.takeIf { it > 0 } ?: o.optLong("size"), sha)
        }.sortedBy { it.size }
    }

    /**
     * Resolves the file's download URL (HF redirects to a pre-signed CDN URL). The token is only sent
     * to huggingface.co; DownloadManager receives the signed URL and no credentials.
     */
    private fun resolve(repo: String, path: String, token: String?): Pair<String, String?> {
        val url = HUB.toHttpUrl().newBuilder().addPathSegments(repo).addPathSegment("resolve")
            .addPathSegment("main").addPathSegments(path).build()
        val request = Request.Builder().url(url).head().apply {
            if (!token.isNullOrBlank()) header("Authorization", "Bearer ${token.trim()}")
        }.build()
        http.newCall(request).execute().use { response ->
            val etag = (response.header("X-Linked-ETag") ?: response.header("ETag"))?.trim('"')
                ?.takeIf { it.matches(Regex("[0-9a-f]{64}")) }
            return when {
                response.isRedirect -> {
                    val location = response.header("Location") ?: throw IOException("Download redirect without location")
                    val target = response.request.url.resolve(location) ?: throw IOException("Bad download redirect")
                    if (target.scheme != "https") throw IOException("Refusing a non-HTTPS download location")
                    // A same-host relative redirect (renamed repo) still needs the token: follow once more.
                    if (target.host == "huggingface.co") resolveFollow(target, token, etag) else target.toString() to etag
                }
                response.isSuccessful -> url.toString() to etag
                response.code == 401 || response.code == 403 ->
                    throw IOException("This model is gated. Add a Hugging Face token and accept its license on huggingface.co.")
                else -> throw IOException("Hugging Face HTTP ${response.code}")
            }
        }
    }

    private fun resolveFollow(url: HttpUrl, token: String?, etag: String?): Pair<String, String?> {
        val request = Request.Builder().url(url).head().apply {
            if (!token.isNullOrBlank()) header("Authorization", "Bearer ${token.trim()}")
        }.build()
        http.newCall(request).execute().use { response ->
            val location = response.header("Location")
            if (response.isRedirect && location != null) {
                val target = response.request.url.resolve(location) ?: throw IOException("Bad download redirect")
                if (target.scheme != "https") throw IOException("Refusing a non-HTTPS download location")
                return target.toString() to (etag ?: response.header("X-Linked-ETag")?.trim('"'))
            }
            if (response.isSuccessful) return url.toString() to etag
            throw IOException("Hugging Face HTTP ${response.code}")
        }
    }

    /** Starts a background download (DownloadManager: survives app restarts, shows a notification). */
    suspend fun startDownload(context: Context, repo: String, file: HfFile, token: String?, allowMobileData: Boolean): LocalModel =
        withContext(Dispatchers.IO) {
            require(isRepoId(repo)) { "Invalid repository id" }
            require(!file.split) { "Split GGUF files (multiple parts) aren't supported yet. Pick a single-file quantization." }
            val store = LocalModelStore.get(context)
            val dir = store.directory
            val needed = file.size + 256L * 1024 * 1024
            if (dir.usableSpace < needed) {
                throw IOException("Not enough free storage: ${formatBytes(file.size)} needed, ${formatBytes(dir.usableSpace)} free.")
            }
            val (url, etag) = resolve(repo, file.path, token)
            val safeName = (repo.replace('/', '_') + "_" + file.name).replace(Regex("[^A-Za-z0-9._-]"), "_")
            val target = File(dir, safeName)
            target.delete()
            val request = DownloadManager.Request(Uri.parse(url))
                .setTitle(file.name)
                .setDescription("Angel model download · $repo")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setAllowedOverMetered(allowMobileData)
                .setAllowedOverRoaming(false)
                .setDestinationUri(Uri.fromFile(target))
            val id = (context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
            val model = LocalModel(
                id = LocalModelStore.newId(),
                name = file.name.removeSuffix(".gguf").removeSuffix(".GGUF"),
                source = LocalModel.Source.FILE,
                location = target.path,
                size = file.size,
                status = LocalModel.Status.DOWNLOADING,
                repo = repo,
                sha256 = file.sha256 ?: etag,
                downloadId = id
            )
            store.upsert(model)
            model
        }

    data class Progress(val downloaded: Long, val total: Long, val status: Int, val reason: Int)

    fun progress(context: Context, downloadId: Long): Progress? {
        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        manager.query(DownloadManager.Query().setFilterById(downloadId)).use { cursor ->
            if (cursor == null || !cursor.moveToFirst()) return null
            fun long(column: String) = cursor.getLong(cursor.getColumnIndexOrThrow(column))
            fun int(column: String) = cursor.getInt(cursor.getColumnIndexOrThrow(column))
            return Progress(
                long(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR),
                long(DownloadManager.COLUMN_TOTAL_SIZE_BYTES),
                int(DownloadManager.COLUMN_STATUS),
                int(DownloadManager.COLUMN_REASON)
            )
        }
    }

    fun cancelDownload(context: Context, model: LocalModel) {
        if (model.downloadId >= 0) {
            (context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).remove(model.downloadId)
        }
        LocalModelStore.get(context).remove(model.id)
    }

    private val reconcileLock = kotlinx.coroutines.sync.Mutex()

    /**
     * Moves finished downloads to READY after checking the GGUF header and SHA-256 (when Hugging Face
     * published one), and marks failures. Safe to call repeatedly (e.g. on app start).
     */
    suspend fun reconcile(context: Context) = withContext(Dispatchers.IO) {
        reconcileLock.lock()
        try {
            reconcileLocked(context)
        } finally {
            reconcileLock.unlock()
        }
    }

    private suspend fun reconcileLocked(context: Context) {
        val store = LocalModelStore.get(context)
        for (model in store.models.value.filter { it.status == LocalModel.Status.DOWNLOADING || it.status == LocalModel.Status.VERIFYING }) {
            val p = progress(context, model.downloadId)
            when {
                p == null && model.status == LocalModel.Status.DOWNLOADING ->
                    store.update(model.id) { it.copy(status = LocalModel.Status.FAILED, error = "Download was removed") }
                p == null || p.status == DownloadManager.STATUS_SUCCESSFUL -> verify(context, model)
                p.status == DownloadManager.STATUS_FAILED ->
                    store.update(model.id) { it.copy(status = LocalModel.Status.FAILED, error = "Download failed (code ${p.reason})") }
            }
        }
    }

    private suspend fun verify(context: Context, model: LocalModel) {
        val store = LocalModelStore.get(context)
        store.update(model.id) { it.copy(status = LocalModel.Status.VERIFYING) }
        val file = File(model.location)
        val error = try {
            when {
                !file.isFile -> "Downloaded file is missing"
                !file.inputStream().use { input -> LocalModelStore.isGguf(ByteArray(4).also { input.read(it) }) } -> "Not a GGUF file"
                model.sha256 != null && sha256(file) != model.sha256 -> "Checksum mismatch: the download is corrupt"
                else -> null
            }
        } catch (e: IOException) {
            e.message ?: "Could not verify the download"
        }
        if (error == null) {
            store.update(model.id) { it.copy(status = LocalModel.Status.READY, size = file.length(), error = null) }
        } else {
            file.delete()
            store.update(model.id) { it.copy(status = LocalModel.Status.FAILED, error = error) }
        }
    }

    suspend fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(1 shl 20)
        file.inputStream().use { input ->
            while (true) {
                currentCoroutineContext().ensureActive()
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** Adds a GGUF the user picked with the system file picker, loaded in place (not copied). */
    suspend fun importDocument(context: Context, uri: Uri): LocalModel = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        var name = "model.gguf"
        var size = -1L
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                c.getString(0)?.let { name = it }
                if (!c.isNull(1)) size = c.getLong(1)
            }
        }
        val header = ByteArray(4)
        resolver.openInputStream(uri)?.use { it.read(header) } ?: throw IOException("Can't read the selected file")
        if (!LocalModelStore.isGguf(header)) throw IOException("$name is not a GGUF model file.")
        if (Regex("-\\d{5}-of-\\d{5}\\.gguf$", RegexOption.IGNORE_CASE).containsMatchIn(name)) {
            throw IOException("Split GGUF files (multiple parts) aren't supported yet.")
        }
        // Keep read access across restarts so the model can be loaded in place.
        runCatching { resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        val model = LocalModel(
            id = LocalModelStore.newId(),
            name = name.removeSuffix(".gguf").removeSuffix(".GGUF"),
            source = LocalModel.Source.LINKED,
            location = uri.toString(),
            size = size
        )
        LocalModelStore.get(context).upsert(model)
        model
    }
}


