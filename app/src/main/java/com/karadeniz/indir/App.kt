package com.karadeniz.indir

import android.app.Application
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

// Mehmet Karadeniz, 2026-2027
class App : Application() {
    /** yt-dlp ve ffmpeg açıldığında tamamlanır; hata varsa mesajı taşır. */
    val ready = CompletableDeferred<String?>()

    override fun onCreate() {
        super.onCreate()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            val err = try {
                YoutubeDL.getInstance().init(this@App)
                FFmpeg.getInstance().init(this@App)
                null
            } catch (e: Throwable) {
                e.message ?: e.toString()
            }
            ready.complete(err)
        }
    }
}
