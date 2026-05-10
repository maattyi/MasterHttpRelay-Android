package com.velum.vpn.net

import android.util.Base64
import com.velum.vpn.core.LogBus
import com.velum.vpn.core.MultiIdDispatcher
import com.velum.vpn.core.RelayConfig
import com.velum.vpn.core.RuntimeRelayConfig
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min

/**
 * RelayClient — talks to the Google Apps Script (GAS) relay over HTTPS.
 *
 * SECURITY MODEL (post-refactor):
 *   * The OkHttp instance is built by [SecureHttpClient.buildGasClient] which:
 *       - uses ONLY the Android system trust store
 *       - restricts to TLS 1.2 / 1.3 with MODERN_TLS cipher suites
 *       - pins the SPKI of Google's GTS roots for script.google.com
 *       - keeps default hostname verification ENABLED
 *   * No trust-all manager. No HostnameVerifier override. No SNI rewrite.
 *   * The client speaks the standard HTTPS chain to script.google.com via
 *     the system DNS resolver. Censorship-bypass behaviour is preserved
 *     via the GAS relay endpoint itself, not via TLS interception.
 *
 * If a network is actively blocking script.google.com, that is handled by
 * the multi-id dispatcher (rotating between alternate Apps Script
 * deployment IDs) — not by faking certificates or rewriting SNI.
 */
