package com.bruh.angel.linuxenv

import android.os.ParcelFileDescriptor
import android.system.Os
import org.apache.commons.compress.archivers.ar.ArArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.CompressorStreamFactory
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.FileVisitResult
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes
import java.io.IOException

/** Runs only in the Shizuku uid-2000 process. Installs verified archives into a fresh staging tree. */
class LinuxInstaller {
    val base = File("/data/local/tmp/angel-linux")
    private val staging get() = File(base, "staging")
    private val received = mutableSetOf<String>()
    private var stagingDistro: LinuxDistro? = null

    /** Install directory of [distro]. The first release only had Alpine, stored as "current". */
    fun dir(distro: LinuxDistro): File {
        if (distro == LinuxDistro.ALPINE) migrateLegacy()
        return File(base, distro.id)
    }
    private fun migrateLegacy() {
        val legacy = File(base, "current")
        if (legacy.isDirectory && !File(base, "alpine").exists()) legacy.renameTo(File(base, "alpine"))
        runCatching { deleteTree(File(base, "previous")) }
    }
    private fun isReady(distro: LinuxDistro) = File(dir(distro), ".ready").readTextOrEmpty() == distro.version
    fun status(distro: LinuxDistro): String = if (isReady(distro)) "Ready: ${distro.version}" else "Not installed"
    fun installed(): List<LinuxDistro> = LinuxDistro.entries.filter { isReady(it) }

    fun install(distro: LinuxDistro, name: String, fd: ParcelFileDescriptor) {
        check(Os.getuid() == 2000)
        val artifact = LinuxArtifacts.forDistro(distro).single { it.name == name }
        if (!base.exists()) check(base.mkdir())
        check(Os.lstat(base.path).st_uid == 2000 && base.canonicalPath == base.absolutePath) { "Unsafe Linux directory" }
        Os.chmod(base.path, 448) // 0700
        if (name == "proot") {
            check(base.usableSpace >= distro.requiredFreeBytes) {
                "Not enough free space for ${distro.label}: about ${distro.requiredFreeBytes / 1_048_576} MB needed, ${base.usableSpace / 1_048_576} MB available"
            }
            deleteTree(staging); check(staging.mkdirs()); received.clear(); stagingDistro = distro
        }
        check(stagingDistro == distro && staging.isDirectory && staging.canonicalPath == staging.absolutePath) { "Installation out of order" }
        val archive = File.createTempFile("download-", ".tmp", base)
        try {
            ParcelFileDescriptor.AutoCloseInputStream(fd).use { input -> archive.outputStream().use { out ->
                val digest = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(32768); var size = 0L
                while (true) {
                    val n = input.read(buffer); if (n < 0) break
                    size += n; check(size <= artifact.maxBytes) { "Archive exceeds size limit" }
                    digest.update(buffer, 0, n); out.write(buffer, 0, n)
                }
                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                check(actual == artifact.sha256) { "Checksum mismatch for $name; refusing extraction" }
            } }
            val destination = File(staging, if (name == "rootfs") "rootfs" else "runtime").apply { mkdirs() }
            archive.inputStream().buffered().use { input ->
                if (name == "rootfs") extract(CompressorStreamFactory().createCompressorInputStream(input), destination, false, distro.stripPrefix, distro.maxExpandedBytes)
                else ArArchiveInputStream(input).use { ar ->
                    var found = false
                    while (true) {
                        val entry = ar.nextEntry ?: break
                        if (entry.name.startsWith("data.tar.")) {
                            extract(CompressorStreamFactory().createCompressorInputStream(ar.buffered()), destination, true)
                            found = true; break
                        }
                    }
                    check(found) { "Missing Debian data archive" }
                }
            }
            received += name
        } catch (e: Exception) {
            runCatching { deleteTree(staging) }
            received.clear(); stagingDistro = null
            throw e
        } finally { archive.delete() }
    }
    fun commit(distro: LinuxDistro): File {
        check(stagingDistro == distro && received == LinuxArtifacts.forDistro(distro).map { it.name }.toSet()) { "Installation incomplete" }
        File(staging, "tmp").mkdirs()
        File(staging, "rootfs/root").mkdirs()
        configure(distro, File(staging, "rootfs"))
        // The downloaded rootfs is never made runnable until ALL pinned hashes have passed.
        val current = dir(distro)
        val previous = File(base, "previous-${distro.id}")
        deleteTree(previous)
        if (current.exists()) check(current.renameTo(previous))
        if (!staging.renameTo(current)) { previous.renameTo(current); error("Cannot commit Linux installation") }
        stagingDistro = null
        return current
    }
    fun markReady(distro: LinuxDistro) { File(dir(distro), ".ready").writeText(distro.version); deleteTree(File(base, "previous-${distro.id}")) }
    fun rollback(distro: LinuxDistro) {
        val current = dir(distro)
        deleteTree(current)
        val previous = File(base, "previous-${distro.id}")
        if (previous.exists()) check(previous.renameTo(current)) { "Could not restore previous Linux installation" }
    }
    fun remove(distro: LinuxDistro) {
        deleteTree(dir(distro))
        deleteTree(File(base, "previous-${distro.id}"))
    }

