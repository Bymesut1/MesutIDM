package com.bymesut1.idm

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    class Found(val url: String, val name: String, val referer: String)

    private lateinit var web: WebView
    private lateinit var urlBar: EditText
    private lateinit var foundBtn: Button
    private lateinit var panel: LinearLayout
    private lateinit var adapter: BaseAdapter
    private val found = LinkedHashMap<String, Found>()
    private val h = Handler(Looper.getMainLooper())
    private val mediaRe = Regex(
        "\\.(mp4|m3u8|mkv|webm|mp3|m4a|flv|avi|mov|zip|rar|7z|apk|pdf)(\\?|&|#|$)",
        RegexOption.IGNORE_CASE
    )

    private fun dp(i: Int) = (i * resources.displayMetrics.density).toInt()

    private val ticker = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            for (d in Dm.items) {
                if (d.state == "İndiriliyor") {
                    if (d.lastTime > 0) d.speed = (d.done.get() - d.lastBytes) * 1000 / maxOf(1L, now - d.lastTime)
                    d.lastBytes = d.done.get()
                    d.lastTime = now
                }
            }
            if (panel.visibility == View.VISIBLE) adapter.notifyDataSetChanged()
            h.postDelayed(this, 700)
        }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        Dm.app = applicationContext
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // Üst bar
        val top = LinearLayout(this).apply { setPadding(dp(4), dp(4), dp(4), dp(4)) }
        urlBar = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine()
            hint = "Adres yaz veya ara"
            imeOptions = EditorInfo.IME_ACTION_GO
            setOnEditorActionListener { _, _, _ -> go(text.toString()); true }
        }
        val goBtn = Button(this).apply { text = "Git"; setOnClickListener { go(urlBar.text.toString()) } }
        top.addView(urlBar, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(goBtn)

        // WebView
        web = WebView(this)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.mediaPlaybackRequiresUserGesture = false
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val u = request?.url?.toString() ?: return null
                if (mediaRe.containsMatchIn(u)) runOnUiThread { addFound(u) }
                return null
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                if (url != null) urlBar.setText(url)
            }
        }
        web.setDownloadListener { url, _, cd, mime, _ ->
            confirmDl(url, URLUtil.guessFileName(url, cd, mime), web.url ?: "")
        }

        // İndirme paneli
        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            visibility = View.GONE
            isClickable = true
        }
        panel.addView(TextView(this).apply {
            text = "Dokun: aç / iptal  •  Uzun bas: listeden sil"
            setPadding(dp(12), dp(8), dp(12), dp(8))
        })
        adapter = object : BaseAdapter() {
            override fun getCount() = Dm.items.size
            override fun getItem(p: Int) = Dm.items[p]
            override fun getItemId(p: Int) = p.toLong()
            override fun getView(p: Int, v: View?, parent: ViewGroup?): View {
                val d = Dm.items.getOrNull(p) ?: return View(this@MainActivity)
                val row = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(12), dp(8), dp(12), dp(8))
                }
                row.addView(TextView(this@MainActivity).apply { text = d.name; textSize = 15f })
                val pb = ProgressBar(this@MainActivity, null, android.R.attr.progressBarStyleHorizontal)
                pb.max = 100
                pb.progress = if (d.total > 0) (d.done.get() * 100 / d.total).toInt() else 0
                row.addView(pb)
                row.addView(TextView(this@MainActivity).apply { text = status(d); textSize = 12f })
                return row
            }
        }
        val lv = ListView(this)
        lv.adapter = adapter
        lv.setOnItemClickListener { _, _, p, _ ->
            val d = Dm.items.getOrNull(p) ?: return@setOnItemClickListener
            if (d.state == "Bitti" && d.uri != null) {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(d.uri, contentResolver.getType(d.uri!!) ?: "*/*")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    })
                } catch (e: ActivityNotFoundException) {
                    Toast.makeText(this, "Açacak uygulama yok. Dosya: İndirilenler/IDM", Toast.LENGTH_LONG).show()
                }
            } else if (d.state == "İndiriliyor" || d.state == "Bekliyor") {
                d.cancelled = true
            }
        }
        lv.setOnItemLongClickListener { _, _, p, _ ->
            val d = Dm.items.getOrNull(p)
            if (d != null && d.state != "İndiriliyor" && d.state != "Bekliyor") {
                Dm.items.remove(d); adapter.notifyDataSetChanged()
            }
            true
        }
        panel.addView(lv, LinearLayout.LayoutParams(-1, 0, 1f))

        val frame = FrameLayout(this)
        frame.addView(web, FrameLayout.LayoutParams(-1, -1))
        frame.addView(panel, FrameLayout.LayoutParams(-1, -1))

        // Alt bar
        val bottom = LinearLayout(this)
        val backBtn = Button(this).apply { text = "◀"; setOnClickListener { onBackPressed() } }
        val dlBtn = Button(this).apply {
            text = "İndirilenler"
            setOnClickListener {
                panel.visibility = if (panel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            }
        }
        foundBtn = Button(this).apply { text = "⬇ Bulunan (0)"; setOnClickListener { showFound() } }
        bottom.addView(backBtn, LinearLayout.LayoutParams(0, -2, 0.6f))
        bottom.addView(dlBtn, LinearLayout.LayoutParams(0, -2, 1f))
        bottom.addView(foundBtn, LinearLayout.LayoutParams(0, -2, 1.2f))

        root.addView(top)
        root.addView(frame, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(bottom)
        setContentView(root)

        h.post(ticker)
        handle(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handle(intent)
    }

    override fun onDestroy() {
        h.removeCallbacks(ticker)
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (panel.visibility == View.VISIBLE) panel.visibility = View.GONE
        else if (web.canGoBack()) web.goBack()
        else super.onBackPressed()
    }

    private fun handle(i: Intent?) {
        var u: String? = null
        if (i != null) {
            if (i.action == Intent.ACTION_VIEW) u = i.dataString
            else if (i.action == Intent.ACTION_SEND)
                u = Regex("https?://\\S+").find(i.getStringExtra(Intent.EXTRA_TEXT) ?: "")?.value
        }
        if (u == null) {
            if (web.url == null) web.loadUrl("https://www.google.com")
            return
        }
        if (mediaRe.containsMatchIn(u)) confirmDl(u, URLUtil.guessFileName(u, null, null), "")
        else web.loadUrl(u)
    }

    private fun go(s: String) {
        val t = s.trim()
        if (t.isEmpty()) return
        val u = when {
            t.startsWith("http://") || t.startsWith("https://") -> t
            t.contains(".") && !t.contains(" ") -> "https://$t"
            else -> "https://www.google.com/search?q=" + Uri.encode(t)
        }
        web.loadUrl(u)
    }

    private fun addFound(u: String) {
        if (found.containsKey(u)) return
        found[u] = Found(u, URLUtil.guessFileName(u, null, null), web.url ?: "")
        foundBtn.text = "⬇ Bulunan (${found.size})"
    }

    private fun showFound() {
        val list = found.values.toList().takeLast(40).reversed()
        if (list.isEmpty()) {
            Toast.makeText(this, "Henüz medya/dosya bulunamadı. Videoyu oynat veya sayfayı gez.", Toast.LENGTH_LONG).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Bulunan medya/dosyalar")
            .setItems(list.map { it.name + "\n" + it.url.take(60) }.toTypedArray()) { _, i ->
                startDl(list[i].url, list[i].name, list[i].referer)
            }
            .setNeutralButton("Temizle") { _, _ ->
                found.clear(); foundBtn.text = "⬇ Bulunan (0)"
            }
            .show()
    }

    private fun confirmDl(url: String, name: String, ref: String) {
        AlertDialog.Builder(this)
            .setTitle("İndirilsin mi?")
            .setMessage(name)
            .setPositiveButton("İndir") { _, _ -> startDl(url, name, ref) }
            .setNegativeButton("Vazgeç", null)
            .show()
    }

    private fun startDl(url: String, name: String, ref: String) {
        val ck = CookieManager.getInstance().getCookie(url) ?: ""
        Dm.start(this, url, name, web.settings.userAgentString, ck, ref)
        Toast.makeText(this, "İndirme başladı: $name", Toast.LENGTH_SHORT).show()
    }

    private fun fmt(b: Long): String = when {
        b < 1024 -> "$b B"
        b < 1048576 -> "%.0f KB".format(b / 1024.0)
        b < 1073741824 -> "%.1f MB".format(b / 1048576.0)
        else -> "%.2f GB".format(b / 1073741824.0)
    }

    private fun status(d: Item): String = when (d.state) {
        "İndiriliyor" ->
            if (d.hls) "${d.done.get()} / ${d.total} parça"
            else {
                val pct = if (d.total > 0) (d.done.get() * 100 / d.total).toInt() else 0
                "%$pct  ${fmt(d.done.get())} / ${if (d.total > 0) fmt(d.total) else "?"}  ${fmt(d.speed)}/s"
            }
        "Hata" -> "Hata: ${d.error}"
        "Bitti" -> "Bitti • İndirilenler/IDM"
        else -> d.state
    }
}
