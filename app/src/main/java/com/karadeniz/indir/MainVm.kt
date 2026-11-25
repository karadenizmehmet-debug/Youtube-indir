package com.karadeniz.indir

import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

enum class Mode { VIDEO, AUDIO }

/** 0 = en iyi kalite */
val QUALITIES = listOf(0, 1080, 720, 480)

data class Done(val title: String, val uri: Uri, val mime: String)

class MainVm(app: Application) : AndroidViewModel(app) {
    private val ctx: Context get() = getApplication()
    private val prefs = app.getSharedPreferences("ayar", Context.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())

    var url by mutableStateOf("")
    var mode by mutableStateOf(Mode.VIDEO)
    var quality by mutableStateOf(prefs.getInt("kalite", 1080))
        private set
    var dark by mutableStateOf(prefs.getBoolean("gece", true))
        private set

    var engineReady by mutableStateOf(false)
        private set
    var engineVersion by mutableStateOf("")
        private set
    var updating by mutableStateOf(false)
        private set

    var busy by mutableStateOf(false)
        private set
    var progress by mutableStateOf(-1f)
        private set
    var status by mutableStateOf("Motor hazırlanıyor…")
        private set
    var error by mutableStateOf(false)
        private set

    val done = mutableStateListOf<Done>()

    private var procId: String? = null
    private var canceled = false

    init {
        viewModelScope.launch {
            val err = (ctx.applicationContext as App).ready.await()
            if (err != null) {
                setStatus("Motor açılamadı: $err", true)
                return@launch
            }
            engineReady = true
            refreshVersion()
            setStatus("Hazır. Linki yapıştır.", false)
            // YouTube sık değişir: yt-dlp'yi günde bir kez kendiliğinden güncelle
            val last = prefs.getLong("sonGuncelleme", 0L)
            if (System.currentTimeMillis() - last > 24 * 3600 * 1000L) updateEngine(silent = true)
        }
    }

    fun toggleDark() {
        dark = !dark
        prefs.edit().putBoolean("gece", dark).apply()
    }

    fun pickQuality(q: Int) {
        quality = q
        prefs.edit().putInt("kalite", q).apply()
    }

    fun updateEngine(silent: Boolean = false) {
        if (updating || busy || !engineReady) return
        updating = true
        if (!silent) setStatus("yt-dlp güncelleniyor…", false)
        viewModelScope.launch {
            try {
                val r = withContext(Dispatchers.IO) {
                    YoutubeDL.getInstance().updateYoutubeDL(ctx, YoutubeDL.UpdateChannel.STABLE)
                }
                prefs.edit().putLong("sonGuncelleme", System.currentTimeMillis()).apply()
                refreshVersion()
                if (!silent) {
                    val msg = if (r == YoutubeDL.UpdateStatus.ALREADY_UP_TO_DATE) "zaten güncel" else "güncellendi"
                    setStatus("yt-dlp $msg ($engineVersion)", false)
                }
            } catch (e: Throwable) {
                if (!silent) setStatus("Güncelleme başarısız: ${e.message}", true)
            } finally {
                updating = false
            }
        }
    }

    private suspend fun refreshVersion() {
        engineVersion = withContext(Dispatchers.IO) {
            runCatching { YoutubeDL.getInstance().version(ctx) }.getOrNull() ?: ""
        }
    }

    fun cancel() {
        val id = procId ?: return
        canceled = true
        runCatching { YoutubeDL.getInstance().destroyProcessById(id) }
    }

