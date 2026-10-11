package io.simplezen.simple_sms.mms.sending

import android.content.Context
import android.util.Log
import java.io.File
import java.io.IOException
import java.util.Random
import kotlin.math.abs

// The encoded PDU of one outbound MMS: written to the cache directory before
// the send, and deleted once the platform has read it (UNFY-311). Naming and
// deletion live together so the delete always recognizes what was written.

private const val SEND_FILE_PREFIX = "send."
private const val SEND_FILE_SUFFIX = ".dat"
private const val TAG = "MmsSendFile"

/** A new, randomly named send file in [Context.getCacheDir]. Not yet created on disk. */
internal fun newMmsSendFile(context: Context): File =
    File(context.cacheDir, SEND_FILE_PREFIX + abs(Random().nextLong()).toString() + SEND_FILE_SUFFIX)

/**
 * Deletes the send file at [path]. Only a file [newMmsSendFile] could have
 * named — directly in [Context.getCacheDir] — is deleted; anything else is
 * logged and left alone. Never throws: it runs inside a broadcast receiver
 * and after a failed send. Returns whether a file was deleted.
 */
internal fun deleteMmsSendFile(context: Context, path: String?): Boolean {
    if (path == null) {
        Log.w(TAG, "No MMS send-file path; nothing to delete.")
        return false
    }
    val file = File(path)
    val inCache = try {
        file.parentFile?.canonicalFile == context.cacheDir.canonicalFile
    } catch (e: IOException) {
        Log.e(TAG, "Could not resolve $path; not deleting it.", e)
        return false
    }
    if (!inCache || !file.name.startsWith(SEND_FILE_PREFIX) || !file.name.endsWith(SEND_FILE_SUFFIX)) {
        Log.e(TAG, "Refusing to delete $path: not an MMS send file in the cache directory.")
        return false
    }
    if (!file.delete()) {
        Log.w(TAG, "MMS send file $path was not deleted (exists=${file.exists()}).")
        return false
    }
    return true
}
