package com.velum.vpn.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicLong

/**
 * Thread-safe stats counter. Mirrors the Windows counterpart so the
 * dashboard reads identical metrics on both platforms.
 */
class Stats(private val windowSeconds: Int = 60) {
    private val bytesUp = AtomicLong(0)
    private val bytesDown = AtomicLong(0)
    private val requests = AtomicLong(0)
    @Volatile private var connOpen = 0
    @Volatile private var connPeak = 0
    private val started = System.currentTimeMillis()

    @Volatile private var lastT = System.currentTimeMillis()
    @Volatile private var lastUp = 0L
    @Volatile private var lastDown = 0L
    @Volatile private var lastRequests = 0L

    private val seriesUp = ArrayDeque<Pair<Long, Double>>()
    private val seriesDown = ArrayDeque<Pair<Long, Double>>()

    @Volatile var gasLatencyMs: Double = 0.0
    @Volatile var cfLatencyMs: Double = 0.0
    @Volatile var relayLatencyMs: Double = 0.0
    @Volatile var cacheHits: Long = 0
    @Volatile var cacheMisses: Long = 0

    private val _flow = MutableStateFlow(buildSnapshot())
    val flow: StateFlow<StatsSnapshot> = _flow

    fun addUp(n: Long) { if (n > 0) bytesUp.addAndGet(n) }
    fun addDown(n: Long) { if (n > 0) bytesDown.addAndGet(n) }
    fun incrRequests() { requests.incrementAndGet() }

    @Synchronized fun connOpened() {
        connOpen++
        if (connOpen > connPeak) connPeak = connOpen
    }
    @Synchronized fun connClosed() { if (connOpen > 0) connOpen-- }

    @Synchronized
    private fun buildSnapshot(): StatsSnapshot {
        val now = System.currentTimeMillis()
        val up = bytesUp.get(); val down = bytesDown.get()
        val req = requests.get()
        val dt = ((now - lastT).coerceAtLeast(1L)).toDouble() / 1000.0
        val sup = (up - lastUp) / dt
        val sdown = (down - lastDown) / dt
        val rps = (req - lastRequests) / dt
        lastT = now; lastUp = up; lastDown = down; lastRequests = req
        seriesUp.addLast(now to sup); seriesDown.addLast(now to sdown)
        while (seriesUp.size > windowSeconds) seriesUp.removeFirst()
        while (seriesDown.size > windowSeconds) seriesDown.removeFirst()
        val total = cacheHits + cacheMisses
        val hitRate = if (total > 0) cacheHits.toDouble() / total * 100.0 else 0.0
        return StatsSnapshot(
            uptimeSec = ((now - started) / 1000).toInt(),
            bytesUp = up, bytesDown = down,
            speedUp = sup, speedDown = sdown,
            requests = req, requestsPerSec = rps,
            connections = connOpen, peakConnections = connPeak,
            gasLatencyMs = gasLatencyMs,
            cfLatencyMs = cfLatencyMs,
            relayLatencyMs = relayLatencyMs,
            cacheHitRate = hitRate,
            seriesUp = seriesUp.toList(),
            seriesDown = seriesDown.toList(),
        )
    }

    @Synchronized
    fun snapshot(): StatsSnapshot {
        val snap = buildSnapshot()
        _flow.value = snap
        return snap
    }
}

data class StatsSnapshot(
    val uptimeSec: Int,
    val bytesUp: Long,
    val bytesDown: Long,
    val speedUp: Double,
    val speedDown: Double,
    val requests: Long,
    val requestsPerSec: Double,
    val connections: Int,
    val peakConnections: Int,
    val gasLatencyMs: Double,
    val cfLatencyMs: Double,
    val relayLatencyMs: Double,
    val cacheHitRate: Double,
    val seriesUp: List<Pair<Long, Double>>,
    val seriesDown: List<Pair<Long, Double>>,
)
