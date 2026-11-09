package com.dobby.control

import java.util.ArrayDeque

/** Journal à l'écran : anneau des dernières lignes, affiché dans l'appli. */
object Logger {
    private val lines = ArrayDeque<String>()
    private const val MAX = 40
    private var t0 = System.currentTimeMillis()

    @Synchronized fun log(s: String) {
        val t = (System.currentTimeMillis() - t0) / 1000.0
        lines.addLast(String.format("%6.1f  %s", t, s))
        while (lines.size > MAX) lines.removeFirst()
    }

    /** Dernières [n] lignes, de la plus récente à la plus ancienne. */
    @Synchronized fun dump(n: Int = 14): String {
        val out = StringBuilder()
        val it = lines.descendingIterator()
        var c = 0
        while (it.hasNext() && c < n) { out.append(it.next()).append('\n'); c++ }
        return out.toString()
    }

    fun hex(b: ByteArray, len: Int = b.size): String {
        val sb = StringBuilder()
        for (i in 0 until minOf(len, b.size)) sb.append(String.format("%02x ", b[i].toInt() and 0xFF))
        return sb.toString().trim()
    }
}
