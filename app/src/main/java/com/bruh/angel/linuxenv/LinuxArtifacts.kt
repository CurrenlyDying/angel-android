package com.bruh.angel.linuxenv

/** Pinned official HTTPS artifacts. SHA-256 is checked before any archive is extracted. */
data class LinuxArtifact(val name: String, val url: String, val sha256: String, val maxBytes: Long = 32_000_000)

object LinuxArtifacts {
    /** Install marker only; kept stable so existing installs stay Ready. Termux's pool keeps just the latest .deb, so these URLs need re-pinning when it moves on. */
    const val PROOT_VERSION = "proot-5.1.107.95-v1"

    /** The proot runtime is the same for every distribution. */
    val runtime = listOf(
        LinuxArtifact("proot", "https://packages.termux.dev/apt/termux-main/pool/main/p/proot/proot_5.1.107.96_aarch64.deb", "8199dca06dccb693ec09fb1759e3e1ad08b4863f0c11c612f89c20bd9ecdc1a0"),
        LinuxArtifact("talloc", "https://packages.termux.dev/apt/termux-main/pool/main/libt/libtalloc/libtalloc_2.5.0_aarch64.deb", "556591f43bb773ad8777e1a29522640866a55f95dab71914418b94a8c58ad5a7"),
        LinuxArtifact("shmem", "https://packages.termux.dev/apt/termux-main/pool/main/liba/libandroid-shmem/libandroid-shmem_0.7_aarch64.deb", "0da3a24d558b93c92bcf8d611e0826a99ff96e396b148e6cdf33b47c47c57ff6")
    )

    /** Download order matters: "proot" starts a fresh staging tree. */
    fun forDistro(distro: LinuxDistro): List<LinuxArtifact> = runtime + distro.rootfs
}
