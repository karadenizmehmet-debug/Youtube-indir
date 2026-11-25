package com.karadeniz.indir

import android.annotation.SuppressLint
import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import kotlinx.coroutines.delay
import java.math.BigInteger
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.coroutines.resume

// Mehmet Karadeniz, 2026-2027

data class IgItem(val index: Int, val thumb: String, val full: String, val isVideo: Boolean)

object Ig {
    private const val APP_ID = "936619743392459"
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
    private val CODE_RE = Regex("""instagram\.com/(?:[^/?#]+/)?(?:p|reel|reels|tv)/([A-Za-z0-9_-]+)""")

    fun shortcode(url: String): String? = CODE_RE.find(url)?.groupValues?.get(1)

    /** Gönderi kodu (DQx…) -> sayısal medya kimliği */
    fun mediaId(code: String): String {
        val c = if (code.length > 28) code.dropLast(28) else code
        var n = BigInteger.ZERO
        val b = BigInteger.valueOf(64)
        for (ch in c) n = n.multiply(b).add(BigInteger.valueOf(ALPHABET.indexOf(ch).toLong()))
        return n.toString()
    }

    fun isLoggedIn(): Boolean =
        CookieManager.getInstance().getCookie("https://www.instagram.com")?.contains("sessionid=") == true

    fun logout() {
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
    }

    /** API yanıtındaki görselleri ve videoları çıkarır */
    fun parse(json: String): List<IgItem> {
        val item = JSONObject(json).getJSONArray("items").getJSONObject(0)
        val nodes = item.optJSONArray("carousel_media")?.let { arr -> (0 until arr.length()).map { arr.getJSONObject(it) } }
            ?: listOf(item)
        return nodes.mapIndexed { i, n ->
            val cands = n.getJSONObject("image_versions2").getJSONArray("candidates")
            val big = cands.getJSONObject(0).getString("url")
            // küçük önizleme: genişliği 320-640 arası olan ilk aday, yoksa en büyüğü
            var thumb = big
            for (k in 0 until cands.length()) {
                val c = cands.getJSONObject(k)
                if (c.optInt("width") in 300..700) { thumb = c.getString("url"); break }
            }
            val video = n.optInt("media_type") == 2
            val full = if (video) n.getJSONArray("video_versions").getJSONObject(0).getString("url") else big
            IgItem(i + 1, thumb, full, video)
        }
    }

