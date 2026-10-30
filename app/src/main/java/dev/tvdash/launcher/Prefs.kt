package dev.tvdash.launcher

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("tvdash", Context.MODE_PRIVATE)

    /** Favorite package names, in display order. */
    var favorites: List<String>
        get() = sp.getString("favorites", "")!!.split(',').filter { it.isNotEmpty() }
        set(value) = sp.edit { putString("favorites", value.joinToString(",")) }

    /** Custom order for the Apps section. Apps missing from it (new installs) follow, A–Z. */
    var appOrder: List<String>
        get() = sp.getString("app_order", "")!!.split(',').filter { it.isNotEmpty() }
        set(value) = sp.edit { putString("app_order", value.joinToString(",")) }

    var hidden: Set<String>
        get() = sp.getStringSet("hidden", emptySet())!!.toSet()
        set(value) = sp.edit { putStringSet("hidden", value) }

    /** Open-Meteo location; falls back to [DEFAULT_LOCATION] until one is picked. */
    var location: WeatherLocation?
        get() {
            val name = sp.getString("loc_name", null) ?: return DEFAULT_LOCATION
            return WeatherLocation(
                name,
                sp.getFloat("loc_lat", 0f).toDouble(),
                sp.getFloat("loc_lon", 0f).toDouble(),
            )
        }
        set(value) = sp.edit {
            if (value == null) {
                remove("loc_name"); remove("loc_lat"); remove("loc_lon")
            } else {
                putString("loc_name", value.name)
                putFloat("loc_lat", value.latitude.toFloat())
                putFloat("loc_lon", value.longitude.toFloat())
            }
        }

    /**
     * When set, weather comes from this Weather Underground station instead of Open-Meteo.
     * Station and key fall back to this TV's defaults ([defaultWuStation], [defaultWuKey]); turning
     * it off is remembered ("wu_off"), so the default station doesn't come straight back.
     */
    var wuStation: WuStation?
        get() {
            if (sp.getBoolean("wu_off", false)) return null
            val id = sp.getString("wu_station", null) ?: defaultWuStation
            val key = sp.getString("wu_key", null) ?: defaultWuKey
            return if (id.isEmpty() || key.isEmpty()) null else WuStation(id, key)
        }
        set(value) = sp.edit {
            if (value == null) {
                remove("wu_station"); remove("wu_key")
                putBoolean("wu_off", true)
            } else {
                remove("wu_off")
                putString("wu_station", value.stationId)
                // Only store a key that differs from the default one.
                if (value.apiKey == defaultWuKey) remove("wu_key") else putString("wu_key", value.apiKey)
            }
        }

    /*
     * Keys and addresses from the PC's local.properties, sent by `tools/tv.ps1 install` (see
     * [DeviceConfigReceiver]) rather than built into the APK, which is published. They belong to
     * this TV: layout exports leave them out and imports keep them.
     */

    /** Weather Underground station used until one is picked here; empty if none. */
    val defaultWuStation: String get() = sp.getString(KEY_WU_DEFAULT_STATION, "")!!

    /** Weather Underground API key used when the station's own isn't given; empty if none. */
    val defaultWuKey: String get() = sp.getString(KEY_WU_DEFAULT_KEY, "")!!

    /** UniFi Protect console for camera tiles, or null to hide cameras. */
    val unifi: UnifiConfig?
        get() {
            val host = sp.getString(KEY_UNIFI_HOST, "")!!
            val key = sp.getString(KEY_UNIFI_KEY, "")!!
            val cert = sp.getString(KEY_UNIFI_CERT, "")!!
            return if (host.isEmpty() || key.isEmpty() || cert.isEmpty()) null else UnifiConfig(host, key, cert)
        }

    /** Stores what tv.ps1 sent; null leaves a value as it is, "" clears it. */
    fun setDeviceConfig(
        wuStation: String?, wuKey: String?, unifiHost: String?, unifiKey: String?, unifiCert: String?,
    ) = sp.edit {
        wuStation?.let { putString(KEY_WU_DEFAULT_STATION, it) }
        wuKey?.let { putString(KEY_WU_DEFAULT_KEY, it) }
        unifiHost?.let { putString(KEY_UNIFI_HOST, it) }
        unifiKey?.let { putString(KEY_UNIFI_KEY, it) }
        unifiCert?.let { putString(KEY_UNIFI_CERT, it) }
    }

    /** Time zone of the weather location, shown by the clock. Null until weather first loads. */
    var clockTimeZone: String?
        get() = sp.getString("clock_tz", null)
        set(value) = sp.edit { putString("clock_tz", value) }

    /** Camera id to tile size (1 for 1×1, 2 for 2×2). Cameras themselves sit in [appOrder]. */
    var cameraSizes: Map<String, Int>
        get() {
            val json = JSONObject(sp.getString("camera_sizes", "{}")!!)
            return json.keys().asSequence().associateWith { json.getInt(it) }
        }
        set(value) = sp.edit { putString("camera_sizes", JSONObject(value).toString()) }

    /** Camera the screensaver shows full screen, or null for clock and weather on black. */
    var screensaverCamera: String?
        get() = sp.getString("screensaver_camera", null)
        set(value) = sp.edit { if (value == null) remove("screensaver_camera") else putString("screensaver_camera", value) }

    /** Custom order of the input tiles. Inputs missing from it follow in port order. */
    var inputOrder: List<String>
        get() = sp.getString("input_order", "")!!.split(',').filter { it.isNotEmpty() }
        set(value) = sp.edit { putString("input_order", value.joinToString(",")) }

    var hiddenInputs: Set<String>
        get() = sp.getStringSet("hidden_inputs", emptySet())!!.toSet()
        set(value) = sp.edit { putStringSet("hidden_inputs", value) }

    /** Input id to the name the user gave it, overriding the TV's label. */
    var inputNames: Map<String, String>
        get() {
            val json = JSONObject(sp.getString("input_names", "{}")!!)
            return json.keys().asSequence().associateWith { json.getString(it) }
        }
        set(value) = sp.edit { putString("input_names", JSONObject(value).toString()) }

    var fahrenheit: Boolean
        get() = sp.getBoolean("fahrenheit", false)
        set(value) = sp.edit { putBoolean("fahrenheit", value) }

    /** Packages a layout import asked for that weren't installed; cleared as they're installed or skipped. */
    var importWanted: List<String>
        get() = sp.getString(KEY_IMPORT_WANTED, "")!!.split(',').filter { it.isNotEmpty() }
        set(value) = sp.edit { putString(KEY_IMPORT_WANTED, value.joinToString(",")) }

    /** App names from the master TV, for apps this TV can't name because they aren't installed. */
    var importLabels: Map<String, String>
        get() {
            val json = JSONObject(sp.getString(KEY_IMPORT_LABELS, "{}")!!)
            return json.keys().asSequence().associateWith { json.getString(it) }
        }
        set(value) = sp.edit { putString(KEY_IMPORT_LABELS, JSONObject(value).toString()) }

    /**
     * Every setting, typed, for copying a layout to another TV. Import-tracking keys and the device
     * config stay behind: they describe this TV, not the layout.
     */
    fun exportAll(): JSONObject = JSONObject().apply {
        for ((key, value) in sp.all) {
            if (key in LOCAL_KEYS || value == null) continue
            val (type, json) = when (value) {
                is String -> "string" to value
                is Boolean -> "boolean" to value
                is Float -> "float" to value.toDouble()
                is Int -> "int" to value
                is Long -> "long" to value
                is Set<*> -> "set" to JSONArray(value.toList())
                else -> continue
            }
            put(key, JSONObject().put("type", type).put("value", json))
        }
    }

    /** Replaces the layout with [settings] from [exportAll]. */
    fun importAll(settings: JSONObject) = sp.edit {
        val keep = LOCAL_KEYS.associateWith { sp.all[it] }
        clear()
        for (key in settings.keys()) {
            val entry = settings.getJSONObject(key)
            when (entry.getString("type")) {
                "string" -> putString(key, entry.getString("value"))
                "boolean" -> putBoolean(key, entry.getBoolean("value"))
                "float" -> putFloat(key, entry.getDouble("value").toFloat())
                "int" -> putInt(key, entry.getInt("value"))
                "long" -> putLong(key, entry.getLong("value"))
                "set" -> entry.getJSONArray("value").let { a -> putStringSet(key, (0 until a.length()).map(a::getString).toSet()) }
            }
        }
        keep.forEach { (key, value) -> if (value is String) putString(key, value) }
    }

    companion object {
        private const val KEY_IMPORT_WANTED = "import_wanted"
        private const val KEY_IMPORT_LABELS = "import_labels"
        private const val KEY_WU_DEFAULT_STATION = "wu_default_station"
        private const val KEY_WU_DEFAULT_KEY = "wu_default_key"
        private const val KEY_UNIFI_HOST = "unifi_host"
        private const val KEY_UNIFI_KEY = "unifi_key"
        private const val KEY_UNIFI_CERT = "unifi_cert_sha256"
        private val LOCAL_KEYS = setOf(
            KEY_IMPORT_WANTED, KEY_IMPORT_LABELS,
            KEY_WU_DEFAULT_STATION, KEY_WU_DEFAULT_KEY, KEY_UNIFI_HOST, KEY_UNIFI_KEY, KEY_UNIFI_CERT,
        )

        val DEFAULT_LOCATION = WeatherLocation("Arnprior, Ontario, Canada", 45.43341, -76.34939)
    }
}
