package dev.tvdash.launcher

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.math.roundToInt

data class WeatherLocation(val name: String, val latitude: Double, val longitude: Double)

/** One hour of forecast; [time] is local to the forecast location. */
data class HourForecast(val time: LocalDateTime, val temperature: Double, val code: Int, val isDay: Boolean)

/** The one kind of precipitation the "chance of" line names, most significant first. */
enum class Precip {
    THUNDERSTORM, HAIL, FREEZING_RAIN, FREEZING_DRIZZLE, SLEET, SNOW, RAIN, DRIZZLE;

    companion object {
        /** WMO weather codes (Open-Meteo); null for codes that aren't precipitation. */
        fun fromWmo(code: Int?): Precip? = when (code) {
            51, 53, 55 -> DRIZZLE
            56, 57 -> FREEZING_DRIZZLE
            61, 63, 65, 80, 81, 82 -> RAIN
            66, 67 -> FREEZING_RAIN
            71, 73, 75, 77, 85, 86 -> SNOW
            95 -> THUNDERSTORM
            96, 99 -> HAIL
            else -> null
        }

        /**
         * The Weather Company's icon codes (Weather Underground forecasts). Mixed icons resolve to
         * the more disruptive type: sleet over rain or snow. Rain/snow (5) gives null, so the caller
         * can ask Open-Meteo which one dominates.
         */
        fun fromTwcIcon(code: Int?): Precip? = when (code) {
            0, 1, 2, 3, 4, 37, 38, 47 -> THUNDERSTORM
            6, 7, 18 -> SLEET
            8 -> FREEZING_DRIZZLE
            9 -> DRIZZLE
            10 -> FREEZING_RAIN
            11, 12, 39, 40, 45 -> RAIN
            13, 14, 15, 16, 41, 42, 43, 46 -> SNOW
            17, 35 -> HAIL
            else -> null
        }
    }
}

/**
 * Today's range, chance of precipitation and the next few hours.
 * [timeZone] is the location's IANA zone, e.g. "America/Toronto".
 */
data class Forecast(
    val high: Double?,
    val low: Double?,
    val precipChance: Int?,
    val precip: Precip,
    val hours: List<HourForecast>,
    val timeZone: String,
    /** Modelled sky right now, which stations can't measure; null if the request didn't ask for it. */
    val nowCode: Int? = null,
    val nowIsDay: Boolean = true,
)

data class Weather(
    val temperature: Double,
    val feelsLike: Double,
    val code: Int,
    val isDay: Boolean,
    val humidity: Int?,
    val windSpeed: Double?,
    val forecast: Forecast,
)

/** Open-Meteo: free, no API key, no account. */
object WeatherClient {
    private const val HOURS_FETCHED = 7
    private const val HOURS_SHOWN = 5

    /** Current conditions plus forecast, in one request. */
    suspend fun current(location: WeatherLocation, fahrenheit: Boolean): Weather {
        val url = forecastUrl(location.latitude, location.longitude, fahrenheit) +
            "&current=temperature_2m,apparent_temperature,weather_code,is_day,relative_humidity_2m,wind_speed_10m"
        val json = JSONObject(get(url))
        val current = json.getJSONObject("current")
        return Weather(
            temperature = current.getDouble("temperature_2m"),
            feelsLike = current.getDouble("apparent_temperature"),
            code = current.getInt("weather_code"),
            isDay = current.optInt("is_day", 1) == 1,
            humidity = current.optDouble("relative_humidity_2m").takeUnless { it.isNaN() }?.roundToInt(),
            windSpeed = current.optDouble("wind_speed_10m").takeUnless { it.isNaN() },
            forecast = parseForecast(json),
        )
    }

    /**
     * Forecast only, for Weather Underground stations, which supply their own readings. It also
     * carries the modelled sky (weather code), since a station measures but can't describe it.
     */
    suspend fun forecast(latitude: Double, longitude: Double, fahrenheit: Boolean): Forecast =
        parseForecast(JSONObject(get(forecastUrl(latitude, longitude, fahrenheit) + "&current=weather_code,is_day")))

    // forecast_hours starts at the current hour, so fetch one extra and drop the hour already under way.
    private fun forecastUrl(latitude: Double, longitude: Double, fahrenheit: Boolean) =
        "https://api.open-meteo.com/v1/forecast?latitude=$latitude&longitude=$longitude&timezone=auto" +
            "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max," +
            "rain_sum,showers_sum,snowfall_sum&forecast_days=2" +
            "&hourly=temperature_2m,weather_code,is_day&forecast_hours=$HOURS_FETCHED" +
            // Match Weather Underground's units: km/h with °C, mph with °F.
            if (fahrenheit) "&temperature_unit=fahrenheit&wind_speed_unit=mph" else ""

