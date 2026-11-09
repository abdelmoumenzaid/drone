package com.dobby.control

import android.net.Network
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * Liaison UDP avec le drone.
 *  - envoie le RC à 20 Hz + le heartbeat 1 Hz (indispensables),
 *  - relance start-RTP tant que la vidéo est active,
 *  - route les paquets par le Wi-Fi du drone (bindSocket), même sans internet.
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

    // Décollage / atterrissage : à confirmer par capture. null = bouton inactif (sécurité).
    // Exemple une fois confirmé : Pair(0x51, 0x2C)
    var cmdTakeoff: Pair<Int, Int>? = null
    var cmdLand: Pair<Int, Int>? = null

    private var socket: DatagramSocket? = null
    private var thread: Thread? = null
    @Volatile private var running = false
    private var seq = 0
    private var counter = 0
    private val pending = ArrayList<ByteArray>()
    private lateinit var addr: InetAddress

    @Synchronized private fun nextSeq(): Int { seq = (seq + 1) and 0xFFFF; return seq }

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

    fun takeoff() { cmdTakeoff?.let { enqueue(Protocol.moduleCmd(it.first, it.second, nextSeq())) } }
    fun land()    { cmdLand?.let    { enqueue(Protocol.moduleCmd(it.first, it.second, nextSeq())) } }
    @Synchronized private fun enqueue(p: ByteArray) { pending.add(p) }

    private fun loop() {
        try {
            addr = InetAddress.getByName(DRONE_IP)
            val s = DatagramSocket(null)
            s.reuseAddress = true
            wifi?.bindSocket(s)          // forcer la route par le Wi-Fi du drone
            s.bind(java.net.InetSocketAddress(LOCAL_PORT))
            socket = s
        } catch (e: Exception) {
            // repli : socket non liée à un port précis
            try {
                val s = DatagramSocket()
                wifi?.bindSocket(s)
                socket = s
            } catch (_: Exception) { return }
        }

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

            if (now - lastHb >= 1000) { send(Protocol.HEARTBEAT, PORT_CONTROL); lastHb = now }
            if (video && now - lastRtp >= 500) {
                send(Protocol.startRtp(RTP_PORT, nextSeq()), PORT_CONTROL); lastRtp = now
            }
            synchronized(this) {
                while (pending.isNotEmpty()) send(pending.removeAt(0), PORT_CONTROL)
            }
            try { Thread.sleep(50) } catch (_: InterruptedException) { break }  // 20 Hz
        }
        socket?.close()
    }

    private fun send(data: ByteArray, port: Int) {
        try { socket?.send(DatagramPacket(data, data.size, addr, port)) } catch (_: Exception) {}
    }
}
