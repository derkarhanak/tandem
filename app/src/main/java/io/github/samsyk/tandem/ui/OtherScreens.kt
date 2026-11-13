package io.github.samsyk.tandem.ui

import android.content.ClipData
import android.content.ClipboardManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import io.github.samsyk.tandem.Prefs
import io.github.samsyk.tandem.R
import io.github.samsyk.tandem.engine.Engine
import io.github.samsyk.tandem.engine.EngineState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceScreen(vm: MainViewModel, id: String) {
    val ctx = LocalContext.current
    val s = vm.snapshot
    val d = s?.devices?.find { it.id == id }
    if (s == null || d == null) {
        LaunchedEffect(Unit) { vm.back() }
        return
    }
    var confirmRemove by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }

    Scaffold(topBar = { TopAppBar(title = { Text(d.name) }, navigationIcon = { BackButton(vm) }) }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
                Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconBubble(
                        painterResource(if (looksLikePhone(d.name)) R.drawable.ic_phone else R.drawable.ic_laptop),
                        MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer, 52.dp,
                    )
                    Column(Modifier.padding(start = 16.dp)) {
                        Text(if (d.connected) "Connected" else "Not connected", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                        Text(deviceStatus(d), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            SectionTitle("Details")
            if (d.connected && d.address != null) Fact(
                "Connection",
                (if (d.connectionType?.contains("relay") == true) "Through a relay (slower) · " else "Direct · ") + d.address,
            )
            ListItem(
                headlineContent = { Text("Device code", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) },
                supportingContent = { Text(d.id, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) },
                trailingContent = {
                    TextButton(onClick = {
                        ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Device ID", d.id))
                        vm.message = "Copied"
                    }) { Text("Copy") }
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
            SectionTitle("Folders shared with it")
            val shared = s.folders.filter { d.id in it.deviceIds }
            if (shared.isEmpty()) Text("None yet.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp, top = 4.dp))
            shared.forEach { f -> Fact(f.label, folderStateLabel(f).first) }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 16.dp)) {
                FilledTonalButton(onClick = { renaming = true }) { Text("Rename") }
                TextButton(onClick = { confirmRemove = true }) { Text("Remove device", color = MaterialTheme.colorScheme.error) }
            }
            Spacer(Modifier.height(32.dp))
        }
    }

    if (renaming) RenameDialog(d.name, onDismiss = { renaming = false }) { vm.rename(d.id, it); renaming = false }
    if (confirmRemove) AlertDialog(
        onDismissRequest = { confirmRemove = false },
        title = { Text("Remove ${d.name}?") },
        text = { Text("This phone will stop syncing with it. No files are deleted on either device.") },
        confirmButton = { Button(onClick = { confirmRemove = false; vm.removeDevice(d.id) { vm.back() } }) { Text("Remove") } },
        dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Cancel") } },
    )
}

@Composable
private fun RenameDialog(current: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename") },
        text = { OutlinedTextField(name, { name = it }, singleLine = true) },
        confirmButton = { Button(enabled = name.isNotBlank(), onClick = { onSave(name.trim()) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: MainViewModel, engine: EngineState) {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++ }
    var on by remember { mutableStateOf(Prefs.enabled) }
    var wifi by remember { mutableStateOf(Prefs.wifiOnly) }
    var charging by remember { mutableStateOf(Prefs.chargingOnly) }
    var saver by remember { mutableStateOf(Prefs.pauseOnSaver) }
    var boot by remember { mutableStateOf(Prefs.startOnBoot) }
    var renaming by remember { mutableStateOf(false) }
    val s = vm.snapshot
    val files = remember(tick) { Perms.files(ctx) }
    val battery = remember(tick) { Perms.battery(ctx) }

    Scaffold(topBar = { TopAppBar(title = { Text("Settings") }, navigationIcon = { BackButton(vm) }) }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = 8.dp)) {
            Toggle("Sync", if (on) "On — folders stay up to date in the background" else "Off", on) { on = it; vm.setSyncOn(it) }

            SectionTitle("When to sync", Modifier.padding(horizontal = 8.dp))
            Toggle("Only on Wi-Fi", "Don't use mobile data", wifi) { wifi = it; Prefs.wifiOnly = it }
            Toggle("Only while charging", "Saves battery on big folders", charging) { charging = it; Prefs.chargingOnly = it }
            Toggle("Pause in Battery Saver", "Wait until Battery Saver is off", saver) { saver = it; Prefs.pauseOnSaver = it }
            Toggle("Start when the phone turns on", "Keep syncing after a restart", boot) { boot = it; Prefs.startOnBoot = it }

            SectionTitle("This phone", Modifier.padding(horizontal = 8.dp))
            Row2("Name", s?.myName ?: "—") { if (s != null) renaming = true }
            Row2("Access to files", if (files) "Allowed" else "Needed — tap to allow") { ctx.startActivity(Perms.filesIntent(ctx)) }
            Row2("Run in the background", if (battery) "Allowed" else "Restricted — tap to allow, or Android may pause sync") {
                if (!battery) ctx.startActivity(Perms.batteryIntent(ctx))
            }

            SectionTitle("About", Modifier.padding(horizontal = 8.dp))
            Row2("Sync engine", if (engine is EngineState.Running) "Syncthing ${s?.version ?: ""}".trim() else "Not running")
            Row2("Engine log", "For when something goes wrong") { vm.go(Screen.Log) }
            Spacer(Modifier.height(32.dp))
        }
    }
    if (renaming && s != null) RenameDialog(s.myName, onDismiss = { renaming = false }) { vm.rename(s.myId, it); renaming = false }
}

@Composable
private fun Toggle(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(title, fontWeight = FontWeight.Medium) },
        supportingContent = { Text(detail) },
        trailingContent = { Switch(checked, onChange) },
        modifier = Modifier.clickable { onChange(!checked) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

@Composable
private fun Row2(title: String, detail: String, onClick: (() -> Unit)? = null) {
    ListItem(
        headlineContent = { Text(title, fontWeight = FontWeight.Medium) },
        supportingContent = { Text(detail) },
        modifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier,
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogScreen(vm: MainViewModel) {
    val ctx = LocalContext.current
    var text by remember { mutableStateOf("Loading…") }
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(tick) {
        text = withContext(Dispatchers.IO) {
            val f = Engine.logFile(ctx)
            if (!f.exists()) "No log yet." else f.readLines().takeLast(300).joinToString("\n")
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Engine log") }, navigationIcon = { BackButton(vm) },
                actions = { IconButton(onClick = { tick++ }) { Icon(Icons.Default.Refresh, "Refresh") } },
            )
        },
    ) { pad ->
        Text(
            text, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 15.sp,
            modifier = Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState(), reverseScrolling = true)
                .horizontalScroll(rememberScrollState()).padding(12.dp),
        )
    }
}

@Composable
fun OnboardingScreen(vm: MainViewModel) {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++ }
    val files = remember(tick) { Perms.files(ctx) }
    val notif = remember(tick) { Perms.notifications(ctx) }
    val battery = remember(tick) { Perms.battery(ctx) }
    val askNotif = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }

    Column(
        Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Spacer(Modifier.height(24.dp))
        IconBubble(painterResource(R.drawable.ic_sync), MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer, 72.dp)
        Text("Tandem", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
        Text(
            "Keep folders in sync between this phone and your other devices — directly, with no cloud in between.",
            style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Step("Access to your files", "So Tandem can keep the folders you choose up to date.", files, "Allow") {
            ctx.startActivity(Perms.filesIntent(ctx))
        }
        Step("Notifications", "Shows that sync is running, with a button to turn it off.", notif, "Allow") {
            if (android.os.Build.VERSION.SDK_INT >= 33) askNotif.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        Step("Run in the background", "Stops Android from pausing sync to save battery.", battery, "Allow") {
            ctx.startActivity(Perms.batteryIntent(ctx))
        }
        Spacer(Modifier.height(8.dp))
        Button(enabled = files, onClick = { vm.finishOnboarding() }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Text("Start syncing")
        }
        if (!files) Text(
            "File access is the one thing Tandem can't work without.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun Step(title: String, detail: String, done: Boolean, action: String, onClick: () -> Unit) {
    Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.size(12.dp))
            if (done) Icon(Icons.Default.CheckCircle, "Done", tint = Good, modifier = Modifier.size(28.dp))
            else FilledTonalButton(onClick = onClick) { Text(action) }
        }
    }
}
