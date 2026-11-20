package com.silvershireinn.app.crypto

import android.util.Base64
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * End-to-end encryption for chat files (photos, videos, voice notes, documents).
 *
 * The file is encrypted on the device before upload, so the server only ever stores noise.
 * Format (identical on the web client):
 *   plaintext split into chunks of [cs] bytes (1 MiB); chunk i is AES-256-GCM with
 *   nonce = iv8 (8 random bytes) || i (4 bytes, big endian), AAD = one byte: 1 on the last
 *   chunk, 0 otherwise (a cut-off file fails instead of playing short). Each encrypted chunk
 *   is cs + 16 bytes (the last one shorter). An empty file is one empty last chunk.
 * The key, iv8 and cs travel inside the (already end-to-end encrypted) file message:
 *   "_att": {..., "ek": b64 key, "iv": b64 iv8, "cs": chunk size}
 * Chunks make big videos seekable / streamable: any position maps to one chunk.
 */
object MediaCrypto {
    const val CHUNK = 1 shl 20
    private const val TAG_LEN = 16

    class Key(val key: ByteArray, val iv8: ByteArray, val cs: Int = CHUNK) {
        fun putInto(att: JSONObject): JSONObject = att
            .put("ek", Base64.encodeToString(key, Base64.NO_WRAP))
            .put("iv", Base64.encodeToString(iv8, Base64.NO_WRAP))
            .put("cs", cs)

        fun encode(): String = JSONObject().also { putInto(it) }.toString()

        companion object {
            fun from(att: JSONObject?): Key? {
                if (att == null) return null
                val ek = att.optString("ek", ""); val iv = att.optString("iv", "")
                if (ek.isBlank() || iv.isBlank()) return null
                return try {
                    val k = Base64.decode(ek, Base64.NO_WRAP); val v = Base64.decode(iv, Base64.NO_WRAP)
                    val cs = att.optInt("cs", CHUNK)
                    if (k.size != 32 || v.size != 8 || cs <= 0 || cs > 16 * CHUNK) null else Key(k, v, cs)
                } catch (_: Exception) { null }
            }
        }
    }

    fun newKey(): Key {
        val r = SecureRandom()
        return Key(ByteArray(32).also { r.nextBytes(it) }, ByteArray(8).also { r.nextBytes(it) })
    }

    fun chunkCount(plainSize: Long, cs: Int): Long = if (plainSize <= 0) 1 else (plainSize + cs - 1) / cs

    fun encryptedSize(plainSize: Long, cs: Int = CHUNK): Long =
        if (plainSize < 0) -1 else plainSize + TAG_LEN * chunkCount(plainSize, cs)

    private fun nonce(k: Key, index: Long): ByteArray = ByteArray(12).also {
        System.arraycopy(k.iv8, 0, it, 0, 8)
        it[8] = (index ushr 24).toByte(); it[9] = (index ushr 16).toByte()
        it[10] = (index ushr 8).toByte(); it[11] = index.toByte()
    }

    private fun cipher(k: Key, mode: Int, index: Long, last: Boolean): Cipher =
        Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, SecretKeySpec(k.key, "AES"), GCMParameterSpec(128, nonce(k, index)))
            updateAAD(byteArrayOf(if (last) 1 else 0))
        }

    fun encryptChunk(k: Key, index: Long, plain: ByteArray, len: Int, last: Boolean): ByteArray =
        cipher(k, Cipher.ENCRYPT_MODE, index, last).doFinal(plain, 0, len)

    fun decryptChunk(k: Key, index: Long, enc: ByteArray, len: Int, last: Boolean): ByteArray =
        cipher(k, Cipher.DECRYPT_MODE, index, last).doFinal(enc, 0, len)

    private fun readFully(input: InputStream, buf: ByteArray): Int {
        var n = 0
        while (n < buf.size) { val r = input.read(buf, n, buf.size - n); if (r < 0) break; n += r }
        return n
    }

    /** Wraps [src] (plaintext) as a stream of encrypted chunks. Needs no file size up front. */
    class EncryptingStream(private val src: InputStream, private val k: Key) : InputStream() {
        private var cur = ByteArray(k.cs); private var curLen = -1
        private var next = ByteArray(k.cs); private var nextLen = -1
        private var index = 0L
        private var out: ByteArray = ByteArray(0); private var outPos = 0
        private var done = false

        private fun fill(): Boolean {
            if (done) return false
            if (curLen < 0) curLen = readFully(src, cur)
            nextLen = if (curLen == k.cs) readFully(src, next) else 0
            val last = nextLen <= 0
            out = encryptChunk(k, index++, cur, curLen, last); outPos = 0
            if (last) done = true
            else { val t = cur; cur = next; next = t; curLen = nextLen }
            return true
        }

        override fun read(): Int {
            val b = ByteArray(1); return if (read(b, 0, 1) <= 0) -1 else b[0].toInt() and 0xff
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            while (outPos >= out.size) if (!fill()) return -1
            val n = minOf(len, out.size - outPos)
            System.arraycopy(out, outPos, b, off, n); outPos += n
            return n
        }

        override fun close() { src.close() }
    }

    /** Decrypt a whole encrypted stream into [out]. Throws if anything was changed or cut off. */
    fun decryptStream(input: InputStream, out: OutputStream, k: Key) {
        val step = k.cs + TAG_LEN
        var cur = ByteArray(step); var next = ByteArray(step)
        var curLen = readFully(input, cur)
        var index = 0L
        while (true) {
            val nextLen = if (curLen == step) readFully(input, next) else 0
            val last = nextLen <= 0
            if (curLen < TAG_LEN) throw java.io.IOException("encrypted file is cut off")
            out.write(decryptChunk(k, index++, cur, curLen, last))
            if (last) break
            val t = cur; cur = next; next = t; curLen = nextLen
        }
    }

    fun decryptBytes(enc: ByteArray, k: Key): ByteArray =
        ByteArrayOutputStream(maxOf(0, enc.size - TAG_LEN)).also { decryptStream(enc.inputStream(), it, k) }.toByteArray()

    fun encryptBytes(plain: ByteArray, k: Key): ByteArray = EncryptingStream(plain.inputStream(), k).readBytes()
}
