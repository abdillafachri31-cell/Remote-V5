package id.arunika.remote

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var address: TextView
    private lateinit var key: TextView
    private lateinit var toggle: Button
    private val handler = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() { refreshStatus(); handler.postDelayed(this, 1500) }
    }
    private fun dp(n: Int) = (resources.displayMetrics.density * n).toInt()
    private fun label(t: String, sz: Float = 15f) = TextView(this).apply {
        text = t; textSize = sz; setTextColor(Color.rgb(222, 236, 250)); setPadding(0, dp(8), 0, dp(8))
        setTextIsSelectable(true)
    }
    private fun button(parent: LinearLayout, t: String, click: () -> Unit): Button {
        return Button(this).apply {
            text = t; isAllCaps = false; setOnClickListener { click() }
            parent.addView(this, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.setBackgroundColor(Color.rgb(9, 20, 38))
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(22), dp(20), dp(22), dp(36)) }
        val scroll = ScrollView(this).apply { addView(root) }; setContentView(scroll)
        root.addView(label("✦  ARUNIKA REMOTE", 25f))
        root.addView(label("Mode Tanpa Hosting • Redmi Note 10 Pro / Android 13", 13f))
        root.addView(label("1. Pasang dan aktifkan Tailscale di Redmi & iPhone (akun yang sama).\n2. Aktifkan Aksesibilitas Arunika dan izin notifikasi.\n3. Aktifkan remote di bawah ini.\n4. Dari Safari iPhone buka alamat yang tampil lalu masukkan kode akses.\n\nTidak perlu server, Netlify, atau laptop selama pemakaian.", 14f))
        toggle = button(root, "Aktifkan remote") { toggleRemote() }
        button(root, "Buka pengaturan Aksesibilitas") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        status = label("Status: memeriksa ...")
        address = label("Alamat Safari: -", 16f)
        root.addView(status)
        root.addView(address)
        root.addView(label("Kode akses (RAHASIA) • masukkan sekali di Safari:", 13f))
        key = label(RemoteConfig.token(this), 16f)
        root.addView(key)
        button(root, "Ganti kode akses") {
            AlertDialog.Builder(this).setTitle("Ganti kode akses?")
                .setMessage("Kode lama langsung tidak bisa digunakan setelah layanan Arunika diaktifkan ulang. Jangan bagikan kode melalui pesan atau screenshot.")
                .setPositiveButton("Ganti") { _, _ ->
                    key.text = RemoteConfig.regenerate(this)
                    RemoteAccessibilityService.instance?.applySettings()
                    if (RemoteConfig.enabled(this)) {
                        RemoteConfig.setEnabled(this, false)
                        RemoteAccessibilityService.instance?.applySettings()
                        RemoteConfig.setEnabled(this, true)
                        RemoteAccessibilityService.instance?.applySettings()
                    }
                }.setNegativeButton("Batal", null).show()
        }
        root.addView(label("PENTING: Aplikasi hanya bisa diakses melalui jaringan privat Tailscale. Tidak bisa membuka PIN/sidik jari Redmi dan tidak menampilkan aplikasi yang memblokir screenshot. Kamu selalu bisa menekan HENTIKAN REMOTE di aplikasi atau notifikasi.", 12f))
        refreshStatus()
    }
    private fun notificationsAllowed(): Boolean {
        val mgr = getSystemService(NotificationManager::class.java)
        return mgr.areNotificationsEnabled() && (Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
    }
    private fun toggleRemote() {
        if (RemoteConfig.enabled(this)) {
            RemoteConfig.setEnabled(this, false)
            RemoteAccessibilityService.instance?.applySettings()
            refreshStatus(); return
        }
        if (!notificationsAllowed()) {
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 7)
            } else {
                AlertDialog.Builder(this).setMessage("Aktifkan izin notifikasi Arunika pada pengaturan aplikasi.")
                    .setPositiveButton("OK", null).show()
            }
            return
        }
        RemoteConfig.setEnabled(this, true)
        if (RemoteAccessibilityService.instance == null) {
            AlertDialog.Builder(this).setTitle("Izinkan Aksesibilitas")
                .setMessage("Aktifkan layanan Arunika Remote di Setelan > Aksesibilitas agar dapat menampilkan layar dan menjalankan perintah yang kamu kirim.")
                .setPositiveButton("Buka setelan") { _, _ -> startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                .setNegativeButton("Nanti", null).show()
        } else RemoteAccessibilityService.instance?.applySettings()
        refreshStatus()
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (requestCode == 7 && notificationsAllowed()) toggleRemote()
    }
    private fun refreshStatus() {
        if (!::status.isInitialized) return
        val enabled = RemoteConfig.enabled(this)
        val service = RemoteAccessibilityService.instance
        status.text = when {
            !enabled -> "Status: Remote nonaktif"
            service == null -> "Status: Menunggu Aksesibilitas diaktifkan"
            else -> "Status: ${service.stateLabel}"
        }
        val tail = service?.serverAddress?.takeIf { it.isNotEmpty() } ?: RemoteConfig.tailscaleIp()?.hostAddress
        address.text = if (enabled && tail != null) "Alamat Safari: http://$tail:18765" else "Alamat Safari: menunggu Tailscale aktif"
        toggle.text = if (enabled) "HENTIKAN REMOTE" else "Aktifkan remote"
    }
    override fun onResume() { super.onResume(); handler.post(refresh) }
    override fun onPause() { handler.removeCallbacks(refresh); super.onPause() }
}
