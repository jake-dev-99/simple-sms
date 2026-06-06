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

/**
 * First-party Kotlin port of the vendored `Base64`; logic preserved 1:1,
 * including its byte-level quirks. `decodeBase64` is reached by the (still-Java)
 * `PduParser` for base64-encoded MMS part bodies.
 *
 * Faithful-port notes:
 *  - The alphabet table is indexed by the **sign-extended** byte value
 *    (`base64Alphabet[byte.toInt()]`), exactly as the vendored Java did — so a
 *    high (>= 0x80, i.e. negative) byte produces a negative index and throws
 *    `ArrayIndexOutOfBoundsException`. That behaviour is preserved, not guarded.
 *  - The bit regrouping uses arithmetic shifts (`shr`) on int-promoted bytes,
 *    matching Java's `>>` on `byte` operands — including the `-1` ("not in
 *    alphabet") sentinel paths, which both implementations carry through identically.
 *
 * `@JvmStatic` keeps the Java call site `Base64.decodeBase64(...)` unchanged.
 */
object Base64 {
    /** Used to get the number of Quadruples. */
    private const val FOURBYTE = 4

    /** Byte used to pad output. */
    private val PAD: Byte = '='.code.toByte()

    /** The base length. */
    private const val BASELENGTH = 255

    // Create arrays to hold the base64 characters
    private val base64Alphabet = ByteArray(BASELENGTH)

    // Populating the character arrays
    init {
        for (i in 0 until BASELENGTH) {
            base64Alphabet[i] = (-1).toByte()
        }
        var i = 'Z'.code
        while (i >= 'A'.code) {
            base64Alphabet[i] = (i - 'A'.code).toByte()
            i--
        }
        i = 'z'.code
        while (i >= 'a'.code) {
            base64Alphabet[i] = (i - 'a'.code + 26).toByte()
            i--
        }
        i = '9'.code
        while (i >= '0'.code) {
            base64Alphabet[i] = (i - '0'.code + 52).toByte()
            i--
        }

        base64Alphabet['+'.code] = 62
        base64Alphabet['/'.code] = 63
    }

    /**
     * Decodes Base64 data into octects
     *
     * @param base64Data Byte array containing Base64 data
     * @return Array containing decoded data.
     */
    @JvmStatic
    fun decodeBase64(base64Data: ByteArray): ByteArray {
        // RFC 2045 requires that we discard ALL non-Base64 characters
        var base64Data = discardNonBase64(base64Data)

        // handle the edge case, so we don't have to worry about it later
        if (base64Data.isEmpty()) {
            return ByteArray(0)
        }

        val numberQuadruple = base64Data.size / FOURBYTE
        val decodedData: ByteArray
        var b1: Byte = 0
        var b2: Byte = 0
        var b3: Byte = 0
        var b4: Byte = 0
        var marker0: Byte = 0
        var marker1: Byte = 0

        // Throw away anything not in base64Data

        var encodedIndex = 0
        var dataIndex = 0
        run {
            // this sizes the output array properly - rlw
            var lastData = base64Data.size
            // ignore the '=' padding
            while (base64Data[lastData - 1] == PAD) {
                if (--lastData == 0) {
                    return ByteArray(0)
                }
            }
            decodedData = ByteArray(lastData - numberQuadruple)
        }

        for (i in 0 until numberQuadruple) {
            dataIndex = i * 4
            marker0 = base64Data[dataIndex + 2]
            marker1 = base64Data[dataIndex + 3]

            b1 = base64Alphabet[base64Data[dataIndex].toInt()]
            b2 = base64Alphabet[base64Data[dataIndex + 1].toInt()]

            if (marker0 != PAD && marker1 != PAD) {
                // No PAD e.g 3cQl
                b3 = base64Alphabet[marker0.toInt()]
                b4 = base64Alphabet[marker1.toInt()]

                decodedData[encodedIndex] = ((b1.toInt() shl 2) or (b2.toInt() shr 4)).toByte()
                decodedData[encodedIndex + 1] =
                    (((b2.toInt() and 0xf) shl 4) or ((b3.toInt() shr 2) and 0xf)).toByte()
                decodedData[encodedIndex + 2] = ((b3.toInt() shl 6) or b4.toInt()).toByte()
            } else if (marker0 == PAD) {
                // Two PAD e.g. 3c[Pad][Pad]
                decodedData[encodedIndex] = ((b1.toInt() shl 2) or (b2.toInt() shr 4)).toByte()
            } else if (marker1 == PAD) {
                // One PAD e.g. 3cQ[Pad]
                b3 = base64Alphabet[marker0.toInt()]

                decodedData[encodedIndex] = ((b1.toInt() shl 2) or (b2.toInt() shr 4)).toByte()
                decodedData[encodedIndex + 1] =
                    (((b2.toInt() and 0xf) shl 4) or ((b3.toInt() shr 2) and 0xf)).toByte()
            }
            encodedIndex += 3
        }
        return decodedData
    }

    /**
     * Check octect wheter it is a base64 encoding.
     *
     * @param octect to be checked byte
     * @return ture if it is base64 encoding, false otherwise.
     */
    private fun isBase64(octect: Byte): Boolean {
        return if (octect == PAD) {
            true
        } else if (base64Alphabet[octect.toInt()].toInt() == -1) {
            false
        } else {
            true
        }
    }

    /**
     * Discards any characters outside of the base64 alphabet, per
     * the requirements on page 25 of RFC 2045 - "Any characters
     * outside of the base64 alphabet are to be ignored in base64
     * encoded data."
     *
     * @param data The base-64 encoded data to groom
     * @return The data, less non-base64 characters (see RFC 2045).
     */
    private fun discardNonBase64(data: ByteArray): ByteArray {
        val groomedData = ByteArray(data.size)
        var bytesCopied = 0

        for (i in data.indices) {
            if (isBase64(data[i])) {
                groomedData[bytesCopied++] = data[i]
            }
        }

        val packedData = ByteArray(bytesCopied)

        System.arraycopy(groomedData, 0, packedData, 0, bytesCopied)

        return packedData
    }
}