    fun parseList(json: String): List<IgItem> {
        val arr = JSONArray(json)
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            IgItem(it + 1, o.getString("thumb"), o.getString("full"), o.optBoolean("video"))
        }
    }

    /** Sayfadaki script bloklarından gönderinin medyasını bulan JS (giriş gerekmez) */
    private fun extractJs(code: String, mediaId: String) = """
        (function(code, pk){
          function norm(item){
            var nodes = item.carousel_media || [item];
            return nodes.map(function(n){
              var c = (n.image_versions2||{}).candidates||[]; if(!c.length) return null;
              var big=c[0].url, th=big;
              for(var k=0;k<c.length;k++){ if(c[k].width>=300&&c[k].width<=700){th=c[k].url;break;} }
              var v = n.media_type==2 && n.video_versions && n.video_versions.length;
              return {thumb:th, full: v? n.video_versions[0].url : big, video: !!v};
            }).filter(Boolean);
          }
          function normGql(m){
            var nodes = m.edge_sidecar_to_children ? m.edge_sidecar_to_children.edges.map(function(e){return e.node;}) : [m];
            return nodes.map(function(n){
              var r=n.display_resources||[], th=n.display_url;
              for(var k=0;k<r.length;k++){ if(r[k].config_width>=300&&r[k].config_width<=700){th=r[k].src;break;} }
              return {thumb:th, full: (n.is_video&&n.video_url)? n.video_url : n.display_url, video: !!n.is_video};
            }).filter(function(x){return x.full;});
          }
          var found=null, seen=0;
          function isMe(o){ return o.code===code || o.shortcode===code || String(o.pk)===pk || (typeof o.id==='string' && o.id.indexOf(pk)===0); }
          function walk(o,d){
            if(found||!o||typeof o!=='object'||d>60||++seen>300000) return;
            if(Array.isArray(o)){ for(var i=0;i<o.length;i++) walk(o[i],d+1); return; }
            if(isMe(o)){
              if(o.image_versions2 || o.carousel_media){ var a=norm(o); if(a.length){found=a;return;} }
              if(o.display_url){ var b=normGql(o); if(b.length){found=b;return;} }
            }
            for(var k in o) walk(o[k],d+1);
          }
          var ss=document.querySelectorAll('script');
          for(var i=0;i<ss.length&&!found;i++){
            var t=ss[i].textContent; if(!t||t.indexOf(code)<0&&t.indexOf(pk)<0) continue;
            var tt=t.trim();
            if(tt[0]==='{'||tt[0]==='['){ try{ walk(JSON.parse(tt),0); }catch(e){} }
            if(!found){
              var m=t.match(/"contextJSON":"((?:\\.|[^"\\])*)"/);
              if(m){ try{ walk(JSON.parse(JSON.parse('"'+m[1]+'"')),0); }catch(e){} }
            }
          }
          // Son çare: yalnız tek görselli gönderiler için sayfadaki büyük resim
          if(!found){
            var og=document.querySelector('meta[property="og:image"]');
            if(og && og.content && location.href.indexOf('/embed')>0){
              var img=document.querySelector('img.EmbeddedMediaImage');
              var u=(img&&img.src)||og.content; found=[{thumb:u, full:u, video:false}];
            }
          }
          return found ? JSON.stringify(found) : '';
        })('$code', '$mediaId');
    """.trimIndent()

    /** Görünmez bir WebView içinde gönderiyi açar ve medyayı okur. */
    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    class Client(context: Context) {
        val web = WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            // Masaüstü görünümü: Instagram girişsiz gönderiyi bu görünümde açıyor
            settings.userAgentString =
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    currentUrl = url
                    pendingLoad?.invoke(); pendingLoad = null
                }
            }
            addJavascriptInterface(Bridge(), "IgBridge")
        }
        private var currentUrl: String? = null
        private var pendingLoad: (() -> Unit)? = null
        private var pendingResult: ((Int, String) -> Unit)? = null
        private val main = Handler(Looper.getMainLooper())

        inner class Bridge {
            @JavascriptInterface
            fun done(status: Int, body: String) {
                pendingResult?.invoke(status, body); pendingResult = null
            }
        }

        private suspend fun load(url: String) {
            withTimeout(30_000) {
                suspendCancellableCoroutine { cont ->
                    main.post {
                        pendingLoad = { if (cont.isActive) cont.resume(Unit) }
                        web.loadUrl(url)
                    }
                }
            }
        }

        private suspend fun eval(js: String): String = suspendCancellableCoroutine { cont ->
            main.post {
                web.evaluateJavascript(js) { v ->
                    val s = runCatching { JSONTokener(v).nextValue() as? String }.getOrNull() ?: ""
                    if (cont.isActive) cont.resume(s)
                }
            }
        }

        /** Sayfayı açar, veri gelene kadar birkaç kez dener. */
        private suspend fun scrape(url: String, code: String, pk: String): List<IgItem> {
            load(url)
            repeat(10) {
                val r = eval(extractJs(code, pk))
                if (r.isNotEmpty()) return parseList(r)
                delay(800)
            }
            return emptyList()
        }

        /** Girişliyse resmi API; değilse gönderi sayfası, sonra gömülü (embed) sayfa. */
        suspend fun fetchPost(code: String): List<IgItem> {
            val pk = mediaId(code)
            if (isLoggedIn()) {
                runCatching {
                    load("https://www.instagram.com/")
                    val (st, body) = apiInfo(pk)
                    if (st == 200) return parse(body)
                }
            }
            scrape("https://www.instagram.com/p/$code/", code, pk).let { if (it.isNotEmpty()) return it }
            scrape("https://www.instagram.com/p/$code/embed/captioned/", code, pk).let { if (it.isNotEmpty()) return it }
            return emptyList()
        }

        private suspend fun apiInfo(mediaId: String): Pair<Int, String> = withTimeout(30_000) {
            suspendCancellableCoroutine { cont ->
                main.post {
                    pendingResult = { s, b -> if (cont.isActive) cont.resume(s to b) }
                    web.evaluateJavascript(
                        """
                        (function(){
                          fetch('/api/v1/media/$mediaId/info/', {credentials:'include', headers:{
                            'X-IG-App-ID':'$APP_ID','X-ASBD-ID':'359341','X-Requested-With':'XMLHttpRequest'}})
                          .then(function(r){ return r.text().then(function(t){ IgBridge.done(r.status, t); }); })
                          .catch(function(e){ IgBridge.done(-1, String(e)); });
                        })();
                        """.trimIndent(), null
                    )
                }
            }
        }

        fun reset() { currentUrl = null }
    }
}

class InstaVm(app: Application) : AndroidViewModel(app) {
    private val ctx: Context get() = getApplication()

    var url by mutableStateOf("")
    var loggedIn by mutableStateOf(Ig.isLoggedIn())
        private set
    var busy by mutableStateOf(false)
        private set
    var progress by mutableStateOf(-1f)
        private set
    var status by mutableStateOf("")
        private set
    var error by mutableStateOf(false)
        private set
    var code by mutableStateOf("")
        private set

