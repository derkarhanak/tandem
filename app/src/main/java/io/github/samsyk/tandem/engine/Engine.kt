package io.github.samsyk.tandem.engine

import android.content.Context
import android.os.Build
import android.provider.Settings
import io.github.samsyk.tandem.Prefs
import io.github.samsyk.tandem.randomToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

sealed interface EngineState {
    data object Off : EngineState
    data object Starting : EngineState
    data object Running : EngineState
    data class Failed(val message: String) : EngineState
}

/**
 * Runs the Syncthing engine (shipped as libsyncthingnative.so) as a child process and keeps it alive
 * while sync is turned on. The app controls it only through its localhost REST API.
 */
object Engine {
    const val PORT = 18384

    private val _state = MutableStateFlow<EngineState>(EngineState.Off)
    val state: StateFlow<EngineState> = _state

    /** Why syncing is paused by the user's rules ("wifi", "charger", "saver"), or null. */
    private val _waiting = MutableStateFlow<String?>(null)
    val waiting: StateFlow<String?> = _waiting

    @Volatile
    var api: SyncthingApi? = null
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var process: Process? = null
    @Volatile private var wanted = false
    @Volatile private var launching = false
    private var crashes = 0

    fun logFile(ctx: Context) = File(ctx.filesDir, "syncthing.log")

    @Synchronized
    fun start(ctx: Context) {
        wanted = true
        if (launching || process?.isAlive == true) return
        launching = true
        _state.value = EngineState.Starting
        val app = ctx.applicationContext
        scope.launch { run(app) }
    }

    fun stop() {
        wanted = false
        val p = process
        val a = api
        scope.launch {
            try { a?.post("/rest/system/shutdown") } catch (_: Exception) {}
            if (p != null && !p.waitFor(8, TimeUnit.SECONDS)) p.destroy()
            if (!wanted) _state.value = EngineState.Off
        }
    }

    fun setWaiting(reason: String?) {
        if (_waiting.value == reason) return
        _waiting.value = reason
        scope.launch { applyRules() }
    }

    private suspend fun applyRules() {
        val a = api ?: return
        try {
            if (_waiting.value != null) {
                a.post("/rest/system/pause")
                Prefs.pausedByRules = true
            } else if (Prefs.pausedByRules) {
                a.post("/rest/system/resume")
                Prefs.pausedByRules = false
            }
        } catch (_: Exception) {
        }
    }

    private suspend fun run(ctx: Context) {
        val home = File(ctx.filesDir, "syncthing").apply { mkdirs() }
        val key = Prefs.apiKey
        val bin = File(ctx.applicationInfo.nativeLibraryDir, "libsyncthingnative.so")
        val pb = ProcessBuilder(
            bin.path, "serve",
            "--home=${home.path}",
            "--no-browser", "--no-restart",
            "--gui-address=127.0.0.1:$PORT",
            "--gui-apikey=$key",
            "--log-file=${logFile(ctx).path}",
            "--log-max-size=1000000", "--log-max-old-files=1",
        )
        pb.environment().apply {
            put("HOME", home.path)
            put("STNODEFAULTFOLDER", "1")
            put("STNOUPGRADE", "1")
            put("TMPDIR", ctx.cacheDir.path)
        }
        pb.redirectErrorStream(true)
        pb.redirectOutput(File(ctx.cacheDir, "engine-output.txt"))

        val p = try {
            pb.start()
        } catch (e: IOException) {
            launching = false
            _state.value = EngineState.Failed("Couldn't start the sync engine: ${e.message}")
            return
        }
        synchronized(this) { process = p }
        launching = false

        val client = SyncthingApi("http://127.0.0.1:$PORT", key)
        var ready = false
        for (i in 0 until 80) {
            if (!p.isAlive) break
            if (client.ping()) { ready = true; break }
            delay(250)
        }
        if (!ready) {
            p.destroy()
            p.waitFor(5, TimeUnit.SECONDS)
            synchronized(this) { if (process === p) process = null }
            _state.value = EngineState.Failed(
                if (p.isAlive) "The sync engine didn't respond. Open the engine log in Settings to see why."
                else "The sync engine closed while starting. Open the engine log in Settings to see why."
            )
            return
        }
        api = client
        try { firstRunSetup(ctx, client) } catch (_: Exception) {}
        applyRules()
        _state.value = EngineState.Running

        val started = System.currentTimeMillis()
        val code = runInterruptible { p.waitFor() }
        api = null
        synchronized(this) { if (process === p) process = null }

        if (!wanted) {
            _state.value = EngineState.Off
            return
        }
        // It stopped on its own. Restart a few times, then give up and say so.
        if (System.currentTimeMillis() - started > 120_000) crashes = 0
        crashes++
        if (crashes > 5) {
            _state.value = EngineState.Failed("The sync engine keeps stopping (code $code). Open the engine log in Settings to see why.")
            crashes = 0
            return
        }
        _state.value = EngineState.Starting
        delay(2000L * crashes)
        if (wanted) start(ctx)
    }

    /** One-time setup: name the phone, lock the web interface, decline usage reporting. */
    private suspend fun firstRunSetup(ctx: Context, api: SyncthingApi) {
        if (Prefs.configured) return
        val myId = api.obj("/rest/system/status").getString("myID")

        val me = api.obj("/rest/config/devices/$myId")
        me.put("name", phoneName(ctx))
        api.put("/rest/config/devices/$myId", me)

        val opts = api.obj("/rest/config/options")
        opts.put("urAccepted", -1)
        opts.put("startBrowser", false)
        api.put("/rest/config/options", opts)

        val folderDefaults = api.obj("/rest/config/defaults/folder")
        folderDefaults.put("ignorePerms", true) // Android storage can't keep Unix permissions
        folderDefaults.put("path", "/storage/emulated/0")
        api.put("/rest/config/defaults/folder", folderDefaults)

        // Other apps on the phone can reach localhost, so the web interface gets a password.
        val gui = api.obj("/rest/config/gui")
        gui.put("user", "tandem")
        gui.put("password", randomToken(24))
        api.put("/rest/config/gui", gui)

        Prefs.configured = true
    }

    fun phoneName(ctx: Context): String {
        val n = Settings.Global.getString(ctx.contentResolver, Settings.Global.DEVICE_NAME)
        return (n ?: Build.MODEL ?: "Phone").replaceFirstChar { it.uppercase() }
    }
}
