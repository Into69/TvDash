package dev.tvdash.launcher

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import org.json.JSONException
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/**
 * A notification from Home Assistant. [color] is null for the integration's default grey;
 * [backgroundAlpha] is 0–1; [textScale] follows the integration's font sizes.
 */
data class HaNotification(
    val title: String,
    val message: String,
    val durationMs: Long = DEFAULT_DURATION_S * 1000L,
    val position: Int = POSITION_BOTTOM_RIGHT,
    val color: Int? = null,
    val backgroundAlpha: Float = 1f,
    val textScale: Float = 1f,
    val icon: Bitmap? = null,
    val image: Bitmap? = null,
) {
    companion object {
        const val POSITION_BOTTOM_RIGHT = 0
        const val POSITION_BOTTOM_LEFT = 1
        const val POSITION_TOP_RIGHT = 2
        const val POSITION_TOP_LEFT = 3
        const val POSITION_CENTER = 4
        const val DEFAULT_DURATION_S = 5
    }
}

/**
 * Keeps [HaNotificationServer] running while other apps are on screen. It's a foreground service
 * so Android doesn't stop TvDash's process behind a long film. Started by the home screen and
 * at app start-up. Stopped when the Home screen settings turn notifications off.
 */
class HaNotificationService : Service() {
    private var server: HaNotificationServer? = null

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, getString(R.string.ha_channel), NotificationManager.IMPORTANCE_MIN),
            )
        }
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(getString(R.string.ha_listening, HaNotificationServer.PORT))
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .build()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
        server = HaNotificationServer(applicationContext).also { it.start() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        server?.stop()
        server = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "HaNotifications"
        private const val CHANNEL = "ha_server"
        private const val NOTIFICATION_ID = 7676

        /**
         * Starts the service if the settings allow it. Safe to call often. Android 12+ refuses
         * the start from the background unless TvDash may draw over other apps; then the home
         * screen starts it when it next opens.
         */
        fun start(context: Context) {
            if (!Prefs(context).haEnabled) return
            try {
                ContextCompat.startForegroundService(context, Intent(context, HaNotificationService::class.java))
            } catch (e: IllegalStateException) {
                Log.w(TAG, "Couldn't start the Home Assistant receiver from the background", e)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, HaNotificationService::class.java))
        }
    }
}

/**
 * A small HTTP server on port [PORT] for Home Assistant's "Notifications for Android TV / Fire
 * TV" integration (`nfandroidtv`). It sends a multipart POST to `/` with the fields msg, title,
 * duration, position, fontsize, transparency, color and interrupt, plus file parts filename (the
 * icon) and filename2 (an image). Its setup check is a plain GET.
 *
 * A JSON POST also works, for `rest_command` or curl:
 * `{"title": "…", "message": "…", "duration": 10, "position": "top-right", "color": "red"}`.
 *
 * The integration can't authenticate, so only senders on the home network are accepted. With
 * `ha.allowFrom` in local.properties, only those addresses are accepted.
 */
class HaNotificationServer(private val context: Context) {
    @Volatile
    private var socket: ServerSocket? = null
    private val workers = Executors.newFixedThreadPool(2)

