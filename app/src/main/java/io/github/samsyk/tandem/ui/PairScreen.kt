package io.github.samsyk.tandem.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.activity.compose.rememberLauncherForActivityResult
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import io.github.samsyk.tandem.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PairScreen(vm: MainViewModel) {
    val ctx = LocalContext.current
    val myId = vm.snapshot?.myId
    var id by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    val qr = remember(myId) { myId?.let { qrBitmap(it).asImageBitmap() } }
    val scan = rememberLauncherForActivityResult(ScanContract()) { r -> r.contents?.let { id = it.trim() } }

    Scaffold(topBar = { TopAppBar(title = { Text("Pair a device") }, navigationIcon = { BackButton(vm) }) }) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Add your other device", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(
                "On your computer, open Syncthing and choose Actions → Show ID. Then scan its code here.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = {
                    scan.launch(
                        ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                            .setPrompt("Point at the other device's code").setBeepEnabled(false).setOrientationLocked(false)
                    )
                },
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                Icon(painterResource(R.drawable.ic_scan), null, Modifier.size(20.dp))
                Spacer(Modifier.size(8.dp))
                Text("Scan its code")
            }
            OutlinedTextField(
                value = id, onValueChange = { id = it },
                label = { Text("…or type its code") },
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = name, onValueChange = { name = it },
                label = { Text("Name it (e.g. Laptop)") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                enabled = id.isNotBlank(),
                onClick = { vm.addDevice(id, name.trim().ifBlank { "My computer" }) { vm.back() } },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Add device") }

            HorizontalDivider(Modifier.padding(vertical = 12.dp))

            Text("This phone's code", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(
                "Or add this phone from your other device: scan or type this code there.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (qr != null && myId != null) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Image(
                        qr, "QR code of this phone's ID",
                        filterQuality = FilterQuality.None,
                        modifier = Modifier.size(232.dp).background(Color.White, RoundedCornerShape(20.dp)).padding(14.dp),
                    )
                }
                Text(
                    myId, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
                    OutlinedButton(onClick = {
                        ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Device ID", myId))
                        vm.message = "Copied"
                    }) { Text("Copy") }
                    OutlinedButton(onClick = {
                        ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, myId), "Share code"))
                    }) {
                        Icon(Icons.Default.Share, null, Modifier.size(18.dp)); Spacer(Modifier.size(6.dp)); Text("Share")
                    }
                }
            } else {
                Text("Turn sync on to see this phone's code.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}
