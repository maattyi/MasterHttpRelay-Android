package com.velum.vpn.core

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.*

/**
 * Lightweight in-memory log ring for the Logs screen.
 * Wraps [Log] so anywhere in the app that uses Android logcat is also
 * mirrored into the UI.
 */
object LogBus {
    data class Line(val id: Long, val text: String, val level: String)

    private const val MAX = 2000
    private val list = ArrayDeque<Line>()
    private var seq = 0L
    private val df = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val _flow = MutableStateFlow<List<Line>>(emptyList())
    val flow: StateFlow<List<Line>> = _flow

    @Synchronized
    fun add(level: String, tag: String, msg: String) {
        val line = Line(++seq, "${df.format(Date())} [$level] $tag: $msg", level)
        list.addLast(line)
        while (list.size > MAX) list.removeFirst()
        _flow.value = list.toList()
    }

    @Synchronized
    fun clear() { list.clear(); _flow.value = emptyList() }

    fun i(tag: String, msg: String) { Log.i(tag, msg); add("INFO", tag, msg) }
    fun d(tag: String, msg: String) { Log.d(tag, msg); add("DEBUG", tag, msg) }
    fun w(tag: String, msg: String) { Log.w(tag, msg); add("WARNING", tag, msg) }
    fun e(tag: String, msg: String, t: Throwable? = null) {
        Log.e(tag, msg, t)
        add("ERROR", tag, if (t != null) "$msg :: ${t.message}" else msg)
    }
}