    fun start() {
        thread(name = "ha-notify-accept", isDaemon = true) {
            val server = try {
                ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(PORT))
                }
            } catch (e: IOException) {
                Log.w(TAG, "Couldn't listen on port $PORT", e)
                return@thread
            }
            socket = server
            Log.i(TAG, "Listening for Home Assistant on port $PORT")
            while (!server.isClosed) {
                val client = try {
                    server.accept()
                } catch (e: IOException) {
                    break
                }
                workers.execute { serve(client) }
            }
        }
    }

    fun stop() {
        try {
            socket?.close()
        } catch (_: IOException) {
        }
        socket = null
        workers.shutdownNow()
    }

    private fun serve(client: Socket): Unit = client.use { s ->
        try {
            s.soTimeout = READ_TIMEOUT_MS
            val input = BufferedInputStream(s.getInputStream())
            val out = s.getOutputStream()
            if (!allowed(s.inetAddress)) {
                Log.w(TAG, "Refused a notification from ${s.inetAddress.hostAddress}")
                respond(out, 403, "Forbidden")
                return
            }
            val request = readRequest(input, out) ?: return respond(out, 400, "Bad request")
            when (request.method) {
                // The integration's setup check, and a quick way to see TvDash is listening.
                "GET", "HEAD" -> respond(out, 200, "TvDash")
                "POST" -> {
                    val notification = parse(request) ?: return respond(out, 400, "Nothing to show")
                    NotificationCards.post(notification)
                    respond(out, 200, "OK")
                }
                else -> respond(out, 405, "Method not allowed")
            }
        } catch (e: BodyTooLarge) {
            Log.w(TAG, "Notification too large from ${s.inetAddress.hostAddress}")
            runCatching { respond(s.getOutputStream(), 413, "Too large") }
        } catch (e: IOException) {
            Log.w(TAG, "Notification from ${s.inetAddress.hostAddress} failed", e)
        }
    }

    /** The home network only, or exactly the addresses tv.ps1 sent as ha.allowFrom. */
    private fun allowed(address: InetAddress): Boolean {
        val allowList = Prefs(context).haAllowFrom
        val plain = (address as? Inet6Address)?.let(::unmapped) ?: address
        if (allowList.isNotEmpty()) {
            return allowList.any { allowed ->
                runCatching { InetAddress.getByName(allowed) == plain }.getOrDefault(false)
            }
        }
        if (plain.isLoopbackAddress || plain.isSiteLocalAddress || plain.isLinkLocalAddress) return true
        // IPv6 unique local addresses (fc00::/7), which isSiteLocalAddress doesn't cover.
        return plain is Inet6Address && (plain.address[0].toInt() and 0xFE) == 0xFC
    }

    /** ::ffff:10.0.0.5 → 10.0.0.5, so IPv4 senders on a dual-stack socket match the IPv4 checks. */
    private fun unmapped(address: Inet6Address): InetAddress? {
        val b = address.address
        val mapped = (0 until 10).all { b[it].toInt() == 0 } && b[10].toInt() == -1 && b[11].toInt() == -1
        return if (mapped) InetAddress.getByAddress(b.copyOfRange(12, 16)) else null
    }

    private class Request(val method: String, val headers: Map<String, String>, val body: ByteArray)

    private class BodyTooLarge : IOException()

    private fun readRequest(input: InputStream, out: OutputStream): Request? {
        val requestLine = readLine(input) ?: return null
        val method = requestLine.substringBefore(' ').uppercase()
        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = readLine(input) ?: return null
            if (line.isEmpty()) break
            if (headers.size >= MAX_HEADERS) return null
            val colon = line.indexOf(':')
            if (colon > 0) headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
        }
        if (method != "POST") return Request(method, headers, ByteArray(0))
        // curl asks first before sending larger bodies.
        if (headers["expect"].equals("100-continue", ignoreCase = true)) {
            out.write("HTTP/1.1 100 Continue\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
            out.flush()
        }
        val body = when {
            headers["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true -> readChunked(input)
            else -> {
                val length = headers["content-length"]?.toLongOrNull() ?: 0L
                if (length > MAX_BODY_BYTES) throw BodyTooLarge()
                ByteArray(length.toInt()).also { DataInputStream(input).readFully(it) }
            }
        }
        return Request(method, headers, body)
    }

    private fun readChunked(input: InputStream): ByteArray {
        val body = ByteArrayOutputStream()
        val data = DataInputStream(input)
        while (true) {
            val size = readLine(input)?.substringBefore(';')?.trim()?.toIntOrNull(16) ?: throw IOException("Bad chunk")
            if (size == 0) break
            if (body.size() + size > MAX_BODY_BYTES) throw BodyTooLarge()
            body.write(ByteArray(size).also(data::readFully))
            readLine(input) // the CRLF after each chunk
        }
        while (readLine(input)?.isNotEmpty() == true) Unit // trailers
        return body.toByteArray()
    }

    /** One header line without its CRLF, or null at end of stream. */
    private fun readLine(input: InputStream): String? {
        val line = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b == -1) return if (line.size() == 0) null else line.toString("ISO-8859-1")
            if (b == '\n'.code) break
            if (line.size() >= MAX_LINE_BYTES) throw IOException("Header line too long")
            line.write(b)
        }
        return line.toString("ISO-8859-1").removeSuffix("\r")
    }

    private fun parse(request: Request): HaNotification? {
        val type = request.headers["content-type"].orEmpty()
        return when {
            type.startsWith("multipart/form-data", ignoreCase = true) -> {
                val boundary = Regex("boundary=\"?([^\";]+)\"?", RegexOption.IGNORE_CASE).find(type)?.groupValues?.get(1)
                    ?: return null
                val parts = multipart(request.body, boundary)
                fun field(name: String) = parts[name]?.toString(Charsets.UTF_8)
                fromFields(::field, icon = parts["filename"], image = parts["filename2"])
            }
            type.startsWith("application/x-www-form-urlencoded", ignoreCase = true) -> {
                val fields = request.body.toString(Charsets.UTF_8).split('&').filter { it.isNotEmpty() }.associate {
                    URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('=', ""), "UTF-8")
                }
                fromFields(fields::get, icon = null, image = null)
            }
            else -> try {
                val json = JSONObject(request.body.toString(Charsets.UTF_8))
                fun field(name: String) = if (json.has(name) && !json.isNull(name)) json.get(name).toString() else null
                fromFields({ name -> if (name == "msg") field("message") ?: field("msg") else field(name) }, icon = null, image = null)
            } catch (e: JSONException) {
                null
            }
        }
    }

    /**
     * Builds the notification from the integration's fields. JSON may also give names where it
     * gives numbers: a position like "top-right", a color like "red", a fontsize like "large" and
     * a transparency like "25%".
     */
    private fun fromFields(field: (String) -> String?, icon: ByteArray?, image: ByteArray?): HaNotification? {
        val title = field("title")?.trim().orEmpty()
        val message = field("msg")?.trim().orEmpty()
        if (title.isEmpty() && message.isEmpty() && image == null) return null
        val duration = (field("duration")?.toDoubleOrNull() ?: HaNotification.DEFAULT_DURATION_S.toDouble())
            .coerceIn(1.0, MAX_DURATION_S)
        val position = field("position")?.let { POSITIONS[it.lowercase()] ?: it.toIntOrNull() }
            ?.takeIf { it in 0..4 } ?: HaNotification.POSITION_BOTTOM_RIGHT
        val transparency = field("transparency")?.let { TRANSPARENCIES[it] ?: it.toIntOrNull() }?.coerceIn(1, 5) ?: 1
        val fontSize = field("fontsize")?.let { FONT_SIZES[it.lowercase()] ?: it.toIntOrNull() } ?: 0
        return HaNotification(
            title = title,
            message = message,
            durationMs = (duration * 1000).toLong(),
            position = position,
            color = field("color")?.let(::parseColor),
            // 1 = 0% transparent … 5 = 100%; keep a little backing so the text stays readable.
            backgroundAlpha = (1f - (transparency - 1) * 0.25f).coerceAtLeast(0.15f),
            textScale = TEXT_SCALES[fontSize] ?: 1f,
            icon = icon?.let { decode(it, ICON_PX) }?.takeIf { it.width > 4 && it.height > 4 },
            image = image?.let { decode(it, IMAGE_PX) },
        )
    }

    /** "#F44336" or a name the integration knows; null for its default grey, which gets no accent. */
    private fun parseColor(value: String): Int? {
        val hex = COLORS[value.lowercase()] ?: value
        if (hex.equals(DEFAULT_GREY, ignoreCase = true)) return null
        return runCatching { android.graphics.Color.parseColor(hex) }.getOrNull()
    }

    /** Decoded at about [targetPx] wide, so a camera snapshot doesn't hold a full-size bitmap. */
    private fun decode(bytes: ByteArray, targetPx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= targetPx) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    /** Field name to its bytes. */
    private fun multipart(body: ByteArray, boundary: String): Map<String, ByteArray> {
        val parts = mutableMapOf<String, ByteArray>()
        val delimiter = "--$boundary".toByteArray(Charsets.ISO_8859_1)
        val nextDelimiter = "\r\n--$boundary".toByteArray(Charsets.ISO_8859_1)
        val headerEnd = "\r\n\r\n".toByteArray(Charsets.ISO_8859_1)
        var pos = indexOf(body, delimiter, 0).takeIf { it >= 0 } ?: return parts
        while (true) {
            pos += delimiter.size
            if (pos + 1 >= body.size || (body[pos] == '-'.code.toByte() && body[pos + 1] == '-'.code.toByte())) break
            pos += 2 // CRLF after the delimiter
            val headersEnd = indexOf(body, headerEnd, pos).takeIf { it >= 0 } ?: break
            val headers = String(body, pos, headersEnd - pos, Charsets.UTF_8)
            val dataStart = headersEnd + headerEnd.size
            val dataEnd = indexOf(body, nextDelimiter, dataStart).takeIf { it >= 0 } ?: break
            PART_NAME.find(headers)?.groupValues?.get(1)?.let { parts[it] = body.copyOfRange(dataStart, dataEnd) }
            pos = dataEnd + 2
        }
        return parts
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray, from: Int): Int {
        outer@ for (i in from..haystack.size - needle.size) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }

    private fun respond(out: OutputStream, status: Int, text: String) {
        val reason = when (status) {
            200 -> "OK"; 400 -> "Bad Request"; 403 -> "Forbidden"; 405 -> "Method Not Allowed"
            413 -> "Payload Too Large"; else -> "Error"
        }
        val body = text.toByteArray()
        val head = "HTTP/1.1 $status $reason\r\nContent-Type: text/plain; charset=utf-8\r\n" +
            "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n"
        out.write(head.toByteArray(Charsets.ISO_8859_1))
        out.write(body)
        out.flush()
    }

    companion object {
        private const val TAG = "HaNotifications"

        /** The port the integration always uses. */
        const val PORT = 7676

        private const val READ_TIMEOUT_MS = 15_000
        private const val MAX_LINE_BYTES = 8 * 1024
        private const val MAX_HEADERS = 100
        private const val MAX_BODY_BYTES = 12L * 1024 * 1024
        private const val MAX_DURATION_S = 600.0
        private const val ICON_PX = 128
        private const val IMAGE_PX = 960

        private val PART_NAME = Regex("""(?:^|;)\s*name="([^"]*)"""", RegexOption.IGNORE_CASE)

        private val POSITIONS = mapOf(
            "bottom-right" to 0, "bottom-left" to 1, "top-right" to 2, "top-left" to 3, "center" to 4,
        )
        private val TRANSPARENCIES = mapOf("0%" to 1, "25%" to 2, "50%" to 3, "75%" to 4, "100%" to 5)

        /** The integration's font size codes: medium 0, small 1, large 2, max 3. */
        private val FONT_SIZES = mapOf("medium" to 0, "small" to 1, "large" to 2, "max" to 3)
        private val TEXT_SCALES = mapOf(0 to 1f, 1 to 0.85f, 2 to 1.25f, 3 to 1.5f)

        private const val DEFAULT_GREY = "#607d8b"
        private val COLORS = mapOf(
            "grey" to DEFAULT_GREY, "black" to "#000000", "indigo" to "#303F9F", "green" to "#4CAF50",
            "red" to "#F44336", "cyan" to "#00BCD4", "teal" to "#009688", "amber" to "#FFC107", "pink" to "#E91E63",
        )
    }
}
