package com.noam.usbformatter

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.R as M
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

class MainActivity : AppCompatActivity() {
    private val ACTION = "com.noam.usbformatter.PERMISSION"
    private lateinit var usb: UsbManager
    private lateinit var devField: MaterialAutoCompleteTextView
    private lateinit var etLabel: TextInputEditText
    private lateinit var btnFormat: MaterialButton
    private lateinit var bar: LinearProgressIndicator
    private lateinit var tvStatus: TextView
    private lateinit var tvLog: TextView
    private var devices = listOf<UsbDevice>()
    private var selDev = 0
    private var selFs = "FAT32"
    private var selTable = "MBR"
    private var busy = false
    @Volatile private var cancelTest = false
    private var pending: (UsbDevice) -> Unit = {}
    private lateinit var btnTest: MaterialButton
    private lateinit var btnStop: MaterialButton

    private val receiver = object : BroadcastReceiver() {
        @Suppress("DEPRECATION")
        override fun onReceive(c: Context, i: Intent) {
            val dev: UsbDevice? = if (Build.VERSION.SDK_INT >= 33)
                i.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            else i.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            if (i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) && dev != null) pending(dev)
            else log("Permission USB refusée.")
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun lp(top: Int = 16) = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(top) }

    private fun dropdown(hint: String): Pair<TextInputLayout, MaterialAutoCompleteTextView> {
        val til = TextInputLayout(this, null, M.attr.textInputOutlinedExposedDropdownMenuStyle)
        til.hint = hint
        val ac = MaterialAutoCompleteTextView(til.context)
        ac.inputType = InputType.TYPE_NULL
        til.addView(ac, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        return til to ac
    }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        usb = getSystemService(Context.USB_SERVICE) as UsbManager
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(32), dp(20), dp(24))
        }

        root.addView(TextView(this).apply {
            text = "Formateur USB"
            setTextAppearance(M.style.TextAppearance_Material3_HeadlineLarge)
        })
        root.addView(TextView(this).apply {
            text = "Formate une clé USB en FAT32, FAT16, exFAT, ext4 ou NTFS (MBR ou GPT), sans root."
            setTextAppearance(M.style.TextAppearance_Material3_BodyMedium)
        }, lp(4))

        root.addView(MaterialButton(this, null, M.attr.materialButtonOutlinedStyle).apply {
            text = "Détecter les clés USB"
            setOnClickListener { scan() }
        }, lp(24))

        val (devLayout, dev) = dropdown("Clé USB")
        devField = dev
        dev.setOnItemClickListener { _, _, pos, _ -> selDev = pos }
        root.addView(devLayout, lp(16))

        val (fsLayout, fsField) = dropdown("Format")
        val fsList = listOf("FAT32", "FAT16", "exFAT", "ext4", "NTFS")
        fsField.setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, fsList))
        fsField.setText(fsList[0], false)
        fsField.setOnItemClickListener { _, _, pos, _ -> selFs = fsList[pos] }
        root.addView(fsLayout, lp(12))

        val (tableLayout, tableField) = dropdown("Table de partition")
        val tableList = listOf("MBR", "GPT")
        tableField.setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, tableList))
        tableField.setText(tableList[0], false)
        tableField.setOnItemClickListener { _, _, pos, _ -> selTable = tableList[pos] }
        root.addView(tableLayout, lp(12))
        root.addView(TextView(this).apply {
            text = "MBR : compatible partout, jusqu'à 2 To. GPT : disques de plus de 2 To, systèmes récents."
            setTextAppearance(M.style.TextAppearance_Material3_BodySmall)
        }, lp(4))

        val labelLayout = TextInputLayout(this).apply { hint = "Nom du volume (optionnel)" }
        etLabel = TextInputEditText(labelLayout.context).apply { setSingleLine() }
        labelLayout.addView(etLabel)
        root.addView(labelLayout, lp(12))

        btnFormat = MaterialButton(this).apply {
            text = "Formater"
            setOnClickListener { confirm() }
        }
        root.addView(btnFormat, lp(24))

        btnTest = MaterialButton(this, null, M.attr.materialButtonOutlinedStyle).apply {
            text = "Tester la capacité (fausse clé ?)"
            setOnClickListener { chooseTest() }
        }
        root.addView(btnTest, lp(8))
        btnStop = MaterialButton(this, null, M.attr.materialButtonOutlinedStyle).apply {
            text = "Arrêter le test"
            visibility = View.GONE
            setOnClickListener { cancelTest = true }
        }
        root.addView(btnStop, lp(8))

        bar = LinearProgressIndicator(this).apply { max = 100 }
        root.addView(bar, lp(24))
        tvStatus = TextView(this).apply { setTextAppearance(M.style.TextAppearance_Material3_LabelLarge) }
        root.addView(tvStatus, lp(8))

        val card = MaterialCardView(this, null, M.attr.materialCardViewFilledStyle)
        tvLog = TextView(this).apply {
            setPadding(dp(16), dp(16), dp(16), dp(16))
            setTextAppearance(M.style.TextAppearance_Material3_BodyMedium)
        }
        card.addView(tvLog)
        root.addView(card, lp(16))

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
        devField.setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, names))
        selDev = 0
        devField.setText(names.firstOrNull() ?: "", false)
        log(if (devices.isEmpty()) "Aucune clé détectée. Branche-la avec un adaptateur OTG." else "${devices.size} clé(s) détectée(s).")
    }

    private fun confirm() {
        if (busy) return
        val d = devices.getOrNull(selDev)
        if (d == null) { log("Sélectionne d'abord une clé."); return }
        MaterialAlertDialogBuilder(this)
            .setTitle("Tout sera effacé")
            .setMessage(
                "Formater cette clé en $selFs ($selTable) ? TOUTES les données seront perdues." +
                    when (selFs) {
                        "FAT16" -> "\n\nFAT16 est limité à 4 Go : sur une clé plus grosse, seuls 4 Go seront utilisés."
                        "ext4" -> "\n\next4 est le format Linux : Android ne sait pas l'ouvrir, mais un PC Linux oui."
                        "NTFS" -> "\n\nNTFS : Windows et les PC le lisent, mais Android ne sait pas l'ouvrir. Clés à secteurs de 512 octets seulement."
                        else -> ""
                    }
            )
            .setPositiveButton("Formater") { _, _ ->
                pending = { startFormat(it) }
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
        val fs = selFs
        val gpt = selTable == "GPT"
        val label = etLabel.text.toString()
        btnFormat.isEnabled = false
        btnTest.isEnabled = false
        Thread {
            var dev: UsbMassStorage? = null
            try {
                dev = UsbMassStorage.open(usb, d)
                log("Capacité : ${dev.blockCount * dev.blockSize / 1000000} Mo (secteurs de ${dev.blockSize} octets)")
                val p: Progress = { pct, msg -> runOnUiThread { bar.setProgressCompat(pct, true); tvStatus.text = msg } }
                when (fs) {
                    "FAT32" -> Formatters.formatFat32(dev, label, gpt, p)
                    "FAT16" -> Formatters.formatFat16(dev, label, gpt, p)
                    "ext4" -> Ext4.format(dev, label, gpt, p)
                    "NTFS" -> Ntfs.format(dev, label, gpt, p)
                    else -> Formatters.formatExFat(dev, label, gpt, p)
                }
                log("Formatage $fs (${if (gpt) "GPT" else "MBR"}) terminé. Débranche puis rebranche la clé.")
            } catch (e: Throwable) {
                log("Erreur : ${e.message}")
            } finally {
                dev?.close()
                runOnUiThread { btnFormat.isEnabled = true; btnTest.isEnabled = true; busy = false }
            }
        }.start()
    }

    private fun chooseTest() {
        if (busy) return
        val d = devices.getOrNull(selDev)
        if (d == null) { log("Sélectionne d'abord une clé."); return }
        MaterialAlertDialogBuilder(this)
            .setTitle("Détecter une fausse capacité")
            .setItems(arrayOf("Test rapide (quelques minutes, probable)", "Test complet (long mais fiable)")) { _, which ->
                confirmTest(d, which == 1)
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun confirmTest(d: UsbDevice, full: Boolean) {
        val detail = if (full)
            "Le test complet écrit puis relit toute la capacité : compte plusieurs minutes, voire plusieurs heures sur une grosse clé. Garde l'appli ouverte. Tu peux l'arrêter à tout moment."
        else
            "Le test rapide dure quelques minutes mais peut rater certaines fausses clés."
        MaterialAlertDialogBuilder(this)
            .setTitle("Tout sera effacé")
            .setMessage("Ce test écrit sur toute la clé et efface TOUTES les données.\n\n" + detail)
            .setPositiveButton("Lancer") { _, _ ->
                pending = { startTest(it, full) }
                if (usb.hasPermission(d)) startTest(d, full) else requestPerm(d)
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun startTest(d: UsbDevice, full: Boolean) {
        if (busy) return
        busy = true
        cancelTest = false
        btnFormat.isEnabled = false
        btnTest.isEnabled = false
        btnStop.visibility = View.VISIBLE
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        Thread {
            var dev: UsbMassStorage? = null
            var res: CapacityTest.Result? = null
            try {
                dev = UsbMassStorage.open(usb, d)
                log("Test de capacité (${if (full) "complet" else "rapide"}) : ${dev.blockCount * dev.blockSize / 1000000} Mo annoncés…")
                val p: Progress = { pct, msg -> runOnUiThread { bar.setProgressCompat(pct, true); tvStatus.text = msg } }
                res = if (full) CapacityTest.full(dev, p) { cancelTest } else CapacityTest.quick(dev, p) { cancelTest }
            } catch (e: Throwable) {
                log("Erreur : ${e.message}")
            } finally {
                dev?.close()
                runOnUiThread {
                    btnFormat.isEnabled = true; btnTest.isEnabled = true
                    btnStop.visibility = View.GONE
                    window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    busy = false
                }
            }
            res?.let { r ->
                log(r.text)
                runOnUiThread {
                    MaterialAlertDialogBuilder(this)
                        .setTitle(if (r.ok) "Clé OK" else "Résultat du test")
                        .setMessage(r.text)
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
        }.start()
    }
}
