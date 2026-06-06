package com.google.android.mms

import android.net.Uri

/**
 * Outbound MMS part holder (name / mime-type / bytes / uri). First-party Kotlin
 * port of the vendored `MMSPart`; the four public fields are kept as `@JvmField`
 * so callers read/write `part.Name` / `.MimeType` / `.Data` / `.Path` directly,
 * exactly as the vendored public-field design (the lone consumer is
 * `Transaction`). `Name`/`MimeType` default to `""`; `Data`/`Path` are nullable
 * (the vendored `byte[]`/`Uri` fields defaulted to null).
 */
class MMSPart {
    @JvmField
    var Name: String = ""

    @JvmField
    var MimeType: String = ""

    @JvmField
    var Data: ByteArray? = null

    @JvmField
    var Path: Uri? = null
}
