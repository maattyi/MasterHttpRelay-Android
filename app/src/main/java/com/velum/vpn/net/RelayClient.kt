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
import java.io.ByteArrayOutputStream
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import java.security.cert.X509Certificate
import kotlin.math.min

/**
 * Android port of the V3 relay engine.
 *
 * Wire-protocol identical to the Python desktop client.  Differences:
 *   - OkHttp owns the connection pool & HTTP/2 multiplexing (it's already
 *     production-grade, no need to re-implement h2 manually)
 *   - Two-tier batch window (5 ms micro / 50 ms macro) restored from V1.2
 *   - youtube_via_relay flag pass-through (V2 fix retained)
 *   - Single-attempt retry, no retry storms
 *   - Coalescing of concurrent identical GETs
 *   - Parallel range download (16-way) for likely large files
 */
class RelayClient(
    private val cfg: RelayConfig,
    private val rcfg: RuntimeRelayConfig,
    private val multiId: MultiIdDispatcher,
    private val onLatency: ((gas: Double, cf: Double, relay: Double) -> Unit)? = null,
) {
    private val gasHost = "script.google.com"
    private val frontDomain = rcfg.frontDomain
    private val googleIp = rcfg.googleIp
    private val authKey = rcfg.authKey

    private val concurrencySem = Semaphore(cfg.maxRelayConcurrency)

    private val batchLock = Mutex()
    private val batchPending = mutableListOf<PendingRequest>()
    private var batchTimerJob: Job? = null
    @Volatile private var batchEnabled = true
    private var batchDisabledAt = 0L          // Fix #4: timestamp when batch was disabled
    private val batchReEnableMs = 30_000L    // re-enable after 30 s
    private var consecutiveBatchFails = 0    // Fix #4: count failures before disabling

    // ── Coalescing (Fix #1 + #2: atomic compute with leader self-registration) ──
    private data class CoalesceEntry(
        val leaderDef: CompletableDeferred<Response>,
        val followers: MutableList<CompletableDeferred<Response>> = mutableListOf(),
    )
    private val coalesce = ConcurrentHashMap<String, CoalesceEntry>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * OkHttp client tuned for the relay path. The custom DNS overrides
     * `script.google.com` to the configured googleIp so the TCP connection
     * lands on Google's edge while TLS SNI uses [frontDomain].
     */
    private val http: OkHttpClient = run {
        val trustAll: TrustManager = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }
        val ctx = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(trustAll), java.security.SecureRandom())
        }
        val ssf: SSLSocketFactory = SniRewriteSocketFactory(ctx.socketFactory, frontDomain)

        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(cfg.relayTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .writeTimeout(cfg.relayTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .callTimeout(cfg.relayTimeoutMs.toLong() + 5_000, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(true)
            .connectionPool(ConnectionPool(cfg.poolMinIdle, 45, TimeUnit.SECONDS))
            .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
            .sslSocketFactory(ssf, trustAll as X509TrustManager)
            .hostnameVerifier { _, _ -> true }
            .dns(object : Dns {
                override fun lookup(hostname: String): List<java.net.InetAddress> {
                    if (hostname.equals(gasHost, ignoreCase = true)) {
                        return listOf(java.net.InetAddress.getByName(googleIp))
                    }
                    return Dns.SYSTEM.lookup(hostname)
                }
            })
            .build()
    }

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
        multiId.configure(
            rcfg.scriptIds,
            failThreshold = cfg.multiIdFailThreshold,
            cooldownSec = cfg.multiIdCooldownSec.toLong(),
        )
    }

    fun shutdown() {
        scope.cancel()
        http.dispatcher.executorService.shutdown()
        http.connectionPool.evictAll()
    }

    /**
     * Public entrypoint: relay an HTTP request through GAS.
     * Returns a synthetic [Response] containing the upstream status / headers / body.
     */
    suspend fun relay(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: ByteArray = ByteArray(0),
    ): Response {
        val payload = buildPayload(method, url, headers, body)
        val hasRange = headers.keys.any { it.equals("range", true) }
        if (method.equals("GET", true) && body.isEmpty() && !hasRange) {
            return coalescedSubmit(url, payload)
        }
        return batchSubmit(payload)
    }

    /**
     * Smart relay strategy: parallel range fetch for likely large downloads,
     * single relay otherwise. Mirrors the desktop ProxyServer._relay_smart.
     */
    suspend fun relaySmart(
        method: String, url: String, headers: Map<String, String>, body: ByteArray,
    ): Response {
        if (method.equals("GET", true) && body.isEmpty()) {
            if (headers.keys.any { it.equals("range", true) }) {
                return relay(method, url, headers, body)
            }
            if (isLikelyDownload(url)) {
                return relayParallel(method, url, headers, body)
            }
        }
        return relay(method, url, headers, body)
    }

    private fun isLikelyDownload(url: String): Boolean {
        val path = url.substringBefore("?").lowercase()
        val ext = path.substringAfterLast('.', "")
        return ext in largeExts
    }

    private fun isYoutubeUrl(url: String): Boolean {
        val host = try { URI(url).host?.lowercase()?.trimEnd('.') ?: return false }
                   catch (_: Exception) { return false }
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

    // ── Coalescing (Fix #1 + #2: atomic compute, no runBlocking) ──────

    private suspend fun coalescedSubmit(url: String, payload: JsonObject): Response {
        val mine = CompletableDeferred<Response>()
        val entry = coalesce.compute(url) { _, existing ->
            existing?.also { it.followers += mine } ?: CoalesceEntry(mine)
        }!!
        // Follower path: just await the leader's result
        if (entry.leaderDef !== mine) return mine.await()
        // Leader path: execute and fan-out
        return try {
            val r = batchSubmit(payload)
            coalesce.remove(url)
            entry.followers.forEach { it.complete(r) }
            entry.leaderDef.complete(r)
            r
        } catch (e: Throwable) {
            coalesce.remove(url)
            entry.followers.forEach { it.completeExceptionally(e) }
            entry.leaderDef.completeExceptionally(e)
            throw e
        }
    }

    // ── Batching ──────────────────────────────────────────────────────

    private data class PendingRequest(
        val payload: JsonObject,
        val deferred: CompletableDeferred<Response>,
    )

    private suspend fun batchSubmit(payload: JsonObject): Response {
        // Fix #4: TTL-based re-enable for batchEnabled
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
                item.deferred.complete(Response.error(502, "relay error: $e"))
            }
            return
        }
        try {
            val results = relayBatch(batch.map { it.payload })
            results.forEachIndexed { i, r -> batch[i].deferred.complete(r) }
        } catch (e: Throwable) {
            // Fix #4: disable batching after consecutive failures, with TTL re-enable
            consecutiveBatchFails++
            if (consecutiveBatchFails >= 3 && batchEnabled) {
                batchEnabled = false
                batchDisabledAt = System.currentTimeMillis()
                LogBus.w("Relay", "Batching disabled after $consecutiveBatchFails failures; re-enabling in ${batchReEnableMs}ms")
            }
            for (item in batch) {
                try {
                    item.deferred.complete(relayWithRetry(item.payload))
                } catch (e2: Throwable) {
                    item.deferred.complete(Response.error(502, e2.message ?: "relay error"))
                }
            }
        }
    }

    // ── Core relay path ───────────────────────────────────────────────

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
                onLatency?.invoke(latency, parseCfLatency(resp), latency)
                return parseRelayResponse(resp.body)
            } catch (e: Throwable) {
                last = e
                multiId.reportErr(sid)
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
            onLatency?.invoke(latency, parseCfLatency(raw), latency)
            val text = raw.body.toString(Charsets.UTF_8).trim()
            val data = Json.parseToJsonElement(text).jsonObject
            data["e"]?.let { throw RuntimeException("batch error: $it") }
            val items = data["q"]?.jsonArray ?: throw RuntimeException("missing q[]")
            return items.map { parseRelayJson(it.jsonObject) }
        } catch (e: Throwable) {
            multiId.reportErr(sid)
            throw e
        }
    }

    private fun execPath(sid: String) = "/macros/s/$sid/exec"

    /** Sends a single POST request with auth key injection. */
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
        val data = try { Json.parseToJsonElement(text).jsonObject }
                   catch (e: Throwable) { return Response.error(502, "bad relay JSON: $e") }
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
        val body = if (bodyB64.isEmpty()) ByteArray(0)
                   else Base64.decode(bodyB64, Base64.DEFAULT)
        return Response(status, headers, body)
    }

    // ── Parallel range download ───────────────────────────────────────

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
        val parts = mutableListOf(first.body)
        coroutineScope {
            val deferreds = ranges.map { (s, e) ->
                async {
                    sem.withPermit {
                        val rh = headers.toMutableMap().apply { put("Range", "bytes=$s-$e") }
                        repeat(3) { attempt ->
                            try {
                                val r = relay("GET", url, rh, ByteArray(0))
                                val expected = (e - s + 1).toInt()
                                if (r.body.size == expected) return@withPermit r.body
                            } catch (_: Throwable) {}
                            delay(300L * (attempt + 1))
                        }
                        throw RuntimeException("range $s-$e failed")
                    }
                }
            }
            deferreds.forEachIndexed { _, d -> parts += d.await() }
        }
        // Fix #3: O(N) assembly instead of O(N²) fold
        val totalBytes = parts.sumOf { it.size }
        val full = ByteArray(totalBytes)
        var off = 0
        for (p in parts) { System.arraycopy(p, 0, full, off, p.size); off += p.size }
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

    /** Materialise as raw HTTP/1.1 wire bytes (used by the proxy/VPN layer). */
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
        val skip = setOf("transfer-encoding", "connection", "keep-alive",
                         "content-length", "content-encoding")
        for ((k, v) in headers) {
            if (k.lowercase() in skip) continue
            sb.append("$k: $v\r\n")
        }
        sb.append("Content-Length: ${body.size}\r\n\r\n")
        return sb.toString().toByteArray() + body
    }
}
