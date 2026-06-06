package com.google.android.mms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the [ContentType] port: a sample of the MIME constants, the
 * prefix/suffix predicates (text/image/audio/video/unspecified), the
 * DRM/supported checks against the built lists, and the defensive-copy getters.
 */
class ContentTypeTest {

    @Test
    fun constants() {
        assertEquals("text/plain", ContentType.TEXT_PLAIN)
        assertEquals("image/jpeg", ContentType.IMAGE_JPEG)
        assertEquals("application/smil", ContentType.APP_SMIL)
        assertEquals("application/ogg", ContentType.AUDIO_OGG)
        assertEquals("application/vnd.wap.mms-message", ContentType.MMS_MESSAGE)
    }

    @Test
    fun prefixPredicates() {
        assertTrue(ContentType.isTextType("text/plain"))
        assertFalse(ContentType.isTextType("image/png"))
        assertFalse(ContentType.isTextType(null))
        assertTrue(ContentType.isImageType("image/png"))
        assertTrue(ContentType.isAudioType("audio/amr"))
        assertTrue(ContentType.isVideoType("video/mp4"))
        assertFalse(ContentType.isImageType(null))
    }

    @Test
    fun unspecifiedAndDrm() {
        assertTrue(ContentType.isUnspecified("image/*"))
        assertFalse(ContentType.isUnspecified("image/png"))
        assertFalse(ContentType.isUnspecified(null))
        assertTrue(ContentType.isDrmType(ContentType.APP_DRM_CONTENT))
        assertTrue(ContentType.isDrmType(ContentType.APP_DRM_MESSAGE))
        assertFalse(ContentType.isDrmType("text/plain"))
    }

    @Test
    fun supportedChecks() {
        assertTrue(ContentType.isSupportedType(ContentType.TEXT_PLAIN))
        assertFalse(ContentType.isSupportedType("foo/bar"))
        assertFalse(ContentType.isSupportedType(null))
        assertTrue(ContentType.isSupportedImageType(ContentType.IMAGE_PNG))
        // image/* is an image type but is NOT in the supported list.
        assertFalse(ContentType.isSupportedImageType(ContentType.IMAGE_UNSPECIFIED))
        assertTrue(ContentType.isSupportedAudioType(ContentType.AUDIO_AMR))
        assertTrue(ContentType.isSupportedVideoType(ContentType.VIDEO_MP4))

        // AUDIO_OGG is registered in the audio-types list but its value is
        // "application/ogg", which does NOT match the isAudioType "audio/"
        // prefix — so isSupportedAudioType returns false for it. This is a
        // faithful vendored quirk; pinned here so a future "fix" to the
        // predicate doesn't silently change wire behaviour.
        assertTrue(ContentType.getAudioTypes().contains(ContentType.AUDIO_OGG))
        assertFalse(ContentType.isSupportedAudioType(ContentType.AUDIO_OGG))
    }

    @Test
    fun getters_returnDefensiveCopies() {
        assertEquals(6, ContentType.getImageTypes().size)
        assertEquals(22, ContentType.getAudioTypes().size)
        assertEquals(4, ContentType.getVideoTypes().size)
        assertTrue(ContentType.getSupportedTypes().contains(ContentType.TEXT_PLAIN))
        assertTrue(ContentType.getImageTypes().contains(ContentType.IMAGE_PNG))

        // Mutating a returned list must not affect the internal list.
        val imgs = ContentType.getImageTypes()
        imgs.clear()
        assertEquals(6, ContentType.getImageTypes().size)
    }
}
