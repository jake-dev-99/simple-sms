package io.simplezen.simple_sms.messaging

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.annotation.VisibleForTesting
import io.simplezen.simple_sms.mms.sending.deleteStaleMmsSendFiles
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runs [markStaleOutboxFailed] off the main thread (UNFY-299), after retrying
 * the status writes that did not land ([OwedRowWrites], UNFY-335).
 *
 * [onEngineStart] sweeps now and again once [STALE_OUTBOX_AGE_MS] later, so a
 * send lost in a reboot is marked failed within that time of the app's next
 * start even when the row was too young at startup. Requests coalesce, so a
 * burst of engine starts runs at most one immediate and one delayed sweep
 * each; a failed sweep is retried by the next request.
 *
 * Owed writes go first, so a send the carrier accepted is recorded as sent
 * rather than failed for lack of a result; a row that still owes one is left
 * alone. [onSend] retries them before each new send, when the app is likeliest
 * to have write access again.
 */
internal object OutboxSweeper {
    private const val TAG = "OutboxSweeper"

    private val executor: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "simple_sms-outbox-sweep").apply { isDaemon = true }
        }
    private val immediateQueued = AtomicBoolean(false)
    private val delayedQueued = AtomicBoolean(false)
    private val retryQueued = AtomicBoolean(false)

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

    /** Retries the owed status writes off the main thread, as a send starts. */
    fun onSend(context: Context) {
        val appContext = context.applicationContext
        if (retryQueued.compareAndSet(false, true)) {
            executor.execute {
                retryQueued.set(false)
                retryOwedWrites(appContext)
            }
        }
    }

    /**
     * Purpose: one sweep's rows: retries the owed status writes, then marks
     * stale outbox rows failed, leaving alone those in [pending] and those
     * that still owe a write.
     *
     * @param pending the rows live handlers still await.
     * @returns how many rows were marked failed.
     * @throws Exception the provider's failure to sweep.
     */
    @VisibleForTesting
    internal fun sweepOnce(context: Context, pending: Set<Uri>): Int {
        val owed = retryOwedWrites(context)
        val failed = markStaleOutboxFailed(context, pending + owed.rows())
        if (failed > 0) Log.w(TAG, "Marked $failed stale outbox message(s) failed (SENT result lost)")
        return failed
    }

    private fun retryOwedWrites(context: Context): OwedRowWrites {
        val owed = OwedRowWrites.forApp(context)
        val landed = owed.retry(context)
        if (landed > 0) Log.i(TAG, "Recorded $landed owed status write(s)")
        return owed
    }

    private fun sweep(context: Context) {
        try {
            sweepOnce(context, SendResultRouter.pendingSends())
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
