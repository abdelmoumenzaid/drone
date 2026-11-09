package com.dobby.control

import android.content.Context
import android.media.MediaCodec
import android.media.MediaFormat
import android.net.Network
import android.util.AttributeSet
import android.view.SurfaceHolder
import android.view.SurfaceView
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress

/**
 * Reçoit le flux RTP/H.264 du drone (port local RTP_PORT), le dépacketise
 * (NAL simple + fragmentation FU-A + agrégat STAP-A) et le décode avec MediaCodec
 * vers la surface. Format confirmé : H.264 Annex-B, SPS = type NAL 7.
 */
class VideoView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : SurfaceView(context, attrs), SurfaceHolder.Callback {

    var wifi: Network? = null
    @Volatile private var running = false
    private var thread: Thread? = null
    private var codec: MediaCodec? = null
    private var configured = false
    private var sps: ByteArray? = null
    private var pps: ByteArray? = null

    private val START = byteArrayOf(0, 0, 0, 1)
    private val fu = ByteArrayOutputStream()

    init { holder.addCallback(this) }

    override fun surfaceCreated(h: SurfaceHolder) {}
    override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, ht: Int) {}
    override fun surfaceDestroyed(h: SurfaceHolder) { stop() }

    fun start() {
        if (running) return
        running = true
        configured = false; sps = null; pps = null
        thread = Thread { run() }.apply { isDaemon = true; start() }
    }

    fun stop() {
        running = false
        thread?.interrupt()
        try { codec?.stop(); codec?.release() } catch (_: Exception) {}
        codec = null
    }

    private fun run() {
        val sock: DatagramSocket
        try {
            sock = DatagramSocket(null)
            sock.reuseAddress = true
            wifi?.bindSocket(sock)
            sock.bind(InetSocketAddress(DroneLink.RTP_PORT))
            sock.soTimeout = 2000
        } catch (_: Exception) { return }

        val buf = ByteArray(2048)
        val pkt = DatagramPacket(buf, buf.size)
        while (running && !Thread.interrupted()) {
            try {
                sock.receive(pkt)
                handleRtp(buf, pkt.length)
            } catch (_: java.net.SocketTimeoutException) {
            } catch (_: Exception) { break }
        }
        sock.close()
    }

    /** Extrait les NAL d'un paquet RTP et les envoie au décodeur. */
    private fun handleRtp(data: ByteArray, len: Int) {
        if (len < 13) return
        // En-tête RTP : 12 octets (on suppose pas de CSRC ni d'extension).
        val p = 12
        val nalType = data[p].toInt() and 0x1F
        when {
            nalType in 1..23 -> emit(data.copyOfRange(p, len))
            nalType == 28 -> {   // FU-A
                val fuHeader = data[p + 1].toInt()
                val startBit = fuHeader and 0x80 != 0
                val endBit = fuHeader and 0x40 != 0
                val origType = fuHeader and 0x1F
                if (startBit) {
                    fu.reset()
                    val nalHeader = (data[p].toInt() and 0xE0) or origType
                    fu.write(nalHeader)
                }
                fu.write(data, p + 2, len - (p + 2))
                if (endBit) { emit(fu.toByteArray()); fu.reset() }
            }
            nalType == 24 -> {   // STAP-A : plusieurs NAL agrégés
                var i = p + 1
                while (i + 2 <= len) {
                    val size = ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
                    i += 2
                    if (i + size > len) break
                    emit(data.copyOfRange(i, i + size))
                    i += size
                }
            }
        }
    }

    /** nal = NAL unit brut (sans start code). */
    private fun emit(nal: ByteArray) {
        if (nal.isEmpty()) return
        when (nal[0].toInt() and 0x1F) {
            7 -> { sps = nal; tryConfigure() }
            8 -> { pps = nal; tryConfigure() }
        }
        if (!configured) return
        feed(START + nal)
    }

    private fun tryConfigure() {
        if (configured) return
        val s = sps ?: return
        val p = pps ?: return
        try {
            val fmt = MediaFormat.createVideoFormat("video/avc", 1280, 720)
            fmt.setByteBuffer("csd-0", java.nio.ByteBuffer.wrap(START + s))
            fmt.setByteBuffer("csd-1", java.nio.ByteBuffer.wrap(START + p))
            val c = MediaCodec.createDecoderByType("video/avc")
            c.configure(fmt, holder.surface, null, 0)
            c.start()
            codec = c
            configured = true
        } catch (_: Exception) {}
    }

    private fun feed(au: ByteArray) {
        val c = codec ?: return
        try {
            val inIdx = c.dequeueInputBuffer(10000)
            if (inIdx >= 0) {
                val ib = c.getInputBuffer(inIdx) ?: return
                ib.clear(); ib.put(au)
                c.queueInputBuffer(inIdx, 0, au.size, System.nanoTime() / 1000, 0)
            }
            val info = MediaCodec.BufferInfo()
            var outIdx = c.dequeueOutputBuffer(info, 0)
            while (outIdx >= 0) {
                c.releaseOutputBuffer(outIdx, true)   // render = true
                outIdx = c.dequeueOutputBuffer(info, 0)
            }
        } catch (_: Exception) {}
    }
}
