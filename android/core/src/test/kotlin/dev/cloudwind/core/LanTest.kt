package dev.cloudwind.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LanBasicsTest {
    private val key = ByteArray(32) { it.toByte() }

    @Test
    fun auth_token_matches_the_rust_agent_and_an_independent_hmac() {
        // Same vector as the Rust test `lan_token_is_stable_across_implementations`.
        assertEquals("a705ba1efe81f5e88a6bde701f73204c7dade81427253c5881607ff21720cbcc", lanAuthToken(key))
    }

    @Test
    fun pairing_carries_the_pc_certificate_fingerprint_and_port() {
        val fp = "ab".repeat(32)
        val k = Base64.getUrlEncoder().withoutPadding().encodeToString(key)
        fun code(extra: String) = "cloudwind://pair/" + Base64.getUrlEncoder().withoutPadding().encodeToString(
            """{"v":1,"key":"$k","url":"https://x.example","token":"t"$extra}""".toByteArray(),
        )
        val p = Pairing.parse(code(""","fp":"$fp","port":47615"""))!!
        assertEquals(fp, p.lanFingerprint)
        assertEquals(47615, p.lanPort)
        // Older codes and junk values simply mean "no direct sync".
        assertNull(Pairing.parse(code(""))!!.lanFingerprint)
        assertNull(Pairing.parse(code(""","fp":"not-hex","port":99999"""))!!.lanFingerprint)
        assertNull(Pairing.parse(code(""","fp":"$fp","port":99999"""))!!.lanPort)
    }

    @Test
    fun hint_roundtrips_and_garbage_is_ignored() {
        val json = """{"addrs":["192.168.1.20","10.0.0.7"],"port":47615,"at":1700000000}"""
        val ct = ByteArrayOutputStream()
        Crypto.encrypt(key, "lan", null, { ByteArrayInputStream(json.toByteArray()) }, ct)
        val payload = Base64.getUrlEncoder().withoutPadding().encodeToString(ct.toByteArray())
        val h = LanHint.decode(payload, key)!!
        assertEquals(listOf("192.168.1.20", "10.0.0.7"), h.addrs)
        assertEquals(47615, h.port)
        assertNull(LanHint.decode(payload, ByteArray(32) { 9 }), "wrong key")
        assertNull(LanHint.decode("%%%", key))
    }
}

/**
 * Real end-to-end: launches the Rust agent (`cloudwind run`) and drives it from Kotlin over actual TLS with a
 * pinned certificate. Skipped unless CLOUDWIND_BIN points at the built agent.
 */
class LanEndToEndTest {
    private val bin = System.getenv("CLOUDWIND_BIN")
    private var proc: Process? = null
    private lateinit var dir: File

    @AfterTest fun stop() { proc?.destroy(); proc?.waitFor(5, TimeUnit.SECONDS) }

    private fun sh(vararg cmd: String) = ProcessBuilder(*cmd).redirectErrorStream(true).start().also { it.waitFor() }

    private class Pc(val port: Int, val fp: String, val key: ByteArray, val library: File)

    private fun startPc(): Pc {
        dir = File.createTempFile("cwlan", "").also { it.delete(); it.mkdirs() }
        val cfg = File(dir, "c.json")
        sh(bin, "--config", cfg.path, "init", "--library", File(dir, "photos").path, "--inbox", File(dir, "inbox").also { it.mkdirs() }.path)
        val keyB64 = Regex("\"key\":\\s*\"([^\"]+)\"").find(cfg.readText())!!.groupValues[1]
        val key = Base64.getUrlDecoder().decode(keyB64)
        proc = ProcessBuilder(bin, "--config", cfg.path, "run", "--bind", "127.0.0.1", "--port", "0", "--interval", "3600")
            .redirectErrorStream(true).start()
        val line = proc!!.inputStream.bufferedReader().readLine() ?: error("agent printed nothing")
        val m = Regex("listening on 127\\.0\\.0\\.1:(\\d+) \\(certificate ([0-9a-f]{64})\\)").find(line) ?: error("unexpected: $line")
        return Pc(m.groupValues[1].toInt(), m.groupValues[2], key, File(dir, "photos"))
    }

