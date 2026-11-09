package com.dobby.control

import android.app.Activity
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var videoView: VideoView
    private lateinit var stickLeft: JoystickView
    private lateinit var stickRight: JoystickView
    private lateinit var txtStatus: TextView
    private lateinit var txtLog: TextView
    private lateinit var btnTakeoff: Button
    private lateinit var btnLand: Button

    private var link: DroneLink? = null
    private var wifi: Network? = null
    private var camOn = false

    private val NEUTRAL = Protocol.NEUTRAL
    private val RANGE = 500

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Fichier de log : dossier Téléchargements si possible, sinon dossier privé de l'appli.
        val dir = try {
            android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOWNLOADS)
        } catch (_: Exception) { null } ?: getExternalFilesDir(null)
        Logger.init(dir)
        Logger.log("Démarrage. Fichier log : ${Logger.filePath}")

        videoView = findViewById(R.id.videoView)
        stickLeft = findViewById(R.id.stickLeft)
        stickRight = findViewById(R.id.stickRight)
        txtStatus = findViewById(R.id.txtStatus)
        txtLog = findViewById(R.id.txtLog)
        btnTakeoff = findViewById(R.id.btnTakeoff)
        btnLand = findViewById(R.id.btnLand)
        val btnStop = findViewById<Button>(R.id.btnStop)
        val btnCam = findViewById<Button>(R.id.btnCam)
        val swArm = findViewById<Switch>(R.id.swArm)

        // boutons de test des commandes du module vol 0x7D
        findViewById<Button>(R.id.btnT03).setOnClickListener { link?.testFlight(0x03) }
        findViewById<Button>(R.id.btnT04).setOnClickListener { link?.testFlight(0x04) }
        findViewById<Button>(R.id.btnT08).setOnClickListener { link?.testFlight(0x08) }
        findViewById<Button>(R.id.btnT0B).setOnClickListener { link?.testFlight(0x0B) }
        findViewById<Button>(R.id.btnT11).setOnClickListener { link?.testFlight(0x11) }

        // Partage du journal (pour me l'envoyer facilement)
        findViewById<Button>(R.id.btnLog).setOnClickListener {
            val send = android.content.Intent(android.content.Intent.ACTION_SEND)
            send.type = "text/plain"
            send.putExtra(android.content.Intent.EXTRA_SUBJECT, "Dobby log")
            send.putExtra(android.content.Intent.EXTRA_TEXT, Logger.fullText())
            startActivity(android.content.Intent.createChooser(send, "Envoyer le journal"))
        }

        // Joystick gauche : x = lacet, y = gaz (haut = +)
        stickLeft.onMove = { x, y ->
            link?.yaw = (NEUTRAL + x * RANGE).toInt()
            link?.throttle = (NEUTRAL - y * RANGE).toInt()
        }
        // Joystick droit : x = translation (roll), y = avant/arrière (pitch)
        stickRight.onMove = { x, y ->
            link?.roll = (NEUTRAL + x * RANGE).toInt()
            link?.pitch = (NEUTRAL - y * RANGE).toInt()
        }

        swArm.setOnCheckedChangeListener { _, checked -> setArmed(checked) }
        // décollage/atterrissage envoyés même sans armer, pour les tests (hélices enlevées)
        btnTakeoff.setOnClickListener { link?.takeoff(); toast("Décollage envoyé") }
        btnLand.setOnClickListener { link?.land(); toast("Atterrissage envoyé") }
        btnStop.setOnClickListener {
            link?.setNeutral(); swArm.isChecked = false; setArmed(false); toast("ARRÊT D'URGENCE")
        }
        btnCam.setOnClickListener { camOn = !camOn; toggleCam(btnCam) }

        // Binding au Wi-Fi du drone : seulement sur Android 6+ (le J1 route déjà par le Wi-Fi).
        if (Build.VERSION.SDK_INT >= 23) requestWifi() else startLink()

        ui.post(statusTick)
    }

    private val ui = Handler(Looper.getMainLooper())
    private val statusTick = object : Runnable {
        override fun run() { updateStatus(); ui.postDelayed(this, 500) }
    }

    private fun updateStatus() {
        val l = link
        val armedTxt = if (l?.armed == true) "ARMÉ" else "DÉSARMÉ"
        val droneTxt = if (l != null && System.currentTimeMillis() - l.lastRxMs < 2000)
            "Drone ✓ (${l.rxCount}, max ${l?.maxRx}o)" else "Drone ✗ pas de réponse"
        val vidTxt = if (camOn) "   Vidéo: ${videoView.packetCount} paq" else ""
        txtStatus.text = "$armedTxt    $droneTxt$vidTxt"
        txtLog.text = Logger.dump()
    }

    private fun requestWifi() {
        val cm = getSystemService(ConnectivityManager::class.java)
        val req = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        cm?.requestNetwork(req, object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                wifi = network
                runOnUiThread { startLink() }
            }
        })
    }

    private fun startLink() {
        if (link != null) return
        videoView.wifi = wifi
        link = DroneLink(wifi).also { it.start() }
        toast("Liaison drone démarrée")
    }

    private fun setArmed(on: Boolean) {
        link?.armed = on
        if (!on) link?.setNeutral()
        Logger.log(if (on) "ARMÉ" else "DÉSARMÉ")
    }

    private fun toggleCam(btnCam: Button) {
        link?.video = camOn
        if (camOn) {
            link?.enableVideo()          // séquence d'activation caméra
            videoView.start(); btnCam.text = "CAM ■"; toast("Flux vidéo démarré")
        } else { videoView.stop(); btnCam.text = "CAM" }
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        super.onDestroy()
        ui.removeCallbacks(statusTick)
        link?.stop()
        videoView.stop()
    }
}
