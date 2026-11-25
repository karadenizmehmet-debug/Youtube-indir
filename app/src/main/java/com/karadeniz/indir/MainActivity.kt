package com.karadeniz.indir

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Mehmet Karadeniz, 2026-2027
class MainActivity : ComponentActivity() {
    private val vm: MainVm by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleShare(intent)
        setContent { Screen(vm, ::shareApp, ::openFile, ::shareFile) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    private fun handleShare(i: Intent?) {
        if (i?.action != Intent.ACTION_SEND) return
        val text = i.getStringExtra(Intent.EXTRA_TEXT) ?: return
        MainVm.extractUrl(text)?.let { vm.url = it }
    }

    private fun shareApp() {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(
                Intent.EXTRA_TEXT,
                "İndir — YouTube, Instagram ve daha fazlasından video indir:\n" +
                    "https://github.com/karadenizmehmet-debug/youtube-indir/releases/latest"
            )
        }
        startActivity(Intent.createChooser(send, "Uygulamayı paylaş"))
    }

    private fun openFile(d: Done) {
        val i = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(d.uri, d.mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { startActivity(i) }
    }

    private fun shareFile(d: Done) {
        val i = Intent(Intent.ACTION_SEND).apply {
            type = d.mime
            putExtra(Intent.EXTRA_STREAM, d.uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(i, "Paylaş"))
    }
}

private val Accent = Color(0xFFE53935)

@Composable
private fun Screen(
    vm: MainVm,
    onShareApp: () -> Unit,
    onOpen: (Done) -> Unit,
    onShareFile: (Done) -> Unit,
) {
    val scheme = if (vm.dark) {
        darkColorScheme(primary = Accent, background = Color(0xFF111418), surface = Color(0xFF1A1E24))
    } else {
        lightColorScheme(primary = Accent, background = Color(0xFFF6F7F9), surface = Color.White)
    }
    // İndirme sürerken ekran kapanmasın
    val view = LocalView.current
    SideEffect { view.keepScreenOn = vm.busy }

    MaterialTheme(colorScheme = scheme) {
        Column(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .safeDrawingPadding()
                .imePadding()
        ) {
            Header(vm, onShareApp)
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                LinkBox(vm)
                ModePicker(vm)
                ActionArea(vm)
                if (vm.done.isNotEmpty()) {
                    Text("İndirilenler", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
                    vm.done.forEach { DoneCard(it, onOpen, onShareFile) }
                }
                Footer(vm)
            }
        }
    }
}

@Composable
private fun Header(vm: MainVm, onShareApp: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("İndir", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.width(6.dp))
        Text("v${BuildConfig.VERSION_NAME}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        Spacer(Modifier.weight(1f))
        IconButton(onClick = vm::toggleDark) {
            Text(if (vm.dark) "☀️" else "🌙", fontSize = 20.sp)
        }
        IconButton(onClick = onShareApp) {
            Icon(Icons.Filled.Share, contentDescription = "Paylaş", tint = MaterialTheme.colorScheme.onSurface)
        }
    }
}

@Composable
private fun LinkBox(vm: MainVm) {
    val clip = LocalClipboardManager.current
    OutlinedTextField(
        value = vm.url,
        onValueChange = { vm.url = it },
        modifier = Modifier.fillMaxWidth(),
        label = { Text("Video linki") },
        placeholder = { Text("https://youtu.be/… veya instagram.com/reel/…") },
        singleLine = true,
        enabled = !vm.busy,
        trailingIcon = {
            TextButton(onClick = {
                val t = clip.getText()?.text.orEmpty()
                vm.url = MainVm.extractUrl(t) ?: t
            }, enabled = !vm.busy) { Text(if (vm.url.isEmpty()) "Yapıştır" else "Değiştir") }
        }
    )
}

@Composable
private fun ModePicker(vm: MainVm) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = vm.mode == Mode.VIDEO, onClick = { vm.mode = Mode.VIDEO }, label = { Text("🎬 Video (MP4)") }, enabled = !vm.busy)
        FilterChip(selected = vm.mode == Mode.AUDIO, onClick = { vm.mode = Mode.AUDIO }, label = { Text("🎵 Ses (MP3)") }, enabled = !vm.busy)
    }
    if (vm.mode == Mode.VIDEO) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            QUALITIES.forEach { q ->
                FilterChip(
                    selected = vm.quality == q,
                    onClick = { vm.pickQuality(q) },
                    label = { Text(if (q == 0) "En iyi" else "${q}p") },
                    enabled = !vm.busy
                )
            }
        }
    }
}

@Composable
private fun ActionArea(vm: MainVm) {
    if (vm.busy) {
        OutlinedButton(onClick = vm::cancel, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("İptal") }
        if (vm.progress >= 0f) {
            LinearProgressIndicator(progress = { vm.progress }, modifier = Modifier.fillMaxWidth())
            Text("%${(vm.progress * 100).toInt()}", color = MaterialTheme.colorScheme.onBackground)
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
    } else {
        Button(
            onClick = vm::download,
            enabled = vm.engineReady && vm.url.isNotBlank() && !vm.updating,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Accent)
        ) { Text("İndir", fontSize = 18.sp, color = Color.White) }
    }
    Text(
        vm.status,
        fontSize = 13.sp,
        color = if (vm.error) Color(0xFFEF5350) else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.75f)
    )
}

@Composable
private fun DoneCard(d: Done, onOpen: (Done) -> Unit, onShare: (Done) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(d.title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
            Row {
                TextButton(onClick = { onOpen(d) }) { Text("Aç") }
                TextButton(onClick = { onShare(d) }) { Text("Paylaş") }
            }
        }
    }
}

@Composable
private fun Footer(vm: MainVm) {
    val dim = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f)
    Spacer(Modifier.height(8.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Motor: yt-dlp ${vm.engineVersion.ifEmpty { "—" }}", fontSize = 12.sp, color = dim)
        Spacer(Modifier.weight(1f))
        TextButton(onClick = { vm.updateEngine() }, enabled = vm.engineReady && !vm.busy && !vm.updating) {
            Text(if (vm.updating) "Güncelleniyor…" else "Motoru güncelle", fontSize = 12.sp)
        }
    }
    Text("Dosyalar: Filmler/İndir ve Müzik/İndir klasörü", fontSize = 12.sp, color = dim)
    Text("Yalnızca indirme hakkın olan içerikler için kullan.", fontSize = 12.sp, color = dim)
    Text("© Mehmet Karadeniz, 2026-2027", fontSize = 12.sp, color = dim)
}