class RelayClient(
    private val cfg: RelayConfig,
    private val rcfg: RuntimeRelayConfig,
    private val multiId: MultiIdDispatcher,
    private val onLatency: ((gas: Double, cf: Double, relay: Double) -> Unit)? = null,
) {
    private val gasHost = "script.google.com"
    private val authKey = rcfg.authKey

    private val concurrencySem = Semaphore(cfg.maxRelayConcurrency)

    private val batchLock = Mutex()
    private val batchPending = mutableListOf<PendingRequest>()
    private var batchTimerJob: Job? = null
    @Volatile private var batchEnabled = true
    private var batchDisabledAt = 0L
    private val batchReEnableMs = 30_000L
    private var consecutiveBatchFails = 0

    private data class CoalesceEntry(
        val leaderDef: CompletableDeferred<Response>,
        val followers: MutableList<CompletableDeferred<Response>> = mutableListOf(),
    )
    private val coalesce = ConcurrentHashMap<String, CoalesceEntry>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * The HTTPS client used to reach the GAS relay. Built once via the
     * hardened [SecureHttpClient]: TLS 1.2/1.3, system trust, certificate
     * pinning on Google roots, default hostname verifier.
     */
    private val http: OkHttpClient = SecureHttpClient.buildGasClient(
        connectTimeoutMs = 10_000L,
        readTimeoutMs = cfg.relayTimeoutMs.toLong(),
        writeTimeoutMs = cfg.relayTimeoutMs.toLong(),
        callTimeoutMs = (cfg.relayTimeoutMs + 5_000).toLong(),
        poolSize = cfg.poolMinIdle.coerceAtLeast(4),
    )

    private val jsonMedia = "application/json".toMediaType()

    private val youtubeSuffixes = listOf(
        "youtube.com", "youtu.be", "youtube-nocookie.com",
        "googlevideo.com", "ytimg.com", "ggpht.com", "gvt1.com", "gvt2.com",
    )

    private val largeExts = setOf(
        "zip", "tar", "gz", "bz2", "xz", "7z", "rar",
        "exe", "msi", "dmg", "deb", "rpm", "apk",
        "iso", "img",
        "mp4", "mkv", "avi", "mov", "webm",
        "mp3", "flac", "wav", "aac",
        "pdf", "doc", "docx", "ppt", "pptx", "wasm",
    )

    init {
        runCatching {
            multiId.configure(
                rcfg.scriptIds,
                failThreshold = cfg.multiIdFailThreshold,
                cooldownSec = cfg.multiIdCooldownSec.toLong(),
            )
        }
    }

    fun shutdown() {
        runCatching { scope.cancel() }
        runCatching { http.dispatcher.executorService.shutdown() }
        runCatching { http.connectionPool.evictAll() }
    }

    suspend fun relay(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: ByteArray = ByteArray(0),
    ): Response {
        return try {
            val payload = buildPayload(method, url, headers, body)
            val hasRange = headers.keys.any { it.equals("range", true) }
            if (method.equals("GET", true) && body.isEmpty() && !hasRange) {
                coalescedSubmit(url, payload)
            } else {
                batchSubmit(payload)
            }
        } catch (e: Throwable) {
            LogBus.e("Relay", "relay() top-level failure: ${e.message}", e)
            Response.error(502, "relay error: ${e.message ?: e.toString()}")
        }
    }

    suspend fun relaySmart(
        method: String, url: String, headers: Map<String, String>, body: ByteArray,
    ): Response {
        return try {
            if (method.equals("GET", true) && body.isEmpty()) {
                if (headers.keys.any { it.equals("range", true) }) {
                    return relay(method, url, headers, body)
                }
                if (isLikelyDownload(url)) {
                    return relayParallel(method, url, headers, body)
                }
            }
            relay(method, url, headers, body)
        } catch (e: Throwable) {
            LogBus.e("Relay", "relaySmart() failure: ${e.message}", e)
            Response.error(502, "relay error: ${e.message ?: e.toString()}")
        }
    }

    private fun isLikelyDownload(url: String): Boolean {
        val path = url.substringBefore("?").lowercase()
        val ext = path.substringAfterLast('.', "")
        return ext in largeExts
    }

    private fun isYoutubeUrl(url: String): Boolean {
        val host = try {
            URI(url).host?.lowercase()?.trimEnd('.') ?: return false
        } catch (_: Throwable) { return false }
        return youtubeSuffixes.any { host == it || host.endsWith(".$it") }
    }

    private fun buildPayload(
        method: String, url: String, headers: Map<String, String>, body: ByteArray,
    ): JsonObject = buildJsonObject {
        put("m", method.uppercase())
        put("u", url)
        put("r", true)
        if (cfg.youtubeViaRelay && isYoutubeUrl(url)) put("youtube_via_relay", true)
        if (headers.isNotEmpty()) {
            put("h", buildJsonObject {
                headers.forEach { (k, v) ->
                    if (!k.equals("accept-encoding", true)) put(k, v)
                }
            })
        }
        if (body.isNotEmpty()) {
            put("b", Base64.encodeToString(body, Base64.NO_WRAP))
            (headers["Content-Type"] ?: headers["content-type"])?.let { put("ct", it) }
        }
    }

    private suspend fun coalescedSubmit(url: String, payload: JsonObject): Response {
        val mine = CompletableDeferred<Response>()
        val entry: CoalesceEntry = coalesce.compute(url) { _, existing ->
            if (existing != null) {
                existing.followers += mine
                existing
            } else {
                CoalesceEntry(leaderDef = mine)
            }
        } ?: return Response.error(502, "coalesce returned null")

        if (entry.leaderDef !== mine) {
            return mine.await()
        }

        return try {
            val r = batchSubmit(payload)
            coalesce.remove(url)
            for (f in entry.followers) runCatching { f.complete(r) }
            entry.leaderDef.complete(r)
            r
        } catch (e: Throwable) {
            coalesce.remove(url)
            for (f in entry.followers) runCatching { f.completeExceptionally(e) }
            entry.leaderDef.completeExceptionally(e)
            throw e
        }
    }

    private data class PendingRequest(
        val payload: JsonObject,
        val deferred: CompletableDeferred<Response>,
    )

    private suspend fun batchSubmit(payload: JsonObject): Response {
        if (!batchEnabled) {
            if (System.currentTimeMillis() - batchDisabledAt > batchReEnableMs) {
                batchEnabled = true
                consecutiveBatchFails = 0
                LogBus.i("Relay", "Batching re-enabled after TTL")
            }
        }
        if (!batchEnabled) return relayWithRetry(payload)

        val def = CompletableDeferred<Response>()
        batchLock.withLock {
            batchPending += PendingRequest(payload, def)
            if (batchPending.size >= cfg.batchMax) {
                val batch = batchPending.toList()
                batchPending.clear()
                batchTimerJob?.cancel(); batchTimerJob = null
                scope.launch { batchSend(batch) }
            } else if (batchTimerJob == null) {
                batchTimerJob = scope.launch { batchTimer() }
            }
            Unit
        }
        return def.await()
    }

    private suspend fun batchTimer() {
        delay(cfg.batchWindowMicroMs.toLong())
        batchLock.withLock {
            if (batchPending.size <= 1) {
                if (batchPending.isNotEmpty()) {
                    val batch = batchPending.toList()
                    batchPending.clear()
                    batchTimerJob = null
                    scope.launch { batchSend(batch) }
                }
                return
            }
        }
        delay((cfg.batchWindowMacroMs - cfg.batchWindowMicroMs).toLong().coerceAtLeast(1))
        batchLock.withLock {
            if (batchPending.isNotEmpty()) {
                val batch = batchPending.toList()
                batchPending.clear()
                batchTimerJob = null
                scope.launch { batchSend(batch) }
            }
            Unit
        }
    }

    private suspend fun batchSend(batch: List<PendingRequest>) {
        if (batch.size == 1) {
            val item = batch[0]
            try {
                item.deferred.complete(relayWithRetry(item.payload))
            } catch (e: Throwable) {
                runCatching { item.deferred.complete(Response.error(502, "relay error: ${e.message}")) }
            }
            return
        }
        try {
            val results = relayBatch(batch.map { it.payload })
            results.forEachIndexed { i, r -> runCatching { batch[i].deferred.complete(r) } }
        } catch (e: Throwable) {
            consecutiveBatchFails++
            if (consecutiveBatchFails >= 3 && batchEnabled) {
                batchEnabled = false
                batchDisabledAt = System.currentTimeMillis()
                LogBus.w("Relay", "Batching disabled after $consecutiveBatchFails fails; TTL ${batchReEnableMs}ms")
            }
            for (item in batch) {
                try {
                    item.deferred.complete(relayWithRetry(item.payload))
                } catch (e2: Throwable) {
                    runCatching { item.deferred.complete(Response.error(502, e2.message ?: "relay error")) }
                }
            }
        }
    }

    private suspend fun relayWithRetry(payload: JsonObject): Response = concurrencySem.withPermit {
        var last: Throwable? = null
        for (attempt in 0 until cfg.relayRetryCount) {
            val sid = multiId.nextId() ?: rcfg.scriptIds.firstOrNull()
                ?: return Response.error(502, "no relay endpoints configured")
            val t0 = System.currentTimeMillis()
            try {
                val resp = postJson(execPath(sid), payload)
                val latency = (System.currentTimeMillis() - t0).toDouble()
                multiId.reportOk(sid, latency)
                runCatching { onLatency?.invoke(latency, parseCfLatency(resp), latency) }
                return parseRelayResponse(resp.body)
            } catch (e: Throwable) {
                last = e
                runCatching { multiId.reportErr(sid) }
            }
        }
        throw last ?: RuntimeException("relay failed")
    }

    private suspend fun relayBatch(payloads: List<JsonObject>): List<Response> = concurrencySem.withPermit {
        val sid = multiId.nextId() ?: rcfg.scriptIds.firstOrNull()
            ?: throw RuntimeException("no relay endpoints configured")
        val body = buildJsonObject {
            put("k", authKey)
            put("q", JsonArray(payloads))
        }
        val t0 = System.currentTimeMillis()
        try {
            val raw = postJson(execPath(sid), body)
            val latency = (System.currentTimeMillis() - t0).toDouble()
            multiId.reportOk(sid, latency)
            runCatching { onLatency?.invoke(latency, parseCfLatency(raw), latency) }
            val text = raw.body.toString(Charsets.UTF_8).trim()
            val data = Json.parseToJsonElement(text).jsonObject
            data["e"]?.let { throw RuntimeException("batch error: $it") }
            val items = data["q"]?.jsonArray ?: throw RuntimeException("missing q[]")
            return items.map { parseRelayJson(it.jsonObject) }
        } catch (e: Throwable) {
            runCatching { multiId.reportErr(sid) }
            throw e
        }
    }

    private fun execPath(sid: String) = "/macros/s/$sid/exec"

    private suspend fun postJson(path: String, payload: JsonObject): RawResponse =
        withContext(Dispatchers.IO) {
            val withKey = JsonObject(payload + ("k" to JsonPrimitive(authKey)))
            val req = Request.Builder()
                .url("https://$gasHost$path")
                .post(withKey.toString().toRequestBody(jsonMedia))
                .header("Accept-Encoding", "gzip")
                .header("Connection", "keep-alive")
                .build()
            http.newCall(req).execute().use { r ->
                val bytes = r.body?.bytes() ?: ByteArray(0)
                val hdrs = r.headers.toMultimap().mapValues { it.value.firstOrNull() ?: "" }
                RawResponse(r.code, hdrs, bytes)
            }
        }

    private fun parseCfLatency(raw: RawResponse): Double =
        raw.headers["x-cf-relay"]?.toDoubleOrNull() ?: 0.0

    private fun parseRelayResponse(body: ByteArray): Response {
        val text = body.toString(Charsets.UTF_8).trim()
        if (text.isEmpty()) return Response.error(502, "empty relay response")
        val data = try {
            Json.parseToJsonElement(text).jsonObject
        } catch (e: Throwable) { return Response.error(502, "bad relay JSON: ${e.message}") }
        return parseRelayJson(data)
    }

    private fun parseRelayJson(data: JsonObject): Response {
        data["e"]?.let { return Response.error(502, "relay error: $it") }
        val status = data["s"]?.jsonPrimitive?.intOrNull ?: 200
        val headers = mutableMapOf<String, String>()
        (data["h"] as? JsonObject)?.forEach { (k, v) ->
            when (v) {
                is JsonArray -> headers[k] = v.joinToString(", ") {
                    (it as? JsonPrimitive)?.contentOrNull ?: it.toString()
                }
                is JsonPrimitive -> headers[k] = v.contentOrNull ?: ""
                else -> headers[k] = v.toString()
            }
        }
        val bodyB64 = (data["b"] as? JsonPrimitive)?.contentOrNull ?: ""
        val parsedBody = if (bodyB64.isEmpty()) ByteArray(0)
        else try { Base64.decode(bodyB64, Base64.DEFAULT) } catch (_: Throwable) { ByteArray(0) }
        return Response(status, headers, parsedBody)
    }

    suspend fun relayParallel(
        method: String, url: String, headers: Map<String, String>, body: ByteArray,
        chunkSize: Int = cfg.chunkSize, maxParallel: Int = cfg.maxParallel,
    ): Response {
        if (!method.equals("GET", true) || body.isNotEmpty()) {
            return relay(method, url, headers, body)
        }
        val firstHeaders = headers.toMutableMap().apply { put("Range", "bytes=0-${chunkSize - 1}") }
        val first = relay("GET", url, firstHeaders, ByteArray(0))
        if (first.status != 206) return first
        val totalSize = (first.headers.entries.firstOrNull { it.key.equals("content-range", true) }
            ?.value ?: "")
            .substringAfter('/').toLongOrNull() ?: return rewrite206To200(first)
        if (totalSize <= chunkSize || first.body.size >= totalSize) return rewrite206To200(first)

        val ranges = mutableListOf<Pair<Long, Long>>()
        var start = first.body.size.toLong()
        while (start < totalSize) {
            val end = min(start + chunkSize - 1, totalSize - 1)
            ranges += start to end
            start = end + 1
        }
        val sem = Semaphore(maxParallel)
        val parts = ArrayList<ByteArray>(ranges.size + 1)
        parts.add(first.body)
        coroutineScope {
            val deferreds = ranges.map { (s, e) ->
                async {
                    sem.withPermit {
                        val rh = headers.toMutableMap().apply { put("Range", "bytes=$s-$e") }
                        var lastErr: Throwable? = null
                        repeat(3) { attempt ->
                            try {
                                val r = relay("GET", url, rh, ByteArray(0))
                                val expected = (e - s + 1).toInt()
                                if (r.body.size == expected) return@withPermit r.body
                            } catch (t: Throwable) { lastErr = t }
                            delay(300L * (attempt + 1))
                        }
                        throw RuntimeException("range $s-$e failed", lastErr)
                    }
                }
            }
            for (d in deferreds) parts.add(d.await())
        }

        val totalBytes = parts.sumOf { it.size }
        val full = ByteArray(totalBytes)
        var off = 0
        for (p in parts) {
            System.arraycopy(p, 0, full, off, p.size)
            off += p.size
        }

        val outHeaders = first.headers.toMutableMap().also { hdrs ->
            hdrs.entries.removeAll { e ->
                e.key.lowercase() in setOf(
                    "content-length", "content-range", "content-encoding",
                    "transfer-encoding", "connection",
                )
            }
            hdrs["Content-Length"] = full.size.toString()
        }
        return Response(200, outHeaders, full)
    }

    private fun rewrite206To200(r: Response): Response {
        val h = r.headers.toMutableMap()
        h.entries.removeAll { it.key.equals("content-range", true) }
        h["Content-Length"] = r.body.size.toString()
        return Response(200, h, r.body)
    }
}