    fun download() {
        if (busy || !engineReady) return
        val link = extractUrl(url)
        if (link == null) {
            setStatus("Geçerli bir link bulunamadı.", true)
            return
        }
        val id = UUID.randomUUID().toString()
        procId = id
        canceled = false
        busy = true
        progress = -1f
        setStatus("Bağlanıyor…", false)
        val audio = mode == Mode.AUDIO
        val q = quality

        viewModelScope.launch {
            val dir = File(ctx.cacheDir, "dl/$id").apply { mkdirs() }
            try {
                val req = YoutubeDLRequest(link).apply {
                    addOption("--no-playlist")
                    addOption("--no-mtime")
                    addOption("--newline")
                    addOption("-o", File(dir, "%(title).80B.%(ext)s").absolutePath)
                    if (audio) {
                        addOption("-f", "ba/b")
                        addOption("-x")
                        addOption("--audio-format", "mp3")
                        addOption("--audio-quality", "0")
                    } else {
                        addOption("-f", videoFormat(q))
                        addOption("--merge-output-format", "mp4")
                    }
                }
                withContext(Dispatchers.IO) {
                    YoutubeDL.getInstance().execute(req, id) { p, _, line ->
                        main.post {
                            if (p >= 0f) progress = p / 100f
                            val l = line.trim()
                            if (l.isNotEmpty()) status = shorten(l)
                        }
                    }
                }
                setStatus("Galeriye kaydediliyor…", false)
                val saved = withContext(Dispatchers.IO) { saveToMedia(dir, audio) }
                done.add(0, saved)
                setStatus("✓ İndirildi: ${saved.title}", false)
                url = ""
            } catch (e: Throwable) {
                if (canceled) setStatus("İptal edildi.", false)
                else setStatus(friendlyError(e.message ?: e.toString()), true)
            } finally {
                withContext(Dispatchers.IO) { dir.deleteRecursively() }
                busy = false
                progress = -1f
                procId = null
            }
        }
    }

    private fun saveToMedia(dir: File, audio: Boolean): Done {
        val f = dir.listFiles()
            ?.filter { it.isFile && !it.name.endsWith(".part") && !it.name.endsWith(".ytdl") }
            ?.maxByOrNull { it.length() }
            ?: throw IllegalStateException("İndirilen dosya bulunamadı")
        val ext = f.extension.lowercase()
        val mime = when (ext) {
            "mp3" -> "audio/mpeg"
            "m4a" -> "audio/mp4"
            "mp4" -> "video/mp4"
            "webm" -> "video/webm"
            "mkv" -> "video/x-matroska"
            "jpg", "jpeg" -> "image/jpeg"
            else -> if (audio) "audio/*" else "video/*"
        }
        val (collection, folder) = when {
            mime.startsWith("audio") -> MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to Environment.DIRECTORY_MUSIC
            mime.startsWith("image") -> MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to Environment.DIRECTORY_PICTURES
            else -> MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to Environment.DIRECTORY_MOVIES
        }
        val resolver = ctx.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, f.name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "$folder/İndir")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: throw IllegalStateException("Galeriye yazılamadı")
        resolver.openOutputStream(uri)?.use { out -> f.inputStream().use { it.copyTo(out) } }
            ?: throw IllegalStateException("Galeriye yazılamadı")
        values.clear()
        values.put(MediaStore.MediaColumns.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return Done(f.nameWithoutExtension, uri, mime)
    }

    private fun setStatus(s: String, err: Boolean) {
        status = s
        error = err
    }

    companion object {
        private val URL_RE = Regex("""https?://\S+""")

        fun extractUrl(text: String): String? = URL_RE.find(text)?.value?.trimEnd('.', ',', ')', '"')

        fun videoFormat(q: Int): String =
            if (q <= 0) "bv*[ext=mp4]+ba[ext=m4a]/bv*+ba/b"
            else "bv*[height<=$q][ext=mp4]+ba[ext=m4a]/bv*[height<=$q]+ba/b[height<=$q]/b"

        private fun shorten(l: String) = if (l.length > 140) l.take(140) + "…" else l

        fun friendlyError(raw: String): String {
            val line = raw.lines().lastOrNull { it.contains("ERROR") }?.substringAfter("ERROR:")?.trim()
                ?: raw.lines().lastOrNull { it.isNotBlank() }?.trim() ?: raw
            val hint = when {
                line.contains("login", true) || line.contains("cookies", true) ->
                    "\nBu içerik giriş gerektiriyor (gizli hesap / yaş sınırı)."
                line.contains("Unsupported URL", true) -> "\nBu site desteklenmiyor."
                line.contains("Sign in to confirm", true) -> "\nYouTube doğrulama istedi; motoru güncelleyip tekrar dene."
                else -> "\nMotoru güncelleyip tekrar dene."
            }
            return "Hata: ${shorten(line)}$hint"
        }
    }
}
