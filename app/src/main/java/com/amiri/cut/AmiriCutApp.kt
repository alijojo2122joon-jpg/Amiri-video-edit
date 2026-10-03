package com.amiri.cut

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import com.amiri.cut.media.ThumbnailCache
import com.amiri.cut.media.WaveformCache
import com.amiri.cut.storage.AppSettings
import com.amiri.cut.storage.CacheManager
import com.amiri.cut.storage.ProjectRepository

/** Minimal manual dependency container — no DI framework needed at this size. */
class AmiriCutApp : Application() {
    lateinit var settings: AppSettings; private set
    lateinit var projects: ProjectRepository; private set
    lateinit var caches: CacheManager; private set
    lateinit var thumbnails: ThumbnailCache; private set
    lateinit var waveforms: WaveformCache; private set

    /** Outlives screens: used for saving a project after its editor is closed. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        settings = AppSettings(this)
        projects = ProjectRepository(this)
        caches = CacheManager(this)
        thumbnails = ThumbnailCache(this, caches) { settings.performance }
        waveforms = WaveformCache(this, caches)
    }
}