data class RawResponse(val code: Int, val headers: Map<String, String>, val body: ByteArray)

data class Response(
    val status: Int,
    val headers: Map<String, String>,
    val body: ByteArray,
) {
    companion object {
        fun error(status: Int, message: String): Response =
            Response(status, mapOf("Content-Type" to "text/plain"), message.toByteArray())
    }

    fun toHttpBytes(): ByteArray {
        val sb = StringBuilder()
        val statusText = when (status) {
            200 -> "OK"; 206 -> "Partial Content"
            301 -> "Moved"; 302 -> "Found"; 304 -> "Not Modified"
            400 -> "Bad Request"; 401 -> "Unauthorized"; 403 -> "Forbidden"
            404 -> "Not Found"; 429 -> "Too Many Requests"
            500 -> "Internal Server Error"; 502 -> "Bad Gateway"
            else -> "OK"
        }
        sb.append("HTTP/1.1 $status $statusText\r\n")
        val skip = setOf(
            "transfer-encoding", "connection", "keep-alive",
            "content-length", "content-encoding"
        )
        for ((k, v) in headers) {
            if (k.lowercase() in skip) continue
            sb.append("$k: $v\r\n")
        }
        sb.append("Content-Length: ${body.size}\r\n\r\n")
        return sb.toString().toByteArray() + body
    }
}
