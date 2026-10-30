package dev.tvdash.launcher

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URL
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager

data class Camera(val id: String, val name: String, val type: String)

/** The console's address, an API key, and the SHA-256 of its self-signed certificate. */
data class UnifiConfig(val host: String, val apiKey: String, val certSha256: String)

/**
 * UniFi Protect's integration API on the local console (X-API-KEY auth).
 *
 * The console serves a self-signed certificate, so rather than trusting the system store (which
 * would reject it) or trusting anything (which would hand the camera key to whoever answers),
 * connections only succeed with the exact certificate pinned in local.properties.
 */
object ProtectClient {
    /** From [Prefs.unifi]; set at startup ([TvDashApp]) and whenever tv.ps1 sends new settings. */
    @Volatile
    var config: UnifiConfig? = null

    val isConfigured: Boolean get() = config != null

    /** For log messages. */
    val host: String get() = config?.host.orEmpty()

    suspend fun cameras(): List<Camera> = withContext(Dispatchers.IO) {
        val json = JSONArray(String(get("/cameras")))
        (0 until json.length()).map { i ->
            val c = json.getJSONObject(i)
            Camera(c.getString("id"), c.getString("name"), c.optString("type"))
        }
    }

    /** Current frame, decoded at roughly [targetWidth] pixels so tiles don't hold full-size images. */
    suspend fun snapshot(cameraId: String, targetWidth: Int): Bitmap? = withContext(Dispatchers.IO) {
        val bytes = get("/cameras/$cameraId/snapshot")
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= targetWidth) sample *= 2
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    /**
     * A playable live stream for the camera. Uses the stream Protect already shares if there is one
     * (best quality first), otherwise asks Protect to share its high-quality stream.
     */
    suspend fun liveStreamUrl(cameraId: String): String? = withContext(Dispatchers.IO) {
        val path = "/cameras/$cameraId/rtsps-stream"
        val url = bestStream(JSONObject(String(request("GET", path))))
            ?: bestStream(JSONObject(String(request("POST", path, """{"qualities":["high"]}"""))))
        url?.let(::plainRtsp)
    }

    private fun bestStream(streams: JSONObject) =
        listOf("high", "medium", "low").firstNotNullOfOrNull { quality ->
            streams.optString(quality).takeIf { it.startsWith("rtsps://") }
        }

    /**
     * rtsps://host:7441/alias?enableSrtp → rtsp://host:7447/alias: the console's plain RTSP port.
     * ExoPlayer can't do RTSPS/SRTP; the video stays on the home network (the API key doesn't travel here).
     */
    private fun plainRtsp(url: String): String {
        val uri = Uri.parse(url)
        return "rtsp://${uri.host}:$PLAIN_RTSP_PORT${uri.path}"
    }

    private fun get(path: String): ByteArray = request("GET", path)

    private fun request(method: String, path: String, body: String? = null): ByteArray {
        val config = config ?: throw IOException("UniFi Protect isn't set up on this TV")
        val url = URL("https://${config.host}/proxy/protect/integration/v1$path")
        val conn = url.openConnection() as HttpsURLConnection
        conn.sslSocketFactory = socketFactoryFor(config.certSha256)
        // The cert is for "unifi.local", not the IP; the pin already proves it's the console.
        conn.hostnameVerifier = HostnameVerifier { host, _ -> host == config.host }
        conn.setRequestProperty("X-API-KEY", config.apiKey)
        conn.connectTimeout = 5_000
        conn.readTimeout = 10_000
        conn.requestMethod = method
        if (body != null) {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(body.toByteArray()) }
        }
        try {
            if (conn.responseCode !in 200..299) throw IOException("HTTP ${conn.responseCode}")
            return conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    }

    private const val PLAIN_RTSP_PORT = 7447

    /** The socket factory for the last pin used; rebuilt only when tv.ps1 sends a different one. */
    private var pinnedSocketFactory: Pair<String, SSLSocketFactory>? = null

    @Synchronized
    private fun socketFactoryFor(certSha256: String): SSLSocketFactory {
        pinnedSocketFactory?.let { (pin, factory) -> if (pin == certSha256) return factory }
        return pinnedFactory(certSha256).also { pinnedSocketFactory = certSha256 to it }
    }

    private fun pinnedFactory(certSha256: String): SSLSocketFactory {
        val pin = certSha256.replace(":", "").lowercase()
        val trustPinnedOnly = object : X509TrustManager {
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                val actual = MessageDigest.getInstance("SHA-256").digest(chain[0].encoded)
                    .joinToString("") { "%02x".format(it) }
                if (actual != pin) throw CertificateException("Protect console certificate changed")
            }

            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
                throw CertificateException("Not a server")

            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        return SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustPinnedOnly), null) }.socketFactory
    }
}
