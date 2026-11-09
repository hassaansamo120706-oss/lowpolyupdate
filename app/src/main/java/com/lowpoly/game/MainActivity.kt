package com.lowpoly.game

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.*
import java.net.Inet4Address
import java.net.NetworkInterface

@Suppress("DEPRECATION")
class MainActivity : Activity() {
    private var web: WebView? = null
    private var listener: Discovery.Listener? = null
    private var mcLock: android.net.wifi.WifiManager.MulticastLock? = null
    private var serversBox: LinearLayout? = null
    private var hosting = false
    private val prefs by lazy { getSharedPreferences("lowpoly", MODE_PRIVATE) }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= 28)
            window.attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        showMenu()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
    }

    private fun lanIps(): List<String> = try {
        NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filter { it is Inet4Address && it.isSiteLocalAddress }.map { it.hostAddress!! }
    } catch (_: Exception) { emptyList() }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun showMenu() {
        web?.let { (it.parent as? ViewGroup)?.removeView(it); it.destroy() }
        web = null
        stopDiscovery()
        val ips = lanIps()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setBackgroundColor(Color.rgb(20, 40, 60)); setPadding(dp(24), dp(16), dp(24), dp(16))
        }
        fun tv(t: String, sz: Float, c: Int, bold: Boolean = false) = TextView(this).apply {
            text = t; textSize = sz; setTextColor(c); gravity = Gravity.CENTER
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }
        root.addView(tv("LOWPOLY", 28f, Color.rgb(52, 224, 161), true))
        root.addView(tv(if (ips.isEmpty()) "Not on Wi-Fi / hotspot" else "This phone: " + ips.joinToString(", "), 13f, Color.LTGRAY))

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        val host = Button(this).apply { text = "HOST GAME" }
        val ip = EditText(this).apply {
            hint = "Host IP (e.g. 192.168.1.23)"; setText(prefs.getString("ip", ""))
            inputType = InputType.TYPE_CLASS_PHONE; setTextColor(Color.WHITE); setHintTextColor(Color.GRAY)
            layoutParams = LinearLayout.LayoutParams(dp(220), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val join = Button(this).apply { text = "JOIN LAN" }
        row.addView(ip); row.addView(join)

        val onlineUrl = EditText(this).apply {
            hint = "Website URL (https://yourdomain.com)"
            setText(prefs.getString("online_url", ""))
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(true)
            setTextColor(Color.WHITE); setHintTextColor(Color.GRAY)
            layoutParams = LinearLayout.LayoutParams(dp(300), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val online = Button(this).apply { text = "PLAY ONLINE" }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        serversBox = box
        val scroll = ScrollView(this).apply {
            addView(box)
            layoutParams = LinearLayout.LayoutParams(dp(320), dp(96))
        }
        root.addView(tv("ONLINE MULTIPLAYER · INTERNET", 13f, Color.rgb(52, 224, 161), true))
        root.addView(onlineUrl)
        root.addView(online)
        root.addView(tv("LAN MULTIPLAYER · SAME WI-FI / HOTSPOT", 13f, Color.rgb(52, 224, 161), true))
        root.addView(host)
        root.addView(tv("Games found on this Wi-Fi", 13f, Color.rgb(52, 224, 161), true))
        root.addView(scroll)
        root.addView(tv("or enter IP manually", 12f, Color.GRAY)); root.addView(row)
        setContentView(root)
        showServers(emptyList())
        startDiscovery()

        host.setOnClickListener {
            stopDiscovery(); startService(Intent(this, HostService::class.java)); hosting = true
            val msg = if (ips.isEmpty()) "Connect to Wi-Fi (or turn on your hotspot) so friends can reach you."
                      else "Friends: tap JOIN and enter\n\n" + ips.joinToString("\n")
            AlertDialog.Builder(this).setTitle("Hosting").setMessage(msg).setCancelable(false)
                .setPositiveButton("Play") { _, _ -> openGame("http://127.0.0.1:${RelayServer.PORT}/") }.show()
        }
        online.setOnClickListener {
            var address = onlineUrl.text.toString().trim()
            if (address.isEmpty()) {
                Toast.makeText(this, "Enter your website URL first", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            if (!address.startsWith("https://") && !address.startsWith("http://"))
                address = "https://$address"
            if (!android.util.Patterns.WEB_URL.matcher(address).matches()) {
                Toast.makeText(this, "Enter a valid website URL", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            prefs.edit().putString("online_url", address).apply()
            openGame(address)
        }
        join.setOnClickListener {
            val a = ip.text.toString().trim().removePrefix("http://").substringBefore("/").substringBefore(":")
            if (a.isEmpty()) { Toast.makeText(this, "Enter the host's IP", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            prefs.edit().putString("ip", a).apply()
            openGame("http://$a:${RelayServer.PORT}/")
        }
    }

    private fun showServers(list: List<Discovery.Server>) {
        val box = serversBox ?: return
        box.removeAllViews()
        if (list.isEmpty()) {
            box.addView(TextView(this).apply {
                text = "Searching…  (ask a friend to tap HOST GAME)"; textSize = 12f
                setTextColor(Color.LTGRAY); gravity = Gravity.CENTER
            })
            return
        }
        for (s in list) box.addView(Button(this).apply {
            text = "${s.name} · ${s.players + 1} player${if (s.players == 0) "" else "s"} · ${s.ip}"
            setOnClickListener {
                prefs.edit().putString("ip", s.ip).apply()
                openGame("http://${s.ip}:${RelayServer.PORT}/")
            }
        })
    }

    private fun startDiscovery() {
        if (listener != null) return
        val wm = applicationContext.getSystemService(WIFI_SERVICE) as android.net.wifi.WifiManager
        mcLock = wm.createMulticastLock("lowpoly").also { it.setReferenceCounted(false); it.acquire() }
        listener = Discovery.Listener { list -> runOnUiThread { showServers(list) } }.also { it.start() }
    }

    private fun stopDiscovery() {
        listener?.end(); listener = null
        mcLock?.takeIf { it.isHeld }?.release(); mcLock = null
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun openGame(url: String) {
        stopDiscovery()
        val w = WebView(this)
        w.settings.apply {
            javaScriptEnabled = true; domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }
        w.setBackgroundColor(Color.rgb(142, 201, 240))
        w.webChromeClient = WebChromeClient()
        w.webViewClient = object : WebViewClient() {
            override fun onReceivedError(v: WebView, r: WebResourceRequest, e: WebResourceError) {
                if (r.isForMainFrame) {
                    Toast.makeText(this@MainActivity, "Can't reach the host. Same Wi-Fi? Host still running?", Toast.LENGTH_LONG).show()
                    showMenu()
                }
            }
        }
        web = w
        setContentView(w)
        w.loadUrl(url)
    }

    override fun onBackPressed() {
        if (web != null) {
            AlertDialog.Builder(this).setMessage("Leave the game?")
                .setPositiveButton("Leave") { _, _ ->
                    if (hosting) { stopService(Intent(this, HostService::class.java)); hosting = false }
                    showMenu()
                }.setNegativeButton("Stay", null).show()
        } else super.onBackPressed()
    }

    override fun onPause() { web?.onPause(); stopDiscovery(); super.onPause() }
    override fun onResume() { super.onResume(); web?.onResume(); if (web == null) startDiscovery() }
    override fun onDestroy() {
        if (isFinishing && hosting) stopService(Intent(this, HostService::class.java))
        super.onDestroy()
    }
}
