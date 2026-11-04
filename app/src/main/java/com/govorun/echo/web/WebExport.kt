package com.govorun.echo.web

import com.govorun.echo.BuildConfig
import com.govorun.echo.NetworkPolicy
import com.govorun.echo.notes.NoteStore
import com.govorun.echo.notes.smartName
import com.govorun.echo.notes.typedTitle
import com.govorun.echo.notes.aboutLine
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.PublicKey
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.zip.GZIPOutputStream
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Echo on a computer: the page at echo.govorun.club shows a code, the phone reads it and sends the notes' text through a blind relay.
 *
 * The code is `https://…/p#2.<room>.<the tab's P-256 public key>`; the room is open to anyone who saw it, the key opens nothing
 * by itself. A message is `0x02 · the phone's public key · 12-byte IV · AES-256-GCM(key, gzip(JSON))`, the room as additional
 * data, key = HKDF-SHA-256(ECDH(phone, tab), salt = room, info = "echo-web 2"): only that tab can read it. The phone's own key
 * lives in the Android Keystore ([PhoneKey]) and never leaves it; the page listens to the first phone that sends and no other.
 * Only text leaves the phone; the sound stays.
 */
internal object WebExport {
    /** The room and the tab's public key, uncompressed (0x04 · x · y). */
    class Pairing(val room: String, val computer: ByteArray, val browser: String = "", val os: String = "")

    sealed interface Result {
        data object Sent : Result

        /** Nobody waited in the room: the tab was closed or the code is old. */
        data object NoComputer : Result

        data object Failed : Result
    }

    private val roomName = Regex("^[A-Za-z0-9_-]{22}$")

    fun parse(code: String): Pairing? {
        val parts = code.substringAfter('#', "").split('.')
        if (parts.size != 3 || parts[0] != "2" || !roomName.matches(parts[1])) return null
        val key = runCatching { java.util.Base64.getUrlDecoder().decode(parts[2]) }.getOrNull()
        val query = runCatching { java.net.URI(code).rawQuery.orEmpty().split('&').associate { field ->
            val kv = field.split('=', limit = 2)
            kv[0] to java.net.URLDecoder.decode(kv.getOrElse(1) { "" }, "UTF-8")
        } }.getOrDefault(emptyMap())
        val browser = query["browser"].orEmpty().takeIf { it in setOf("Chrome", "Edge", "Firefox", "Safari", "Браузер") }.orEmpty()
        val os = query["os"].orEmpty().takeIf { it in setOf("Windows", "macOS", "Linux", "ChromeOS", "Android", "iOS") }.orEmpty()
        return key?.takeIf { it.size == 65 && it[0] == 4.toByte() && onCurve(it) }?.let { Pairing(parts[1], it, browser, os) }
    }

    /**
     * One note as the page shows it: only what the list and the reader need, never the sound. Beside what was said come its layers
     * (today the fair copy from Govorun, «Текст»), each with the name the app gives it: the page shows whatever layers come, so a
     * new or renamed layer in the app needs no change there.
     */
    fun noteJson(
        n: NoteStore.Entry,
        names: Map<String, String> = emptyMap(),
    ): JSONObject {
        val layers = WebLayers.of(n, names)
        return JSONObject()
            .put("layers", JSONArray(layers))
            .put("originalName", if (n.mode == com.govorun.echo.notes.NoteText.MODE) names["typed"] ?: "Текст" else names["original"] ?: "Расшифровка")
            .put("about", n.aboutLine().take(META_CAP))
            .put("aboutCut", n.aboutLine().length > META_CAP)
            .put("sourceCut", n.text.length > TEXT_CAP)
            .put("id", n.id)
            .put("title", n.typedTitle().ifBlank { n.smartName() }.take(META_CAP))
            .put("text", n.text.take(TEXT_CAP))
            .put("cut", n.text.length > TEXT_CAP || n.aboutLine().length > META_CAP || n.typedTitle().ifBlank { n.smartName() }.length > META_CAP || layers.any { it.optBoolean("cut") })
            .put("tags", JSONArray(n.tags))
            .put("at", n.startedAtMs)
            .put("dur", n.durationMs)
            .put("archived", n.archived)
            .put("pinned", n.pinned)

    }

