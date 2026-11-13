package io.github.samsyk.tandem.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.samsyk.tandem.R
import io.github.samsyk.tandem.engine.Device
import io.github.samsyk.tandem.engine.EngineState
import io.github.samsyk.tandem.engine.Folder
import io.github.samsyk.tandem.engine.PendingDevice
import io.github.samsyk.tandem.engine.PendingFolder
import io.github.samsyk.tandem.engine.Snapshot

enum class Tone { Good, Busy, Warn, Bad, Off }

data class Headline(val title: String, val detail: String, val tone: Tone, val action: String? = null)

fun headline(engine: EngineState, waiting: String?, s: Snapshot?): Headline {
    if (engine is EngineState.Off) return Headline("Sync is off", "Your folders won't update until you turn it on.", Tone.Off, "Turn on")
    if (engine is EngineState.Failed) return Headline("Sync stopped working", engine.message, Tone.Bad, "Try again")
    if (engine is EngineState.Starting || s == null) return Headline("Starting up…", "Getting the sync engine ready.", Tone.Busy)
    when (waiting) {
        "wifi" -> return Headline("Waiting for Wi-Fi", "You chose to sync only on Wi-Fi.", Tone.Warn)
        "charger" -> return Headline("Waiting for the charger", "You chose to sync only while charging.", Tone.Warn)
        "saver" -> return Headline("Paused for Battery Saver", "Sync picks up again when Battery Saver is off.", Tone.Warn)
    }
    s.folders.firstOrNull { it.hasProblem }?.let { f ->
        val why = f.error ?: "${plural(f.pullErrors.toLong(), "file")} couldn't be synced."
        return Headline("Problem with “${f.label}”", why, Tone.Bad)
    }
    if (s.devices.isEmpty()) return Headline("Let's pair a device", "Connect your computer to start syncing.", Tone.Off, "Pair a device")
    val syncing = s.folders.filter { it.syncing }
    if (syncing.isNotEmpty()) {
        val n = syncing.sumOf { it.needFiles }
        return Headline("Syncing ${plural(n, "file")}", "${bytes(syncing.sumOf { it.needBytes })} to go", Tone.Busy)
    }
    if (s.folders.any { it.scanning }) return Headline("Checking for changes…", "Looking through your folders.", Tone.Busy)
    val online = s.devices.count { it.connected }
    if (online == 0) return Headline("Your devices are offline", "Changes will sync as soon as one is reachable.", Tone.Warn)
    if (s.folders.isEmpty()) return Headline("Paired — no folders yet", "Accept a shared folder or add one of your own.", Tone.Good)
    return Headline(
        "Everything's in sync",
        "${plural(s.folders.size.toLong(), "folder")} · ${online} of ${plural(s.devices.size.toLong(), "device")} connected",
        Tone.Good,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(vm: MainViewModel, engine: EngineState, waiting: String?) {
    val s = vm.snapshot
    var showAdd by remember { mutableStateOf(false) }
    var accepting by remember { mutableStateOf<PendingFolder?>(null) }
    val h = headline(engine, waiting, s)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Tandem", fontWeight = FontWeight.SemiBold) },
                actions = { IconButton(onClick = { vm.go(Screen.Settings) }) { Icon(Icons.Default.Settings, "Settings") } },
            )
        },
        floatingActionButton = {
            if (engine is EngineState.Running) ExtendedFloatingActionButton(
                onClick = { showAdd = true },
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text("Add") },
            )
        },
    ) { pad ->
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = pad.calculateTopPadding() + 4.dp, bottom = 120.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                StatusCard(h) {
                    when (h.action) {
                        "Turn on", "Try again" -> vm.setSyncOn(true)
                        "Pair a device" -> vm.go(Screen.Pair)
                    }
                }
            }
            if (s != null) {
                items(s.pendingDevices, key = { "pd" + it.id }) { p -> PendingDeviceCard(p, vm) }
                items(s.pendingFolders, key = { "pf" + it.id + it.deviceId }) { p ->
                    PendingFolderCard(p, onAccept = { accepting = p }, onDismiss = { vm.dismissFolder(p) })
                }
                item { SectionTitle("Folders") }
                if (s.folders.isEmpty()) item {
                    EmptyCard(
                        "No folders yet",
                        "When your computer shares a folder, it shows up here to accept. You can also add a folder from this phone.",
                        "Add a folder",
                    ) { vm.go(Screen.AddFolder) }
                }
                items(s.folders, key = { "f" + it.id }) { f -> FolderCard(f) { vm.go(Screen.FolderDetail(f.id)) } }
                item { SectionTitle("Devices") }
                if (s.devices.isEmpty()) item {
                    EmptyCard("No devices yet", "Pair your computer by scanning its code.", "Pair a device") { vm.go(Screen.Pair) }
                }
                items(s.devices, key = { "d" + it.id }) { d -> DeviceCard(d) { vm.go(Screen.DeviceDetail(d.id)) } }
            }
        }
    }

    if (showAdd) ModalBottomSheet(onDismissRequest = { showAdd = false }) {
        SheetOption(painterResource(R.drawable.ic_qr), "Pair a device", "Connect your computer or another phone") {
            showAdd = false; vm.go(Screen.Pair)
        }
        SheetOption(painterResource(R.drawable.ic_folder), "Add a folder", "Choose a folder on this phone to keep in sync") {
            showAdd = false; vm.go(Screen.AddFolder)
        }
        Spacer(Modifier.height(24.dp))
    }
    accepting?.let { p -> AcceptFolderDialog(p, vm) { accepting = null } }
}

