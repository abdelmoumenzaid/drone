package com.dobby.control

import android.net.Network
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress

/**
 * Liaison UDP avec le drone, calquée sur DoFun.
 *  - RC à 20 Hz (canal 8 = compteur libre) + heartbeat 1 Hz,
 *  - vidéo : start-RTP envoyé DEPUIS CE SOCKET (port 8000), et quand le drone répond
 *    « OK », envoi de 7D/04, 7D/11, 7D/03 (poignée de main de l'événement 13 de DoFun),
 *  - journal de chaque type de réponse du drone (en-tête 0x0A).
 */
class DroneLink(private val wifi: Network?) {

    companion object {
        const val DRONE_IP = "192.168.1.1"
        const val PORT_CONTROL = 7088
        const val LOCAL_PORT = 8000
        const val RTP_PORT = 7078
    }

    @Volatile var roll = Protocol.NEUTRAL
    @Volatile var pitch = Protocol.NEUTRAL
    @Volatile var throttle = Protocol.NEUTRAL
    @Volatile var yaw = Protocol.NEUTRAL
    @Volatile var armed = false
    @Volatile var video = false

    // Diagnostic
    @Volatile var lastRxMs = 0L
    @Volatile var rxCount = 0
    @Volatile var maxRx = 0
    @Volatile var rtpAcks = 0          // réponses « OK » du drone à start-RTP
    @Volatile var cameraReplies = 0    // réponses du module caméra (0x51 / 0x7D)

    // État décodé du paquet 0x48 (ZOWarningMdel de DoFun)
    @Volatile var stateText = ""
    private var lastFly = -1
    private var lastMotor = -1
    private var lastWarn = -1L

    private var socket: DatagramSocket? = null
    private var thread: Thread? = null
    private var rxThread: Thread? = null
    @Volatile private var running = false
    private var seq = 0
    private var counter = 0
    private var rtpReqLogged = 0
    private val pending = ArrayList<ByteArray>()
    private val seen = HashMap<Int, Int>()   // (module<<8 | cmd) -> lignes déjà journalisées
    private lateinit var addr: InetAddress

    @Synchronized private fun nextSeq(): Int { seq = (seq + 1) and 0xFFFF; return seq }
    @Synchronized private fun enqueue(p: ByteArray) { pending.add(p) }

    fun start() {
        if (running) return
        running = true
        thread = Thread { loop() }.apply { isDaemon = true; start() }
    }

    fun stop() {
        running = false
        thread?.interrupt()
        socket?.close()
    }

    fun setNeutral() {
        roll = Protocol.NEUTRAL; pitch = Protocol.NEUTRAL
        throttle = Protocol.NEUTRAL; yaw = Protocol.NEUTRAL
    }

    fun takeoff() {
        val p = Protocol.takeoffPacket(); enqueue(p)
        Logger.log("TX décollage  ${Logger.hex(p)}")
    }

    fun land() {
        val p = Protocol.landPacket(nextSeq()); enqueue(p)
        Logger.log("TX atterrissage  ${Logger.hex(p)}")
    }

    /** Test : envoie une commande du module vol (0x7D) avec l'identifiant donné. */
    fun testFlight(cmdId: Int) {
        val p = Protocol.moduleCmd(0x7D, cmdId, nextSeq()); enqueue(p)
        Logger.log("TX test 7D/${String.format("%02x", cmdId)}  ${Logger.hex(p)}")
    }

    private fun loop() {
        try {
            addr = InetAddress.getByName(DRONE_IP)
            val s = DatagramSocket(null)
            s.reuseAddress = true
            wifi?.bindSocket(s)          // Android 6+ : forcer la route par le Wi-Fi du drone
            s.bind(InetSocketAddress(LOCAL_PORT))
            socket = s
        } catch (e: Exception) {
            try {
                val s = DatagramSocket()
                wifi?.bindSocket(s)
                socket = s
            } catch (_: Exception) { return }
        }
        Logger.log("Socket commande prête (port local ${socket?.localPort})")

        rxThread = Thread { receiveLoop() }.apply { isDaemon = true; start() }

        var lastHb = 0L
        var lastRtp = 0L
        while (running && !Thread.interrupted()) {
            val now = System.currentTimeMillis()
            counter = (counter + 1) and 0xFFFF
            val pkt = if (armed)
                Protocol.rc(roll, pitch, throttle, yaw, counter)
            else
                Protocol.rc(Protocol.NEUTRAL, Protocol.NEUTRAL, Protocol.NEUTRAL, Protocol.NEUTRAL, counter)
            send(pkt, PORT_CONTROL)

            // Chaque seconde, comme J() de DoFun : horloge, heartbeat, présentation de l'appli
            // au module caméra (sans elle, la caméra ne répond pas).
            if (now - lastHb >= 1000) {
                val local = now + java.util.TimeZone.getDefault().getOffset(now)
                send(Protocol.timeSync(nextSeq(), local), PORT_CONTROL)
                send(Protocol.HEARTBEAT, PORT_CONTROL)
                send(Protocol.appTypeVersion(nextSeq()), PORT_CONTROL)
                if (lastHb == 0L) Logger.log("TX présentation appli  ${Logger.hex(Protocol.appTypeVersion(0))}")
                lastHb = now
            }

            // Vidéo : comme DoFun (L()), start-RTP toutes les 500 ms depuis le socket de commande,
            // tant que le flux n'arrive pas.
            if (video && now - lastRtp >= 500 && now - VideoState.lastPktMs > 1500) {
                val p = Protocol.startRtp(RTP_PORT, nextSeq())
                send(p, PORT_CONTROL)
                lastRtp = now
                if (rtpReqLogged < 3) { rtpReqLogged++; Logger.log("TX start-RTP  ${Logger.hex(p)}") }
            }

            synchronized(this) {
                while (pending.isNotEmpty()) send(pending.removeAt(0), PORT_CONTROL)
            }
            try { Thread.sleep(50) } catch (_: InterruptedException) { break }  // 20 Hz
        }
        socket?.close()
    }

