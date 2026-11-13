package io.github.samsyk.tandem.engine

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.OffsetDateTime

data class Folder(
    val id: String,
    val label: String,
    val path: String,
    val paused: Boolean,
    val deviceIds: List<String>,
    val state: String,
    val needFiles: Long,
    val needBytes: Long,
    val globalFiles: Long,
    val globalBytes: Long,
    val inSyncBytes: Long,
    val error: String?,
    val pullErrors: Int,
) {
    val progress: Float get() = if (globalBytes > 0) (inSyncBytes.toFloat() / globalBytes).coerceIn(0f, 1f) else 1f
    val syncing: Boolean get() = state.startsWith("sync") && needFiles > 0
    val scanning: Boolean get() = state.startsWith("scan")
    val hasProblem: Boolean get() = state == "error" || error != null || pullErrors > 0
}

data class Device(
    val id: String,
    val name: String,
    val connected: Boolean,
    val paused: Boolean,
    val lastSeen: Instant?,
    val address: String?,
    val connectionType: String?,
    val completion: Double?,
)

data class PendingDevice(val id: String, val name: String, val address: String)
data class PendingFolder(val id: String, val label: String, val deviceId: String, val deviceName: String)

data class Snapshot(
    val myId: String,
    val myName: String,
    val version: String,
    val folders: List<Folder>,
    val devices: List<Device>,
    val pendingDevices: List<PendingDevice>,
    val pendingFolders: List<PendingFolder>,
) {
    fun deviceName(id: String) = devices.find { it.id == id }?.name ?: id.take(7)
}

/** Reads the engine's state and performs changes, in plain terms. */
object Repo {

    suspend fun snapshot(api: SyncthingApi): Snapshot = coroutineScope {
        val status = async { api.obj("/rest/system/status") }
        val config = async { api.obj("/rest/config") }
        val conns = async { api.obj("/rest/system/connections") }
        val stats = async { api.obj("/rest/stats/device") }
        val pendingDev = async { api.obj("/rest/cluster/pending/devices") }
        val pendingFol = async { api.obj("/rest/cluster/pending/folders") }
        val version = async { runCatching { api.obj("/rest/system/version").optString("version") }.getOrDefault("") }

        val myId = status.await().getString("myID")
        val cfg = config.await()
        val devCfg = cfg.optJSONArray("devices").objects()
        val names = devCfg.associate { d ->
            val id = d.getString("deviceID")
            id to d.optString("name").ifBlank { id.take(7) }
        }
        val connMap = conns.await().optJSONObject("connections") ?: JSONObject()
        val statMap = stats.await()

        val devices = devCfg.filter { it.getString("deviceID") != myId }.map { d ->
            async {
                val id = d.getString("deviceID")
                val c = connMap.optJSONObject(id)
                val connected = c?.optBoolean("connected") == true
                val completion = if (connected) runCatching {
                    api.obj("/rest/db/completion?device=${enc(id)}").optDouble("completion")
                }.getOrNull() else null
                Device(
                    id = id,
                    name = names.getValue(id),
                    connected = connected,
                    paused = d.optBoolean("paused"),
                    lastSeen = parseTime(statMap.optJSONObject(id)?.optString("lastSeen")),
                    address = c?.optString("address")?.takeIf { connected && it.isNotBlank() },
                    connectionType = c?.optString("type")?.takeIf { connected && it.isNotBlank() },
                    completion = completion,
                )
            }
        }.awaitAll()

        val folders = cfg.optJSONArray("folders").objects().map { f ->
            async {
                val id = f.getString("id")
                val paused = f.optBoolean("paused")
                val st = if (paused) null else runCatching { api.obj("/rest/db/status?folder=${enc(id)}") }.getOrNull()
                Folder(
                    id = id,
                    label = f.optString("label").ifBlank { id },
                    path = f.optString("path"),
                    paused = paused,
                    deviceIds = f.optJSONArray("devices").objects().map { it.getString("deviceID") }.filter { it != myId },
                    state = if (paused) "paused" else st?.optString("state")?.ifBlank { null } ?: "unknown",
                    needFiles = st?.optLong("needFiles") ?: 0,
                    needBytes = st?.optLong("needBytes") ?: 0,
                    globalFiles = st?.optLong("globalFiles") ?: 0,
                    globalBytes = st?.optLong("globalBytes") ?: 0,
                    inSyncBytes = st?.optLong("inSyncBytes") ?: 0,
                    error = st?.optString("error")?.takeIf { it.isNotBlank() },
                    pullErrors = st?.optInt("pullErrors") ?: 0,
                )
            }
        }.awaitAll()

        val pd = pendingDev.await()
        val pendingDevices = pd.keyList().map { id ->
            val o = pd.optJSONObject(id)
            PendingDevice(id, o?.optString("name")?.ifBlank { null } ?: "Unnamed device", o?.optString("address") ?: "")
        }
        val pf = pendingFol.await()
        val existing = folders.map { it.id }.toSet()
        val pendingFolders = pf.keyList().filter { it !in existing }.flatMap { fid ->
            val offered = pf.optJSONObject(fid)?.optJSONObject("offeredBy")
            offered.keyList().map { dev ->
                PendingFolder(
                    id = fid,
                    label = offered?.optJSONObject(dev)?.optString("label")?.ifBlank { null } ?: fid,
                    deviceId = dev,
                    deviceName = names[dev] ?: dev.take(7),
                )
            }
        }

        Snapshot(
            myId = myId,
            myName = names[myId] ?: "This phone",
            version = version.await(),
            folders = folders.sortedBy { it.label.lowercase() },
            devices = devices.sortedBy { it.name.lowercase() },
            pendingDevices = pendingDevices,
            pendingFolders = pendingFolders,
        )
    }

