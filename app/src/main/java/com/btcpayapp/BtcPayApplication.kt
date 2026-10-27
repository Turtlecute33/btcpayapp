package com.btcpayapp

import android.app.Application
import android.os.StrictMode
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.btcpayapp.core.sync.SyncScheduler
import com.btcpayapp.core.util.Dates
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** The only settings the background job depends on. */
private data class SyncPreferences(
    val backgroundSync: Boolean,
    val syncIntervalMinutes: Int,
    val syncOnUnmeteredOnly: Boolean,
)

class BtcPayApplication : Application() {

    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) enableStrictMode()
        Dates.init(this)
        graph = AppGraph(this)
        observeProcessLifecycle()
        keepBackgroundSyncInSyncWithSettings()
        // Three binder round trips to NotificationManagerService plus six
        // resource lookups. Off the cold-start critical path — this also runs
        // in processes spawned only for SyncJobService or BootReceiver.
        graph.scope.launch { graph.notifier.ensureChannels() }
    }

    /**
     * Debug builds log disk and network work on the main thread, leaks and unsafe
     * intents. Set before the graph, so that its start is checked too.
     */
    private fun enableStrictMode() {
        StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder().detectAll().penaltyLog().build())
        StrictMode.setVmPolicy(StrictMode.VmPolicy.Builder().detectAll().penaltyLog().build())
    }

    /**
     * Drives the auto-lock timer. Using the *process* lifecycle rather than the
     * activity's means a configuration change or a brief dialog does not count
     * as leaving the app, but actually backgrounding it does.
     */
    private fun observeProcessLifecycle() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) = graph.appLock.onEnterForeground()
                override fun onStop(owner: LifecycleOwner) = graph.appLock.onEnterBackground()
            },
        )
    }

    private fun keepBackgroundSyncInSyncWithSettings() {
        graph.scope.launch {
            // Wait for the real document instead of dropping the first
            // emission. `drop(1)` would assume the restored value is always a
            // *second* emission — but on a fresh install the file does not
            // exist, `read()` returns the same default the flow was seeded
            // with, and `MutableStateFlow` conflates it away. The only emission
            // would be dropped, and background sync never armed for anyone who
            // installed the app and did not visit Settings.
            graph.settings.loaded.first { it }
            graph.settings.settings
                // Only the three fields that affect the job. Rescheduling on
                // every unrelated settings write (theme, privacy mode, the
                // terminal's remembered currency) would replace the JobInfo and
                // restart its periodic window each time, pushing the next sync
                // out indefinitely for anyone toggling switches.
                .map { SyncPreferences(it.backgroundSync, it.syncIntervalMinutes, it.syncOnUnmeteredOnly) }
                .distinctUntilChanged()
                .collect { settings ->
                    if (settings.backgroundSync) {
                        SyncScheduler.schedule(
                            context = this@BtcPayApplication,
                            intervalMinutes = settings.syncIntervalMinutes,
                            unmeteredOnly = settings.syncOnUnmeteredOnly,
                        )
                    } else {
                        SyncScheduler.cancel(this@BtcPayApplication)
                    }
                }
        }
    }
}
