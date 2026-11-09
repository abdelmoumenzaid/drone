package com.dobby.control

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.dobby.control.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private var link: DroneLink? = null
    private var cm: ConnectivityManager? = null
    private var wifi: Network? = null
    private var camOn = false

    private val NEUTRAL = Protocol.NEUTRAL
    private val RANGE = 500

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        cm = getSystemService(ConnectivityManager::class.java)
        requestWifi()

        // Joystick gauche : x = lacet, y = gaz (haut = +)
        b.stickLeft.onMove = { x, y ->
            link?.yaw = (NEUTRAL + x * RANGE).toInt()
            link?.throttle = (NEUTRAL - y * RANGE).toInt()
        }
        // Joystick droit : x = translation (roll), y = avant/arrière (pitch)
        b.stickRight.onMove = { x, y ->
            link?.roll = (NEUTRAL + x * RANGE).toInt()
            link?.pitch = (NEUTRAL - y * RANGE).toInt()
        }

        b.swArm.setOnCheckedChangeListener { _, checked -> setArmed(checked) }
        b.btnTakeoff.setOnClickListener { if (link?.armed == true) { link?.takeoff(); toast("Décollage envoyé") } }
        b.btnLand.setOnClickListener { if (link?.armed == true) { link?.land(); toast("Atterrissage envoyé") } }
        b.btnStop.setOnClickListener {
            link?.setNeutral(); b.swArm.isChecked = false; setArmed(false); toast("ARRÊT D'URGENCE")
        }
        b.btnCam.setOnClickListener { toggleCam() }
    }

    private fun requestWifi() {
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
        b.videoView.wifi = wifi
        link = DroneLink(wifi).also { it.start() }
        toast("Connecté au Wi-Fi du drone")
    }

    private fun setArmed(on: Boolean) {
        link?.armed = on
        if (!on) link?.setNeutral()
        b.txtStatus.text = if (on) "ARMÉ" else "DÉSARMÉ"
        b.btnTakeoff.isEnabled = on
        b.btnLand.isEnabled = on
    }

    private fun toggleCam() {
        camOn = !camOn
        link?.video = camOn
        if (camOn) { b.videoView.start(); b.btnCam.text = "CAM ■"; toast("Flux vidéo démarré") }
        else { b.videoView.stop(); b.btnCam.text = "CAM" }
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        super.onDestroy()
        link?.stop()
        b.videoView.stop()
    }
}
