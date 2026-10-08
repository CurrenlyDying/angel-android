package com.bruh.angel.linuxenv

import android.content.Context
import android.os.Build
import android.os.ParcelFileDescriptor
import com.bruh.angel.shizuku.ShizukuBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class LinuxDownloader(private val context: Context, private val bridge: ShizukuBridge) {
    private val http = OkHttpClient.Builder().followRedirects(false).connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS).callTimeout(45, TimeUnit.MINUTES).build()

    /** [progress] gets a status line and, while a file is downloading, how far along it is (0..1). */
    suspend fun install(distro: LinuxDistro, progress: (String, Float?) -> Unit): String = withContext(Dispatchers.IO) {
        check(Build.SUPPORTED_ABIS.contains("arm64-v8a")) { "This Linux download supports ARM64 devices only" }
        check(context.cacheDir.usableSpace >= distro.downloadMb * 1_048_576L + 64_000_000) {
            "Not enough free space to download ${distro.label} (about ${distro.downloadMb} MB)"
        }
        val api = bridge.api()
        val artifacts = LinuxArtifacts.forDistro(distro)
        for (artifact in artifacts) {
            currentCoroutineContext().ensureActive()
            val what = if (artifact.name == "rootfs") "${distro.label} (${distro.downloadMb} MB)" else artifact.name
            progress("Downloading $what over HTTPS…", 0f)
            val file = File.createTempFile("linux-", ".archive", context.cacheDir)
            try {
                http.newCall(Request.Builder().url(artifact.url).build()).execute().use { response ->
                    check(response.isSuccessful) { "Download ${artifact.name}: HTTP ${response.code}" }
                    val digest = MessageDigest.getInstance("SHA-256")
                    val total = response.body!!.contentLength()
                    response.body!!.byteStream().use { input -> file.outputStream().use { output ->
                        val buffer = ByteArray(32768); var size = 0L; var reported = -1
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = input.read(buffer); if (n < 0) break
                            size += n; check(size <= artifact.maxBytes) { "Download exceeds size limit" }
                            output.write(buffer, 0, n); digest.update(buffer, 0, n)
                            if (total > 0) {
                                val percent = (size * 100 / total).toInt()
                                if (percent != reported) { reported = percent; progress("Downloading $what over HTTPS… $percent%", size.toFloat() / total) }
                            }
                        }
                    } }
                    check(digest.digest().joinToString("") { "%02x".format(it) } == artifact.sha256) { "Checksum mismatch: ${artifact.name}. Nothing from this archive was extracted." }
                }
                progress(if (artifact.name == "rootfs") "Verified. Unpacking ${distro.label}; this can take a minute…" else "Verified ${artifact.name}; installing…", null)
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { api.installArtifact(distro.id, artifact.name, it) }
            } finally { file.delete() }
        }
        progress("Testing ${distro.label}…", null)
        api.finishInstall(distro.id)
    }
}