    // ── changes ──

    suspend fun addDevice(api: SyncthingApi, rawId: String, name: String): String {
        val check = api.obj("/rest/svc/deviceid?id=${enc(rawId.trim())}")
        if (check.has("error") || !check.has("id")) throw IOException("That code doesn't look like a device ID")
        val id = check.getString("id")
        val d = api.obj("/rest/config/defaults/device")
        d.put("deviceID", id)
        d.put("name", name)
        d.put("addresses", JSONArray().put("dynamic"))
        api.post("/rest/config/devices", d.toString())
        return id
    }

    suspend fun dismissDevice(api: SyncthingApi, id: String) {
        api.delete("/rest/cluster/pending/devices?device=${enc(id)}")
    }

    suspend fun dismissFolder(api: SyncthingApi, p: PendingFolder) {
        api.delete("/rest/cluster/pending/folders?folder=${enc(p.id)}&device=${enc(p.deviceId)}")
    }

    suspend fun createFolder(api: SyncthingApi, id: String, label: String, path: String, devices: List<String>) {
        File(path).mkdirs()
        val f = api.obj("/rest/config/defaults/folder")
        f.put("id", id)
        f.put("label", label)
        f.put("path", path)
        f.put("type", "sendreceive")
        f.put("ignorePerms", true)
        f.put("fsWatcherEnabled", true)
        val arr = JSONArray()
        devices.forEach { arr.put(JSONObject().put("deviceID", it).put("introducedBy", "").put("encryptionPassword", "")) }
        f.put("devices", arr)
        api.post("/rest/config/folders", f.toString())
    }

    suspend fun setFolderPaused(api: SyncthingApi, id: String, paused: Boolean) {
        val f = api.obj("/rest/config/folders/${enc(id)}")
        f.put("paused", paused)
        api.put("/rest/config/folders/${enc(id)}", f)
    }

    suspend fun shareFolder(api: SyncthingApi, id: String, devices: List<String>) {
        val f = api.obj("/rest/config/folders/${enc(id)}")
        val arr = f.optJSONArray("devices") ?: JSONArray()
        val have = arr.objects().map { it.getString("deviceID") }.toSet()
        devices.filter { it !in have }.forEach {
            arr.put(JSONObject().put("deviceID", it).put("introducedBy", "").put("encryptionPassword", ""))
        }
        f.put("devices", arr)
        api.put("/rest/config/folders/${enc(id)}", f)
    }

    suspend fun rescan(api: SyncthingApi, id: String) {
        api.post("/rest/db/scan?folder=${enc(id)}")
    }

    suspend fun removeFolder(api: SyncthingApi, id: String) {
        api.delete("/rest/config/folders/${enc(id)}")
    }

    suspend fun removeDevice(api: SyncthingApi, id: String) {
        api.delete("/rest/config/devices/${enc(id)}")
    }

    suspend fun rename(api: SyncthingApi, deviceId: String, name: String) {
        val d = api.obj("/rest/config/devices/${enc(deviceId)}")
        d.put("name", name)
        api.put("/rest/config/devices/${enc(deviceId)}", d)
    }

    fun newFolderId(): String {
        val a = "abcdefghijkmnopqrstuvwxyz234567"
        val r = java.security.SecureRandom()
        fun part() = (1..5).map { a[r.nextInt(a.length)] }.joinToString("")
        return "${part()}-${part()}"
    }

    private fun parseTime(s: String?): Instant? {
        if (s.isNullOrBlank()) return null
        return runCatching { OffsetDateTime.parse(s).toInstant() }.getOrNull()?.takeIf { it.epochSecond > 86_400 }
    }
}