    private fun parseForecast(json: JSONObject): Forecast {
        val zone = json.getString("timezone")
        val now = LocalDateTime.now(runCatching { ZoneId.of(zone) }.getOrDefault(ZoneId.systemDefault()))
        val hourly = json.getJSONObject("hourly")
        val times = hourly.getJSONArray("time")
        val temps = hourly.getJSONArray("temperature_2m")
        val codes = hourly.getJSONArray("weather_code")
        val isDay = hourly.getJSONArray("is_day")
        val hours = (0 until times.length())
            .map { i -> HourForecast(LocalDateTime.parse(times.getString(i)), temps.getDouble(i), codes.getInt(i), isDay.getInt(i) == 1) }
            .filter { it.time.isAfter(now) }
            .take(HOURS_SHOWN)
        val daily = json.getJSONObject("daily")
        val high = daily.getJSONArray("temperature_2m_max").doubleOrNull(0)
        val rainMm = (daily.getJSONArray("rain_sum").doubleOrNull(0) ?: 0.0) +
            (daily.getJSONArray("showers_sum").doubleOrNull(0) ?: 0.0)
        // Snowfall is in cm of snow; roughly 1 cm of snow holds 1 mm of water, which makes them comparable.
        val snowMm = daily.getJSONArray("snowfall_sum").doubleOrNull(0) ?: 0.0
        // The day's code is its most significant weather, which names the type outright when it's wet.
        val precip = Precip.fromWmo(daily.optJSONArray("weather_code")?.doubleOrNull(0)?.toInt()) ?: when {
            snowMm > rainMm -> Precip.SNOW
            rainMm > 0 -> Precip.RAIN
            // Nothing expected either way: call it snow on days that stay below freezing.
            high != null && high <= 0 -> Precip.SNOW
            else -> Precip.RAIN
        }
        val current = json.optJSONObject("current")
        return Forecast(
            high = high,
            low = daily.getJSONArray("temperature_2m_min").doubleOrNull(0),
            precipChance = daily.getJSONArray("precipitation_probability_max").doubleOrNull(0)?.roundToInt(),
            precip = precip,
            hours = hours,
            timeZone = zone,
            nowCode = current?.takeIf { it.has("weather_code") }?.getInt("weather_code"),
            nowIsDay = current?.optInt("is_day", 1) != 0,
        )
    }

    suspend fun search(query: String): List<WeatherLocation> {
        val url = "https://geocoding-api.open-meteo.com/v1/search?count=8&name=" +
            URLEncoder.encode(query, "UTF-8")
        val results = JSONObject(get(url)).optJSONArray("results") ?: return emptyList()
        return (0 until results.length()).map { i ->
            val r = results.getJSONObject(i)
            val name = listOf("name", "admin1", "country")
                .mapNotNull { key -> r.optString(key).takeIf { it.isNotEmpty() } }
                .distinct()
                .joinToString(", ")
            WeatherLocation(name, r.getDouble("latitude"), r.getDouble("longitude"))
        }
    }

    /** WMO weather interpretation codes. */
    fun describe(code: Int): String = when (code) {
        0 -> "Clear"
        1, 2 -> "Partly cloudy"
        3 -> "Overcast"
        45, 48 -> "Fog"
        in 51..57 -> "Drizzle"
        in 61..67 -> "Rain"
        in 71..77 -> "Snow"
        in 80..82 -> "Showers"
        85, 86 -> "Snow showers"
        in 95..99 -> "Thunderstorm"
        else -> ""
    }

    /** Emoji for a WMO code, for the compact hourly row. */
    fun icon(code: Int, isDay: Boolean): String = when (code) {
        0 -> if (isDay) "☀️" else "🌙"
        1, 2 -> if (isDay) "⛅" else "☁️"
        3 -> "☁️"
        45, 48 -> "🌫️"
        in 51..67, in 80..82 -> "🌧️"
        in 71..77, 85, 86 -> "🌨️"
        in 95..99 -> "⛈️"
        else -> ""
    }

    private suspend fun get(url: String): String {
        val (code, body) = httpGet(url)
        if (code != HttpURLConnection.HTTP_OK) throw IOException("HTTP $code")
        return body
    }
}

data class WuStation(val stationId: String, val apiKey: String)

data class StationReading(
    val name: String,
    val temperature: Double,
    val feelsLike: Double,
    val humidity: Int?,
    val windSpeed: Double?,
    val raining: Boolean,
    val latitude: Double,
    val longitude: Double,
)

/**
 * Weather Underground personal weather station data. The API is only open to PWS owners,
 * whose key (wunderground.com/member/api-keys) can read any public station and the daily
 * forecast, but not the hourly forecast.
 */
object WundergroundClient {
    class InvalidKey : IOException("API key rejected")
    class StationOffline : IOException("Station has no recent data")

