package com.google.android.mms.util_alt

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pins the [DrmConvertSession] port's [DrmConvertSession.open] guard paths —
 * the input combinations that return `null` *before* a [android.drm.DrmManagerClient]
 * is constructed (null context, null mime, empty mime).
 *
 * The success path of `open` and the instance `convert`/`close` methods require
 * a live `DrmManagerClient` / DRM framework (no plain-JUnit/Robolectric seam),
 * so they're excluded — mirroring the framework-free testable surface used for
 * the sibling `DownloadDrmHelper` port.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DrmConvertSessionTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun open_nullContext_returnsNull() {
        // Context guard short-circuits before any DrmManagerClient is created.
        assertNull(DrmConvertSession.open(null, "application/vnd.oma.drm.message"))
    }

    @Test
    fun open_nullMimeType_returnsNull() {
        assertNull(DrmConvertSession.open(context, null))
    }

    @Test
    fun open_emptyMimeType_returnsNull() {
        assertNull(DrmConvertSession.open(context, ""))
    }
}
