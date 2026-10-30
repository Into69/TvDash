package dev.tvdash.launcher

import android.content.Context
import android.os.SystemClock
import android.text.format.DateFormat
import android.view.View
import android.widget.TextView
import androidx.core.view.isVisible
import org.json.JSONException
import java.io.IOException
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/**
 * Loads and formats the weather for the home screen and the screensaver: a Weather Underground
 * station when one is set (plus Open-Meteo for what a station can't provide), otherwise Open-Meteo.
 */
class WeatherFeed(private val context: Context, private val prefs: Prefs) {

    sealed class Result

    /** Ready-to-show text. [timeZone] is the location's zone, for the clock. */
    data class Display(
        val icon: String,
        val temperature: String,
        val now: String,
        val today: String,
        val place: String,
        val hours: String,
        val timeZone: String?,
    ) : Result()

    /** Nothing to show (no key, station offline, no network): just this message. */
    data class Message(val text: String) : Result()

    /** Station forecast, kept for [FORECAST_CACHE_MS] to spare the Weather Underground quota. */
    private var stationForecast: Triple<String, Long, Forecast>? = null

    /** How long until the next refresh: stations report every few minutes, the model far less often. */
    val refreshIntervalMs get() = if (prefs.wuStation != null) STATION_INTERVAL_MS else WEATHER_INTERVAL_MS

    fun clearCache() {
        stationForecast = null
    }

    suspend fun load(): Result {
        val station = prefs.wuStation
        if (station != null) {
            return try {
                val reading = WundergroundClient.current(station, prefs.fahrenheit)
                val forecast = stationForecast(station, reading)
                // The station measures; the sky comes from the forecast model, unless the gauge says rain.
                val code = if (reading.raining) RAIN_CODE else forecast?.nowCode
                display(
                    temperature = reading.temperature,
                    feelsLike = reading.feelsLike,
                    condition = if (reading.raining) string(R.string.weather_raining) else code?.let(WeatherClient::describe).orEmpty(),
                    icon = code?.let { WeatherClient.icon(it, forecast?.nowIsDay ?: true) }.orEmpty(),
                    place = reading.name,
                    windSpeed = reading.windSpeed,
                    humidity = reading.humidity,
                    forecast = forecast,
                )
            } catch (e: WundergroundClient.InvalidKey) {
                Message(string(R.string.wu_invalid_key))
            } catch (e: WundergroundClient.StationOffline) {
                Message(context.getString(R.string.wu_offline, station.stationId))
            } catch (e: IOException) {
                Message(string(R.string.weather_unavailable))
            } catch (e: JSONException) {
                Message(string(R.string.weather_unavailable))
            }
        }
        val location = prefs.location ?: return Message(string(R.string.weather_set_location))
        return try {
            val weather = WeatherClient.current(location, prefs.fahrenheit)
            display(
                temperature = weather.temperature,
                feelsLike = weather.feelsLike,
                condition = WeatherClient.describe(weather.code),
                icon = WeatherClient.icon(weather.code, weather.isDay),
                place = location.name.substringBefore(','),
                windSpeed = weather.windSpeed,
                humidity = weather.humidity,
                forecast = weather.forecast,
            )
        } catch (e: IOException) {
            Message(string(R.string.weather_unavailable))
        } catch (e: JSONException) {
            Message(string(R.string.weather_unavailable))
        }
    }

    /** "Clear · Feels like 1°" / "High 9°  Low -2° · Rain 4%" / "Arnprior · Calm · Humidity 77%". */
    private fun display(
        temperature: Double,
        feelsLike: Double,
        condition: String,
        icon: String,
        place: String,
        windSpeed: Double?,
        humidity: Int?,
        forecast: Forecast?,
    ): Display {
        val wind = windSpeed?.roundToInt()?.let {
            if (it == 0) string(R.string.weather_calm) else context.getString(R.string.weather_wind, it, if (prefs.fahrenheit) "mph" else "km/h")
        }
        return Display(
            icon = icon,
            temperature = context.getString(R.string.weather_degrees, temperature.roundToInt()),
            now = listOf(condition, context.getString(R.string.weather_feels_like, feelsLike.roundToInt()))
                .filter { it.isNotEmpty() }.joinToString(SEPARATOR),
            today = todayLine(forecast),
            place = listOfNotNull(place.ifEmpty { null }, wind, humidity?.let { context.getString(R.string.weather_humidity, it) })
                .joinToString(SEPARATOR),
            hours = forecast?.let(::formatHours).orEmpty(),
            timeZone = forecast?.timeZone,
        )
    }

