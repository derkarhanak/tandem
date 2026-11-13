package io.github.samsyk.tandem.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.provider.DocumentsContract
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import java.time.Duration
import java.time.Instant

@Composable
fun TandemTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> darkColorScheme(primary = Color(0xFF7FD6CF), secondary = Color(0xFFB1CCC8), tertiary = Color(0xFFB0C7EA))
        else -> lightColorScheme(primary = Color(0xFF0E6E6B), secondary = Color(0xFF4A6361), tertiary = Color(0xFF48607D))
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

@Composable
fun BackButton(vm: MainViewModel) {
    IconButton(onClick = { vm.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(start = 4.dp, top = 12.dp, bottom = 2.dp),
    )
}

@Composable
fun IconBubble(painter: Painter, container: Color, content: Color, size: Dp = 44.dp) {
    Box(
        Modifier.size(size).background(container, CircleShape),
        contentAlignment = Alignment.Center,
    ) { Icon(painter, null, tint = content, modifier = Modifier.size(size * 0.5f)) }
}

@Composable
fun Dot(color: Color) {
    Box(Modifier.size(8.dp).background(color, CircleShape))
}

val Good = Color(0xFF2E9E5B)

fun bytes(n: Long): String {
    var v = n.toDouble()
    for (unit in listOf("B", "KB", "MB", "GB")) {
        if (v < 1000) return if (unit == "B" || unit == "KB" || v >= 100) "${v.toInt()} $unit" else "%.1f $unit".format(v)
        v /= 1000
    }
    return "%.1f TB".format(v)
}

fun plural(n: Long, one: String, many: String = one + "s") = "$n ${if (n == 1L) one else many}"

fun ago(t: Instant?): String {
    if (t == null) return "Never connected"
    val d = Duration.between(t, Instant.now())
    return when {
        d.toMinutes() < 1 -> "Seen just now"
        d.toMinutes() < 60 -> "Seen ${d.toMinutes()} min ago"
        d.toHours() < 24 -> "Seen ${d.toHours()} h ago"
        d.toDays() < 30 -> "Seen ${plural(d.toDays(), "day")} ago"
        else -> "Not seen for over a month"
    }
}

fun shortPath(p: String): String =
    p.removePrefix("/storage/emulated/0").trimStart('/').ifEmpty { "Phone storage" }

fun looksLikePhone(name: String) =
    Regex("phone|pixel|galaxy|moto|edge|xiaomi|redmi|oneplus|android|iphone|tablet|ipad", RegexOption.IGNORE_CASE)
        .containsMatchIn(name)

/** Turns a folder picked in Android's file picker into a real path the engine can use. */
fun treeToPath(uri: Uri): String? {
    if (uri.authority != "com.android.externalstorage.documents") return null
    val docId = DocumentsContract.getTreeDocumentId(uri)
    val parts = docId.split(":", limit = 2)
    val base = if (parts[0].equals("primary", true)) "/storage/emulated/0" else "/storage/${parts[0]}"
    val rel = parts.getOrElse(1) { "" }.trim('/')
    return if (rel.isEmpty()) base else "$base/$rel"
}

fun safeName(s: String) = s.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifEmpty { "Shared folder" }

fun qrBitmap(text: String, size: Int = 600): Bitmap {
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 1))
    val px = IntArray(size * size) { i -> if (m[i % size, i / size]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
    return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
}

object Perms {
    fun files(ctx: Context): Boolean =
        if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    fun filesIntent(ctx: Context): Intent =
        if (Build.VERSION.SDK_INT >= 30)
            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${ctx.packageName}"))
        else Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))

    fun notifications(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    fun battery(ctx: Context): Boolean =
        ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)

    @android.annotation.SuppressLint("BatteryLife")
    fun batteryIntent(ctx: Context) =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}"))
}
