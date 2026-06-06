package com.google.android.mms.util_alt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the [DownloadDrmHelper] port: the three DRM MIME/extension constants and
 * the two framework-free helpers ([DownloadDrmHelper.isDrmConvertNeeded] and
 * [DownloadDrmHelper.modifyDrmFwLockFileExtension]).
 *
 * `isDrmMimeType`/`getOriginalMimeType` are excluded — both construct a real
 * `DrmManagerClient`, which has no plain-JUnit seam — matching the vendored
 * helper's testable surface.
 */
class DownloadDrmHelperTest {

    @Test
    fun constants() {
        assertEquals("application/vnd.oma.drm.message", DownloadDrmHelper.MIMETYPE_DRM_MESSAGE)
        assertEquals(".dm", DownloadDrmHelper.EXTENSION_DRM_MESSAGE)
        assertEquals(".fl", DownloadDrmHelper.EXTENSION_INTERNAL_FWDL)
    }

    @Test
    fun isDrmConvertNeeded_matchesOnlyTheDrmMessageMime() {
        assertTrue(DownloadDrmHelper.isDrmConvertNeeded(DownloadDrmHelper.MIMETYPE_DRM_MESSAGE))
        assertFalse(DownloadDrmHelper.isDrmConvertNeeded("text/plain"))
        // Null is handled by the constant-receiver equals (no NPE) → false.
        assertFalse(DownloadDrmHelper.isDrmConvertNeeded(null))
    }

    @Test
    fun modifyDrmFwLockFileExtension_replacesExtensionOrAppends() {
        // Existing extension is stripped, then .fl appended.
        assertEquals("foo.fl", DownloadDrmHelper.modifyDrmFwLockFileExtension("foo.dm"))
        // No extension → .fl appended directly.
        assertEquals("foo.fl", DownloadDrmHelper.modifyDrmFwLockFileExtension("foo"))
        // Only the LAST dot counts (lastIndexOf).
        assertEquals("a.b.fl", DownloadDrmHelper.modifyDrmFwLockFileExtension("a.b.c"))
        // Null in → null out (faithful guard).
        assertNull(DownloadDrmHelper.modifyDrmFwLockFileExtension(null))
    }
}
