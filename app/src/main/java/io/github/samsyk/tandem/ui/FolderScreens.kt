package io.github.samsyk.tandem.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.samsyk.tandem.R
import io.github.samsyk.tandem.engine.PendingFolder
import java.io.File

@Composable
private fun rememberFolderPicker(vm: MainViewModel, onPicked: (String) -> Unit) =
    rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) {
            val p = treeToPath(uri)
            if (p == null) vm.message = "Pick a folder in the phone's storage or SD card" else onPicked(p)
        }
    }

@Composable
fun AcceptFolderDialog(p: PendingFolder, vm: MainViewModel, onDone: () -> Unit) {
    var path by rememberSaveable { mutableStateOf("/storage/emulated/0/Documents/" + safeName(p.label)) }
    val picker = rememberFolderPicker(vm) { path = it }
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text("Where should “${p.label}” go?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Files from ${p.deviceName} will be kept in this folder on your phone:")
                OutlinedCard(shape = RoundedCornerShape(14.dp)) {
                    Text(shortPath(path), Modifier.padding(14.dp), fontWeight = FontWeight.Medium)
                }
                TextButton(onClick = { picker.launch(null) }) { Text("Choose a different folder") }
                Text(
                    "If that folder already has files, they'll be combined with the shared ones.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { Button(onClick = { vm.acceptFolder(p, path); onDone() }) { Text("Start syncing") } },
        dismissButton = { TextButton(onClick = onDone) { Text("Cancel") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddFolderScreen(vm: MainViewModel) {
    val s = vm.snapshot
    var path by rememberSaveable { mutableStateOf<String?>(null) }
    var label by rememberSaveable { mutableStateOf("") }
    val chosen = remember { mutableStateListOf<String>() }
    val picker = rememberFolderPicker(vm) { p ->
        path = p
        if (label.isBlank()) label = File(p).name
    }
    LaunchedEffect(s?.devices?.size) { if (chosen.isEmpty()) s?.devices?.forEach { chosen.add(it.id) } }

    Scaffold(topBar = { TopAppBar(title = { Text("Add a folder") }, navigationIcon = { BackButton(vm) }) }) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Which folder?", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            OutlinedCard(onClick = { picker.launch(null) }, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconBubble(painterResource(R.drawable.ic_folder), MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer, 40.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(path?.let { shortPath(it) } ?: "Tap to choose a folder", fontWeight = FontWeight.Medium)
                }
            }
            OutlinedTextField(label, { label = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())

            Text("Share it with", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (s == null || s.devices.isEmpty()) {
                Text("No devices paired yet. You can share it later.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else s.devices.forEach { d ->
                ListItem(
                    headlineContent = { Text(d.name) },
                    supportingContent = { Text(deviceStatus(d)) },
                    trailingContent = {
                        Checkbox(d.id in chosen, { on -> if (on) chosen.add(d.id) else chosen.remove(d.id) })
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
            }
            Button(
                enabled = path != null && label.isNotBlank(),
                onClick = { vm.addFolder(label.trim(), path!!, chosen.toList()) { vm.back() } },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) { Text("Start syncing") }
            Text(
                "The other device will be asked to accept the folder.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(32.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderScreen(vm: MainViewModel, id: String) {
    val s = vm.snapshot
    val f = s?.folders?.find { it.id == id }
    if (s == null || f == null) {
        LaunchedEffect(Unit) { vm.back() }
        return
    }
    var confirmRemove by remember { mutableStateOf(false) }
    var sharing by remember { mutableStateOf(false) }
    val (stateLabel, _) = folderStateLabel(f)

    Scaffold(topBar = { TopAppBar(title = { Text(f.label) }, navigationIcon = { BackButton(vm) }) }) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
                Column(Modifier.fillMaxWidth().padding(20.dp)) {
                    Text(stateLabel, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                    when {
                        f.hasProblem -> Text(
                            f.error ?: "${plural(f.pullErrors.toLong(), "file")} couldn't be synced. Check that the folder still exists and Tandem can write to it.",
                            color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 6.dp),
                        )
                        f.syncing -> {
                            LinearProgressIndicator(progress = { f.progress }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp))
                            Text("${plural(f.needFiles, "file")} left · ${bytes(f.needBytes)}", Modifier.padding(top = 6.dp))
                        }
                        f.paused -> Text("Changes aren't being synced until you resume.", Modifier.padding(top = 6.dp))
                        else -> Text("This folder matches your other devices.", Modifier.padding(top = 6.dp))
                    }
                }
            }

            SectionTitle("Details")
            Fact("Location", shortPath(f.path))
            Fact("Size", if (f.paused) "—" else "${bytes(f.globalBytes)} · ${plural(f.globalFiles, "file")}")

            SectionTitle("Shared with")
            if (f.deviceIds.isEmpty()) Text("Not shared with any device yet.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp))
            f.deviceIds.forEach { did ->
                val d = s.devices.find { it.id == did }
                Fact(d?.name ?: did.take(7), d?.let { deviceStatus(it) } ?: "Unknown device")
            }
            val others = s.devices.filter { it.id !in f.deviceIds }
            if (others.isNotEmpty()) TextButton(onClick = { sharing = true }) { Text("Share with another device") }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                FilledTonalButton(onClick = { vm.setFolderPaused(f.id, !f.paused) }) { Text(if (f.paused) "Resume" else "Pause") }
                OutlinedButton(onClick = { vm.rescan(f.id) }, enabled = !f.paused) { Text("Check for changes") }
            }
            TextButton(onClick = { confirmRemove = true }) {
                Text("Stop syncing this folder", color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(32.dp))
        }
    }

    if (confirmRemove) AlertDialog(
        onDismissRequest = { confirmRemove = false },
        title = { Text("Stop syncing “${f.label}”?") },
        text = { Text("The files stay on your phone and on your other devices. They just won't update each other anymore.") },
        confirmButton = { Button(onClick = { confirmRemove = false; vm.removeFolder(f.id) { vm.back() } }) { Text("Stop syncing") } },
        dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Cancel") } },
    )
    if (sharing) {
        val picks = remember { mutableStateListOf<String>() }
        AlertDialog(
            onDismissRequest = { sharing = false },
            title = { Text("Share “${f.label}” with") },
            text = {
                Column {
                    s.devices.filter { it.id !in f.deviceIds }.forEach { d ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(d.id in picks, { on -> if (on) picks.add(d.id) else picks.remove(d.id) })
                            Text(d.name)
                        }
                    }
                }
            },
            confirmButton = {
                Button(enabled = picks.isNotEmpty(), onClick = { sharing = false; vm.shareFolder(f.id, picks.toList()) }) { Text("Share") }
            },
            dismissButton = { TextButton(onClick = { sharing = false }) { Text("Cancel") } },
        )
    }
}

@Composable
fun Fact(title: String, value: String) {
    ListItem(
        headlineContent = { Text(title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) },
        supportingContent = { Text(value, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}