    suspend fun current(station: WuStation, fahrenheit: Boolean): StationReading {
        val url = "https://api.weather.com/v2/pws/observations/current" +
            "?format=json&numericPrecision=decimal&units=${units(fahrenheit)}" +
            "&stationId=${URLEncoder.encode(station.stationId, "UTF-8")}" +
            "&apiKey=${URLEncoder.encode(station.apiKey, "UTF-8")}"
        val obs = JSONObject(get(url)).getJSONArray("observations").getJSONObject(0)
        val values = obs.getJSONObject(if (fahrenheit) "imperial" else "metric")
        val temperature = values.getDouble("temp")
        // The station reports both; whichever has moved away from the air temperature is the one that applies.
        val windChill = values.doubleOrNull("windChill")
        val heatIndex = values.doubleOrNull("heatIndex")
        val feelsLike = when {
            windChill != null && windChill < temperature -> windChill
            heatIndex != null && heatIndex > temperature -> heatIndex
            else -> temperature
        }
        return StationReading(
            name = obs.optString("neighborhood").ifBlank { obs.getString("stationID") },
            temperature = temperature,
            feelsLike = feelsLike,
            humidity = obs.doubleOrNull("humidity")?.roundToInt(),
            windSpeed = values.doubleOrNull("windSpeed"),
            raining = (values.doubleOrNull("precipRate") ?: 0.0) > 0.0,
            latitude = obs.getDouble("lat"),
            longitude = obs.getDouble("lon"),
        )
    }

    data class Outlook(val high: Double?, val low: Double?, val precipChance: Int?, val precip: Precip?)

    /**
     * Today at a point. High/low use the calendar-day values, which stay set all day. Precipitation
     * comes from the day part, which goes null once the daytime has passed, so fall back to tonight.
     */
    suspend fun todayOutlook(latitude: Double, longitude: Double, apiKey: String, fahrenheit: Boolean): Outlook {
        val url = "https://api.weather.com/v3/wx/forecast/daily/5day" +
            "?geocode=$latitude,$longitude&format=json&language=en-US&units=${units(fahrenheit)}" +
            "&apiKey=${URLEncoder.encode(apiKey, "UTF-8")}"
        val json = JSONObject(get(url))
        val part = json.optJSONArray("daypart")?.optJSONObject(0)
        val chances = part?.optJSONArray("precipChance")
        val types = part?.optJSONArray("precipType")
        val icons = part?.optJSONArray("iconCode")
        val index = (0..1).firstOrNull { chances != null && !chances.isNull(it) }
        // The icon is the most specific (sleet, freezing rain, hail…); precipType only says rain or snow,
        // and "precip" for a mix, which is left null for Open-Meteo to settle.
        val precip = index?.let { i ->
            Precip.fromTwcIcon(icons?.takeUnless { it.isNull(i) }?.optInt(i))
                ?: when (types?.optString(i)) {
                    "rain" -> Precip.RAIN
                    "snow" -> Precip.SNOW
                    else -> null
                }
        }
        return Outlook(
            high = json.getJSONArray("calendarDayTemperatureMax").doubleOrNull(0),
            low = json.getJSONArray("calendarDayTemperatureMin").doubleOrNull(0),
            precipChance = index?.let { chances?.doubleOrNull(it)?.roundToInt() },
            precip = precip,
        )
    }

    private fun units(fahrenheit: Boolean) = if (fahrenheit) "e" else "m"

    private suspend fun get(url: String): String {
        val (code, body) = httpGet(url)
        when (code) {
            HttpURLConnection.HTTP_OK -> return body
            // Unknown station IDs and stations that stopped reporting both come back empty.
            HttpURLConnection.HTTP_NO_CONTENT -> throw StationOffline()
            HttpURLConnection.HTTP_UNAUTHORIZED, HttpURLConnection.HTTP_FORBIDDEN -> throw InvalidKey()
            else -> throw IOException("HTTP $code")
        }
    }
}

private fun JSONObject.doubleOrNull(key: String): Double? =
    if (isNull(key)) null else optDouble(key).takeUnless { it.isNaN() }

private fun JSONArray.doubleOrNull(index: Int): Double? =
    if (length() <= index || isNull(index)) null else optDouble(index).takeUnless { it.isNaN() }

/** Returns the status code and body, including for error responses. */
private suspend fun httpGet(url: String): Pair<Int, String> = withContext(Dispatchers.IO) {
    val conn = URL(url).openConnection() as HttpURLConnection
    conn.connectTimeout = 10_000
    conn.readTimeout = 10_000
    try {
        val code = conn.responseCode
        val stream = if (code < 400) conn.inputStream else conn.errorStream
        code to (stream?.bufferedReader()?.use { it.readText() } ?: "")
    } finally {
        conn.disconnect()
    }
}