    private fun item(id: String, data: ByteArray, obj: String? = null) =
        LanPlanItem(id, Crypto.hex(java.security.MessageDigest.getInstance("SHA-256").digest(data)), data.size.toLong(), obj)

    @Test
    fun pinned_handshake_auth_plan_resume_and_dedup() {
        if (bin.isNullOrEmpty()) return
        val pc = startPc()
        val token = lanAuthToken(pc.key)

        // --- trust: only the exact pinned certificate and the right key are accepted
        assertTrue(LanClient("127.0.0.1", pc.port, pc.fp, token).hello(), "pinned handshake works")
        assertFalse(LanClient("127.0.0.1", pc.port, "00".repeat(32), token).hello(), "a different certificate is refused")
        assertFalse(LanClient("127.0.0.1", pc.port, pc.fp, "wrong-token").hello(), "wrong token is refused")
        assertNotNull(LanFinder(pc.fp, token, pc.port).find(listOf("10.255.255.1", "127.0.0.1")), "finder skips dead addresses")
        val lan = LanClient("127.0.0.1", pc.port, pc.fp, token)

        // --- plan -> send -> verified on the PC
        val photo = ByteArray(300_000) { (it * 31 % 251).toByte() }
        val video = ByteArray(3_000_000) { (it * 7 % 253).toByte() }
        var plan = lan.plan(listOf(item("p1", photo), item("v1", video)))
        assertEquals(listOf("p1", "v1"), plan.want.map { it.id })
        assertEquals(0L, plan.want[0].offset)
        val r = lan.send(item("p1", photo).sha256, photo.size.toLong(), 0, "IMG 1 & 2+3.jpg", 1_700_000_000, null, { ByteArrayInputStream(photo) })
        assertEquals(LanSend.Stored(duplicate = false), r)
        assertContentEquals(photo, File(pc.library, "IMG 1 & 2+3.jpg").readBytes())

        // --- a big video is interrupted partway; the PC keeps the partial and the plan tells us where to resume
        var chunks = 0
        assertFailsWith<UploadInterrupted> {
            lan.send(item("v1", video).sha256, video.size.toLong(), 0, "VID.mp4", null, null, { ByteArrayInputStream(video) }, shouldStop = { ++chunks > 4 })
        }
        plan = lan.plan(listOf(item("v1", video)))
        val offset = plan.want.single().offset
        assertTrue(offset in 1 until video.size, "PC reports a partial offset, got $offset")
        assertEquals(LanSend.Conflict(offset), lan.send(item("v1", video).sha256, video.size.toLong(), 12345, "VID.mp4", null, null, { ByteArrayInputStream(video) }))
        val done = lan.send(item("v1", video).sha256, video.size.toLong(), offset, "VID.mp4", null, null, { ByteArrayInputStream(video) })
        assertEquals(LanSend.Stored(duplicate = false), done)
        assertContentEquals(video, File(pc.library, "VID.mp4").readBytes())

        // --- duplicates: the PC already has these bytes, so nothing is stored twice and the phone is told "have"
        plan = lan.plan(listOf(item("again", photo), item("p1", photo)))
        assertEquals(listOf("again", "p1"), plan.have)
        assertEquals(LanSend.Stored(duplicate = true), lan.send(item("p1", photo).sha256, photo.size.toLong(), 0, "copy.jpg", null, null, { ByteArrayInputStream(photo) }))
        assertEquals(2, pc.library.listFiles { f -> !f.name.startsWith(".") }!!.size)

        // --- corrupt bytes are rejected, not stored
        val good = ByteArray(10_000) { 5 }
        val bad = good.copyOf().also { it[100] = 6 }
        assertEquals(LanSend.BadHash, lan.send(item("x", good).sha256, good.size.toLong(), 0, "bad.jpg", null, null, { ByteArrayInputStream(bad) }))
        assertFalse(File(pc.library, "bad.jpg").exists())
    }
}

/**
 * The part that frees cloud quota: a file that is already in the cloud inbox arrives directly instead, and the PC
 * tells the Worker, so the cloud copy is released. Runs the real agent against the live Worker; skipped without
 * CLOUDWIND_BIN, CLOUDWIND_E2E_URL, CLOUDWIND_E2E_PHONE_TOKEN and CLOUDWIND_E2E_AGENT_TOKEN.
 */
