package io.simplezen.simple_sms.mms.sending

import android.content.Context
import android.util.Log
import java.io.File
import java.io.IOException

// The encoded PDU of one outbound MMS: written to the cache directory before
// the send, and deleted once the platform is done with it (UNFY-311). Naming
// and deletion live together so the delete always recognizes what was
// written.

private const val SEND_FILE_PREFIX = "send."
private const val SEND_FILE_SUFFIX = ".dat"
private const val TAG = "MmsSendFile"

/**
 * Runs [send] with a new, empty send file in [Context.getCacheDir]. [send]
 * writes the PDU and hands the file to the platform, returning true once the
 * platform owns it — the send's SENT result then deletes it (see
 * [deleteMmsSendFile]). In every other case — [send] returns false or throws
 * anything — no SENT result will name the file, so it is deleted here.
 */
internal fun useMmsSendFile(context: Context, send: (File) -> Boolean) {
    val file = File.createTempFile(SEND_FILE_PREFIX, SEND_FILE_SUFFIX, context.cacheDir)
    var handedOff = false
    try {
        handedOff = send(file)
    } finally {
        if (!handedOff && !file.delete()) {
            Log.w(TAG, "MMS send file ${file.path} was not deleted (exists=${file.exists()}).")
        }
    }
}

/**
 * Deletes the send file at [path], named by a SENT result. Only a file
 * [useMmsSendFile] could have created — directly in [Context.getCacheDir] —
 * is deleted; anything else is logged and left alone. Never throws: it runs
 * inside a broadcast receiver. Returns whether a file was deleted.
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
