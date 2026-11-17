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
import android.webkit.JavascriptInterface
import android.webkit.URLUtil
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
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
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : Activity() {

    class Found(val url: String, val name: String, val referer: String, val hls: Boolean)

    private lateinit var web: WebView
    private lateinit var urlBar: EditText
    private lateinit var foundBtn: Button
    private lateinit var panel: LinearLayout
    private lateinit var adapter: BaseAdapter
    private val found = LinkedHashMap<String, Found>()
    private val h = Handler(Looper.getMainLooper())
    private var ytLast = ""

    // YouTube için Invidious adresleri (sırayla denenir). İstersen kendi adresini en başa yaz.
    private val invidious = listOf(
        "https://yt.chocolatemoo53.com",
        "https://yewtu.be",
        "https://inv.nadeko.net"
    )

    private val mediaRe = Regex(
        "\\.(mp4|m3u8|mkv|webm|mp3|m4a|flv|avi|mov|zip|rar|7z|apk|pdf)(\\?|&|#|$)",
        RegexOption.IGNORE_CASE
    )

    // Her sayfaya ve iframe'e giren izleme kodu: uzantısız video/playlist adreslerini yakalar
    private val hookJs = """
(function(){
  if (window.__idmHooked) return;
  window.__idmHooked = true;
  var seen = {};
  function rep(u, kind){
    try {
      u = String(u);
      if (u.indexOf('blob:') === 0 || u.indexOf('data:') === 0) return;
      try { u = new URL(u, location.href).href; } catch (e) { return; }
      if (!/^https?:/i.test(u) || seen[u]) return;
      seen[u] = 1;
      if (typeof IDM !== 'undefined') IDM.found(u, location.href, kind || 'video');
    } catch (e) {}
  }
  function chk(u, ct, cl){
    ct = (ct || '').toLowerCase();
    var len = parseInt(cl || '0', 10);
    if (ct.indexOf('mpegurl') >= 0) { rep(u, 'hls'); return; }
    if (ct.indexOf('video/mp2t') >= 0) return;
    if (/^(video|audio)\//.test(ct) && (!len || len > 1000000)) rep(u, 'video');
  }
  var xo = XMLHttpRequest.prototype.open;
  XMLHttpRequest.prototype.open = function(m, u){
    var self = this, url = u;
    this.addEventListener('readystatechange', function(){
      if (self.readyState === 2) {
        try { chk(url, self.getResponseHeader('content-type'), self.getResponseHeader('content-length')); } catch (e) {}
      }
    });
    this.addEventListener('load', function(){
      try {
        if (self.responseType === '' || self.responseType === 'text') {
          var t = self.responseText;
          if (t && t.length < 3000000 && t.indexOf('#EXTM3U') === 0) rep(url, 'hls');
        }
      } catch (e) {}
    });
    return xo.apply(this, arguments);
  };
  var of = window.fetch;
  if (of) {
    window.fetch = function(inp, init){
      var url = (typeof inp === 'string') ? inp : (inp && inp.url);
      var p = of.apply(this, arguments);
      try {
        p.then(function(r){
          try {
            var ct = (r.headers.get('content-type') || '').toLowerCase();
            var cl = parseInt(r.headers.get('content-length') || '0', 10);
            chk(url, ct, r.headers.get('content-length'));
            if (ct.indexOf('text/plain') >= 0 || (ct.indexOf('octet-stream') >= 0 && cl > 0 && cl < 500000)) {
              r.clone().text().then(function(t){
                if (t.indexOf('#EXTM3U') === 0) rep(url, 'hls');
              }).catch(function(){});
            }
          } catch (e) {}
        }).catch(function(){});
      } catch (e) {}
      return p;
    };
  }
  setInterval(function(){
    try {
      var vs = document.getElementsByTagName('video');
      for (var i = 0; i < vs.length; i++) {
        var s = vs[i].currentSrc || vs[i].src;
        if (s) rep(s, /m3u8/i.test(s) ? 'hls' : 'video');
        var ss = vs[i].getElementsByTagName('source');
        for (var j = 0; j < ss.length; j++) {
          if (ss[j].src) rep(ss[j].src, /m3u8/i.test(ss[j].src) ? 'hls' : 'video');
        }
      }
    } catch (e) {}
  }, 1500);
})();
"""

    inner class Bridge {
        @JavascriptInterface
        fun found(url: String, page: String, kind: String) {
            runOnUiThread { addFound(url, page, kind) }
        }
    }

    private fun dp(i: Int) = (i * resources.displayMetrics.density).toInt()

    private fun safe(s: String) = s.replace(Regex("[\\\\/:*?\"<>|]"), "_")

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
        val s = web.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.mediaPlaybackRequiresUserGesture = false
        s.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        // "WebView" izlerini temizle: normal Chrome gibi görün
        s.userAgentString = s.userAgentString.replace("; wv", "").replace(Regex("Version/\\d+\\.\\d+\\s"), "")
        if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
            WebSettingsCompat.setRequestedWithHeaderOriginAllowList(s, emptySet())
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)

        web.addJavascriptInterface(Bridge(), "IDM")
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(web, hookJs, setOf("*"))
        }

        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val u = request?.url?.toString() ?: return null
                if (mediaRe.containsMatchIn(u)) {
                    val ref = request.requestHeaders?.get("Referer") ?: ""
                    runOnUiThread { addFound(u, ref, "") }
                }
                return null
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                if (url != null) urlBar.setText(url)
            }

            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                if (url != null && !urlBar.hasFocus()) urlBar.setText(url)
                checkYoutube(url)
            }
        }
        web.setDownloadListener { url, _, cd, mime, _ ->
            confirmDl(url, URLUtil.guessFileName(url, cd, mime), web.url ?: "", false)
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
        if (mediaRe.containsMatchIn(u)) confirmDl(u, URLUtil.guessFileName(u, null, null), "", false)
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

    private fun refreshBtn() {
        foundBtn.text = "⬇ Bulunan (${found.size})"
    }

    private fun addFound(u: String, page: String, kind: String) {
        if (found.containsKey(u)) return
        if (found.size > 300) found.remove(found.keys.first())
        val isHls = kind == "hls" || u.substringBefore('?').lowercase().endsWith(".m3u8")
        val known = setOf("mp4", "mkv", "webm", "mp3", "m4a", "flv", "avi", "mov", "zip", "rar", "7z", "apk", "pdf")
        val guessed = URLUtil.guessFileName(u, null, null).substringAfterLast('.', "").lowercase()
        val ext = when {
            isHls -> "m3u8"
            guessed in known -> guessed
            else -> "mp4"
        }
        val title = (web.title ?: "").trim().ifBlank { Uri.parse(u).host ?: "video" }
        val name = safe(title).take(80) + "." + ext
        found[u] = Found(u, name, page.ifBlank { web.url ?: "" }, isHls)
        refreshBtn()
    }

    // ---------- YouTube (Invidious üzerinden) ----------
    private fun checkYoutube(url: String?) {
        if (url == null) return
        if (!url.contains("youtube.com") && !url.contains("youtu.be")) return
        val id = Regex("(?:[?&]v=|youtu\\.be/|/shorts/)([A-Za-z0-9_-]{11})").find(url)?.groupValues?.get(1) ?: return
        if (id == ytLast) return
        ytLast = id
        Thread {
            for (inst in invidious) {
                try {
                    val c = URL("$inst/api/v1/videos/$id?local=true").openConnection() as HttpURLConnection
                    c.connectTimeout = 10000
                    c.readTimeout = 15000
                    if (c.responseCode != 200) continue
                    val j = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
                    val title = j.optString("title", "YouTube")
                    val list = ArrayList<Found>()
                    val fs = j.optJSONArray("formatStreams")
                    if (fs != null) for (i in 0 until fs.length()) addYt(list, inst, title, fs.getJSONObject(i), 0)
                    val af = j.optJSONArray("adaptiveFormats")
                    if (af != null) for (i in 0 until af.length()) addYt(list, inst, title, af.getJSONObject(i), 1)
                    if (list.isEmpty()) continue
                    runOnUiThread {
                        for (f in list) found[f.url] = f
                        refreshBtn()
                        Toast.makeText(this, "YouTube: ${list.size} kaynak bulundu", Toast.LENGTH_SHORT).show()
                    }
                    return@Thread
                } catch (e: Exception) {
                }
            }
        }.start()
    }

    private fun addYt(list: ArrayList<Found>, inst: String, title: String, o: JSONObject, mode: Int) {
        if (list.size >= 16) return
        var u = o.optString("url")
        if (u.isEmpty()) return
        if (u.startsWith("/")) u = inst + u
        val type = o.optString("type")
        if (type.contains("webm") || type.contains("av01")) return
        val audio = type.startsWith("audio/")
        val q = o.optString("qualityLabel", o.optString("quality", ""))
        val tag = when {
            audio -> "ses"
            mode == 1 -> "$q sadece-video"
            else -> q
        }
        val ext = if (audio) "m4a" else "mp4"
        list.add(Found(u, safe(title).take(70) + " [" + tag + "]." + ext, "", false))
    }

    private fun showFound() {
        val list = found.values.toList().takeLast(60).reversed()
            .sortedBy { if (it.url.contains("master")) 0 else 1 }
        if (list.isEmpty()) {
            Toast.makeText(this, "Henüz medya/dosya bulunamadı. Videoyu oynat veya sayfayı gez.", Toast.LENGTH_LONG).show()
            return
        }
        val labels = list.map { f ->
            f.name + "\n" + (Uri.parse(f.url).host ?: "") + (if (f.hls) "  •  HLS" else "")
        }
        AlertDialog.Builder(this)
            .setTitle("Bulunan medya/dosyalar")
            .setItems(labels.toTypedArray()) { _, i ->
                startDl(list[i].url, list[i].name, list[i].referer, list[i].hls)
            }
            .setNeutralButton("Temizle") { _, _ ->
                found.clear(); ytLast = ""; refreshBtn()
            }
            .show()
    }

    private fun confirmDl(url: String, name: String, ref: String, hls: Boolean) {
        AlertDialog.Builder(this)
            .setTitle("İndirilsin mi?")
            .setMessage(name)
            .setPositiveButton("İndir") { _, _ -> startDl(url, name, ref, hls) }
            .setNegativeButton("Vazgeç", null)
            .show()
    }

    private fun startDl(url: String, name: String, ref: String, hls: Boolean) {
        val ck = CookieManager.getInstance().getCookie(url) ?: ""
        Dm.start(this, url, name, web.settings.userAgentString, ck, ref, hls)
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