@Composable
private fun SheetOption(icon: Painter, title: String, detail: String, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title, fontWeight = FontWeight.Medium) },
        supportingContent = { Text(detail) },
        leadingContent = { IconBubble(icon, MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer) },
        modifier = Modifier.clickable(onClick = onClick).padding(horizontal = 8.dp),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

@Composable
fun StatusCard(h: Headline, onAction: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val (container, content) = when (h.tone) {
        Tone.Good -> cs.primaryContainer to cs.onPrimaryContainer
        Tone.Busy -> cs.tertiaryContainer to cs.onTertiaryContainer
        Tone.Warn -> cs.secondaryContainer to cs.onSecondaryContainer
        Tone.Bad -> cs.errorContainer to cs.onErrorContainer
        Tone.Off -> cs.surfaceContainerHigh to cs.onSurface
    }
    val icon: Painter = when (h.tone) {
        Tone.Good -> rememberVectorPainter(Icons.Default.CheckCircle)
        Tone.Busy -> painterResource(R.drawable.ic_sync)
        Tone.Warn -> painterResource(R.drawable.ic_pause)
        Tone.Bad -> rememberVectorPainter(Icons.Default.Warning)
        Tone.Off -> painterResource(R.drawable.ic_cloud_off)
    }
    val spin by rememberInfiniteTransition(label = "spin").animateFloat(
        0f, -360f, infiniteRepeatable(tween(1600, easing = LinearEasing), RepeatMode.Restart), label = "spin",
    )
    Card(
        colors = CardDefaults.cardColors(containerColor = container, contentColor = content),
        shape = RoundedCornerShape(28.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(22.dp)) {
            Box(
                Modifier.size(52.dp).background(content.copy(alpha = 0.12f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, null, Modifier.size(28.dp).rotate(if (h.tone == Tone.Busy) spin else 0f))
            }
            Spacer(Modifier.height(16.dp))
            Text(h.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(h.detail, style = MaterialTheme.typography.bodyMedium, color = content.copy(alpha = 0.8f))
            if (h.action != null) {
                Spacer(Modifier.height(16.dp))
                Button(onClick = onAction) { Text(h.action) }
            }
        }
    }
}

@Composable
private fun PendingDeviceCard(p: PendingDevice, vm: MainViewModel) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(18.dp)) {
            Text("“${p.name}” wants to connect", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "Only accept devices you own. Code starts with ${p.id.take(7)}.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.acceptDevice(p) }) { Text("Accept") }
                TextButton(onClick = { vm.dismissDevice(p) }) { Text("Not now") }
            }
        }
    }
}

@Composable
private fun PendingFolderCard(p: PendingFolder, onAccept: () -> Unit, onDismiss: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(18.dp)) {
            Text("${p.deviceName} wants to share “${p.label}”", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text("Accept to keep a copy of this folder on your phone.", style = MaterialTheme.typography.bodyMedium)
            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onAccept) { Text("Accept") }
                TextButton(onClick = onDismiss) { Text("Not now") }
            }
        }
    }
}

@Composable
private fun EmptyCard(title: String, detail: String, action: String, onClick: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(18.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FilledTonalButton(onClick = onClick, modifier = Modifier.padding(top = 10.dp)) { Text(action) }
        }
    }
}

@Composable
fun FolderCard(f: Folder, onClick: () -> Unit) {
    ElevatedCard(onClick = onClick, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBubble(painterResource(R.drawable.ic_folder), MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(f.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(shortPath(f.path), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.width(8.dp))
                StateChip(f)
            }
            if (f.syncing) {
                Spacer(Modifier.height(14.dp))
                LinearProgressIndicator(progress = { f.progress }, modifier = Modifier.fillMaxWidth())
                Text(
                    "${plural(f.needFiles, "file")} left · ${bytes(f.needBytes)}",
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

fun folderStateLabel(f: Folder): Pair<String, Tone> = when {
    f.paused -> "Paused" to Tone.Off
    f.hasProblem -> "Problem" to Tone.Bad
    f.syncing -> "Syncing" to Tone.Busy
    f.scanning -> "Checking" to Tone.Busy
    f.state.startsWith("sync") -> "Syncing" to Tone.Busy
    f.state == "idle" -> "Up to date" to Tone.Good
    f.state == "unknown" -> "Starting" to Tone.Off
    else -> f.state.replace('-', ' ').replaceFirstChar { it.uppercase() } to Tone.Busy
}

@Composable
fun StateChip(f: Folder) {
    val (label, tone) = folderStateLabel(f)
    val cs = MaterialTheme.colorScheme
    val (bg, fg) = when (tone) {
        Tone.Good -> Good.copy(alpha = 0.14f) to Good
        Tone.Busy -> cs.tertiaryContainer to cs.onTertiaryContainer
        Tone.Bad -> cs.errorContainer to cs.onErrorContainer
        else -> cs.surfaceContainerHighest to cs.onSurfaceVariant
    }
    Text(
        label, color = fg, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.background(bg, RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

@Composable
fun DeviceCard(d: Device, onClick: () -> Unit) {
    ElevatedCard(onClick = onClick, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBubble(
                painterResource(if (looksLikePhone(d.name)) R.drawable.ic_phone else R.drawable.ic_laptop),
                MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(d.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Dot(if (d.connected) Good else MaterialTheme.colorScheme.outline)
                    Spacer(Modifier.width(6.dp))
                    Text(deviceStatus(d), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

fun deviceStatus(d: Device): String = when {
    d.paused -> "Paused"
    d.connected && d.completion != null && d.completion < 99.95 -> "Connected · ${d.completion.toInt()}% synced"
    d.connected -> "Connected"
    else -> ago(d.lastSeen)
}
