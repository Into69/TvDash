package dev.tvdash.launcher

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Loads this TV's UniFi settings before any screen (home, screensaver, camera) needs them. */
class TvDashApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ProtectClient.config = Prefs(this).unifi
    }
}

/**
 * Receives the keys and addresses from the PC's local.properties, which the published APK doesn't
 * contain: `tools/tv.ps1 install` sends them with
 * `am broadcast -n dev.tvdash.launcher/.DeviceConfigReceiver --es unifi_host … --es wu_default_key …`.
 * Only the shell can send it (the manifest requires a permission apps can't hold).
 */
class DeviceConfigReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val prefs = Prefs(context)
        prefs.setDeviceConfig(
            wuStation = intent.getStringExtra(EXTRA_WU_STATION)?.trim(),
            wuKey = intent.getStringExtra(EXTRA_WU_KEY)?.trim(),
            unifiHost = intent.getStringExtra(EXTRA_UNIFI_HOST)?.trim(),
            unifiKey = intent.getStringExtra(EXTRA_UNIFI_KEY)?.trim(),
            unifiCert = intent.getStringExtra(EXTRA_UNIFI_CERT)?.trim(),
        )
        ProtectClient.config = prefs.unifi
        resultData = "ok"
    }

    companion object {
        private const val EXTRA_WU_STATION = "wu_default_station"
        private const val EXTRA_WU_KEY = "wu_default_key"
        private const val EXTRA_UNIFI_HOST = "unifi_host"
        private const val EXTRA_UNIFI_KEY = "unifi_key"
        private const val EXTRA_UNIFI_CERT = "unifi_cert_sha256"
    }
}