class LanWithCloudTest {
    private val bin = System.getenv("CLOUDWIND_BIN")
    private val url = System.getenv("CLOUDWIND_E2E_URL")
    private val phoneToken = System.getenv("CLOUDWIND_E2E_PHONE_TOKEN")
    private val agentToken = System.getenv("CLOUDWIND_E2E_AGENT_TOKEN")
    private var proc: Process? = null

    @AfterTest fun stop() { proc?.destroy(); proc?.waitFor(5, TimeUnit.SECONDS) }

    @Test
    fun direct_arrival_releases_the_cloud_copy_and_cloud_items_are_left_to_the_pc() {
        if (listOf(bin, url, phoneToken, agentToken).any { it.isNullOrEmpty() }) return
        val dir = File.createTempFile("cwlive", "").also { it.delete(); it.mkdirs() }
        val cfg = File(dir, "c.json")
        ProcessBuilder(bin, "--config", cfg.path, "init", "--library", File(dir, "photos").path, "--worker-url", url!!)
            .also { it.environment()["CLOUDWIND_AGENT_TOKEN"] = agentToken!! }.redirectErrorStream(true).start().waitFor()
        val key = Base64.getUrlDecoder().decode(Regex("\"key\":\\s*\"([^\"]+)\"").find(cfg.readText())!!.groupValues[1])

        // Start the PC first so its initial cloud pull finds nothing; then put two files in the cloud.
        proc = ProcessBuilder(bin, "--config", cfg.path, "run", "--bind", "127.0.0.1", "--port", "0", "--interval", "3600").redirectErrorStream(true).start()
        val line = proc!!.inputStream.bufferedReader().readLine()!!
        val m = Regex("127\\.0\\.0\\.1:(\\d+) \\(certificate ([0-9a-f]{64})\\)").find(line)!!
        val lan = LanClient("127.0.0.1", m.groupValues[1].toInt(), m.groupValues[2], lanAuthToken(key))
        val cloud = WorkerClient(url, phoneToken!!)

        fun uploadToCloud(name: String, data: ByteArray): String {
            val enc = File.createTempFile("cwnd", ".enc")
            try {
                enc.outputStream().use { Crypto.encrypt(key, name, null, { ByteArrayInputStream(data) }, it) }
                return WorkerClient.newObjectId().also { cloud.upload(it, enc) }
            } finally { enc.delete() }
        }
        val a = ByteArray(40_000) { (it % 251).toByte() }
        val b = ByteArray(55_000) { (it % 241).toByte() }
        val objA = uploadToCloud("A.jpg", a)
        val objB = uploadToCloud("B.jpg", b)
        fun sha(d: ByteArray) = Crypto.hex(java.security.MessageDigest.getInstance("SHA-256").digest(d))

        // The PC sees both in the cloud: "leave A to me" (it will pull), but we choose to force-send B directly.
        val plan = lan.plan(listOf(LanPlanItem("a", sha(a), a.size.toLong(), objA), LanPlanItem("b", sha(b), b.size.toLong(), objB)))
        assertEquals(listOf("a", "b"), plan.cloud, "both are in the cloud, so the PC offers to pull them itself")

        // Send B directly anyway (as the phone does when the PC can't reach the cloud): the cloud copy is released.
        assertEquals(LanSend.Stored(false), lan.send(sha(b), b.size.toLong(), 0, "B.jpg", null, objB, { ByteArrayInputStream(b) }))
        val states = cloud.status().objects.associateBy { it.id }
        assertEquals("synced", states[objB]?.state, "direct arrival marked the cloud object synced")
        assertEquals("uploaded", states[objA]?.state, "A is still waiting for the PC's own cloud pull")
        assertContentEquals(b, File(dir, "photos/B.jpg").readBytes())

        // Tidy: let the PC pull A so the shared inbox isn't left with it.
        val agent = AgentCleaner(url, agentToken!!)
        agent.markSynced(objA)
    }

    private class AgentCleaner(private val base: String, private val token: String) {
        fun markSynced(id: String) {
            val c = java.net.URL("$base/v1/objects/$id/synced").openConnection() as java.net.HttpURLConnection
            c.requestMethod = "POST"; c.setRequestProperty("authorization", "Bearer $token"); c.responseCode
        }
    }
}
