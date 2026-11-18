package com.bymesut1.idm

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.ServiceWorkerClient
import android.webkit.ServiceWorkerController
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ArrayAdapter
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.webkit.UserAgentMetadata
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : Activity() {

    class Tab {
        lateinit var web: WebView
        val found = LinkedHashMap<String, Found>()
        val seen = HashSet<String>()
        var ytLast = ""
        var desktop = false
    }

    private val HOME = "https://www.google.com"
    private val tabs = ArrayList<Tab>()
    private var cur: Tab? = null

    private lateinit var content: FrameLayout
    private lateinit var webFrame: FrameLayout
    private lateinit var fullFrame: FrameLayout
    private lateinit var urlBar: EditText
    private lateinit var progress: ProgressBar
    private lateinit var foundBtn: Button
    private lateinit var tabBtn: Button
    private lateinit var panel: LinearLayout
    private lateinit var panelTitle: TextView
    private lateinit var panelAction: Button
    private lateinit var panelSearch: EditText
    private lateinit var panelList: ListView
    private lateinit var dlAdapter: BaseAdapter
    private lateinit var histAdapter: BaseAdapter
    private var panelMode = 0
    private var histRows: List<HRow> = emptyList()
    private lateinit var hist: HistoryDb

    private val h = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val probePool = Executors.newFixedThreadPool(3)

    private var customView: View? = null
    private var customCb: WebChromeClient.CustomViewCallback? = null
    private var fileCb: ValueCallback<Array<Uri>>? = null

    private var mobileUa = ""
    private var desktopUa = ""
    private var chMajor = "130"
    private var chFull = "130.0.0.0"

    private val mediaRe = Regex(
        "\\.(mp4|m3u8|mpd|mkv|webm|mp3|m4a|flv|avi|mov|aac|ogg|opus|wav|zip|rar|7z|apk|pdf|iso|srt|vtt|ass|ssa|ttml|dfxp|sbv)(\\?|&|#|$)",
        RegexOption.IGNORE_CASE
    )
    private val segRe = Regex("\\.(ts|m4s|m4f|cmfv|cmfa|vtt|webvtt|srt)$", RegexOption.IGNORE_CASE)
    private val knownExt = setOf("mp4", "mkv", "webm", "mp3", "m4a", "flv", "avi", "mov", "aac", "ogg", "opus", "wav", "zip", "rar", "7z", "apk", "pdf", "iso", "srt", "vtt", "ass", "ssa", "ttml", "dfxp", "sbv")
    private val subExt = setOf("srt", "vtt", "ass", "ssa", "ttml", "dfxp", "sbv")
    private val subSegRe = Regex("(seg|segment|chunk|frag|fragment)[-_]?\\d+|/\\d+\\.(web)?vtt$", RegexOption.IGNORE_CASE)

    private val subHintRe = Regex("(subtitle|caption|altyaz|/subs?/|[?&](lang|language|srclang)=|[/_.-](vtt|srt)([/_.?&-]|$))")
    private val staticRe = Regex("\\.(js|css|png|jpe?g|gif|webp|svg|ico|woff2?|ttf|otf|html?|map)$")

    private fun dp(i: Int) = (i * resources.displayMetrics.density).toInt()
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    // ------------------------------------------------------------------ JS köprüsü
    inner class Bridge(private val tab: Tab) {
        @JavascriptInterface
        fun found(url: String, page: String, kind: String) {
            runOnUiThread { addFound(tab, url, page, kind) }
        }

        @JavascriptInterface
        fun sub(url: String, page: String, label: String) {
            runOnUiThread { addSub(tab, url, page, label, null) }
        }

        @JavascriptInterface
        fun subData(url: String, page: String, label: String, ext: String, text: String) {
            runOnUiThread { addSub(tab, url, page, label, ext, text) }
        }

        @JavascriptInterface
        fun chromeMajor(): String = chMajor

        @JavascriptInterface
        fun isMobile(): Boolean = !tab.desktop
    }

    private val ticker = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            for (d in Dm.items) {
                if (d.state == "İndiriliyor" && !d.segments) {
                    if (d.lastTime > 0) d.speed = (d.done.get() - d.lastBytes) * 1000 / maxOf(1L, now - d.lastTime)
                    d.lastBytes = d.done.get()
                    d.lastTime = now
                }
            }
            if (panel.visibility == View.VISIBLE && panelMode == 1) dlAdapter.notifyDataSetChanged()
            h.postDelayed(this, 700)
        }
    }

    // ------------------------------------------------------------------ Chrome kimliği
    private fun initIdentity() {
        val def = WebSettings.getDefaultUserAgent(this)
        chMajor = Regex("Chrome/(\\d+)").find(def)?.groupValues?.get(1) ?: "130"
        chFull = try {
            val v = WebViewCompat.getCurrentWebViewPackage(this)?.versionName
            if (v != null && v.startsWith(chMajor)) v else "$chMajor.0.0.0"
        } catch (e: Exception) {
            "$chMajor.0.0.0"
        }
        mobileUa = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$chMajor.0.0.0 Mobile Safari/537.36"
        desktopUa = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$chMajor.0.0.0 Safari/537.36"
    }

    private fun brand(name: String, major: String, full: String) =
        UserAgentMetadata.BrandVersion.Builder().setBrand(name).setMajorVersion(major).setFullVersion(full).build()

    private fun applyIdentity(t: Tab) {
        val s = t.web.settings
        s.userAgentString = if (t.desktop) desktopUa else mobileUa
        if (WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA)) {
            try {
                val brands = listOf(
                    brand("Chromium", chMajor, chFull),
                    brand("Google Chrome", chMajor, chFull),
                    brand("Not_A Brand", "24", "24.0.0.0")
                )
                val md = UserAgentMetadata.Builder()
                    .setBrandVersionList(brands)
                    .setFullVersion(chFull)
                    .setPlatform(if (t.desktop) "Windows" else "Android")
                    .setPlatformVersion("10.0.0")
                    .setMobile(!t.desktop)
                    .setModel(if (t.desktop) "" else "K")
                    .build()
                WebSettingsCompat.setUserAgentMetadata(s, md)
            } catch (e: Exception) {
            }
        }
    }

    // ------------------------------------------------------------------ Yaşam döngüsü
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        Dm.setup(this)
        hist = HistoryDb(applicationContext)
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        initIdentity()

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // Üst bar
        val top = LinearLayout(this).apply {
            setPadding(dp(4), dp(4), dp(4), dp(4))
            gravity = Gravity.CENTER_VERTICAL
        }
        urlBar = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine()
            hint = "Adres yaz veya ara"
            imeOptions = EditorInfo.IME_ACTION_GO
            setOnEditorActionListener { _, _, _ ->
                go(text.toString())
                true
            }
        }
        top.addView(urlBar, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(smallBtn("Git") { go(urlBar.text.toString()) })
        top.addView(smallBtn("🕘") { openPanel(2) })

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            visibility = View.GONE
        }

        webFrame = FrameLayout(this)
        buildPanel()

        val frame = FrameLayout(this)
        frame.addView(webFrame, FrameLayout.LayoutParams(-1, -1))
        frame.addView(panel, FrameLayout.LayoutParams(-1, -1))

        // Alt bar
        val bottom = LinearLayout(this)
        val backBtn = smallBtn("◀") { onBackPressed() }
        tabBtn = smallBtn("▢ 1") { showTabs() }
        val dlBtn = smallBtn("İndirilenler") { if (panelMode == 1) closePanel() else openPanel(1) }
        foundBtn = smallBtn("⬇ Bulunan (0)") { showFound() }
        val menuBtn = smallBtn("⋮") { showMenu() }
        bottom.addView(backBtn, LinearLayout.LayoutParams(0, -2, 0.6f))
        bottom.addView(tabBtn, LinearLayout.LayoutParams(0, -2, 0.8f))
        bottom.addView(dlBtn, LinearLayout.LayoutParams(0, -2, 1.3f))
        bottom.addView(foundBtn, LinearLayout.LayoutParams(0, -2, 1.5f))
        bottom.addView(menuBtn, LinearLayout.LayoutParams(0, -2, 0.6f))

        root.addView(top)
        root.addView(progress, LinearLayout.LayoutParams(-1, dp(3)))
        root.addView(frame, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(bottom)

        fullFrame = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            visibility = View.GONE
            isClickable = true
        }
        content = FrameLayout(this)
        content.addView(root, FrameLayout.LayoutParams(-1, -1))
        content.addView(fullFrame, FrameLayout.LayoutParams(-1, -1))
        setContentView(content)

        newTab(null)

        if (Build.VERSION.SDK_INT >= 24) {
            try {
                ServiceWorkerController.getInstance().setServiceWorkerClient(object : ServiceWorkerClient() {
                    override fun shouldInterceptRequest(request: WebResourceRequest?): WebResourceResponse? {
                        val r = request ?: return null
                        val t = cur ?: return null
                        if (r.method == "GET") sniff(t, r)
                        return null
                    }
                })
            } catch (e: Exception) {
            }
        }

        h.post(ticker)
        handle(intent)
    }

    private fun smallBtn(label: String, onClick: () -> Unit): Button = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 12f
        minWidth = 0
        minimumWidth = 0
        setPadding(dp(6), 0, dp(6), 0)
        setOnClickListener { onClick() }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handle(intent)
    }

    override fun onDestroy() {
        h.removeCallbacks(ticker)
        for (t in tabs) {
            try {
                t.web.destroy()
            } catch (e: Exception) {
            }
        }
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val t = cur
        if (customView != null) exitFull()
        else if (panel.visibility == View.VISIBLE) closePanel()
        else if (t != null && t.web.canGoBack()) t.web.goBack()
        else if (t != null && tabs.size > 1) closeTab(t)
        else super.onBackPressed()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == 77) {
            fileCb?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data))
            fileCb = null
            return
        }
        super.onActivityResult(requestCode, resultCode, data)
    }

    private fun handle(i: Intent?) {
        var u: String? = null
        if (i != null) {
            if (i.action == Intent.ACTION_VIEW) u = i.dataString
            else if (i.action == Intent.ACTION_SEND)
                u = Regex("https?://\\S+").find(i.getStringExtra(Intent.EXTRA_TEXT) ?: "")?.value
        }
        val t = cur ?: return
        if (u == null) {
            if (t.web.url == null) t.web.loadUrl(HOME)
            return
        }
        val path = u.substringBefore('?')
        if (mediaRe.containsMatchIn(u) && !path.endsWith(".pdf", true)) {
            val kind = when {
                path.endsWith(".m3u8", true) -> Found.HLS
                path.endsWith(".mpd", true) -> Found.DASH
                else -> Found.FILE
            }
            confirmDl(Found(u, URLUtil.guessFileName(u, null, null), "", kind))
        } else {
            t.web.loadUrl(u)
        }
    }

    private fun go(s: String) {
        val t = s.trim()
        if (t.isEmpty()) return
        val u = when {
            t.startsWith("http://") || t.startsWith("https://") || t.startsWith("file://") || t.startsWith("about:") -> t
            t.contains(".") && !t.contains(" ") -> "https://$t"
            else -> "https://www.google.com/search?q=" + Uri.encode(t)
        }
        cur?.web?.loadUrl(u)
        urlBar.clearFocus()
    }

    // ------------------------------------------------------------------ Sekmeler
    private fun newTab(url: String?): Tab {
        val t = Tab()
        t.web = WebView(this)
        configure(t)
        tabs.add(t)
        showTab(t)
        if (url != null) t.web.loadUrl(url)
        return t
    }

    private fun showTab(t: Tab) {
        cur = t
        webFrame.removeAllViews()
        (t.web.parent as? ViewGroup)?.removeView(t.web)
        webFrame.addView(t.web, FrameLayout.LayoutParams(-1, -1))
        urlBar.setText(t.web.url ?: "")
        progress.visibility = View.GONE
        refreshBtn()
        tabBtn.text = "▢ ${tabs.size}"
    }

    private fun closeTab(t: Tab) {
        val i = tabs.indexOf(t)
        if (i < 0) return
        tabs.removeAt(i)
        webFrame.removeView(t.web)
        try {
            t.web.stopLoading()
            t.web.destroy()
        } catch (e: Exception) {
        }
        if (tabs.isEmpty()) {
            newTab(HOME)
            return
        }
        if (cur === t) showTab(tabs[minOf(i, tabs.size - 1)]) else tabBtn.text = "▢ ${tabs.size}"
    }

    private fun showTabs() {
        val names = tabs.map { t ->
            val title = (t.web.title ?: "").ifBlank { t.web.url ?: "Yeni sekme" }
            (if (t === cur) "✓ " else "") + title
        }
        AlertDialog.Builder(this)
            .setTitle("Sekmeler (${tabs.size})")
            .setItems(names.toTypedArray()) { _, i -> showTab(tabs[i]) }
            .setPositiveButton("Yeni sekme") { _, _ -> newTab(HOME) }
            .setNegativeButton("Bu sekmeyi kapat") { _, _ -> cur?.let { closeTab(it) } }
            .setNeutralButton("Vazgeç", null)
            .show()
    }

    // ------------------------------------------------------------------ WebView ayarı (Chrome gibi)
    private fun configure(t: Tab) {
        val w = t.web
        val s = w.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.mediaPlaybackRequiresUserGesture = false
        s.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        s.javaScriptCanOpenWindowsAutomatically = true
        s.setSupportMultipleWindows(true)
        s.useWideViewPort = true
        s.loadWithOverviewMode = true
        s.setSupportZoom(true)
        s.builtInZoomControls = true
        s.displayZoomControls = false
        applyIdentity(t)
        if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
            WebSettingsCompat.setRequestedWithHeaderOriginAllowList(s, emptySet())
        }
        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        cm.setAcceptThirdPartyCookies(w, true)
        w.addJavascriptInterface(Bridge(t), "IDM")
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(w, Hook.JS, setOf("*"))
        }
        w.webViewClient = client(t)
        w.webChromeClient = chrome(t)
        w.setDownloadListener { url, _, cd, mime, _ ->
            if (url.startsWith("blob:")) {
                toast("blob: bağlantıları doğrudan indirilemez")
            } else {
                confirmDl(Found(url, URLUtil.guessFileName(url, cd, mime), t.web.url ?: "", Found.FILE))
            }
        }
    }

    private fun sniff(t: Tab, r: WebResourceRequest) {
        val u = r.url.toString()
        val ref = r.requestHeaders?.get("Referer") ?: ""
        val hasRange = r.requestHeaders?.containsKey("Range") == true
        if (u.contains("/api/timedtext")) {
            runOnUiThread { addYtCaption(t, u, ref) }
            return
        }
        val dest = r.requestHeaders?.get("Sec-Fetch-Dest") ?: ""
        val low = u.lowercase()
        if (mediaRe.containsMatchIn(u)) runOnUiThread { addFound(t, u, ref, "") }
        else if (dest == "track" || (subHintRe.containsMatchIn(low) && !staticRe.containsMatchIn(low.substringBefore('?')))) {
            runOnUiThread { probeSubContent(t, u, ref) }
        } else if (hasRange) runOnUiThread { probeAmbiguous(t, u, ref) }
    }

    private fun externalScheme(u: String): Boolean {
        val sch = u.substringBefore(':', "").lowercase()
        if (sch == "http" || sch == "https" || sch == "about" || sch == "javascript" ||
            sch == "data" || sch == "blob" || sch == "file"
        ) return false
        try {
            val i = if (sch == "intent") Intent.parseUri(u, Intent.URI_INTENT_SCHEME) else Intent(Intent.ACTION_VIEW, Uri.parse(u))
            i.addCategory(Intent.CATEGORY_BROWSABLE)
            i.component = null
            i.selector = null
            startActivity(i)
        } catch (e: Exception) {
        }
        return true
    }

    private fun client(t: Tab) = object : WebViewClient() {
        override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
            val r = request ?: return null
            if (r.method == "GET") sniff(t, r)
            return null
        }

        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
            val u = request?.url?.toString() ?: return false
            return externalScheme(u)
        }

        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            if (t === cur && url != null) urlBar.setText(url)
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            view?.evaluateJavascript("window.__idmScan&&window.__idmScan()", null)
        }

        override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
            if (url == null) return
            if (t === cur && !urlBar.hasFocus()) urlBar.setText(url)
            if (url.startsWith("http") && !isReload) {
                val title = view?.title ?: ""
                io.execute { hist.add(url, title) }
            }
            checkYoutube(t, url)
        }
    }

    private fun chrome(t: Tab) = object : WebChromeClient() {
        override fun onProgressChanged(view: WebView?, newProgress: Int) {
            if (t === cur) {
                progress.progress = newProgress
                progress.visibility = if (newProgress in 1..99) View.VISIBLE else View.GONE
            }
        }

        override fun onReceivedTitle(view: WebView?, title: String?) {
            val u = view?.url
            if (u != null && u.startsWith("http") && !title.isNullOrBlank()) {
                io.execute { hist.setTitle(u, title) }
            }
        }

        override fun onShowCustomView(view: View?, callback: WebChromeClient.CustomViewCallback?) {
            if (view == null) {
                callback?.onCustomViewHidden()
                return
            }
            enterFull(view, callback)
        }

        override fun onHideCustomView() {
            exitFull()
        }

        override fun getDefaultVideoPoster(): Bitmap? = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)

        override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?): Boolean {
            if (!isUserGesture || resultMsg == null) return false
            val nt = newTab(null)
            val tr = resultMsg.obj as WebView.WebViewTransport
            tr.webView = nt.web
            resultMsg.sendToTarget()
            return true
        }

        override fun onPermissionRequest(request: PermissionRequest?) {
            val r = request ?: return
            val ok = r.resources.filter { it == PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID }
            if (ok.isNotEmpty()) r.grant(ok.toTypedArray()) else r.deny()
        }

        override fun onShowFileChooser(
            webView: WebView?, filePathCallback: ValueCallback<Array<Uri>>?,
            fileChooserParams: WebChromeClient.FileChooserParams?
        ): Boolean {
            fileCb?.onReceiveValue(null)
            fileCb = filePathCallback
            return try {
                val pick = fileChooserParams?.createIntent() ?: Intent(Intent.ACTION_GET_CONTENT).setType("*/*")
                startActivityForResult(pick, 77)
                true
            } catch (e: Exception) {
                fileCb = null
                filePathCallback?.onReceiveValue(null)
                false
            }
        }
    }

    // ------------------------------------------------------------------ Tam ekran video
    @Suppress("DEPRECATION")
    private fun enterFull(v: View, cb: WebChromeClient.CustomViewCallback?) {
        if (customView != null) {
            cb?.onCustomViewHidden()
            return
        }
        customView = v
        customCb = cb
        fullFrame.removeAllViews()
        fullFrame.addView(v, FrameLayout.LayoutParams(-1, -1))
        fullFrame.visibility = View.VISIBLE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    }

    @Suppress("DEPRECATION")
    private fun exitFull() {
        val v = customView ?: return
        fullFrame.removeView(v)
        fullFrame.visibility = View.GONE
        customView = null
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        customCb?.onCustomViewHidden()
        customCb = null
    }

    // ------------------------------------------------------------------ Medya yakalama (IDM mantığı)
    private fun refreshBtn() {
        foundBtn.text = "⬇ Bulunan (${cur?.found?.size ?: 0})"
    }

    private fun addFound(t: Tab, u0: String, page: String, kindHint: String) {
        val u = u0.substringBefore('#')
        if (!(u.startsWith("http://") || u.startsWith("https://"))) return
        if (t.found.containsKey(u)) return
        val path = u.substringBefore('?')
        if (kindHint == "sub" || U.extOf(path.substringAfterLast('/')) in subExt) {
            addSub(t, u, page, "", null)
            return
        }
        if (segRe.containsMatchIn(path)) return
        val host = U.host(u)
        if (host.endsWith("googlevideo.com")) return
        if (t.found.size >= 250) t.found.remove(t.found.keys.first())
        val low = path.lowercase()
        val kind = when {
            kindHint == "hls" || low.endsWith(".m3u8") -> Found.HLS
            kindHint == "dash" || low.endsWith(".mpd") -> Found.DASH
            else -> Found.FILE
        }
        val ext = when (kind) {
            Found.HLS -> "m3u8"
            Found.DASH -> "mpd"
            else -> {
                val g = U.extOf(URLUtil.guessFileName(u, null, null))
                if (g in knownExt) g else "mp4"
            }
        }
        val title = (t.web.title ?: "").trim().ifBlank { host.ifBlank { "video" } }
        val f = Found(u, U.safe(title).take(80) + "." + ext, page.ifBlank { t.web.url ?: "" }, kind)
        t.found[u] = f
        refreshBtn()
        probeFound(t, f)
        if (kind == Found.HLS || kind == Found.DASH) probeSubs(t, f)
    }

    // Altyazı dosyası (.srt/.vtt/...) ekle
    private fun addSub(t: Tab, u0: String, page: String, label: String, forceExt: String?, data: String? = null) {
        val u = u0.substringBefore('#')
        if (!(u.startsWith("http://") || u.startsWith("https://")) && data == null) return
        val old = t.found[u]
        if (old != null) {
            // Aynı altyazı sonradan metniyle gelirse metni ekle (daha güvenilir indirme)
            if (data != null && old.data == null) {
                old.data = data
                old.size = data.length.toLong()
            }
            return
        }
        val path = u.substringBefore('?')
        if (subSegRe.containsMatchIn(path)) return
        val file = path.substringAfterLast('/')
        val g = U.extOf(file)
        val ext = forceExt ?: if (g in subExt) g else "vtt"
        val lab = label.ifBlank { U.baseName(file).take(30) }
        val title = (t.web.title ?: "").trim().ifBlank { U.host(u).ifBlank { "video" } }
        val f = Found(
            u, U.safe(title).take(70) + (if (lab.isNotBlank()) " [" + U.safe(lab) + "]" else "") + "." + ext,
            page.ifBlank { t.web.url ?: "" }, Found.FILE, null,
            "Altyazı" + (if (lab.isNotBlank()) " ($lab)" else "") + " • " + ext.uppercase()
        )
        t.found[u] = f
        refreshBtn()
        if (data != null) {
            f.data = data
            f.size = data.length.toLong()
        } else {
            probeFound(t, f)
        }
    }

    // Uzantısız / ipucu veren adresler: ilk baytlara bakıp altyazı mı diye anla
    private fun probeSubContent(t: Tab, u: String, ref: String) {
        if (!(u.startsWith("http://") || u.startsWith("https://"))) return
        if (t.found.containsKey(u)) return
        if (!t.seen.add("S" + u)) return
        if (t.seen.size > 1500) t.seen.clear()
        val ua = t.web.settings.userAgentString
        val ck = CookieManager.getInstance().getCookie(u) ?: ""
        probePool.execute {
            try {
                val k = Net.open(u, ua, ref, ck, "bytes=0-2999")
                try {
                    val code = k.code
                    if (code == 200 || code == 206) {
                        val ct = (k.c.contentType ?: "").lowercase()
                        if (!ct.startsWith("image/") && !ct.startsWith("video/") && !ct.startsWith("audio/")) {
                            val buf = ByteArray(3000)
                            var n = 0
                            k.c.inputStream.use { ins ->
                                while (n < 3000) {
                                    val r = ins.read(buf, n, 3000 - n)
                                    if (r < 0) break
                                    n += r
                                }
                            }
                            val kind = U.sniffSub(String(buf, 0, n, Charsets.UTF_8))
                            if (kind != null) runOnUiThread { addSub(t, u, ref, "", kind) }
                        }
                    }
                } finally {
                    k.c.disconnect()
                }
            } catch (e: Exception) {
            }
        }
    }

    // YouTube oynatıcısı altyazı isteyince (CC açılınca) SRT olarak ekle
    private fun addYtCaption(t: Tab, u: String, ref: String) {
        val lang = Regex("[?&]lang=([^&]+)").find(u)?.groupValues?.get(1) ?: "?"
        val clean = u.replace(Regex("&fmt=[^&]*"), "") + "&fmt=srt"
        addSub(t, clean, ref, "YouTube $lang", "srt")
    }

    // HLS master / DASH içindeki altyazı kanallarını bul
    private fun probeSubs(t: Tab, f: Found) {
        val ua = t.web.settings.userAgentString
        val ck = CookieManager.getInstance().getCookie(f.url) ?: ""
        val titleBase = U.baseName(f.name)
        probePool.execute {
            try {
                val txt = Net.text(f.url, ua, f.referer, ck)
                val list = if (f.kind == Found.HLS) Hls.subs(f.url, txt) else Dash.subtitles(f.url, txt)
                if (list.isNotEmpty()) {
                    runOnUiThread {
                        for (x in list) {
                            if (t.found.containsKey(x.second)) continue
                            if (f.kind == Found.HLS) {
                                t.found[x.second] = Found(
                                    x.second, U.safe(titleBase).take(70) + " [" + U.safe(x.first) + "].vtt", f.referer,
                                    Found.SUBHLS, null, "Altyazı (${x.first}) • HLS"
                                )
                            } else {
                                val ge = U.extOf(x.second.substringBefore('?').substringAfterLast('/'))
                                val ex = if (ge in subExt) ge else "vtt"
                                t.found[x.second] = Found(
                                    x.second, U.safe(titleBase).take(70) + " [" + U.safe(x.first) + "]." + ex, f.referer,
                                    Found.FILE, null, "Altyazı (${x.first}) • ${ex.uppercase()}"
                                )
                            }
                        }
                        refreshBtn()
                    }
                }
            } catch (e: Exception) {
            }
        }
    }

    private fun probeFound(t: Tab, f: Found) {
        val ua = t.web.settings.userAgentString
        val ck = CookieManager.getInstance().getCookie(f.url) ?: ""
        probePool.execute {
            try {
                val k = Net.open(f.url, ua, f.referer, ck, "bytes=0-0")
                try {
                    val code = k.code
                    val ct = (k.c.contentType ?: "").lowercase()
                    var total = -1L
                    if (code == 206) total = k.c.getHeaderField("Content-Range")?.substringAfter('/')?.trim()?.toLongOrNull() ?: -1L
                    else if (code == 200) total = k.c.contentLengthLong
                    runOnUiThread { applyProbe(t, f, ct, total) }
                } finally {
                    k.c.disconnect()
                }
            } catch (e: Exception) {
            }
        }
    }

    private fun applyProbe(t: Tab, f: Found, ct: String, total: Long) {
        if (!t.found.containsKey(f.url)) return
        if (f.kind == Found.FILE) {
            val ext = U.extOf(f.name)
            val big = ext == "zip" || ext == "rar" || ext == "7z" || ext == "apk" || ext == "pdf" || ext == "iso" || ext in subExt
            if (ct.startsWith("text/html") || ct.startsWith("image/") || (!big && total in 1..150_000L)) {
                t.found.remove(f.url)
                refreshBtn()
                return
            }
            val want = U.extForMime(ct)
            if (want != null && ext == "mp4" && want != "mp4" && (ct.startsWith("video/") || ct.startsWith("audio/"))) {
                f.name = U.baseName(f.name) + "." + want
            }
        }
        if (total > 0) f.size = total
        f.mime = ct
    }

    // Uzantısız ama Range ile istenen (oynatıcı) istekleri: içerik türüne bakıp karar ver.
    private fun probeAmbiguous(t: Tab, u: String, ref: String) {
        if (!(u.startsWith("http://") || u.startsWith("https://"))) return
        if (U.host(u).endsWith("googlevideo.com")) return
        if (!t.seen.add(u)) return
        if (t.seen.size > 1500) t.seen.clear()
        val ua = t.web.settings.userAgentString
        val ck = CookieManager.getInstance().getCookie(u) ?: ""
        probePool.execute {
            try {
                val k = Net.open(u, ua, ref, ck, "bytes=0-0")
                try {
                    val code = k.code
                    val ct = (k.c.contentType ?: "").lowercase()
                    var total = -1L
                    if (code == 206) total = k.c.getHeaderField("Content-Range")?.substringAfter('/')?.trim()?.toLongOrNull() ?: -1L
                    else if (code == 200) total = k.c.contentLengthLong
                    val kind = when {
                        ct.contains("mpegurl") -> "hls"
                        ct.contains("dash+xml") -> "dash"
                        ((ct.startsWith("video/") && !ct.contains("mp2t")) || ct.startsWith("audio/")) &&
                            (total < 0 || total > 800_000L) -> "video"
                        ct.contains("octet-stream") && total > 5_000_000L -> "video"
                        else -> ""
                    }
                    if (kind.isNotEmpty()) runOnUiThread { addFound(t, u, ref, kind) }
                } finally {
                    k.c.disconnect()
                }
            } catch (e: Exception) {
            }
        }
    }

    // ------------------------------------------------------------------ YouTube
    private fun checkYoutube(t: Tab, url: String?) {
        if (url == null) return
        val host = U.host(url)
        if (!(host.endsWith("youtube.com") || host == "youtu.be")) return
        val id = Yt.videoId(url) ?: return
        if (id == t.ytLast) return
        t.ytLast = id
        Thread {
            val r = try {
                Yt.resolve(id)
            } catch (e: Exception) {
                null
            }
            runOnUiThread {
                if (r == null || r.entries.isEmpty()) {
                    toast("YouTube: indirme kaynağı alınamadı (YouTube yöntemi değişmiş olabilir)")
                } else {
                    for (f in r.entries) t.found[f.url + (f.audioUrl ?: "")] = f
                    refreshBtn()
                    toast("YouTube: ${r.entries.size} seçenek bulundu")
                }
            }
        }.start()
    }

    // ------------------------------------------------------------------ Bulunanlar listesi
    private fun showFound() {
        val t = cur ?: return
        val all = t.found.values.toList()
        val list = all.filter { it.pri > 0 } + all.filter { it.pri == 0 }.reversed()
        if (list.isEmpty()) {
            Toast.makeText(this, "Henüz medya/dosya bulunamadı. Videoyu oynat veya sayfayı gez.", Toast.LENGTH_LONG).show()
            return
        }
        val ad = object : ArrayAdapter<Found>(this, android.R.layout.simple_list_item_2, android.R.id.text1, list) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getView(position, convertView, parent)
                val f = getItem(position)!!
                v.findViewById<TextView>(android.R.id.text1).text = f.name
                val desc = if (f.label.isNotEmpty()) f.label else {
                    val k = when (f.kind) {
                        Found.HLS -> "HLS akış (parçalı)"
                        Found.DASH -> "DASH akış"
                        else -> U.extOf(f.name).uppercase()
                    }
                    k + (if (f.size > 0) " • " + U.fmt(f.size) else "")
                }
                val tail = f.url.substringBefore('?').substringAfterLast('/').takeLast(30)
                v.findViewById<TextView>(android.R.id.text2).text = desc + "  •  " + U.host(f.url) + (if (tail.isNotEmpty()) "/…" + tail else "")
                return v
            }
        }
        AlertDialog.Builder(this)
            .setTitle("Bulunan medya/dosyalar (${list.size})")
            .setAdapter(ad) { _, i -> foundActions(list[i]) }
            .setPositiveButton("Kapat", null)
            .setNeutralButton("Tümünü kopyala") { _, _ ->
                copy(list.joinToString("\n") { it.url + (if (it.audioUrl != null) "\n" + it.audioUrl else "") })
            }
            .setNegativeButton("Temizle") { _, _ ->
                t.found.clear()
                t.ytLast = ""
                refreshBtn()
            }
            .show()
    }

    private fun foundActions(f: Found) {
        val opts = ArrayList<String>()
        val acts = ArrayList<() -> Unit>()
        opts.add("⬇  İndir"); acts.add { startDl(f) }
        if (f.kind == Found.PAIR && f.audioUrl != null) {
            val au: String = f.audioUrl
            opts.add("📋  Video linkini kopyala"); acts.add { copy(f.url) }
            opts.add("📋  Ses linkini kopyala"); acts.add { copy(au) }
            opts.add("🗗  Video linkini yeni sekmede aç"); acts.add { newTab(f.url) }
            opts.add("🗗  Ses linkini yeni sekmede aç"); acts.add { newTab(au) }
        } else {
            opts.add("📋  Tam linki kopyala"); acts.add { copy(f.url) }
            opts.add("🗗  Yeni sekmede aç"); acts.add { newTab(f.url) }
        }
        opts.add("🔗  Linki göster"); acts.add { showText(f.url) }
        opts.add("↗  Linki paylaş"); acts.add { share(f.url) }
        AlertDialog.Builder(this)
            .setTitle(f.name)
            .setItems(opts.toTypedArray()) { _, i -> acts[i]() }
            .setNegativeButton("Kapat", null)
            .show()
    }

    private fun copy(s: String) {
        val cm = getSystemService(ClipboardManager::class.java)
        cm.setPrimaryClip(ClipData.newPlainText("link", s))
        toast("Link kopyalandı (${s.length} karakter)")
    }

    private fun share(s: String) {
        val i = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, s)
        }
        startActivity(Intent.createChooser(i, "Paylaş"))
    }

    private fun showText(s: String) {
        val tv = TextView(this).apply {
            text = s
            setTextIsSelectable(true)
            setPadding(dp(20), dp(12), dp(20), dp(12))
        }
        AlertDialog.Builder(this).setTitle("Tam link").setView(tv)
            .setPositiveButton("Kopyala") { _, _ -> copy(s) }
            .setNegativeButton("Kapat", null).show()
    }

    // ------------------------------------------------------------------ İndirme
    private fun confirmDl(f: Found) {
        AlertDialog.Builder(this)
            .setTitle("İndirilsin mi?")
            .setMessage(f.name)
            .setPositiveButton("İndir") { _, _ -> startDl(f) }
            .setNegativeButton("Vazgeç", null)
            .show()
    }

    private fun startDl(f: Found) {
        val t = cur ?: return
        val ua = t.web.settings.userAgentString
        val ck = CookieManager.getInstance().getCookie(f.url) ?: ""
        Dm.start(this, f, ua, ck)
        toast("İndirme başladı: ${f.name}")
    }

    private fun status(d: Item): String = when (d.state) {
        "İndiriliyor" ->
            if (d.segments) "${d.done.get()} / ${d.total} parça"
            else {
                val pct = if (d.total > 0) minOf(100L, d.done.get() * 100 / d.total).toInt() else 0
                "%$pct  ${U.fmt(d.done.get())} / ${if (d.total > 0) U.fmt(d.total) else "?"}  ${U.fmt(d.speed)}/s"
            }
        "Birleştiriliyor" -> {
            val pct = if (d.total > 0) minOf(100L, d.done.get() * 100 / d.total).toInt() else 0
            "Video + ses birleştiriliyor… %$pct"
        }
        "Kaydediliyor" -> "İndirilenler/IDM klasörüne kaydediliyor…"
        "Hata" -> "Hata: ${d.error}\nTekrar denemek için dokun"
        "İptal" -> "İptal edildi • Tekrar denemek için dokun"
        "Bitti" -> "Bitti • İndirilenler/IDM" + (if (d.note.isNotEmpty()) "\n⚠ ${d.note}" else "")
        else -> d.state
    }

    // ------------------------------------------------------------------ Panel: İndirilenler / Geçmiş
    private fun buildPanel() {
        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            visibility = View.GONE
            isClickable = true
        }
        val head = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(4), dp(4), dp(4))
        }
        panelTitle = TextView(this).apply { textSize = 18f }
        panelAction = smallBtn("") { panelActionClick() }
        head.addView(panelTitle, LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(panelAction)
        head.addView(smallBtn("✕") { closePanel() })
        panelSearch = EditText(this).apply {
            hint = "Geçmişte ara"
            setSingleLine()
            visibility = View.GONE
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    loadHistory()
                }
            })
        }

        dlAdapter = object : BaseAdapter() {
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
                pb.progress = when {
                    d.state == "Bitti" -> 100
                    d.total > 0 -> minOf(100L, d.done.get() * 100 / d.total).toInt()
                    else -> 0
                }
                row.addView(pb)
                row.addView(TextView(this@MainActivity).apply { text = status(d); textSize = 12f })
                return row
            }
        }
        val df = SimpleDateFormat("dd.MM HH:mm", Locale.getDefault())
        histAdapter = object : BaseAdapter() {
            override fun getCount() = histRows.size
            override fun getItem(p: Int) = histRows[p]
            override fun getItemId(p: Int) = p.toLong()
            override fun getView(p: Int, v: View?, parent: ViewGroup?): View {
                val r = histRows.getOrNull(p) ?: return View(this@MainActivity)
                val row = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(12), dp(8), dp(12), dp(8))
                }
                row.addView(TextView(this@MainActivity).apply {
                    text = r.title.ifBlank { r.url }
                    textSize = 15f
                    setSingleLine()
                })
                row.addView(TextView(this@MainActivity).apply {
                    text = df.format(Date(r.ts)) + "  •  " + r.url
                    textSize = 11f
                    setSingleLine()
                })
                return row
            }
        }

        panelList = ListView(this)
        panelList.setOnItemClickListener { _, _, p, _ ->
            if (panelMode == 1) dlClick(p) else histClick(p)
        }
        panelList.setOnItemLongClickListener { _, _, p, _ ->
            if (panelMode == 1) {
                val d = Dm.items.getOrNull(p)
                if (d != null && d.state != "İndiriliyor" && d.state != "Bekliyor" &&
                    d.state != "Birleştiriliyor" && d.state != "Kaydediliyor"
                ) {
                    Dm.items.remove(d)
                    dlAdapter.notifyDataSetChanged()
                }
            } else {
                histLong(p)
            }
            true
        }
        panel.addView(head)
        panel.addView(panelSearch)
        panel.addView(panelList, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    private fun openPanel(mode: Int) {
        panelMode = mode
        panel.visibility = View.VISIBLE
        if (mode == 1) {
            panelTitle.text = "İndirilenler"
            panelSearch.visibility = View.GONE
            panelAction.text = "Bitenleri temizle"
            panelList.adapter = dlAdapter
            dlAdapter.notifyDataSetChanged()
        } else {
            panelTitle.text = "Geçmiş"
            panelSearch.visibility = View.VISIBLE
            panelAction.text = "Tümünü sil"
            panelList.adapter = histAdapter
            loadHistory()
        }
    }

    private fun closePanel() {
        panelMode = 0
        panel.visibility = View.GONE
    }

    private fun panelActionClick() {
        if (panelMode == 1) {
            val dead = Dm.items.filter {
                it.state == "Bitti" || it.state == "Hata" || it.state == "İptal"
            }
            Dm.items.removeAll(dead)
            dlAdapter.notifyDataSetChanged()
        } else {
            AlertDialog.Builder(this)
                .setTitle("Tüm geçmiş silinsin mi?")
                .setPositiveButton("Sil") { _, _ ->
                    io.execute {
                        hist.clear()
                        runOnUiThread { loadHistory() }
                    }
                }
                .setNegativeButton("Vazgeç", null)
                .show()
        }
    }

    private fun loadHistory() {
        val q = panelSearch.text.toString().trim()
        io.execute {
            val rows = try {
                hist.list(q)
            } catch (e: Exception) {
                emptyList()
            }
            runOnUiThread {
                histRows = rows
                histAdapter.notifyDataSetChanged()
            }
        }
    }

    private fun histClick(p: Int) {
        val r = histRows.getOrNull(p) ?: return
        cur?.web?.loadUrl(r.url)
        closePanel()
    }

    private fun histLong(p: Int) {
        val r = histRows.getOrNull(p) ?: return
        AlertDialog.Builder(this)
            .setTitle(r.title.ifBlank { r.url })
            .setItems(arrayOf("Yeni sekmede aç", "Linki kopyala", "Geçmişten sil")) { _, i ->
                when (i) {
                    0 -> {
                        closePanel()
                        newTab(r.url)
                    }
                    1 -> copy(r.url)
                    else -> io.execute {
                        hist.delete(r.id)
                        runOnUiThread { loadHistory() }
                    }
                }
            }
            .show()
    }

    private fun dlClick(p: Int) {
        val d = Dm.items.getOrNull(p) ?: return
        val u = d.uri
        if (d.state == "Bitti" && u != null) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(u, contentResolver.getType(u) ?: "*/*")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                })
            } catch (e: ActivityNotFoundException) {
                Toast.makeText(this, "Açacak uygulama yok. Dosya: İndirilenler/IDM", Toast.LENGTH_LONG).show()
            }
        } else if (d.state == "İndiriliyor" || d.state == "Bekliyor") {
            d.cancelled = true
        } else if (d.state == "Hata" || d.state == "İptal") {
            Dm.retry(this, d)
            dlAdapter.notifyDataSetChanged()
            toast("Tekrar deneniyor")
        }
    }

    // ------------------------------------------------------------------ Menü
    private fun showMenu() {
        val t = cur ?: return
        val items = arrayOf(
            "Yeni sekme",
            "Geçmiş",
            "Masaüstü sitesi: " + (if (t.desktop) "AÇIK" else "kapalı"),
            "Sayfada medya tara / yenile",
            "Sayfayı yenile",
            "Sayfa linkini kopyala"
        )
        AlertDialog.Builder(this)
            .setItems(items) { _, i ->
                when (i) {
                    0 -> newTab(HOME)
                    1 -> openPanel(2)
                    2 -> {
                        t.desktop = !t.desktop
                        applyIdentity(t)
                        t.web.reload()
                    }
                    3 -> {
                        t.web.evaluateJavascript("window.__idmScan&&window.__idmScan()", null)
                        t.ytLast = ""
                        checkYoutube(t, t.web.url)
                        toast("Taranıyor…")
                    }
                    4 -> t.web.reload()
                    else -> copy(t.web.url ?: "")
                }
            }
            .show()
    }
}
