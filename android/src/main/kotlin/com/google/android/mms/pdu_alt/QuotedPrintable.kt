/*
 * Copyright (C) 2015 Jacob Klinker
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.android.mms.pdu_alt

import java.io.ByteArrayOutputStream

/**
 * First-party Kotlin port of the vendored `QuotedPrintable`; logic preserved
 * 1:1, including its byte-level quirks. Decode is reached by the (still-Java)
 * `PduParser` for quoted-printable MMS part bodies.
 *
 * `@JvmStatic` keeps the Java call site `QuotedPrintable.decodeQuotedPrintable(...)`
 * unchanged.
 */
object QuotedPrintable {
    private val ESCAPE_CHAR: Byte = '='.code.toByte()

    /**
     * Decodes an array quoted-printable characters into an array of original bytes.
     * Escaped characters are converted back to their original representation.
     *
     * This function implements a subset of quoted-printable encoding specification
     * (rule #1 and rule #2) as defined in RFC 1521.
     *
     * @param bytes array of quoted-printable characters
     * @return array of original bytes, null if quoted-printable decoding is unsuccessful.
     */
    @JvmStatic
    fun decodeQuotedPrintable(bytes: ByteArray?): ByteArray? {
        if (bytes == null) {
            return null
        }
        val buffer = ByteArrayOutputStream()
        // Faithful translation of the vendored C-style for-loop: the trailing
        // `i++` runs on every normal iteration AND on `continue`, so the soft
        // line-break branch advances i by 3 total (i += 2 here, then i++), and
        // the `bytes[++i]` reads in the hex branch leave i at +2 before the
        // trailing i++ lands it at +3 — matching the original exactly.
        var i = 0
        while (i < bytes.size) {
            val b = bytes[i].toInt()
            if (b == ESCAPE_CHAR.toInt()) {
                try {
                    if ('\r' == bytes[i + 1].toInt().toChar() &&
                        '\n' == bytes[i + 2].toInt().toChar()
                    ) {
                        i += 2
                        i++
                        continue
                    }
                    val u = Character.digit(bytes[++i].toInt().toChar(), 16)
                    val l = Character.digit(bytes[++i].toInt().toChar(), 16)
                    if (u == -1 || l == -1) {
                        return null
                    }
                    buffer.write((u shl 4) + l)
                } catch (e: ArrayIndexOutOfBoundsException) {
                    return null
                }
            } else {
                buffer.write(b)
            }
            i++
        }
        return buffer.toByteArray()
    }
}