    /**
     * Hourly from Open-Meteo at the station's location (the PWS key can't get hourly), with the
     * high/low and precipitation from Weather Underground's daily forecast when it answers.
     */
    private suspend fun stationForecast(station: WuStation, reading: StationReading): Forecast? {
        val cacheKey = "${station.stationId}:${prefs.fahrenheit}"
        stationForecast?.let { (key, at, forecast) ->
            if (key == cacheKey && SystemClock.elapsedRealtime() - at < FORECAST_CACHE_MS) return forecast
        }
        return try {
            val forecast = WeatherClient.forecast(reading.latitude, reading.longitude, prefs.fahrenheit)
            val outlook = try {
                WundergroundClient.todayOutlook(reading.latitude, reading.longitude, station.apiKey, prefs.fahrenheit)
            } catch (e: IOException) {
                null
            } catch (e: JSONException) {
                null
            }
            forecast.copy(
                high = outlook?.high ?: forecast.high,
                low = outlook?.low ?: forecast.low,
                precipChance = outlook?.precipChance ?: forecast.precipChance,
                precip = outlook?.precip ?: forecast.precip,
            ).also { stationForecast = Triple(cacheKey, SystemClock.elapsedRealtime(), it) }
        } catch (e: IOException) {
            null
        } catch (e: JSONException) {
            null
        }
    }

    /** "High 9°  Low -2°  ·  Rain 4%" */
    private fun todayLine(forecast: Forecast?): String = buildList {
        val high = forecast?.high
        val low = forecast?.low
        if (high != null && low != null) add(context.getString(R.string.weather_high_low, high.roundToInt(), low.roundToInt()))
        forecast?.precipChance?.let { chance ->
            val label = when (forecast.precip) {
                Precip.RAIN -> R.string.precip_rain
                Precip.DRIZZLE -> R.string.precip_drizzle
                Precip.SNOW -> R.string.precip_snow
                Precip.SLEET -> R.string.precip_sleet
                Precip.FREEZING_RAIN -> R.string.precip_freezing_rain
                Precip.FREEZING_DRIZZLE -> R.string.precip_freezing_drizzle
                Precip.HAIL -> R.string.precip_hail
                Precip.THUNDERSTORM -> R.string.precip_thunderstorm
            }
            add(context.getString(label, chance))
        }
    }.joinToString(SEPARATOR)

    /** "7 PM ☁️ 5°     8 PM 🌙 4°  …", following the TV's 12/24-hour setting. */
    private fun formatHours(forecast: Forecast): String {
        val pattern = DateTimeFormatter.ofPattern(if (DateFormat.is24HourFormat(context)) "H:mm" else "h a")
        return forecast.hours.joinToString("     ") { hour ->
            "${hour.time.format(pattern)} ${WeatherClient.icon(hour.code, hour.isDay)} ${hour.temperature.roundToInt()}°"
        }
    }

    private fun string(id: Int) = context.getString(id)

    companion object {
        private const val SEPARATOR = "  ·  "

        /** WMO "moderate rain", for when the station's rain gauge says it's raining. */
        private const val RAIN_CODE = 63
        private const val WEATHER_INTERVAL_MS = 30 * 60 * 1000L
        private const val STATION_INTERVAL_MS = 10 * 60 * 1000L
        private const val FORECAST_CACHE_MS = 30 * 60 * 1000L

        /**
         * Fills the weather views under [root] (ids weather_icon, weather_temp, weather_now,
         * weather_today, weather_place, and optionally hourly), as used by both layouts.
         */
        fun bind(root: View, result: Result) {
            val icon = root.findViewById<TextView>(R.id.weather_icon)
            val temp = root.findViewById<TextView>(R.id.weather_temp)
            val now = root.findViewById<TextView>(R.id.weather_now)
            val today = root.findViewById<TextView>(R.id.weather_today)
            val place = root.findViewById<TextView>(R.id.weather_place)
            val hourly = root.findViewById<TextView?>(R.id.hourly)
            when (result) {
                is Display -> {
                    icon.text = result.icon
                    icon.isVisible = result.icon.isNotEmpty()
                    temp.text = result.temperature
                    temp.isVisible = true
                    now.text = result.now
                    today.text = result.today
                    today.isVisible = result.today.isNotEmpty()
                    place.text = result.place
                    place.isVisible = result.place.isNotEmpty()
                    hourly?.text = result.hours
                    hourly?.isVisible = result.hours.isNotEmpty()
                }
                is Message -> {
                    icon.isVisible = false
                    temp.isVisible = false
                    now.text = result.text
                    today.isVisible = false
                    place.isVisible = false
                    hourly?.isVisible = false
                }
            }
        }
    }
}
