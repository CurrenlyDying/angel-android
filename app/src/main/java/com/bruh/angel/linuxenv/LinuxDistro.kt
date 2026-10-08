package com.bruh.angel.linuxenv

/**
 * Root filesystems Angel can run under proot. Every entry is a pinned, checksummed ARM64 image from the
 * distribution's own infrastructure. Several can be installed side by side; one is active.
 */
enum class LinuxDistro(
    val id: String,
    val label: String,
    val tagline: String,
    /** Marker written on success; changing it makes existing installs show as "not installed". */
    val release: String,
    val downloadMb: Int,
    val diskMb: Int,
    val rootfs: LinuxArtifact,
    val aptBased: Boolean,
    /** Folder inside the archive that holds the root filesystem (empty when the archive root is `/`). */
    val stripPrefix: String = "",
    val maxExpandedBytes: Long = 256_000_000,
    /** Appended to the model's device context so it uses the right tools for this distribution. */
    val agentHint: String
) {
    ALPINE(
        id = "alpine",
        label = "Alpine Linux",
        tagline = "Tiny and fast. The best default for scripting.",
        release = "3.23.6",
        downloadMb = 4,
        diskMb = 12,
        rootfs = LinuxArtifact("rootfs", "https://dl-cdn.alpinelinux.org/alpine/v3.23/releases/aarch64/alpine-minirootfs-3.23.6-aarch64.tar.gz", "b17a57958e29735ff0e6e64254d958e65903687a70ca30cc430b33a965ad49d7"),
        aptBased = false,
        agentHint = "Alpine 3.23 (musl, busybox). Install software with `apk add <pkg>`."
    ),
    DEBIAN(
        id = "debian",
        label = "Debian",
        tagline = "Stable and familiar, with the widest package selection.",
        release = "13-20261005",
        downloadMb = 30,
        diskMb = 105,
        rootfs = LinuxArtifact("rootfs", "https://raw.githubusercontent.com/debuerreotype/docker-debian-artifacts/cf1f4a45447842b45e9952e0f018ae734a7341c7/trixie/slim/oci/blobs/rootfs.tar.gz", "bbeda6b4abb749f743f4547ef5a28070a14de8caef211daa5afb78d5b5fa21ba", maxBytes = 48_000_000),
        aptBased = true,
        agentHint = "Debian 13 \"trixie\" slim (glibc, bash and coreutils). Install software with `apt-get update && apt-get install -y <pkg>`; the image is minimal, so curl, git and python3 are not preinstalled."
    ),
    UBUNTU(
        id = "ubuntu",
        label = "Ubuntu",
        tagline = "26.04 LTS base image. Large community and docs.",
        release = "26.04.1",
        downloadMb = 35,
        diskMb = 125,
        rootfs = LinuxArtifact("rootfs", "https://cdimage.ubuntu.com/ubuntu-base/releases/26.04.1/release/ubuntu-base-26.04.1-base-arm64.tar.gz", "5a1906794ced63a71a8119c3f211ef5f0bbe0a243001b4bbd41fdf80c5b219fd", maxBytes = 48_000_000),
        aptBased = true,
        agentHint = "Ubuntu 26.04 LTS base image (glibc). Install software with `apt-get update && apt-get install -y <pkg>`; the image is minimal, so curl, git and python3 are not preinstalled."
    ),
    KALI(
        id = "kali",
        label = "Kali Linux",
        tagline = "Security tools on tap: nmap, metasploit, sqlmap and more via apt.",
        release = "2026.2-minimal",
        downloadMb = 131,
        diskMb = 900,
        rootfs = LinuxArtifact("rootfs", "https://kali.download/nethunter-images/kali-2026.2/rootfs/kali-nethunter-rootfs-minimal-arm64.tar.xz", "d6403a5da175df325611d23af4b92330856059c45454eced7f4cdf3ca6df2e4e", maxBytes = 200_000_000),
        aptBased = true,
        stripPrefix = "kali-arm64/",
        maxExpandedBytes = 1_100_000_000,
        agentHint = "Kali Linux 2026.2 (NetHunter minimal image, Debian-based, glibc). Install tools with `apt-get update && apt-get install -y <pkg>` " +
            "(e.g. nmap, sqlmap, hydra, kali-tools-top10). There is no real root, so raw sockets, packet injection and monitor mode do not work; TCP connect scans do. " +
            "Only use security tools against systems the user owns or is authorized to test."
    );

    val version get() = "$id-$release-${LinuxArtifacts.PROOT_VERSION}"

    /** Download, then the extracted tree, with headroom. */
    val requiredFreeBytes get() = (downloadMb * 2L + diskMb) * 1_048_576 + 64_000_000

    companion object {
        val DEFAULT = ALPINE
        fun fromId(id: String?): LinuxDistro = entries.firstOrNull { it.id == id } ?: DEFAULT
        fun parse(id: String): LinuxDistro = requireNotNull(entries.firstOrNull { it.id == id }) { "Unknown Linux distribution '$id'" }
    }
}
