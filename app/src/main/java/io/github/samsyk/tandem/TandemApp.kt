package io.github.samsyk.tandem

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import java.security.SecureRandom

class TandemApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(SYNC_CHANNEL, "Sync status", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shows that Tandem is keeping your folders in sync"
                setShowBadge(false)
            }
        )
    }

    companion object {
        const val SYNC_CHANNEL = "sync"
    }
}

/** Small settings store. Everything here is local to the phone. */
object Prefs {
    private lateinit var sp: SharedPreferences

    fun init(ctx: Context) {
        if (!::sp.isInitialized) sp = ctx.getSharedPreferences("tandem", Context.MODE_PRIVATE)
    }

    fun register(l: SharedPreferences.OnSharedPreferenceChangeListener) = sp.registerOnSharedPreferenceChangeListener(l)
    fun unregister(l: SharedPreferences.OnSharedPreferenceChangeListener) = sp.unregisterOnSharedPreferenceChangeListener(l)

    private fun bool(key: String, def: Boolean) = sp.getBoolean(key, def)
    private fun set(key: String, v: Boolean) = sp.edit { putBoolean(key, v) }

    var onboarded: Boolean get() = bool("onboarded", false); set(v) = set("onboarded", v)
    var enabled: Boolean get() = bool("enabled", false); set(v) = set("enabled", v)
    var wifiOnly: Boolean get() = bool("wifiOnly", false); set(v) = set("wifiOnly", v)
    var chargingOnly: Boolean get() = bool("chargingOnly", false); set(v) = set("chargingOnly", v)
    var pauseOnSaver: Boolean get() = bool("pauseOnSaver", false); set(v) = set("pauseOnSaver", v)
    var startOnBoot: Boolean get() = bool("startOnBoot", true); set(v) = set("startOnBoot", v)
    var configured: Boolean get() = bool("configured", false); set(v) = set("configured", v)
    var pausedByRules: Boolean get() = bool("pausedByRules", false); set(v) = set("pausedByRules", v)

    /** Key the app uses to talk to its own engine. Generated once, never leaves the phone. */
    val apiKey: String
        get() = sp.getString("apiKey", null) ?: randomToken(32).also { k -> sp.edit { putString("apiKey", k) } }
}

private val rng = SecureRandom()
private const val ALPHABET = "abcdefghijkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"

fun randomToken(n: Int) = buildString { repeat(n) { append(ALPHABET[rng.nextInt(ALPHABET.length)]) } }