    private fun receiveLoop() {
        val s = socket ?: return
        val buf = ByteArray(4096)
        val pkt = DatagramPacket(buf, buf.size)
        try { s.soTimeout = 1000 } catch (_: Exception) {}
        while (running && !Thread.interrupted()) {
            try {
                pkt.length = buf.size
                s.receive(pkt)
                val n = pkt.length
                lastRxMs = System.currentTimeMillis()
                rxCount++
                if (n > maxRx) maxRx = n

                if (n >= 4 && (buf[0].toInt() and 0xFF) == 0x0A) {
                    // Réponse du drone : 0A LEN MODULE ...
                    val mod = buf[2].toInt() and 0xFF
                    val cmd = buf[3].toInt() and 0xFF
                    if (mod == 0x48) {
                        onDroneState(buf, n)          // état + alertes, journalisé à chaque changement
                    } else {
                        val key = (mod shl 8) or cmd
                        val c = seen[key] ?: 0
                        if (c < 3) {
                            seen[key] = c + 1
                            Logger.log("RX ${String.format("%02x/%02x", mod, cmd)} ${n}o  ${Logger.hex(buf, minOf(n, 20))}")
                        }
                    }
                    if (mod == 0x51 || mod == 0x7D) cameraReplies++
                    if (mod == 0x7D && cmd == 0x01 && n >= 7) onRtpAck(buf[6].toInt() and 0xFF)
                } else if (rxCount <= 3 || n > 200) {
                    Logger.log("RX ?? ${n}o  ${Logger.hex(buf, minOf(n, 12))}")
                }
            } catch (_: java.net.SocketTimeoutException) {
            } catch (_: Exception) { break }
        }
    }

    /**
     * Paquet d'état 0x48 (ZOWarningMdel) : [3] moteurs, [4] état de vol, [5] positionnement,
     * [25..28] alertes (32 bits, poids faible en premier).
     */
    private fun onDroneState(b: ByteArray, n: Int) {
        if (n < 29) return
        val motor = b[3].toInt() and 0xFF
        val fly = b[4].toInt() and 0xFF
        val loc = b[5].toInt() and 0xFF
        val w = (b[25].toLong() and 0xFF) or ((b[26].toLong() and 0xFF) shl 8) or
                ((b[27].toLong() and 0xFF) shl 16) or ((b[28].toLong() and 0xFF) shl 24)
        if (fly != lastFly || motor != lastMotor) {
            Logger.log("ÉTAT : ${Warnings.flyName(fly)}, moteurs ${if (motor != 0) "ON" else "off"}, position 0x${String.format("%02x", loc)}")
            lastFly = fly; lastMotor = motor
        }
        val alerts = Warnings.describe(w)
        if (w != lastWarn) {
            Logger.log(if (alerts.isEmpty()) "Alertes : aucune" else "ALERTE : ${alerts.joinToString(" | ")}  (0x${java.lang.Long.toHexString(w)})")
            lastWarn = w
        }
        stateText = Warnings.flyName(fly) + if (alerts.isEmpty()) "" else "  ⚠ " + alerts.joinToString(" | ")
    }

    /** Réponse du drone à start-RTP. Code 1 = accepté → poignée de main vidéo de DoFun. */
    private fun onRtpAck(status: Int) {
        if (status == 1) {
            rtpAcks++
            enqueue(Protocol.moduleCmd(0x7D, 0x04, nextSeq()))   // h()
            enqueue(Protocol.moduleCmd(0x7D, 0x11, nextSeq()))   // i()
            enqueue(Protocol.moduleCmd(0x7D, 0x03, nextSeq()))   // d()  (écran caméra ouvert)
            if (rtpAcks <= 3) Logger.log("start-RTP ACCEPTÉ → envoi 7D/04, 7D/11, 7D/03")
        } else if (rtpAcks < 3) {
            Logger.log("start-RTP REFUSÉ, code $status")
        }
    }

    private fun send(data: ByteArray, port: Int) {
        try { socket?.send(DatagramPacket(data, data.size, addr, port)) } catch (_: Exception) {}
    }
}
