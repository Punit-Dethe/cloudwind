package dev.cloudwind.core

import java.io.File
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Live test against a deployed Worker. Skipped unless CLOUDWIND_E2E_URL and CLOUDWIND_E2E_PHONE_TOKEN are set. */
class E2eTest {
    private val url = System.getenv("CLOUDWIND_E2E_URL")
    private val token = System.getenv("CLOUDWIND_E2E_PHONE_TOKEN")
    private val key = ByteArray(32) { it.toByte() }

    private fun uploadFile(client: WorkerClient, f: File): String {
        val tmp = File.createTempFile("cwnd", ".enc")
        try {
            tmp.outputStream().buffered().use { Crypto.encrypt(key, f.name, f.lastModified() / 1000, { f.inputStream() }, it) }
            val id = WorkerClient.newObjectId()
            client.upload(id, tmp)
            client.upload(id, tmp) // retrying after success must be harmless (409 -> ok)
            return id
        } finally { tmp.delete() }
    }

    @Test
    fun phone_uploads_and_status_is_visible() {
        if (url.isNullOrEmpty() || token.isNullOrEmpty()) return
        val dir = File(System.getenv("CLOUDWIND_E2E_DIR") ?: error("CLOUDWIND_E2E_DIR"))
        val client = WorkerClient(url, token)
        assertTrue(client.checkAccess())
        assertEquals(false, WorkerClient("$url", "wrong").checkAccess())
        val ids = dir.listFiles { f -> f.name.startsWith("IMG_") || f.name.startsWith("VID_") }!!.map { uploadFile(client, it) }
        val st = client.status().objects.associateBy { it.id }
        ids.forEach { assertEquals("uploaded", st[it]?.state) }
    }
}

/** Live: pause a multipart upload mid-way, resume from the saved state, and let the PC verify the bytes. */
class E2eResumeTest {
    private val url = System.getenv("CLOUDWIND_E2E_URL")
    private val token = System.getenv("CLOUDWIND_E2E_PHONE_TOKEN")
    private val key = ByteArray(32) { it.toByte() }

    @Test
    fun paused_upload_resumes_against_the_live_worker() {
        if (url.isNullOrEmpty() || token.isNullOrEmpty()) return
        val src = File(System.getenv("CLOUDWIND_E2E_DIR") ?: error("CLOUDWIND_E2E_DIR"), "VID_resume.mp4")
        val client = WorkerClient(url, token)
        val enc = File.createTempFile("cwnd", ".enc")
        try {
            enc.outputStream().buffered().use { Crypto.encrypt(key, src.name, src.lastModified() / 1000, { src.inputStream() }, it) }
            val id = WorkerClient.newObjectId()
            var saved: ResumeState? = null
            var parts = 0
            assertFailsWith<UploadInterrupted> { client.upload(id, enc, onState = { saved = it }, shouldStop = { parts++ >= 3 }) }
            assertEquals(3, saved!!.parts.size, "three parts were saved before the pause")
            // Persist + reload exactly as the app does.
            client.upload(id, enc, resume = ResumeState.fromJson(saved!!.toJson()), onState = { saved = it })
            assertEquals(null, saved)
            assertEquals("uploaded", client.status().objects.first { it.id == id }.state)
        } finally { enc.delete() }
    }
}
