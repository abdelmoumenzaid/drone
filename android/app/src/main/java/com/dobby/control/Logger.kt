package com.dobby.control

import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.util.ArrayDeque

/** Journal : à l'écran (anneau), ET dans un fichier texte partageable. */
object Logger {
    private val lines = ArrayDeque<String>()
    private const val MAX = 400
    private var t0 = System.currentTimeMillis()
    private var writer: BufferedWriter? = null
    var filePath: String = ""
        private set

    /** Ouvre le fichier de log (écrase l'ancien). Appelé au démarrage. */
    @Synchronized fun init(dir: File?) {
        t0 = System.currentTimeMillis()
        try {
            if (dir != null) {
                if (!dir.exists()) dir.mkdirs()
                val f = File(dir, "dobby_log.txt")
                writer = BufferedWriter(FileWriter(f, false))
                filePath = f.absolutePath
                writer?.write("=== Dobby Control log ===\n")
                writer?.flush()
            }
        } catch (_: Exception) { writer = null }
    }

    @Synchronized fun log(s: String) {
        val t = (System.currentTimeMillis() - t0) / 1000.0
        val line = String.format("%7.1f  %s", t, s)
        lines.addLast(line)
        while (lines.size > MAX) lines.removeFirst()
        try { writer?.write(line); writer?.write("\n"); writer?.flush() } catch (_: Exception) {}
    }

    /** Les [n] dernières lignes (récent en haut), pour l'affichage. */
    @Synchronized fun dump(n: Int = 14): String {
        val out = StringBuilder()
        val it = lines.descendingIterator()
        var c = 0
        while (it.hasNext() && c < n) { out.append(it.next()).append('\n'); c++ }
        return out.toString()
    }

    /** Tout le journal (ancien → récent), pour le partage. */
    @Synchronized fun fullText(): String {
        val out = StringBuilder()
        for (l in lines) out.append(l).append('\n')
        return out.toString()
    }

    fun hex(b: ByteArray, len: Int = b.size): String {
        val sb = StringBuilder()
        for (i in 0 until minOf(len, b.size)) sb.append(String.format("%02x ", b[i].toInt() and 0xFF))
        return sb.toString().trim()
    }
}
