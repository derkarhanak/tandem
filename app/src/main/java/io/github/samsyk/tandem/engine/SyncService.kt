package io.github.samsyk.tandem.engine

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.github.samsyk.tandem.Prefs
import io.github.samsyk.tandem.R
import io.github.samsyk.tandem.TandemApp
import io.github.samsyk.tandem.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** Keeps the engine running in the background, with the ongoing notification Android requires. */
class SyncService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var rules: SyncRules
    private var multicast: WifiManager.MulticastLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        goForeground(notification("Starting…"))
        rules = SyncRules(this) { Engine.setWaiting(it) }
        rules.start()
        // Lets the engine find your other devices on the same Wi-Fi.
        multicast = getSystemService(WifiManager::class.java)?.createMulticastLock("tandem")?.apply {
            setReferenceCounted(false)
            acquire()
        }
        scope.launch {
            combine(Engine.state, Engine.waiting) { s, w -> statusText(s, w) }.collect { text ->
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground(notification("Keeping your folders in sync"))
        if (!Prefs.enabled) {
            stopSelf()
            return START_NOT_STICKY
        }
        Engine.start(this)
        return START_STICKY
    }

    override fun onDestroy() {
        rules.stop()
        multicast?.release()
        scope.cancel()
        Engine.stop()
        super.onDestroy()
    }

    private fun goForeground(n: Notification) {
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, n, type)
    }

    private fun statusText(s: EngineState, waiting: String?) = when {
        s is EngineState.Failed -> "Sync stopped working — open Tandem"
        s is EngineState.Starting -> "Starting…"
        waiting == "wifi" -> "Waiting for Wi-Fi"
        waiting == "charger" -> "Waiting for the charger"
        waiting == "saver" -> "Paused for Battery Saver"
        else -> "Keeping your folders in sync"
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val off = PendingIntent.getBroadcast(
            this, 1, Intent(this, TurnOffReceiver::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, TandemApp.SYNC_CHANNEL)
            .setSmallIcon(R.drawable.ic_sync)
            .setContentTitle("Tandem")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, "Turn off", off)
            .build()
    }

    companion object {
        const val NOTIFICATION_ID = 1

        fun turnOn(ctx: Context) {
            Prefs.enabled = true
            ContextCompat.startForegroundService(ctx, Intent(ctx, SyncService::class.java))
        }

        fun turnOff(ctx: Context) {
            Prefs.enabled = false
            ctx.stopService(Intent(ctx, SyncService::class.java))
            Engine.stop()
        }
    }
}

class TurnOffReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = SyncService.turnOff(context)
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Prefs.init(context)
        val boot = intent.action == Intent.ACTION_BOOT_COMPLETED
        if (Prefs.onboarded && Prefs.enabled && (!boot || Prefs.startOnBoot)) SyncService.turnOn(context)
    }
}

/** Watches Wi-Fi, charging and Battery Saver and tells the engine when to pause. */
class SyncRules(private val ctx: Context, private val onChange: (String?) -> Unit) {
    private val cm = ctx.getSystemService(ConnectivityManager::class.java)
    private val pm = ctx.getSystemService(PowerManager::class.java)
    @Volatile private var caps: NetworkCapabilities? = null

    private val netCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, c: NetworkCapabilities) { caps = c; evaluate() }
        override fun onLost(network: Network) { caps = null; evaluate() }
    }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = evaluate()
    }
    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> evaluate() }

    fun start() {
        cm.registerDefaultNetworkCallback(netCallback)
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
        }
        ContextCompat.registerReceiver(ctx, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        Prefs.register(prefListener)
        evaluate()
    }

    fun stop() {
        runCatching { cm.unregisterNetworkCallback(netCallback) }
        runCatching { ctx.unregisterReceiver(receiver) }
        Prefs.unregister(prefListener)
    }

    private fun charging(): Boolean {
        val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        return (i?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
    }

    private fun onWifi(): Boolean {
        val c = caps ?: cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        val local = c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        return local && c.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    fun evaluate() {
        val reason = when {
            Prefs.chargingOnly && !charging() -> "charger"
            Prefs.pauseOnSaver && pm.isPowerSaveMode -> "saver"
            Prefs.wifiOnly && !onWifi() -> "wifi"
            else -> null
        }
        onChange(reason)
    }
}
