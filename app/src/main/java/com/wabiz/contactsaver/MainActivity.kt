package com.wabiz.contactsaver

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    // ── Views ────────────────────────────────────────────────────────────
    private lateinit var etPrefix       : EditText
    private lateinit var etStartNum     : EditText
    private lateinit var spinnerPadding : Spinner
    private lateinit var etCountryCode  : EditText
    private lateinit var tvPreview      : TextView
    private lateinit var btnStartScan   : Button
    private lateinit var btnStopScan    : Button
    private lateinit var btnAccessibility: Button
    private lateinit var tvLog          : TextView
    private lateinit var scrollLog      : ScrollView
    private lateinit var tvStatus       : TextView

    // ── Broadcast receiver (progress updates from service) ────────────────
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                ScanAccessibilityService.ACTION_PROGRESS -> {
                    val msg = intent.getStringExtra(ScanAccessibilityService.EXTRA_MSG) ?: return
                    appendLog(msg)
                }
                ScanAccessibilityService.ACTION_DONE -> {
                    val saved   = intent.getIntExtra(ScanAccessibilityService.EXTRA_SAVED, 0)
                    val skipped = intent.getIntExtra(ScanAccessibilityService.EXTRA_SKIPPED, 0)
                    val total   = intent.getIntExtra(ScanAccessibilityService.EXTRA_TOTAL_FOUND, 0)
                    onScanDone(saved, skipped, total)
                }
            }
        }
    }

    // ── Lifecycle ────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bindViews()
        setupPaddingSpinner()
        setupPreviewWatcher()
        loadSavedPrefs()
        updatePreview()

        btnAccessibility.setOnClickListener { openAccessibilitySettings() }
        btnStartScan.setOnClickListener    { onStartScan() }
        btnStopScan.setOnClickListener     { onStopScan() }
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter().apply {
            addAction(ScanAccessibilityService.ACTION_PROGRESS)
            addAction(ScanAccessibilityService.ACTION_DONE)
        }
        registerReceiver(receiver, filter)
        refreshUI()
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(receiver)
        savePrefs()
    }

    // ── View wiring ──────────────────────────────────────────────────────

    private fun bindViews() {
        etPrefix         = findViewById(R.id.etPrefix)
        etStartNum       = findViewById(R.id.etStartNum)
        spinnerPadding   = findViewById(R.id.spinnerPadding)
        etCountryCode    = findViewById(R.id.etCountryCode)
        tvPreview        = findViewById(R.id.tvPreview)
        btnStartScan     = findViewById(R.id.btnStartScan)
        btnStopScan      = findViewById(R.id.btnStopScan)
        btnAccessibility = findViewById(R.id.btnAccessibility)
        tvLog            = findViewById(R.id.tvLog)
        scrollLog        = findViewById(R.id.scrollLog)
        tvStatus         = findViewById(R.id.tvStatus)
    }

    private fun setupPaddingSpinner() {
        val options = listOf("1 digit  (1, 2, 3…)", "2 digits  (01, 02…)",
                             "3 digits  (001, 002…)", "4 digits  (0001…)", "5 digits  (00001…)")
        spinnerPadding.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, options)
            .also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        spinnerPadding.setSelection(2) // default = 3 digits
        spinnerPadding.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>, v: View?, pos: Int, id: Long) {
                PrefsHelper.padding = pos + 1
                updatePreview()
            }
            override fun onNothingSelected(p: AdapterView<*>) {}
        }
    }

    private fun setupPreviewWatcher() {
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { updatePreview() }
        }
        etPrefix.addTextChangedListener(watcher)
        etStartNum.addTextChangedListener(watcher)
        etCountryCode.addTextChangedListener(watcher)
    }

    private fun updatePreview() {
        val prefix  = etPrefix.text.toString()
        val start   = etStartNum.text.toString().toIntOrNull() ?: 1
        val padding = PrefsHelper.padding
        val cc      = etCountryCode.text.toString().trimStart('+')
        val name    = "$prefix${start.toString().padStart(padding, '0')}"
        tvPreview.text = "Preview → $name  (+$cc XXXXXXXXXX)"
    }

    // ── Prefs ────────────────────────────────────────────────────────────

    private fun loadSavedPrefs() {
        PrefsHelper.load(this)
        etPrefix.setText(PrefsHelper.prefix)
        etStartNum.setText(PrefsHelper.startNumber.toString())
        spinnerPadding.setSelection((PrefsHelper.padding - 1).coerceIn(0, 4))
        etCountryCode.setText(PrefsHelper.countryCode)
    }

    private fun savePrefs() {
        PrefsHelper.prefix       = etPrefix.text.toString()
        PrefsHelper.startNumber  = etStartNum.text.toString().toIntOrNull() ?: 1
        PrefsHelper.countryCode  = etCountryCode.text.toString().trimStart('+')
        PrefsHelper.save(this)
    }

    // ── Scan control ─────────────────────────────────────────────────────

    private fun onStartScan() {
        if (!isAccessibilityEnabled()) {
            AlertDialog.Builder(this)
                .setTitle("Enable Accessibility")
                .setMessage("Please enable 'WA Contact Saver' in Accessibility Settings, then press Start Scan again.")
                .setPositiveButton("Open Settings") { _, _ -> openAccessibilitySettings() }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }

        if (!hasContactPermission()) {
            ActivityCompat.requestPermissions(this,
                arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS), 101)
            return
        }

        savePrefs()
        tvLog.text = ""
        appendLog("Starting scan…")
        refreshUI()

        ScanAccessibilityService.instance?.startScan(this)
            ?: run { appendLog("❌ Service not active — enable it in Accessibility Settings.") }
    }

    private fun onStopScan() {
        ScanAccessibilityService.instance?.stopScan()
        appendLog("⏹ Scan stopped.")
        refreshUI()
    }

    private fun onScanDone(saved: Int, skipped: Int, total: Int) {
        appendLog("\n✅  DONE!")
        appendLog("   Chats scanned    : $total")
        appendLog("   Already saved    : $skipped")
        appendLog("   New contacts saved: $saved")
        tvStatus.text = "✅ Saved $saved new contacts"
        refreshUI()
    }

    // ── UI state ─────────────────────────────────────────────────────────

    private fun refreshUI() {
        val accessOk = isAccessibilityEnabled()
        val running  = ScanAccessibilityService.isRunning

        btnAccessibility.visibility = if (accessOk) View.GONE else View.VISIBLE
        tvStatus.text = when {
            !accessOk -> "⚠️  Accessibility service not enabled"
            running   -> "🔄 Scanning…"
            else      -> "Ready — press Start Scan"
        }
        btnStartScan.isEnabled = accessOk && !running
        btnStopScan.isEnabled  = running
    }

    private fun appendLog(msg: String) {
        runOnUiThread {
            tvLog.append("$msg\n")
            scrollLog.post { scrollLog.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    // ── Permissions & accessibility ──────────────────────────────────────

    private fun isAccessibilityEnabled(): Boolean {
        val service = "$packageName/${ScanAccessibilityService::class.java.canonicalName}"
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.split(":").any { it.equals(service, ignoreCase = true) }
    }

    private fun openAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun hasContactPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_CONTACTS) ==
                PackageManager.PERMISSION_GRANTED

    override fun onRequestPermissionsResult(rc: Int, perms: Array<out String>, grants: IntArray) {
        super.onRequestPermissionsResult(rc, perms, grants)
        if (rc == 101 && grants.all { it == PackageManager.PERMISSION_GRANTED }) {
            onStartScan()
        } else {
            Toast.makeText(this, "Contacts permission is required.", Toast.LENGTH_LONG).show()
        }
    }
}
