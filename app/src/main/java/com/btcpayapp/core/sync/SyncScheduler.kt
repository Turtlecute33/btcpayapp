package com.btcpayapp.core.sync

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.btcpayapp.BtcPayApplication
import com.btcpayapp.core.util.Log
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Background scheduling, on the platform's [JobScheduler].
 *
 * WorkManager would do the same thing here, but it brings Room and SQLite along
 * for a single periodic job with no chaining, no constraints beyond "network",
 * and no need for a work database. JobScheduler is what WorkManager delegates to
 * on every API level this app supports.
 */
object SyncScheduler {

    private const val JOB_ID = 0x42C
    private val MIN_PERIOD_MS = TimeUnit.MINUTES.toMillis(15)

    fun schedule(context: Context, intervalMinutes: Int, unmeteredOnly: Boolean) {
        val scheduler = context.getSystemService(JobScheduler::class.java) ?: return

        val period = TimeUnit.MINUTES.toMillis(intervalMinutes.toLong()).coerceAtLeast(MIN_PERIOD_MS)
        val networkType = if (unmeteredOnly) JobInfo.NETWORK_TYPE_UNMETERED else JobInfo.NETWORK_TYPE_ANY

        // `schedule` stops a running job with the same id. Every process start
        // and every visit to Settings comes here, and a sync run is often what
        // started the process, so an unchanged job is left alone. The network
        // type getter is deprecated from API 28, but it is exactly what the
        // builder below sets, on every API level this app supports.
        val pending = runCatching { scheduler.getPendingJob(JOB_ID) }.getOrNull()
        @Suppress("DEPRECATION")
        val unchanged = pending != null && pending.intervalMillis == period && pending.networkType == networkType
        if (unchanged) return

        val job = JobInfo.Builder(JOB_ID, ComponentName(context, SyncJobService::class.java))
            .setRequiredNetworkType(networkType)
            .setPeriodic(period)
            .setPersisted(true)
            .setRequiresDeviceIdle(false)
            .setRequiresCharging(false)
            .build()

        // `schedule` returns RESULT_FAILURE rather than throwing when the app is
        // in a restricted standby bucket or over its pending-job quota, so the
        // result is checked: `runCatching` alone never observes that failure,
        // and the app would believe sync was armed when it was not.
        val result = runCatching { scheduler.schedule(job) }
            .getOrElse { JobScheduler.RESULT_FAILURE }
        if (result != JobScheduler.RESULT_SUCCESS) {
            Log.w("SyncScheduler") { "background sync could not be scheduled" }
        }
    }

    fun cancel(context: Context) {
        context.getSystemService(JobScheduler::class.java)?.cancel(JOB_ID)
    }

    fun isScheduled(context: Context): Boolean =
        context.getSystemService(JobScheduler::class.java)
            ?.allPendingJobs
            ?.any { it.id == JOB_ID } == true
}

class SyncJobService : JobService() {

    private var job: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private companion object {
        /** Comfortably inside JobScheduler's ~10 minute ceiling. */
        val MAX_RUNTIME_MS = TimeUnit.MINUTES.toMillis(8)
    }

    override fun onStartJob(params: JobParameters?): Boolean {
        val graph = (application as? BtcPayApplication)?.graph ?: return false

        job = scope.launch {
            val result = try {
                // JobScheduler kills a JobService that overruns its execution
                // window, mid-write. The engine waits up to 20 s for three
                // Keystore decrypts and then gives each account up to 4
                // minutes, with onion timeouts of minutes per request, so
                // unbounded it could overrun — and repeated overruns get the
                // app moved into a restricted standby bucket, which is what
                // "sync stopped working" looks like from outside.
                withTimeoutOrNull(MAX_RUNTIME_MS) {
                    // A document whose read failed stops retrying by itself
                    // about 30 s after the process starts. In a process kept
                    // alive for jobs, with no screen for "Try again", only
                    // this reads it again.
                    graph.retryStorage()
                    graph.syncEngine.run()
                } ?: SyncEngine.Result.Failed
            } catch (e: CancellationException) {
                // onStopJob already asked for a reschedule; do not also call
                // jobFinished, which would tell the system "done, do not
                // reschedule" and drop the retry.
                throw e
            } catch (e: Exception) {
                Log.e("SyncJobService", e) { "sync failed" }
                SyncEngine.Result.Failed
            }

            jobFinished(params, result is SyncEngine.Result.Failed)
        }
        return true
    }

    override fun onStopJob(params: JobParameters?): Boolean {
        job?.cancel()
        return true
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}

/** JobScheduler drops persisted jobs on some OEM builds after an update. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // The filter declares two protected actions; check anyway, so a
        // spoofed intent with no action cannot drive this.
        if (intent.action !in ACTIONS) return

        val graph = (context.applicationContext as? BtcPayApplication)?.graph ?: return
        val appContext = context.applicationContext
        val pending = goAsync()

        // Waits for the settings document rather than reading
        // `settings.settings.value` straight away. At BOOT_COMPLETED the
        // process has just been created and the settings file is still being
        // decrypted on IO, so an immediate read returns the *defaults* — which
        // would silently re-enable background sync for a user who had turned it
        // off and reset a 60-minute interval to 15-minute metered polling, on
        // every boot and every app update.
        graph.scope.launch {
            try {
                val loaded = withTimeoutOrNull(BOOT_TIMEOUT_MS) {
                    // A read that failed stops retrying by itself after about
                    // 30 s, so a process that lived longer would only wait.
                    graph.retryStorage()
                    graph.settings.loaded.first { it }
                }
                if (loaded != true) return@launch
                val settings = graph.settings.settings.value
                if (settings.backgroundSync) {
                    SyncScheduler.schedule(
                        appContext,
                        settings.syncIntervalMinutes,
                        settings.syncOnUnmeteredOnly,
                    )
                }
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        val ACTIONS = setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED)
        const val BOOT_TIMEOUT_MS = 8_000L
    }
}