    /**
     * A message carries at most this much JSON: a semester of lectures goes in parts of about a megabyte after gzip, far under the
     * relay's limit, and the page shows the newest notes while the rest arrive. A note longer than this goes alone.
     */
    const val PART_BYTES = 3_000_000

    /**
     * The most of one text a window gets (some 50 hours of speech): a longer one comes as its beginning, marked, and the page says
     * the whole is on the phone, so one huge note never keeps the rest of the notes from the window.
     */
    const val TEXT_CAP = 1_000_000
    const val META_CAP = 16_000

    /** Notes split into messages of at most [budget] bytes of JSON each, in the order given. */
    fun parts(
        notes: List<JSONObject>,
        budget: Int = PART_BYTES,
    ): List<List<JSONObject>> {
        val out = mutableListOf<MutableList<JSONObject>>()
        var size = 0
        notes.forEach { n ->
            val bytes = n.toString().toByteArray().size
            if (out.isEmpty() || (size + bytes > budget && out.last().isNotEmpty())) {
                out += mutableListOf<JSONObject>()
                size = 0
            }
            out.last() += n
            size += bytes
        }
        return out.ifEmpty { listOf(mutableListOf()) }
    }

    /** All notes, newest first, as the messages that open the window: the page starts over on the first part. */
    fun snapshot(
        notes: List<NoteStore.Entry>,
        device: String,
        names: Map<String, String> = emptyMap(),
        budget: Int = PART_BYTES,
    ): List<JSONObject> {
        val pieces = parts(notes.sortedByDescending { it.startedAtMs }.map { noteJson(it, names) }, budget)
        return pieces.mapIndexed { i, piece ->
            JSONObject().put("v", 1).put("kind", "notes").put("part", i).put("parts", pieces.size).put("device", device)
                .put("original", names["original"] ?: "Исходный").put("notes", JSONArray(piece))
        }
    }

    /** Notes new or changed, and the ids gone (with the first part), as messages of their own. */
    fun changes(
        changed: List<NoteStore.Entry>,
        gone: Collection<String>,
        names: Map<String, String> = emptyMap(),
        budget: Int = PART_BYTES,
    ): List<JSONObject> =
        parts(changed.map { noteJson(it, names) }, budget).mapIndexed { i, piece ->
            JSONObject().put("v", 1).put("kind", "changes").put("notes", JSONArray(piece)).put("gone", JSONArray(if (i == 0) gone else emptyList()))
        }

    /** What a note looks like on the page, as one number: a different one means the page should get it again. */
    fun signature(n: NoteStore.Entry): Int =
        listOf(n.typedTitle().ifBlank { n.smartName() }, n.text, n.fair?.name, n.fair?.about, n.fair?.text, n.fair?.fresh, n.aboutLine(), n.itogi, n.brief, n.mode, n.tags, n.archived, n.pinned, n.durationMs, n.startedAtMs).hashCode()

    /** Against what the page was last sent: the notes it lacks or has in another form, and the ids it holds that are gone. */
    fun diff(
        sent: Map<String, Int>,
        notes: List<NoteStore.Entry>,
    ): Pair<List<NoteStore.Entry>, Set<String>> = notes.filter { sent[it.id] != signature(it) } to (sent.keys - notes.map { it.id }.toSet())

    fun signal(kind: String): JSONObject = JSONObject().put("v", 1).put("kind", kind)