    val items = mutableStateListOf<IgItem>()
    val selected = mutableStateListOf<Int>()
    var lastZip by mutableStateOf<Done?>(null)
        private set

    fun refreshLogin(client: Ig.Client) {
        CookieManager.getInstance().flush()
        loggedIn = Ig.isLoggedIn()
        client.reset()
        setStatus(if (loggedIn) "Instagram girişi tamam." else "Giriş yapılmadı.", !loggedIn)
    }

    fun logout(client: Ig.Client) {
        Ig.logout()
        client.reset()
        loggedIn = false
        items.clear(); selected.clear()
        setStatus("Instagram oturumu kapatıldı.", false)
    }

    fun toggle(i: Int) {
        if (i in selected) selected.remove(i) else selected.add(i)
    }

    fun selectAll() {
        if (selected.size == items.size) selected.clear()
        else { selected.clear(); selected.addAll(items.map { it.index }) }
    }

    fun fetch(client: Ig.Client) {
        if (busy) return
        val c = Ig.shortcode(url)
        if (c == null) { setStatus("Instagram gönderi linki değil (instagram.com/p/…)", true); return }
        busy = true; progress = -1f
        items.clear(); selected.clear(); lastZip = null
        setStatus("Gönderi getiriliyor…", false)
        viewModelScope.launch {
            try {
                val list = client.fetchPost(c)
                if (list.isEmpty()) {
                    setStatus(
                        if (loggedIn) "Gönderi okunamadı. Gizli hesap veya silinmiş gönderi olabilir."
                        else "Bu gönderi girişsiz açılmadı (gizli hesap olabilir). İstersen aşağıdan giriş yapıp tekrar dene.",
                        true
                    )
                } else {
                    code = c
                    items.addAll(list)
                    selected.addAll(list.filter { !it.isVideo }.map { it.index })
                    setStatus("${list.size} öğe bulundu. İndireceklerini seç.", false)
                }
            } catch (e: Throwable) {
                setStatus("Getirilemedi: ${e.message ?: e}", true)
            } finally {
                busy = false
            }
        }
    }

    fun downloadZip() {
        if (busy || selected.isEmpty()) return
        val chosen = items.filter { it.index in selected }.sortedBy { it.index }
        busy = true; progress = 0f
        setStatus("ZIP hazırlanıyor…", false)
        viewModelScope.launch {
            try {
                val done = withContext(Dispatchers.IO) { writeZip(chosen) }
                lastZip = done
                setStatus("✓ ${chosen.size} dosya ZIP'lendi: İndirilenler/İndir/${done.title}.zip", false)
            } catch (e: Throwable) {
                setStatus("ZIP oluşturulamadı: ${e.message ?: e}", true)
            } finally {
                busy = false; progress = -1f
            }
        }
    }

    private fun writeZip(chosen: List<IgItem>): Done {
        val name = "instagram_$code"
        val resolver = ctx.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "$name.zip")
            put(MediaStore.MediaColumns.MIME_TYPE, "application/zip")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/İndir")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri: Uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("İndirilenler klasörüne yazılamadı")
        try {
            resolver.openOutputStream(uri)!!.use { os ->
                ZipOutputStream(os.buffered()).use { zip ->
                    chosen.forEachIndexed { i, it ->
                        val ext = if (it.isVideo) "mp4" else "jpg"
                        zip.putNextEntry(ZipEntry("${name}_%02d.$ext".format(it.index)))
                        val con = URL(it.full).openConnection() as HttpURLConnection
                        con.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 Chrome/131 Mobile Safari/537.36")
                        con.connectTimeout = 20_000; con.readTimeout = 60_000
                        con.inputStream.use { inp -> inp.copyTo(zip) }
                        zip.closeEntry()
                        val p = (i + 1f) / chosen.size
                        Handler(Looper.getMainLooper()).post { progress = p; status = "İndiriliyor ${i + 1}/${chosen.size}" }
                    }
                }
            }
        } catch (e: Throwable) {
            resolver.delete(uri, null, null)
            throw e
        }
        values.clear()
        values.put(MediaStore.MediaColumns.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return Done(name, uri, "application/zip")
    }

    private fun setStatus(s: String, err: Boolean) { status = s; error = err }
}

