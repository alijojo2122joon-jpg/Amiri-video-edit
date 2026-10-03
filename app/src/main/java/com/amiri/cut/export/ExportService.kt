package com.amiri.cut.export

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.amiri.cut.AmiriCutApp
import com.amiri.cut.MainActivity
import com.amiri.cut.R
import com.amiri.cut.core.model.Project
import com.amiri.cut.core.model.newId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

enum class JobState { QUEUED, RUNNING, DONE, FAILED, CANCELLED }

data class ExportJob(
    val id: String,
    val name: String,
    val projectJson: String,
    val settings: ExportSettings,
    val outUri: String,
    val state: JobState = JobState.QUEUED,
    val progress: ExportProgress? = null,
    val error: String? = null,
)

/** Render queue shared by the UI and the export service. */
object ExportQueue {
    private val _jobs = MutableStateFlow<List<ExportJob>>(emptyList())
    val jobs: StateFlow<List<ExportJob>> = _jobs
    private val cancelFlags = HashMap<String, AtomicBoolean>()

    @Synchronized
    fun enqueue(context: Context, name: String, project: Project, settings: ExportSettings, outUri: Uri, json: String) {
        val job = ExportJob(newId(), name, json, settings, outUri.toString())
        cancelFlags[job.id] = AtomicBoolean(false)
        _jobs.value = _jobs.value + job
        val i = Intent(context, ExportService::class.java)
        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i) else context.startService(i)
        @Suppress("UNUSED_VARIABLE") val unused = project
    }

    @Synchronized
    fun update(id: String, f: (ExportJob) -> ExportJob) {
        _jobs.value = _jobs.value.map { if (it.id == id) f(it) else it }
    }

    @Synchronized
    fun nextQueued(): ExportJob? = _jobs.value.firstOrNull { it.state == JobState.QUEUED }

    fun cancelFlag(id: String): AtomicBoolean = synchronized(this) { cancelFlags.getOrPut(id) { AtomicBoolean(false) } }

    fun cancel(id: String) {
        cancelFlag(id).set(true)
        update(id) { if (it.state == JobState.QUEUED) it.copy(state = JobState.CANCELLED) else it }
    }

    @Synchronized
    fun clearFinished() {
        _jobs.value = _jobs.value.filter { it.state == JobState.QUEUED || it.state == JobState.RUNNING }
    }
}

/**
 * Foreground service that renders queued exports one after another, so exporting
 * continues when the user leaves the editor or the app.
 */
class ExportService : Service() {
    private val executor = Executors.newSingleThreadExecutor()
    private var running = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            intent.getStringExtra(EXTRA_ID)?.let { ExportQueue.cancel(it) }
            return START_NOT_STICKY
        }
        startFg(notification("Preparing export…", 0, 0, null))
        if (!running) {
            running = true
            executor.execute { loop() }
        }
        return START_NOT_STICKY
    }

    /**
     * Starts the foreground notification. Tries media processing first (Android 15+), then
     * data sync; if the system refuses both, the export still runs (it just isn't protected
     * from being stopped in the background). Never crashes the app.
     */
    private fun startFg(n: Notification) {
        val types = buildList {
            if (Build.VERSION.SDK_INT >= 35) add(ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING)
            if (Build.VERSION.SDK_INT >= 29) add(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            if (Build.VERSION.SDK_INT < 34) add(0)
        }
        for (type in types) {
            try {
                ServiceCompat.startForeground(this, NOTIF_ID, n, type)
                android.util.Log.i("AmiriExport", "foreground ok type=$type")
                return
            } catch (t: Throwable) {
                android.util.Log.e("AmiriExport", "startForeground type=$type failed: ${t.javaClass.simpleName}: ${t.message}")
            }
        }
        runCatching { notify(n) }
    }

    private fun loop() {
        try { loopJobs() } catch (t: Throwable) {
            android.util.Log.e("AmiriExport", "render loop crashed", t)
        } finally {
            running = false
            runCatching { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH) }
            stopSelf()
        }
    }

    private fun loopJobs() {
        val app = application as AmiriCutApp
        while (true) {
            val job = ExportQueue.nextQueued() ?: break
            val cancel = ExportQueue.cancelFlag(job.id)
            ExportQueue.update(job.id) { it.copy(state = JobState.RUNNING) }
            var tmp = File(app.cacheDir, "${job.id}.mp4")
            try {
                tmp = File(app.caches.dir(com.amiri.cut.storage.CacheManager.Kind.RENDER), "${job.id}.mp4")
                val project = app.projects.json.decodeFromString(Project.serializer(), job.projectJson)
                var last = 0L
                Exporter(this, app, project, job.settings, tmp, cancel) { p ->
                    ExportQueue.update(job.id) { it.copy(progress = p) }
                    val now = System.currentTimeMillis()
                    if (now - last > 700) {
                        last = now
                        runCatching { notify(notification("${job.name} · ${(p.fraction * 100).toInt()}%", p.frame, p.totalFrames, job.id)) }
                    }
                }.run()
                ExportQueue.update(job.id) { it.copy(progress = it.progress?.copy(stage = "Saving")) }
                val dest = runCatching { contentResolver.openOutputStream(Uri.parse(job.outUri), "wt") }.getOrNull()
                    ?: contentResolver.openOutputStream(Uri.parse(job.outUri), "w")
                    ?: error("Can't write to the chosen file")
                dest.use { out -> tmp.inputStream().use { it.copyTo(out) } }
                ExportQueue.update(job.id) { it.copy(state = JobState.DONE) }
                runCatching { notifyDone("Exported ${job.name}") }
            } catch (e: InterruptedException) {
                ExportQueue.update(job.id) { it.copy(state = JobState.CANCELLED) }
                runCatching { contentResolver.delete(Uri.parse(job.outUri), null, null) }
            } catch (t: Throwable) {
                android.util.Log.e("AmiriExport", "export failed", t)
                ExportQueue.update(job.id) { it.copy(state = JobState.FAILED, error = t.message ?: t.javaClass.simpleName) }
                runCatching { contentResolver.delete(Uri.parse(job.outUri), null, null) }
                runCatching { notifyDone("Export failed: ${job.name}") }
            } finally {
                tmp.delete()
            }
        }
    }

    private fun channel(): String {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Export", NotificationManager.IMPORTANCE_LOW))
        }
        return CHANNEL
    }

    private fun notification(text: String, progress: Int, max: Int, jobId: String?): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val b = NotificationCompat.Builder(this, channel())
            .setSmallIcon(R.drawable.ic_stat_export)
            .setContentTitle("Amiri Cut")
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setContentIntent(open)
            .setProgress(max, progress, max == 0)
        if (jobId != null) {
            val cancel = PendingIntent.getService(
                this, jobId.hashCode(),
                Intent(this, ExportService::class.java).setAction(ACTION_CANCEL).putExtra(EXTRA_ID, jobId),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            b.addAction(0, "Cancel", cancel)
        }
        return b.build()
    }

    private fun notify(n: Notification) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, n)
    }

    private fun notifyDone(text: String) {
        val n = NotificationCompat.Builder(this, channel())
            .setSmallIcon(R.drawable.ic_stat_export)
            .setContentTitle("Amiri Cut")
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .build()
        getSystemService(NotificationManager::class.java).notify(NOTIF_DONE_ID, n)
    }

    override fun onDestroy() {
        executor.shutdown()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "export"
        private const val NOTIF_ID = 41
        private const val NOTIF_DONE_ID = 42
        const val ACTION_CANCEL = "com.amiri.cut.CANCEL_EXPORT"
        const val EXTRA_ID = "id"
    }
}
