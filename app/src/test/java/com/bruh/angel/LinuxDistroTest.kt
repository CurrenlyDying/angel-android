package com.bruh.angel

import com.bruh.angel.linuxenv.LinuxArtifacts
import com.bruh.angel.linuxenv.LinuxDistro
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LinuxDistroTest {
    @Test
    fun alpineKeepsLegacyMarkerSoExistingInstallsStayReady() {
        assertEquals("alpine-3.23.6-proot-5.1.107.95-v1", LinuxDistro.ALPINE.version)
    }

    @Test
    fun idsAreUniqueAndUnknownFallsBackToDefault() {
        assertEquals(LinuxDistro.entries.size, LinuxDistro.entries.map { it.id }.toSet().size)
        assertEquals(LinuxDistro.DEFAULT, LinuxDistro.fromId("nope"))
        assertEquals(LinuxDistro.DEFAULT, LinuxDistro.fromId(null))
        assertEquals(LinuxDistro.KALI, LinuxDistro.fromId("kali"))
    }

    @Test
    fun everyArtifactIsPinnedHttpsWithSha256() {
        LinuxDistro.entries.forEach { d ->
            LinuxArtifacts.forDistro(d).forEach { a ->
                assertTrue("${d.id}/${a.name}", a.url.startsWith("https://"))
                assertTrue("${d.id}/${a.name}", Regex("[0-9a-f]{64}").matches(a.sha256))
            }
            assertTrue(d.rootfs.maxBytes >= d.downloadMb * 1_000_000L)
        }
    }
}
