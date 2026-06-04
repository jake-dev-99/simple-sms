package com.klinker.android.send_message

import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * Holds all relevant information for a message to send. First-party Kotlin port
 * of the vendored Klinker `Message.java`; the full public API (nested [Part],
 * all constructor overloads, the media adders, and `bitmapToByteArray`) and its
 * behaviour are preserved so existing callers (`Transaction`, the outbound
 * Kotlin seam) compile and behave unchanged.
 *
 * Accessor names match the original (property names already generate the Java
 * `getX`/`setX` forms; the singular `setAddress`/`setImage` and the `add*`
 * media helpers are explicit functions, as in the vendored source).
 */
class Message {

    /** A single media attachment: raw bytes + MIME type + optional file name. */
    class Part(
        private val media: ByteArray,
        private val contentType: String,
        private val name: String?,
    ) {
        fun getMedia(): ByteArray = media

        fun getContentType(): String = contentType

        fun getName(): String? = name
    }

    var text: String? = null
    var subject: String? = null
    var fromAddress: String? = null
    var addresses: Array<String>? = null
    var images: Array<Bitmap>? = null
    var imageNames: Array<String>? = null
    private val parts: MutableList<Part> = ArrayList()
    var save: Boolean = true
    var delay: Int = 0
    var messageUri: Uri? = null

    /** Default constructor. */
    constructor() : this("", arrayOf(""))

    constructor(text: String, address: String) : this(text, address.trim().split(" ").toTypedArray())

    constructor(text: String, address: String, subject: String) :
        this(text, address.trim().split(" ").toTypedArray(), subject)

    /** Core constructor (text + recipients). */
    constructor(text: String, addresses: Array<String>) {
        this.text = text
        this.addresses = addresses
        this.images = arrayOf()
        this.subject = null
        this.save = true
        this.delay = 0
    }

    /** Core constructor (text + recipients + subject). */
    constructor(text: String, addresses: Array<String>, subject: String?) {
        this.text = text
        this.addresses = addresses
        this.images = arrayOf()
        this.subject = subject
        this.save = true
        this.delay = 0
    }

    constructor(text: String, address: String, image: Bitmap) :
        this(text, address.trim().split(" ").toTypedArray(), arrayOf(image))

    constructor(text: String, address: String, image: Bitmap, subject: String) :
        this(text, address.trim().split(" ").toTypedArray(), arrayOf(image), subject)

    constructor(text: String, addresses: Array<String>, image: Bitmap) :
        this(text, addresses, arrayOf(image))

    constructor(text: String, addresses: Array<String>, image: Bitmap, subject: String) :
        this(text, addresses, arrayOf(image), subject)

    constructor(text: String, address: String, images: Array<Bitmap>) :
        this(text, address.trim().split(" ").toTypedArray(), images)

    constructor(text: String, address: String, images: Array<Bitmap>, subject: String) :
        this(text, address.trim().split(" ").toTypedArray(), images, subject)

    /** Core constructor (text + recipients + images). */
    constructor(text: String, addresses: Array<String>, images: Array<Bitmap>) {
        this.text = text
        this.addresses = addresses
        this.images = images
        this.subject = null
        this.save = true
        this.delay = 0
    }

    /** Core constructor (text + recipients + images + subject). */
    constructor(text: String, addresses: Array<String>, images: Array<Bitmap>, subject: String?) {
        this.text = text
        this.addresses = addresses
        this.images = images
        this.subject = subject
        this.save = true
        this.delay = 0
    }

    /** Sets a single recipient (replaces the recipient list). */
    fun setAddress(address: String) {
        this.addresses = arrayOf(address)
    }

    /** Sets a single image (replaces the image list). */
    fun setImage(image: Bitmap) {
        this.images = arrayOf(image)
    }

    @Deprecated("Use addAudio", ReplaceWith("addAudio(audio)"))
    fun setAudio(audio: ByteArray) {
        addAudio(audio)
    }

    fun addAudio(audio: ByteArray) {
        addAudio(audio, null)
    }

    fun addAudio(audio: ByteArray, name: String?) {
        addMedia(audio, "audio/wav", name)
    }

    @Deprecated("Use addVideo", ReplaceWith("addVideo(video)"))
    fun setVideo(video: ByteArray) {
        addVideo(video)
    }

    fun addVideo(video: ByteArray) {
        addVideo(video, null)
    }

    fun addVideo(video: ByteArray, name: String?) {
        addMedia(video, "video/3gpp", name)
    }

    @Deprecated("Use addMedia", ReplaceWith("addMedia(media, mimeType)"))
    fun setMedia(media: ByteArray, mimeType: String) {
        addMedia(media, mimeType)
    }

    fun addMedia(media: ByteArray, mimeType: String) {
        parts.add(Part(media, mimeType, null))
    }

    fun addMedia(media: ByteArray, mimeType: String, name: String?) {
        parts.add(Part(media, mimeType, name))
    }

    /** Appends a recipient to the end of the recipient list. */
    fun addAddress(address: String) {
        val temp = this.addresses ?: arrayOf()
        this.addresses = temp + address
    }

    /** Appends an image to the end of the image list. */
    fun addImage(image: Bitmap) {
        val temp = this.images ?: arrayOf()
        this.images = temp + image
    }

    /**
     * The media [Part]s (audio/video/other) added via the `add*` helpers.
     * Read-only view; the list itself is mutated by [addMedia].
     */
    fun getParts(): List<Part> = parts

    companion object {
        /**
         * Converts a bitmap to a JPEG byte array (quality 90). Returns a
         * zero-length array for a null image (logged), matching the vendored
         * source.
         */
        @JvmStatic
        fun bitmapToByteArray(image: Bitmap?): ByteArray {
            if (image == null) {
                Log.v("Message", "image is null, returning byte array of size 0")
                return ByteArray(0)
            }
            val stream = ByteArrayOutputStream()
            return try {
                image.compress(Bitmap.CompressFormat.JPEG, 90, stream)
                stream.toByteArray()
            } finally {
                try {
                    stream.close()
                } catch (e: IOException) {
                    // ByteArrayOutputStream.close() is a no-op; ignored as in
                    // the vendored source.
                }
            }
        }
    }
}
