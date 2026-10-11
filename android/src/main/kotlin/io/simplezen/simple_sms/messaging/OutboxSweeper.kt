package io.simplezen.simple_sms.messaging

import android.content.Context
import android.util.Log
import io.simplezen.simple_sms.mms.sending.deleteStaleMmsSendFiles
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runs [markStaleOutboxFailed] off the main thread (UNFY-299).
 *
 * [onEngineStart] sweeps now and again once [STALE_OUTBOX_AGE_MS] later, so a
 * send lost in a reboot is marked failed within that time of the app's next
 * start even when the row was too young at startup. Requests coalesce, so a
 * burst of engine starts runs at most one immediate and one delayed sweep
 * each; a failed sweep is retried by the next request.
 */
internal object OutboxSweeper {
    private const val TAG = "OutboxSweeper"

    private val executor: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "simple_sms-outbox-sweep").apply { isDaemon = true }
        }
    private val immediateQueued = AtomicBoolean(false)
    private val delayedQueued = AtomicBoolean(false)

    fun onEngineStart(context: Context) {
        val appContext = context.applicationContext
        if (immediateQueued.compareAndSet(false, true)) {
            executor.execute {
                immediateQueued.set(false)
                sweep(appContext)
            }
        }
        if (delayedQueued.compareAndSet(false, true)) {
            executor.schedule({
                delayedQueued.set(false)
                sweep(appContext)
            }, STALE_OUTBOX_AGE_MS, TimeUnit.MILLISECONDS)
        }
    }

    private fun sweep(context: Context) {
        try {
            val failed = markStaleOutboxFailed(context, SendResultRouter.pendingSends())
            if (failed > 0) Log.w(TAG, "Marked $failed stale outbox message(s) failed (SENT result lost)")
        } catch (e: Exception) {
            Log.e(TAG, "Stale outbox sweep failed", e)
        }
        // The same lost results leave their MMS send files behind (UNFY-311).
        try {
            val deleted = deleteStaleMmsSendFiles(context, STALE_OUTBOX_AGE_MS)
            if (deleted > 0) Log.w(TAG, "Deleted $deleted stale MMS send file(s) (SENT result lost)")
        } catch (e: Exception) {
            Log.e(TAG, "Stale MMS send-file sweep failed", e)
        }
    }
}