@Composable
fun InstaTab(
    vm: InstaVm,
    client: Ig.Client,
    accent: Color,
    onShowLogin: () -> Unit,
    onOpen: (Done) -> Unit,
    onShare: (Done) -> Unit,
) {
    val clip = LocalClipboardManager.current
    val fg = MaterialTheme.colorScheme.onBackground

    // API çağrısını yapan görünmez WebView ekrana bağlı kalsın
    AndroidView(factory = { (client.web.parent as? android.view.ViewGroup)?.removeView(client.web); client.web },
        modifier = Modifier.size(1.dp).alpha(0f))

    OutlinedTextField(
        value = vm.url,
        onValueChange = { vm.url = it },
        modifier = Modifier.fillMaxWidth(),
        label = { Text("Instagram gönderi linki") },
        placeholder = { Text("https://www.instagram.com/p/…") },
        singleLine = true,
        enabled = !vm.busy,
        trailingIcon = {
            TextButton(onClick = {
                val t = clip.getText()?.text.orEmpty()
                vm.url = MainVm.extractUrl(t) ?: t
            }, enabled = !vm.busy) { Text("Yapıştır") }
        }
    )
    Button(
        onClick = { vm.fetch(client) },
        enabled = !vm.busy && vm.url.isNotBlank(),
        modifier = Modifier.fillMaxWidth().height(48.dp),
        colors = ButtonDefaults.buttonColors(containerColor = accent)
    ) { Text("Görselleri getir", color = Color.White) }

    if (vm.busy) {
        if (vm.progress >= 0f) LinearProgressIndicator(progress = { vm.progress }, modifier = Modifier.fillMaxWidth())
        else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    }
    if (vm.status.isNotEmpty()) {
        Text(vm.status, fontSize = 13.sp, color = if (vm.error) Color(0xFFEF5350) else fg.copy(alpha = 0.75f))
    }

    if (vm.items.isNotEmpty()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${vm.selected.size}/${vm.items.size} seçili", color = fg, fontWeight = FontWeight.Medium)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = vm::selectAll, enabled = !vm.busy) {
                Text(if (vm.selected.size == vm.items.size) "Seçimi kaldır" else "Tümünü seç")
            }
        }
        vm.items.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { item -> Thumb(item, item.index in vm.selected, accent, Modifier.weight(1f)) { if (!vm.busy) vm.toggle(item.index) } }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        Button(
            onClick = vm::downloadZip,
            enabled = !vm.busy && vm.selected.isNotEmpty(),
            modifier = Modifier.fillMaxWidth().height(52.dp),
            colors = ButtonDefaults.buttonColors(containerColor = accent)
        ) { Text("Seçilenleri ZIP indir (${vm.selected.size})", color = Color.White, fontSize = 16.sp) }
    }
    vm.lastZip?.let { z ->
        Row {
            OutlinedButton(onClick = { onOpen(z) }) { Text("ZIP'i aç") }
            Spacer(Modifier.size(8.dp))
            OutlinedButton(onClick = { onShare(z) }) { Text("Paylaş") }
        }
    }
    Spacer(Modifier.height(4.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (vm.loggedIn) "● Instagram'a girişli" else "Giriş gerekmez. Gizli hesaplar için isteğe bağlı giriş:",
            color = if (vm.loggedIn) Color(0xFF43A047) else fg.copy(alpha = 0.6f), fontSize = 12.sp,
            modifier = Modifier.weight(1f)
        )
        if (vm.loggedIn) TextButton(onClick = { vm.logout(client) }) { Text("Çıkış") }
        else TextButton(onClick = onShowLogin) { Text("Giriş yap") }
    }
}

@Composable
private fun Thumb(item: IgItem, checked: Boolean, accent: Color, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(8.dp))
            .border(if (checked) BorderStroke(3.dp, accent) else BorderStroke(1.dp, Color.Gray.copy(alpha = 0.4f)), RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
    ) {
        AsyncImage(model = item.thumb, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        Box(
            Modifier.align(Alignment.TopEnd).padding(6.dp).size(24.dp).clip(CircleShape)
                .background(if (checked) accent else Color.Black.copy(alpha = 0.45f)),
            contentAlignment = Alignment.Center
        ) { Text(if (checked) "✓" else "${item.index}", color = Color.White, fontSize = 12.sp) }
        if (item.isVideo) {
            Text("▶ video", color = Color.White, fontSize = 11.sp,
                modifier = Modifier.align(Alignment.BottomStart).padding(6.dp)
                    .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(4.dp)).padding(horizontal = 4.dp))
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LoginDialog(onDone: () -> Unit) {
    var closed = false
    val close = { if (!closed) { closed = true; onDone() } }
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Instagram girişi", fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 8.dp),
                    color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = close) { Text("Bitti") }
            }
            AndroidView(factory = { c ->
                WebView(c).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    CookieManager.getInstance().setAcceptCookie(true)
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            // Giriş tamamlanınca kendiliğinden kapan
                            if (Ig.isLoggedIn() && url?.contains("/accounts/") != true) close()
                        }
                    }
                    loadUrl("https://www.instagram.com/accounts/login/")
                }
            }, modifier = Modifier.fillMaxSize())
        }
    }
}
