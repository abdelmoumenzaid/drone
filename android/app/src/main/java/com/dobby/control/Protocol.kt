package com.dobby.control

/**
 * Protocole de contrôle du ZeroTech Dobby (reconstitué depuis DoFun 2.2.6).
 *
 * Paquet générique :  A0 | LEN | MODULE | CMD | SEQ(2) | payload | CHECKSUM
 * Checksum = somme additive de tous les octets sauf le dernier, & 0xFF.
 * Tout est confirmé par décompilation ; décollage/atterrissage à confirmer par capture.
 */
object Protocol {
    const val NEUTRAL = 1500

    // Heartbeat fixe (confirmé) : A0 05 E0 C0 45
    val HEARTBEAT = byteArrayOf(0xA0.toByte(), 0x05, 0xE0.toByte(), 0xC0.toByte(), 0x45)

    private fun checksum(buf: ByteArray): Int {
        var s = 0
        for (i in 0 until buf.size - 1) s += buf[i].toInt() and 0xFF
        return s and 0xFF
    }

    /** Paquet RC : A0 18 01 + 10 canaux (16 bits big-endian) + checksum. 24 octets. */
    fun rc(roll: Int, pitch: Int, throttle: Int, yaw: Int, counter: Int): ByteArray {
        // Seuls les 4 manches sont bornés à 1000..2000.
        // Le canal 8 est un compteur libre (wifiDelayCh8 dans DoFun) : le drone mesure la
        // qualité de liaison avec. S'il est figé, le drone croit la liaison perdue et se pose.
        val ch = intArrayOf(
            roll.coerceIn(1000, 2000), pitch.coerceIn(1000, 2000),
            throttle.coerceIn(1000, 2000), yaw.coerceIn(1000, 2000),
            NEUTRAL, NEUTRAL, NEUTRAL, counter, NEUTRAL, NEUTRAL)
        val buf = ByteArray(24)
        buf[0] = 0xA0.toByte()
        buf[1] = 24
        buf[2] = 0x01
        for (i in 0 until 10) {
            val v = ch[i] and 0xFFFF
            buf[3 + i * 2] = ((v ushr 8) and 0xFF).toByte()
            buf[4 + i * 2] = (v and 0xFF).toByte()
        }
        buf[23] = checksum(buf).toByte()
        return buf
    }

    /** Commande générique : A0 LEN <module> <cmd> <seq_hi> <seq_lo> [payload] <chk>. */
    fun cmd(module: Int, cmd: Int, seq: Int, payload: ByteArray = ByteArray(0)): ByteArray {
        val len = 7 + payload.size
        val buf = ByteArray(len)
        buf[0] = 0xA0.toByte()
        buf[1] = (len and 0xFF).toByte()
        buf[2] = (module and 0xFF).toByte()
        buf[3] = (cmd and 0xFF).toByte()
        buf[4] = ((seq ushr 8) and 0xFF).toByte()
        buf[5] = (seq and 0xFF).toByte()
        for (i in payload.indices) buf[6 + i] = payload[i]
        buf[len - 1] = checksum(buf).toByte()
        return buf
    }

    fun moduleCmd(module: Int, cmd: Int, seq: Int): ByteArray = cmd(module, cmd, seq)

    /** Décollage : f() dans DoFun = A0 07 46 46 46 46 05 (sans numéro de séquence). */
    fun takeoffPacket(): ByteArray {
        val buf = byteArrayOf(0xA0.toByte(), 0x07, 0x46, 0x46, 0x46, 0x46, 0x00)
        buf[6] = checksum(buf).toByte()
        return buf
    }

    /** Atterrissage : o() dans DoFun = A0 0D 51 2C <seq> 2C 2C 2C 2C 00 00 <chk>. */
    fun landPacket(seq: Int): ByteArray =
        cmd(0x51, 0x2C, seq, byteArrayOf(0x2C, 0x2C, 0x2C, 0x2C, 0x00, 0x00))

    /**
     * Démarre le flux vidéo : module 0x7D, cmd 0x01, paramètre = port local (little-endian).
     * A0 09 7D 01 <seq_hi> <seq_lo> <port_lo> <port_hi> <chk>.
     */
    fun startRtp(port: Int, seq: Int): ByteArray {
        val buf = ByteArray(9)
        buf[0] = 0xA0.toByte()
        buf[1] = 9
        buf[2] = 0x7D
        buf[3] = 0x01
        buf[4] = ((seq ushr 8) and 0xFF).toByte()
        buf[5] = (seq and 0xFF).toByte()
        buf[6] = (port and 0xFF).toByte()
        buf[7] = ((port ushr 8) and 0xFF).toByte()
        buf[8] = checksum(buf).toByte()
        return buf
    }
}
