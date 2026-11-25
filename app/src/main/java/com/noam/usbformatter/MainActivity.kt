package com.noam.usbformatter

import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.core.content.ContextCompat

class MainActivity : Activity() {
    private val ACTION = "com.noam.usbformatter.PERMISSION"
    private lateinit var usb: UsbManager
    private lateinit var spDev: Spinner
    private lateinit var spFs: Spinner
    private lateinit var etLabel: EditText
    private lateinit var btnFormat: Button
    private lateinit var bar: ProgressBar
    private lateinit var tvStatus: TextView
    private lateinit var tvLog: TextView
    private var devices = listOf<UsbDevice>()
    private var busy = false

    private val receiver = object : BroadcastReceiver() {
        @Suppress("DEPRECATION")
        override fun onReceive(c: Context, i: Intent) {
            val dev: UsbDevice? = if (Build.VERSION.SDK_INT >= 33)
                i.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            else i.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            if (i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) && dev != null) startFormat(dev)
            else log("Permission USB refusée.")
        }
    }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        usb = getSystemService(Context.USB_SERVICE) as UsbManager
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }

        root.addView(TextView(this).apply { text = "Formateur USB"; textSize = 24f })
        root.addView(Button(this).apply { text = "Détecter les clés USB"; setOnClickListener { scan() } })
        spDev = Spinner(this); root.addView(spDev)
        spFs = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, listOf("FAT32", "exFAT"))
        }
        root.addView(spFs)
        etLabel = EditText(this).apply { hint = "Nom du volume (optionnel)"; setSingleLine() }
        root.addView(etLabel)
        btnFormat = Button(this).apply { text = "Formater"; setOnClickListener { confirm() } }
        root.addView(btnFormat)
        bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        root.addView(bar)
        tvStatus = TextView(this); root.addView(tvStatus)
        tvLog = TextView(this); root.addView(tvLog)
        setContentView(ScrollView(this).apply { addView(root) })

        ContextCompat.registerReceiver(this, receiver, IntentFilter(ACTION), ContextCompat.RECEIVER_NOT_EXPORTED)
        scan()
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(receiver)
    }

    private fun log(m: String) = runOnUiThread { tvLog.append(m + "\n") }

    private fun scan() {
        devices = usb.deviceList.values.filter { UsbMassStorage.find(it) != null }
        val names = devices.map { "${it.productName ?: "Clé USB"} (${String.format("%04x:%04x", it.vendorId, it.productId)})" }
        spDev.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, names)
        log(if (devices.isEmpty()) "Aucune clé détectée. Branche-la avec un adaptateur OTG." else "${devices.size} clé(s) détectée(s).")
    }

    private fun confirm() {
        if (busy) return
        val d = devices.getOrNull(spDev.selectedItemPosition)
        if (d == null) { log("Sélectionne d'abord une clé."); return }
        val fs = spFs.selectedItem as String
        AlertDialog.Builder(this)
            .setTitle("Tout sera effacé")
            .setMessage("Formater cette clé en $fs ? TOUTES les données seront perdues.")
            .setPositiveButton("Formater") { _, _ ->
                if (usb.hasPermission(d)) startFormat(d) else requestPerm(d)
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun requestPerm(d: UsbDevice) {
        val i = Intent(ACTION).setPackage(packageName)
        val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        usb.requestPermission(d, PendingIntent.getBroadcast(this, 0, i, flags))
    }

    private fun startFormat(d: UsbDevice) {
        if (busy) return
        busy = true
        val fs = spFs.selectedItem as String
        val label = etLabel.text.toString()
        btnFormat.isEnabled = false
        Thread {
            var dev: UsbMassStorage? = null
            try {
                dev = UsbMassStorage.open(usb, d)
                log("Capacité : ${dev.blockCount * dev.blockSize / 1000000} Mo (secteurs de ${dev.blockSize} octets)")
                val p: Progress = { pct, msg -> runOnUiThread { bar.progress = pct; tvStatus.text = msg } }
                if (fs == "FAT32") Formatters.formatFat32(dev, label, p) else Formatters.formatExFat(dev, label, p)
                log("Formatage $fs terminé. Débranche puis rebranche la clé.")
            } catch (e: Throwable) {
                log("Erreur : ${e.message}")
            } finally {
                dev?.close()
                runOnUiThread { btnFormat.isEnabled = true; busy = false }
            }
        }.start()
    }
}
