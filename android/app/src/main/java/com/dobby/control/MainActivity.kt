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

        videoView = findViewById(R.id.videoView)
        stickLeft = findViewById(R.id.stickLeft)
        stickRight = findViewById(R.id.stickRight)
        txtStatus = findViewById(R.id.txtStatus)
        btnTakeoff = findViewById(R.id.btnTakeoff)
        btnLand = findViewById(R.id.btnLand)
        val btnStop = findViewById<Button>(R.id.btnStop)
        val btnCam = findViewById<Button>(R.id.btnCam)
        val swArm = findViewById<Switch>(R.id.swArm)

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
        btnTakeoff.setOnClickListener { if (link?.armed == true) { link?.takeoff(); toast("Décollage envoyé") } }
        btnLand.setOnClickListener { if (link?.armed == true) { link?.land(); toast("Atterrissage envoyé") } }
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
            "Drone ✓ (${l.rxCount})" else "Drone ✗ pas de réponse"
        val vidTxt = if (camOn) "   Vidéo: ${videoView.packetCount} paq" else ""
        txtStatus.text = "$armedTxt    $droneTxt$vidTxt"
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
        btnTakeoff.isEnabled = on
        btnLand.isEnabled = on
    }

    private fun toggleCam(btnCam: Button) {
        link?.video = camOn
        if (camOn) { videoView.start(); btnCam.text = "CAM ■"; toast("Flux vidéo démarré") }
        else { videoView.stop(); btnCam.text = "CAM" }
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        super.onDestroy()
        ui.removeCallbacks(statusTick)
        link?.stop()
        videoView.stop()
    }
}