    fun gzip(message: JSONObject): ByteArray =
        ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(message.toString().toByteArray()) } }.toByteArray()

    fun seal(
        pairing: Pairing,
        plain: ByteArray,
        phone: KeyPair,
    ): ByteArray {
        val agreement = KeyAgreement.getInstance("ECDH")
        agreement.init(phone.private)
        agreement.doPhase(publicKey(pairing.computer), true)
        val key = hkdf(agreement.generateSecret(), pairing.room.toByteArray(), "echo-web 2".toByteArray())
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        cipher.updateAAD(pairing.room.toByteArray())
        return byteArrayOf(2) + raw(phone.public as ECPublicKey) + iv + cipher.doFinal(plain)
    }

    /**
     * The point really lies on P-256: y² = x³ − 3x + b (mod p). The phone's key is long-lived, so a forged code with a point off
     * the curve must never reach the key agreement (the invalid-curve attack would learn the phone's key piece by piece).
     */
    private fun onCurve(raw: ByteArray): Boolean {
        val x = BigInteger(1, raw.copyOfRange(1, 33))
        val y = BigInteger(1, raw.copyOfRange(33, 65))
        if (x >= P256_P || y >= P256_P) return false
        val right = x.pow(3).subtract(x.multiply(BigInteger.valueOf(3))).add(P256_B).mod(P256_P)
        return y.pow(2).mod(P256_P) == right
    }

    private val P256_P = BigInteger("ffffffff00000001000000000000000000000000ffffffffffffffffffffffff", 16)
    private val P256_B = BigInteger("5ac635d8aa3a93e7b3ebbd55769886bc651d06b0cc53b0f63bce3c3e27d2604b", 16)

    /** A P-256 public key from its 65 raw bytes: the fixed SubjectPublicKeyInfo header of the curve, then the point. */
    private fun publicKey(raw: ByteArray): PublicKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(P256_HEADER + raw))

    private val P256_HEADER =
        "3059301306072a8648ce3d020106082a8648ce3d030107034200".chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    internal fun raw(key: ECPublicKey): ByteArray = byteArrayOf(4) + fixed(key.w.affineX) + fixed(key.w.affineY)

    private fun fixed(n: BigInteger): ByteArray = n.toByteArray().takeLast(32).toByteArray().let { ByteArray(32 - it.size) + it }

    /** RFC 5869 for one block: 32 bytes, as the page's WebCrypto derives them. */
    internal fun hkdf(
        secret: ByteArray,
        salt: ByteArray,
        info: ByteArray,
    ): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(salt, "HmacSHA256"))
        val prk = mac.doFinal(secret)
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        return mac.doFinal(info + byteArrayOf(1))
    }

    internal fun receipt(pairing: Pairing, phone: KeyPair, delivery: String): ByteArray {
        val agreement = KeyAgreement.getInstance("ECDH")
        agreement.init(phone.private); agreement.doPhase(publicKey(pairing.computer), true)
        val key = hkdf(agreement.generateSecret(), pairing.room.toByteArray(), "echo-web 2 receipt".toByteArray())
        return Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(key, "HmacSHA256")); doFinal(delivery.toByteArray()) }
    }

    /** Sends on the calling thread (never the main one); the relay holds the request until the page has taken it. */
    fun send(
        pairing: Pairing,
        message: JSONObject,
        phone: KeyPair,
        timeoutMs: Int,
        lease: (HttpURLConnection) -> java.io.Closeable,
    ): Result {
        val delivery = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(16).also { SecureRandom().nextBytes(it) })
        val payload = JSONObject(message.toString()).put("delivery", delivery)
        val body = seal(pairing, gzip(payload), phone)
        return try { NetworkPolicy.request("${BuildConfig.ECHO_WEB_RELAY}/v1/send/${pairing.room}") { connection ->
            lease(connection).use {
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.connectTimeout = minOf(10_000, timeoutMs)
                connection.readTimeout = timeoutMs
                connection.setRequestProperty("Content-Type", "application/octet-stream")
                connection.setRequestProperty("X-Echo-Delivery", delivery)
                connection.setFixedLengthStreamingMode(body.size)
                connection.outputStream.use { it.write(body) }
                when (connection.responseCode) {
                    200 -> if (java.security.MessageDigest.isEqual(connection.inputStream.use { input -> val buffer = ByteArray(33); var size = 0; while (size < buffer.size) { val got = input.read(buffer, size, buffer.size - size); if (got < 0) break; size += got }; buffer.copyOf(size) }, receipt(pairing, phone, delivery))) Result.Sent else Result.Failed
                    410 -> Result.NoComputer
                    else -> Result.Failed
                }
            }
        } } catch (_: java.io.IOException) {
            Result.Failed
        }
    }
}
