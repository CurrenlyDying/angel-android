package com.bruh.angel

import com.bruh.angel.local.HfFile
import com.bruh.angel.local.HuggingFace
import com.bruh.angel.local.LocalModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class LocalModelFilesTest {
    @Test fun missingAndTruncatedFilesAreNotReady() {
        val directory = Files.createTempDirectory("angel-model-test").toFile()
        try {
            val file = File(directory, "model.gguf")
            val model = LocalModel("id", "Model", LocalModel.Source.FILE, file.path, 8)
            assertEquals(LocalModel.Status.FAILED, model.checkedFile().status)
            file.writeText("GGUF")
            assertFalse(model.checkedFile().ready)
            file.writeText("GGUFtest")
            assertEquals(model, model.checkedFile())
            assertEquals(model.copy(status = LocalModel.Status.DOWNLOADING), model.copy(status = LocalModel.Status.DOWNLOADING).checkedFile())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun separateDownloadsNeverShareTheSameFile() {
        val directory = File("/models")
        val file = HfFile("sub/model-Q4_0.gguf", 100, null)
        val first = HuggingFace.downloadTarget(directory, "first", "owner/repo", file)
        val second = HuggingFace.downloadTarget(directory, "second", "owner/repo", file)
        assertNotEquals(first, second)
        assertEquals(directory, first.parentFile)
        assertTrue(first.name.endsWith("model-Q4_0.gguf"))
    }
}
