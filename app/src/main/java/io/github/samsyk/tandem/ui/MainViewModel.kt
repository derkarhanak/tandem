package io.github.samsyk.tandem.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.samsyk.tandem.Prefs
import io.github.samsyk.tandem.engine.Engine
import io.github.samsyk.tandem.engine.PendingDevice
import io.github.samsyk.tandem.engine.PendingFolder
import io.github.samsyk.tandem.engine.Repo
import io.github.samsyk.tandem.engine.Snapshot
import io.github.samsyk.tandem.engine.SyncService
import io.github.samsyk.tandem.engine.SyncthingApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

sealed interface Screen {
    data object Home : Screen
    data object Pair : Screen
    data object AddFolder : Screen
    data class FolderDetail(val id: String) : Screen
    data class DeviceDetail(val id: String) : Screen
    data object Settings : Screen
    data object Log : Screen
}

class MainViewModel(app: Application) : AndroidViewModel(app) {
    var snapshot by mutableStateOf<Snapshot?>(null)
        private set
    var message by mutableStateOf<String?>(null)
    var onboarded by mutableStateOf(Prefs.onboarded)
        private set
    val stack = mutableStateListOf<Screen>(Screen.Home)

    @Volatile var visible = false

    init {
        viewModelScope.launch {
            while (true) {
                if (visible) refresh()
                delay(2000)
            }
        }
    }

    suspend fun refresh() {
        val api = Engine.api
        snapshot = if (api == null) null else runCatching { Repo.snapshot(api) }.getOrElse { snapshot }
    }

    fun go(s: Screen) { stack.add(s) }
    fun back() { if (stack.size > 1) stack.removeAt(stack.lastIndex) }

    fun finishOnboarding() {
        Prefs.onboarded = true
        onboarded = true
        SyncService.turnOn(getApplication())
    }

    fun setSyncOn(on: Boolean) {
        if (on) SyncService.turnOn(getApplication()) else SyncService.turnOff(getApplication())
    }

    private fun act(what: String, success: String? = null, after: () -> Unit = {}, block: suspend (SyncthingApi) -> Unit) {
        viewModelScope.launch {
            val api = Engine.api
            if (api == null) {
                message = "Turn sync on first"
                return@launch
            }
            try {
                block(api)
                if (success != null) message = success
                after()
                refresh()
            } catch (e: Exception) {
                message = "$what didn't work: ${e.message?.take(140)}"
            }
        }
    }

    fun addDevice(id: String, name: String, after: () -> Unit) =
        act("Adding the device", "Added $name. Now accept this phone on that device.", after) { Repo.addDevice(it, id, name) }

    fun acceptDevice(p: PendingDevice) =
        act("Accepting ${p.name}", "${p.name} is paired") { Repo.addDevice(it, p.id, p.name) }

    fun dismissDevice(p: PendingDevice) = act("Dismissing") { Repo.dismissDevice(it, p.id) }

    fun acceptFolder(p: PendingFolder, path: String) =
        act("Accepting “${p.label}”", "Syncing “${p.label}”") { Repo.createFolder(it, p.id, p.label, path, listOf(p.deviceId)) }

    fun dismissFolder(p: PendingFolder) = act("Dismissing") { Repo.dismissFolder(it, p) }

    fun addFolder(label: String, path: String, devices: List<String>, after: () -> Unit) =
        act("Adding the folder", "Syncing “$label”", after) { Repo.createFolder(it, Repo.newFolderId(), label, path, devices) }

    fun setFolderPaused(id: String, paused: Boolean) =
        act(if (paused) "Pausing" else "Resuming") { Repo.setFolderPaused(it, id, paused) }

    fun shareFolder(id: String, devices: List<String>) =
        act("Sharing the folder", "Shared. Accept it on the other device.") { Repo.shareFolder(it, id, devices) }

    fun rescan(id: String) = act("Checking for changes", "Checking for changes…") { Repo.rescan(it, id) }

    fun removeFolder(id: String, after: () -> Unit) =
        act("Removing the folder", "Stopped syncing. Your files are still on the phone.", after) { Repo.removeFolder(it, id) }

    fun removeDevice(id: String, after: () -> Unit) =
        act("Removing the device", "Device removed", after) { Repo.removeDevice(it, id) }

    fun rename(deviceId: String, name: String) = act("Renaming") { Repo.rename(it, deviceId, name) }
}
