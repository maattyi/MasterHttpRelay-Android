package com.velum.vpn.core

import kotlin.math.max
import kotlin.math.min

/**
 * Health-aware multi Apps-Script-deployment router.
 * Direct port of the Python implementation; same scoring formula so behavior
 * matches across platforms.
 */
class MultiIdDispatcher {
    private data class Endpoint(
        val sid: String,
        var ok: Long = 0,
        var err: Long = 0,
        var recentFailures: Int = 0,
        var parkedUntil: Long = 0,
        var latencyMs: Double = 0.0,
        var lastUsed: Long = 0,
        var uses: Long = 0,
    ) {
        fun successRate(): Double {
            val tot = ok + err
            return if (tot == 0L) 1.0 else ok.toDouble() / tot
        }
        fun score(now: Long): Double {
            val latencyPen = if (latencyMs > 0) min(latencyMs, 3000.0) / 100.0 else 0.0
            val failurePen = recentFailures * 20.0
            val cooldownPen = if (parkedUntil > now) 80.0 else 0.0
            val fairnessPen = min(uses, 20).toDouble() * 0.25
            return successRate() * 50.0 - latencyPen - failurePen - cooldownPen - fairnessPen
        }
    }

    private val lock = Any()
    private var endpoints: List<Endpoint> = emptyList()
    private var failThreshold = 3
    private var cooldownMs = 30_000L
    @Volatile var activeSid: String = ""

    fun configure(ids: List<String>, failThreshold: Int = 3, cooldownSec: Long = 30) {
        synchronized(lock) {
            val seen = mutableSetOf<String>()
            val cleaned = ids.mapNotNull { it.trim().takeIf { s -> s.isNotEmpty() && seen.add(s) } }
            endpoints = cleaned.map { Endpoint(it) }
            this.failThreshold = max(1, failThreshold)
            this.cooldownMs = max(5_000L, cooldownSec * 1000)
        }
    }

    fun nextId(): String? = synchronized(lock) {
        if (endpoints.isEmpty()) return null
        val now = System.currentTimeMillis()
        var live = endpoints.filter { it.parkedUntil <= now }
        if (live.isEmpty()) {
            val ep = endpoints.minBy { it.parkedUntil }
            ep.parkedUntil = 0
            ep.recentFailures = max(0, ep.recentFailures - 1)
            live = listOf(ep)
        }
        val ranked = live.sortedByDescending { it.score(now) }
        val top = ranked.first().score(now)
        val eligible = ranked.filter { top - it.score(now) <= 12.0 }.ifEmpty { ranked.take(1) }
        val chosen = eligible.minWith(compareBy({ it.uses }, { it.lastUsed }))
        chosen.uses++
        chosen.lastUsed = now
        activeSid = chosen.sid
        chosen.sid
    }

    fun reportOk(sid: String, latencyMs: Double = 0.0) = synchronized(lock) {
        endpoints.firstOrNull { it.sid == sid }?.let {
            it.ok++
            it.recentFailures = max(0, it.recentFailures - 1)
            if (latencyMs > 0) {
                it.latencyMs = if (it.latencyMs > 0) it.latencyMs * 0.7 + latencyMs * 0.3 else latencyMs
            }
        }
    }

    fun reportErr(sid: String) = synchronized(lock) {
        endpoints.firstOrNull { it.sid == sid }?.let {
            it.err++
            it.recentFailures++
            if (it.recentFailures >= failThreshold) {
                it.parkedUntil = System.currentTimeMillis() + cooldownMs
            }
        }
    }

    fun snapshot(): MultiIdSnapshot = synchronized(lock) {
        val now = System.currentTimeMillis()
        val healthy = endpoints.count { it.parkedUntil <= now }
        val totalOk = endpoints.sumOf { it.ok }
        val totalErr = endpoints.sumOf { it.err }
        val avgLatency = if (endpoints.isEmpty()) 0.0
                         else endpoints.sumOf { it.latencyMs } / endpoints.size
        MultiIdSnapshot(
            endpoints = endpoints.size,
            endpointsHealthy = healthy,
            latencyMs = avgLatency,
            successRate = if (totalOk + totalErr == 0L) 1.0
                          else totalOk.toDouble() / (totalOk + totalErr),
            activeEndpoint = if (activeSid.length > 12) activeSid.take(12) + "…" else activeSid,
        )
    }
}

data class MultiIdSnapshot(
    val endpoints: Int,
    val endpointsHealthy: Int,
    val latencyMs: Double,
    val successRate: Double,
    val activeEndpoint: String,
)
