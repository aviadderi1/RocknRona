package com.aviad.bama

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.ArrayDeque
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlin.concurrent.thread

/** A Chromecast / Google TV found on the local network. */
data class CastDevice(val id: String, val name: String, val model: String, val host: String, val port: Int) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("model", model).put("host", host).put("port", port)
}

/**
 * Finds Chromecast devices with mDNS (_googlecast._tcp) and shows a web page on one of them.
 *
 * Showing the page uses the public "DashCast" receiver app (the same one used by the
 * open-source tools catt and pychromecast), which loads any URL on the TV.
 * The page is the stage viewer served by [CastServer], so the TV follows the tablet live.
 */
class Chromecast(context: Context, private val listener: Listener) {

    interface Listener {
        fun onDevices(list: List<CastDevice>, scanning: Boolean)
        fun onStatus(deviceId: String?, state: String, message: String)
    }

    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val main = Handler(Looper.getMainLooper())
    private val devices = LinkedHashMap<String, CastDevice>()
    private val resolveQueue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    private var discovery: NsdManager.DiscoveryListener? = null

    @Volatile private var session: Session? = null

    // ---------------------------------------------------------------- discovery

    fun scan(seconds: Long = 8) {
        stopScan()
        devices.clear()
        resolveQueue.clear()
        resolving = false
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                main.post { discovery = null; listener.onDevices(devices.values.toList(), false) }
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onServiceFound(info: NsdServiceInfo) {
                main.post { resolveQueue.add(info); nextResolve() }
            }
            override fun onServiceLost(info: NsdServiceInfo) {}
        }
        discovery = l
        try {
            nsd.discoverServices("_googlecast._tcp", NsdManager.PROTOCOL_DNS_SD, l)
        } catch (e: Exception) {
            discovery = null
            listener.onDevices(emptyList(), false)
            return
        }
        listener.onDevices(emptyList(), true)
        main.postDelayed({ if (discovery === l) { stopScan(); listener.onDevices(devices.values.toList(), false) } }, seconds * 1000)
    }

    fun stopScan() {
        val d = discovery ?: return
        discovery = null
        try { nsd.stopServiceDiscovery(d) } catch (_: Exception) { }
    }

    @Suppress("DEPRECATION")
    private fun nextResolve() {
        if (resolving) return
        val info = resolveQueue.poll() ?: return
        resolving = true
        try {
            nsd.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(si: NsdServiceInfo, errorCode: Int) {
                    main.post { resolving = false; nextResolve() }
                }

                override fun onServiceResolved(si: NsdServiceInfo) {
                    main.post {
                        resolving = false
                        val host = si.host?.hostAddress
                        if (host != null && !host.contains(':')) {
                            val attrs = si.attributes
                            fun attr(k: String) = attrs[k]?.let { String(it, Charsets.UTF_8) }
                            val id = attr("id") ?: si.serviceName
                            val name = attr("fn") ?: si.serviceName.substringBeforeLast('-')
                            val model = attr("md") ?: "Chromecast"
                            devices[id] = CastDevice(id, name, model, host, si.port)
                            listener.onDevices(devices.values.toList(), discovery != null)
                        }
                        nextResolve()
                    }
                }
            })
        } catch (_: Exception) {
            resolving = false
            main.post { nextResolve() }
        }
    }

    // ---------------------------------------------------------------- casting

    val activeId: String? get() = session?.device?.id

    fun cast(device: CastDevice, url: String) {
        disconnect()
        val s = Session(device, url)
        session = s
        thread(isDaemon = true, name = "chromecast") { s.run() }
    }

    fun disconnect() {
        val s = session ?: return
        session = null
        thread(isDaemon = true) { s.stop() }
    }

    private fun status(s: Session, state: String, msg: String) {
        main.post { if (session === s || state == "stopped" || state == "error") listener.onStatus(s.device.id, state, msg) }
    }

    private inner class Session(val device: CastDevice, val url: String) {
        @Volatile private var alive = true
        private var socket: SSLSocket? = null
        private var out: OutputStream? = null
        private var appSession: String? = null
        private var reqId = 1
        @Volatile private var urlSent = false

        @Synchronized
        private fun sendUrl(t: String) {
            if (urlSent || !alive) return
            urlSent = true
            try {
                Thread.sleep(400)
                send(t, NS_DASH, JSONObject().put("url", url).put("force", true).put("reload", false).put("reload_time", 0))
                status(this, "casting", "משדר ל-${device.name}")
            } catch (e: Exception) {
                status(this, "error", "לא ניתן לשלוח את התצוגה ל-${device.name}")
            }
        }

        fun run() {
            status(this, "connecting", "מתחבר ל-${device.name}…")
            try {
                val ctx = SSLContext.getInstance("TLS")
                ctx.init(null, arrayOf<TrustManager>(TrustAll), SecureRandom())
                val sock = ctx.socketFactory.createSocket() as SSLSocket
                sock.connect(InetSocketAddress(device.host, device.port), 6000)
                sock.soTimeout = 0
                sock.startHandshake()
                socket = sock
                out = sock.getOutputStream()
                val input = DataInputStream(sock.getInputStream())

                send("receiver-0", NS_CONN, JSONObject().put("type", "CONNECT"))
                send("receiver-0", NS_RECV, JSONObject().put("type", "LAUNCH").put("appId", DASHCAST).put("requestId", reqId++))

                thread(isDaemon = true) {
                    while (alive) {
                        try { Thread.sleep(5000) } catch (_: Exception) { break }
                        if (!alive) break
                        try { send("receiver-0", NS_HB, JSONObject().put("type", "PING")) } catch (_: Exception) { break }
                    }
                }

                var transport: String? = null
                while (alive) {
                    val len = input.readInt()
                    if (len <= 0 || len > 1_000_000) break
                    val buf = ByteArray(len)
                    input.readFully(buf)
                    val msg = decode(buf)
                    val ns = msg["ns"] ?: continue
                    val payload = msg["payload"] ?: continue
                    val j = try { JSONObject(payload) } catch (_: Exception) { continue }
                    when (j.optString("type")) {
                        "PING" -> send(msg["src"] ?: "receiver-0", NS_HB, JSONObject().put("type", "PONG"))
                        "CLOSE" -> if (msg["src"] == transport) { status(this, "stopped", "השידור הסתיים"); alive = false }
                        "LAUNCH_ERROR" -> { status(this, "error", "המכשיר סירב לפתוח את התצוגה"); alive = false }
                        "RECEIVER_STATUS" -> {
                            val apps = j.optJSONObject("status")?.optJSONArray("applications") ?: JSONArray()
                            var found = false
                            for (i in 0 until apps.length()) {
                                val a = apps.getJSONObject(i)
                                if (a.optString("appId") == DASHCAST) {
                                    found = true
                                    val t = a.optString("transportId")
                                    appSession = a.optString("sessionId")
                                    if (ns == NS_RECV && t.isNotEmpty() && t != transport) {
                                        transport = t
                                        send(t, NS_CONN, JSONObject().put("type", "CONNECT"))
                                        // Fallback: if the receiver never lists its namespace, send anyway.
                                        thread(isDaemon = true) {
                                            try { Thread.sleep(3500) } catch (_: Exception) { }
                                            if (alive && !urlSent) sendUrl(t)
                                        }
                                    }
                                    val nss = a.optJSONArray("namespaces")
                                    var ready = false
                                    if (nss != null) for (k in 0 until nss.length()) {
                                        if (nss.optJSONObject(k)?.optString("name") == NS_DASH) ready = true
                                    }
                                    val tr = transport
                                    if (ready && !urlSent && tr != null) sendUrl(tr)
                                }
                            }
                            if (!found && transport != null && !urlSent) {
                                status(this, "stopped", "השידור ב-${device.name} הופסק")
                                alive = false
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                if (alive) status(this, "error", "לא ניתן להתחבר ל-${device.name}")
            } finally {
                alive = false
                try { socket?.close() } catch (_: Exception) { }
            }
        }

        fun stop() {
            if (!alive) return
            try {
                val sid = appSession
                if (sid != null) send("receiver-0", NS_RECV, JSONObject().put("type", "STOP").put("sessionId", sid).put("requestId", reqId++))
                Thread.sleep(300)
            } catch (_: Exception) { }
            alive = false
            try { socket?.close() } catch (_: Exception) { }
            status(this, "stopped", "השידור הופסק")
        }

        @Synchronized
        private fun send(dest: String, ns: String, payload: JSONObject) {
            val o = out ?: return
            val body = encode("sender-rocknrona", dest, ns, payload.toString())
            val frame = ByteArray(4 + body.size)
            frame[0] = (body.size ushr 24).toByte(); frame[1] = (body.size ushr 16).toByte()
            frame[2] = (body.size ushr 8).toByte(); frame[3] = body.size.toByte()
            System.arraycopy(body, 0, frame, 4, body.size)
            o.write(frame)
            o.flush()
        }
    }

    companion object {
        const val DASHCAST = "84912283"
        const val NS_CONN = "urn:x-cast:com.google.cast.tp.connection"
        const val NS_HB = "urn:x-cast:com.google.cast.tp.heartbeat"
        const val NS_RECV = "urn:x-cast:com.google.cast.receiver"
        const val NS_DASH = "urn:x-cast:com.madmod.dashcast"

        private object TrustAll : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }

        // --- minimal protobuf for CastMessage ---
        private fun varint(b: ByteArrayOutputStream, v0: Int) {
            var v = v0
            while ((v and 0x7F.inv()) != 0) { b.write((v and 0x7F) or 0x80); v = v ushr 7 }
            b.write(v)
        }

        private fun str(b: ByteArrayOutputStream, field: Int, s: String) {
            val bytes = s.toByteArray(Charsets.UTF_8)
            varint(b, (field shl 3) or 2); varint(b, bytes.size); b.write(bytes)
        }

        fun encode(src: String, dest: String, ns: String, payload: String): ByteArray {
            val b = ByteArrayOutputStream()
            varint(b, (1 shl 3) or 0); varint(b, 0)   // protocol_version CASTV2_1_0
            str(b, 2, src)
            str(b, 3, dest)
            str(b, 4, ns)
            varint(b, (5 shl 3) or 0); varint(b, 0)   // payload_type STRING
            str(b, 6, payload)
            return b.toByteArray()
        }

        fun decode(buf: ByteArray): Map<String, String> {
            val m = HashMap<String, String>()
            var i = 0
            fun readVar(): Long {
                var r = 0L; var shift = 0
                while (i < buf.size) {
                    val c = buf[i++].toInt() and 0xFF
                    r = r or ((c and 0x7F).toLong() shl shift)
                    if ((c and 0x80) == 0) break
                    shift += 7
                }
                return r
            }
            while (i < buf.size) {
                val key = readVar().toInt()
                val field = key ushr 3
                when (key and 7) {
                    0 -> readVar()
                    2 -> {
                        val len = readVar().toInt()
                        if (len < 0 || i + len > buf.size) return m
                        val s = String(buf, i, len, Charsets.UTF_8)
                        i += len
                        when (field) { 2 -> m["src"] = s; 3 -> m["dest"] = s; 4 -> m["ns"] = s; 6 -> m["payload"] = s }
                    }
                    5 -> i += 4
                    1 -> i += 8
                    else -> return m
                }
            }
            return m
        }
    }
}
