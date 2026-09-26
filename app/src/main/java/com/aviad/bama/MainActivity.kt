package com.aviad.bama

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Presentation
import android.content.Context
import android.graphics.Color
import android.hardware.display.DisplayManager
import android.view.Display
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Base64
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.webkit.WebViewAssetLoader
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.Charset
import kotlin.concurrent.thread

class MainActivity : ComponentActivity() {

    private lateinit var web: WebView
    private var pageReady = false
    private var pendingUri: Uri? = null
    private var fileCallback: ValueCallback<Array<Uri>>? = null

    private val cast by lazy { CastServer(applicationContext) }
    private val ccDevices = LinkedHashMap<String, CastDevice>()
    private val chromecast by lazy {
        Chromecast(this, object : Chromecast.Listener {
            override fun onDevices(list: List<CastDevice>, scanning: Boolean) {
                list.forEach { ccDevices[it.id] = it }
                val arr = org.json.JSONArray()
                list.forEach { arr.put(it.toJson()) }
                js("window.__ccDevices && window.__ccDevices(" + arr.toString() + "," + scanning + ")")
            }

            override fun onStatus(deviceId: String?, state: String, message: String) {
                js("window.__ccStatus && window.__ccStatus(" + JSONObject.quote(deviceId ?: "") + "," +
                    JSONObject.quote(state) + "," + JSONObject.quote(message) + ")")
            }
        })
    }
    private var casting = false
    private var stage: StagePresentation? = null
    private val displayManager by lazy { getSystemService(Context.DISPLAY_SERVICE) as DisplayManager }
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(id: Int) { refreshStage() }
        override fun onDisplayRemoved(id: Int) { refreshStage() }
        override fun onDisplayChanged(id: Int) { }
    }

    private val pickLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            val uri = res.data?.data
            if (res.resultCode == Activity.RESULT_OK && uri != null) sendPdf(uri)
            else js("window.__pdfCancel && window.__pdfCancel()")
        }

    private val chooserLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            val uri = res.data?.data
            fileCallback?.onReceiveValue(
                if (res.resultCode == Activity.RESULT_OK && uri != null) arrayOf(uri) else null
            )
            fileCallback = null
        }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        hideSystemBars()

        web = WebView(this)
        web.setBackgroundColor(0xFF111214.toInt())
        setContentView(web)

        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            textZoom = 100
            mediaPlaybackRequiresUserGesture = true
        }

        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView, request: WebResourceRequest
            ): WebResourceResponse? = loader.shouldInterceptRequest(request.url)

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val u = request.url
                if (u.host == "appassets.androidplatform.net") return false
                try { startActivity(Intent(Intent.ACTION_VIEW, u)) } catch (_: Exception) {}
                return true
            }

            override fun onPageFinished(view: WebView, url: String) {
                pageReady = true
                pendingUri?.let { pendingUri = null; sendPdf(it) }
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                w: WebView, cb: ValueCallback<Array<Uri>>, p: FileChooserParams
            ): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = cb
                val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("application/pdf")
                return try { chooserLauncher.launch(i); true } catch (e: Exception) { fileCallback = null; false }
            }
        }

        web.addJavascriptInterface(Bridge(), "Android")

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                web.evaluateJavascript("(window.appBack && window.appBack()) ? 'y' : 'n'") { r ->
                    if (r == null || !r.contains("y")) finish()
                }
            }
        })

        handleIncoming(intent)
        web.loadUrl("https://appassets.androidplatform.net/assets/index.html")
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncoming(intent)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onDestroy() {
        try { chromecast.stopScan(); chromecast.disconnect() } catch (_: Exception) { }
        stopCast()
        cast.shutdown()
        super.onDestroy()
    }

    /** Full-screen stage view on a wireless (Miracast) or HDMI display. */
    private inner class StagePresentation(ctx: Context, display: Display) : Presentation(ctx, display) {
        private var view: WebView? = null

        @SuppressLint("SetJavaScriptEnabled")
        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            val wv = WebView(context)
            wv.setBackgroundColor(Color.BLACK)
            wv.settings.javaScriptEnabled = true
            wv.settings.domStorageEnabled = true
            wv.settings.textZoom = 100
            wv.webViewClient = WebViewClient()
            setContentView(wv)
            wv.loadUrl("http://127.0.0.1:${cast.port}/")
            view = wv
        }

        override fun onStop() {
            view?.destroy()
            view = null
            super.onStop()
        }
    }

    private fun stageDisplay(): Display? =
        displayManager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION).firstOrNull()

    private fun refreshStage() {
        runOnUiThread {
            if (!casting) return@runOnUiThread
            val d = stageDisplay()
            if (d == null) {
                if (stage != null) {
                    try { stage?.dismiss() } catch (_: Exception) { }
                    stage = null
                    js("window.__castDisplay && window.__castDisplay(null)")
                }
                return@runOnUiThread
            }
            if (stage?.display?.displayId == d.displayId) return@runOnUiThread
            try { stage?.dismiss() } catch (_: Exception) { }
            val p = StagePresentation(this, d)
            try {
                p.show()
                stage = p
                js("window.__castDisplay && window.__castDisplay(" + JSONObject.quote(d.name ?: "מסך חיצוני") + ")")
            } catch (e: Exception) {
                stage = null
            }
        }
    }

    private fun castInfoJson(): String {
        val o = JSONObject()
        o.put("port", cast.port)
        o.put("ips", org.json.JSONArray(CastServer.localIps()))
        o.put("display", stage?.display?.name ?: JSONObject.NULL)
        return o.toString()
    }

    private fun startCast(): String {
        if (!casting) {
            if (cast.start() == 0) return "{}"
            casting = true
            try { displayManager.registerDisplayListener(displayListener, null) } catch (_: Exception) { }
        }
        refreshStage()
        return castInfoJson()
    }

    private fun stopCast() {
        try { chromecast.disconnect() } catch (_: Exception) { }
        if (!casting) return
        casting = false
        try { displayManager.unregisterDisplayListener(displayListener) } catch (_: Exception) { }
        try { stage?.dismiss() } catch (_: Exception) { }
        stage = null
        cast.stop()
    }

    override fun onPause() {
        super.onPause()
        web.evaluateJavascript("typeof persist==='function' && persist()", null)
    }

    private fun hideSystemBars() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    /** PDF opened or shared into the app (VIEW / SEND). */
    private fun handleIncoming(intent: Intent?) {
        if (intent == null) return
        val uri: Uri? = when (intent.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> streamUri(intent)
            else -> null
        }
        if (uri == null) return
        if (pageReady) sendPdf(uri) else pendingUri = uri
    }

    @Suppress("DEPRECATION")
    private fun streamUri(intent: Intent): Uri? =
        if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri

    private fun js(code: String) {
        if (::web.isInitialized) web.evaluateJavascript(code, null)
    }

    private fun launchPicker(source: String) {
        val pkg = when (source) {
            "drive" -> "com.google.android.apps.docs"
            "dropbox" -> "com.dropbox.android"
            else -> null
        }
        if (pkg != null) {
            val direct = Intent(Intent.ACTION_GET_CONTENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/pdf")
                .setPackage(pkg)
            try {
                pickLauncher.launch(direct)
                return
            } catch (e: ActivityNotFoundException) {
                val name = if (source == "drive") "Google Drive" else "Dropbox"
                Toast.makeText(this, "בחר $name מהתפריט של בוחר הקבצים", Toast.LENGTH_LONG).show()
            } catch (e: Exception) { /* fall through to system picker */ }
        }
        val doc = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("application/pdf")
        try {
            pickLauncher.launch(doc)
        } catch (e: Exception) {
            js("window.__pdfError(" + JSONObject.quote("לא נמצא בוחר קבצים במכשיר") + ")")
        }
    }

    private fun sendPdf(uri: Uri) {
        thread {
            try {
                var name = "file.pdf"
                try {
                    contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                        if (c.moveToFirst()) c.getString(0)?.let { name = it }
                    }
                } catch (_: Exception) {}
                val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: throw IllegalStateException("empty")
                if (bytes.size > 80 * 1024 * 1024) {
                    runOnUiThread { js("window.__pdfError(" + JSONObject.quote("הקובץ גדול מדי") + ")") }
                    return@thread
                }
                val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                runOnUiThread {
                    js("window.__pdfBegin(" + JSONObject.quote(name) + ")")
                    val step = 400_000
                    var i = 0
                    while (i < b64.length) {
                        val e = minOf(i + step, b64.length)
                        js("window.__pdfChunk('" + b64.substring(i, e) + "')")
                        i = e
                    }
                    js("window.__pdfEnd()")
                }
            } catch (e: Exception) {
                runOnUiThread {
                    js("window.__pdfError(" + JSONObject.quote("לא ניתן לקרוא את הקובץ") + ")")
                }
            }
        }
    }

    private val userAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"

    /** Fetch a web page natively (no CORS) and hand the HTML back to the page. */
    private fun fetchPage(id: String, url: String, binary: Boolean = false) {
        try {
            var u = URL(url)
            var conn: HttpURLConnection
            var hops = 0
            while (true) {
                conn = u.openConnection() as HttpURLConnection
                conn.instanceFollowRedirects = false
                conn.connectTimeout = 12000
                conn.readTimeout = 15000
                conn.setRequestProperty("User-Agent", userAgent)
                conn.setRequestProperty("Accept", "text/html,application/xhtml+xml,*/*;q=0.8")
                conn.setRequestProperty("Accept-Language", "he-IL,he;q=0.9,en;q=0.8")
                val code = conn.responseCode
                if (code in 300..399 && hops < 8) {
                    val loc = conn.getHeaderField("Location")
                    conn.disconnect()
                    if (loc == null) throw IllegalStateException("redirect")
                    u = URL(u, loc)
                    hops++
                    continue
                }
                break
            }
            val code = conn.responseCode
            if (code >= 400) throw IllegalStateException("HTTP $code")
            var bytes = conn.inputStream.use { it.readBytes() }
            if (bytes.size > 4 * 1024 * 1024) bytes = bytes.copyOf(4 * 1024 * 1024)
            val ctype = conn.contentType ?: "application/octet-stream"
            val text = if (binary) "data:" + ctype.substringBefore(';').trim() + ";base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
                else String(bytes, charsetOf(conn.contentType, bytes))
            conn.disconnect()
            val finalUrl = u.toString()
            runOnUiThread {
                js("window.__httpDone(" + JSONObject.quote(id) + ",true," + JSONObject.quote(text) + "," + JSONObject.quote(finalUrl) + ")")
            }
        } catch (e: Exception) {
            val msg = e.message ?: "error"
            runOnUiThread {
                js("window.__httpDone(" + JSONObject.quote(id) + ",false," + JSONObject.quote(msg) + ",'')")
            }
        }
    }

    private fun charsetOf(contentType: String?, bytes: ByteArray): Charset {
        val rx = Regex("charset=[\"']?([\\w-]+)", RegexOption.IGNORE_CASE)
        val name = contentType?.let { rx.find(it)?.groupValues?.get(1) }
            ?: rx.find(String(bytes, 0, minOf(bytes.size, 4096), Charsets.ISO_8859_1))?.groupValues?.get(1)
        return try { if (name != null) Charset.forName(name) else Charsets.UTF_8 } catch (_: Exception) { Charsets.UTF_8 }
    }

    inner class Bridge {
        @JavascriptInterface
        fun pickPdf(source: String) {
            runOnUiThread { launchPicker(source) }
        }

        @JavascriptInterface
        fun keepAwake(on: Boolean) {
            runOnUiThread {
                if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }

        @JavascriptInterface
        fun httpGetData(id: String, url: String) {
            thread { fetchPage(id, url, true) }
        }

        @JavascriptInterface
        fun httpGet(id: String, url: String) {
            thread { fetchPage(id, url) }
        }

        @JavascriptInterface
        fun openUrl(url: String) {
            runOnUiThread {
                try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (_: Exception) {}
            }
        }

        @JavascriptInterface
        fun castStart(): String {
            val r = arrayOf("{}")
            val latch = java.util.concurrent.CountDownLatch(1)
            runOnUiThread { try { r[0] = startCast() } finally { latch.countDown() } }
            latch.await(3, java.util.concurrent.TimeUnit.SECONDS)
            return r[0]
        }

        @JavascriptInterface
        fun castInfo(): String = castInfoJson()

        @JavascriptInterface
        fun castStop() {
            runOnUiThread { stopCast() }
        }

        @JavascriptInterface
        fun ccScan() {
            runOnUiThread { chromecast.scan() }
        }

        @JavascriptInterface
        fun ccCast(id: String) {
            runOnUiThread {
                val d = ccDevices[id] ?: return@runOnUiThread
                startCast()
                val ips = CastServer.localIps()
                val prefix = d.host.substringBeforeLast('.') + "."
                val ip = ips.firstOrNull { it.startsWith(prefix) } ?: ips.firstOrNull()
                if (ip == null) {
                    js("window.__ccStatus(" + JSONObject.quote(id) + ",'error'," + JSONObject.quote("הטאבלט לא מחובר לרשת Wi-Fi") + ")")
                    return@runOnUiThread
                }
                chromecast.cast(d, "http://$ip:${cast.port}/")
            }
        }

        @JavascriptInterface
        fun ccStop() {
            runOnUiThread { chromecast.disconnect() }
        }

        @JavascriptInterface
        fun castState(json: String) {
            if (casting) cast.push(json)
        }

        @JavascriptInterface
        fun toast(msg: String) {
            runOnUiThread { Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show() }
        }
    }
}