    /** Small, distro-independent fixes so networking and the package manager work under proot. */
    private fun configure(distro: LinuxDistro, rootfs: File) {
        fun write(path: String, text: String) {
            val file = File(rootfs, path)
            file.parentFile!!.mkdirs()
            Files.deleteIfExists(file.toPath()) // replace symlinks (e.g. systemd's resolv.conf) instead of writing through them
            file.writeText(text)
        }
        write("etc/resolv.conf", "nameserver 1.1.1.1\nnameserver 8.8.8.8\n")
        val hosts = File(rootfs, "etc/hosts")
        if (!Files.isRegularFile(hosts.toPath(), LinkOption.NOFOLLOW_LINKS) || hosts.length() == 0L) {
            write("etc/hosts", "127.0.0.1 localhost\n::1 localhost ip6-localhost ip6-loopback\n")
        }
        // apt drops privileges to the _apt user for downloads; proot's simulated root cannot do that.
        if (distro.aptBased) write("etc/apt/apt.conf.d/99angel-proot", "APT::Sandbox::User \"root\";\n")
    }
    /** Never traverse symlinks created by commands inside the mutable rootfs. */
    internal fun deleteTree(root: File) {
        if (!Files.exists(root.toPath(), LinkOption.NOFOLLOW_LINKS)) return
        Files.walkFileTree(root.toPath(), object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                // Commands inside the rootfs may have made a directory read-only; deleting its children needs write access.
                runCatching { Os.chmod(dir.toString(), 448) } // 0700
                return FileVisitResult.CONTINUE
            }
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.delete(file)
                return FileVisitResult.CONTINUE
            }
            override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                // The walker opens a directory before preVisitDirectory runs, so a mode-000 directory (e.g. a proot mount point) fails here.
                if (exc !is java.nio.file.AccessDeniedException) throw exc
                Os.chmod(file.toString(), 448) // 0700
                deleteTree(file.toFile())
                return FileVisitResult.CONTINUE
            }
            override fun postVisitDirectory(dir: Path, error: IOException?): FileVisitResult {
                if (error != null) throw error
                Files.delete(dir)
                return FileVisitResult.CONTINUE
            }
        })
    }
    internal fun extract(input: InputStream, root: File, termux: Boolean, stripPrefix: String = "", maxBytes: Long = 256_000_000) {
        val links = mutableListOf<Triple<File, String, Boolean>>()
        val prefix = "data/data/com.termux/files/usr/"
        var total = 0L; var count = 0
        TarArchiveInputStream(input).use { tar ->
            while (true) {
                val entry = tar.nextEntry ?: break
                check(++count <= 100_000)
                var name = entry.name.removePrefix("./")
                if (termux) { if (!name.startsWith(prefix)) continue; name = name.removePrefix(prefix) }
                if (stripPrefix.isNotEmpty()) {
                    if (name.trimEnd('/') == stripPrefix.trimEnd('/')) continue
                    require(name.startsWith(stripPrefix)) { "Unexpected archive layout" }
                    name = name.removePrefix(stripPrefix)
                }
                if (name.isEmpty() || name == ".") continue
                require(!name.startsWith('/') && name.split('/').none { it == ".." }) { "Unsafe archive path" }
                val dest = File(root, name)
                require(dest.canonicalPath.startsWith(root.canonicalPath + "/")) { "Archive path escape" }
                when {
                    entry.isDirectory -> { check(dest.isDirectory || dest.mkdirs()) }
                    entry.isSymbolicLink || entry.isLink -> links += Triple(dest, entry.linkName, entry.isLink)
                    entry.isFile -> {
                        total += entry.size; check(total <= maxBytes && entry.size >= 0) { "Expanded archive too large" }
                        dest.parentFile!!.mkdirs()
                        dest.outputStream().use { tar.copyTo(it) }
                        Os.chmod(dest.path, entry.mode and 511) // Strip setuid/setgid.
                    }
                    else -> Unit // Never create device nodes, sockets or FIFOs.
                }
            }
        }
        links.forEach { (dest, raw, hard) ->
            val link = when {
                termux -> raw.removePrefix("/data/data/com.termux/files/usr/")
                hard && stripPrefix.isNotEmpty() -> raw.removePrefix("./").removePrefix(stripPrefix)
                else -> raw
            }
            val target = (if (hard || link.startsWith('/')) File(root, link.removePrefix("./").trimStart('/')) else File(dest.parentFile, link)).canonicalFile
            require(target.path == root.canonicalPath || target.path.startsWith(root.canonicalPath + "/")) { "Archive link escape" }
            val parent = dest.parentFile!!.canonicalPath
            require(parent == root.canonicalPath || parent.startsWith(root.canonicalPath + "/"))
            dest.parentFile!!.mkdirs()
            // SELinux denies hard links (link permission) to the shell user, so hard links become relative symlinks.
            val from = dest.parentFile!!.absolutePath.split('/'); val to = target.path.split('/')
            val common = from.zip(to).takeWhile { it.first == it.second }.size
            val relative = (List(from.size - common) { ".." } + to.drop(common)).joinToString("/").ifEmpty { "." }
            Os.symlink(relative, dest.path)
        }
    }
    private fun File.readTextOrEmpty(): String = if (isFile) readText() else ""
}

