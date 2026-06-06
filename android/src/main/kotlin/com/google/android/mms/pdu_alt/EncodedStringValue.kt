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

import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.UnsupportedEncodingException
import java.nio.charset.Charset
import java.util.regex.Pattern

/**
 * Encoded-string-value = Text-string | Value-length Char-set Text-string
 *
 * First-party Kotlin port of the vendored `EncodedStringValue`; logic preserved
 * 1:1, including its byte/charset quirks. Consumed by the (still-Java) `pdu_alt`
 * codec (`PduParser`, `PduComposer`, `PduPersister`, the `*Ind`/`*Conf`/`*Req`
 * types) and by first-party Kotlin, so the public API is kept call-compatible
 * for both.
 *
 * Accessor shape: `string` and `characterSet` are exposed as Kotlin properties
 * (not `fun getX()`), so they compile to the identical `getString()` /
 * `getCharacterSet()` / `setCharacterSet()` for Java callers **and** keep
 * working as `.string` / `.characterSet` for the Kotlin consumers that already
 * used the (formerly synthetic) property access. `getTextString` /
 * `setTextString` stay methods (only ever called Java-style).
 *
 * Java→Kotlin friction, resolved faithfully (per the Phase-5 ruling):
 *  - `mData` stays nullable (`ByteArray?`): the vendored `byte[]` field is left
 *    null by the `String` constructor's (impossible) `catch`, so non-null sites
 *    use `!!` to preserve the exact NPE-on-null behaviour.
 *  - `new String(byte[])` → `String(bytes, Charset.defaultCharset())` (the
 *    vendored no-charset constructor decodes with the platform default, which is
 *    UTF-8 on Android — not Kotlin's hard-coded UTF-8).
 *  - `new String(byte[], String name)` → a `Charset.isSupported(name)` gate that
 *    throws the **checked** `UnsupportedEncodingException` before
 *    `String(bytes, Charset.forName(name))`. This is the literal internal
 *    expansion of the vendored constructor and keeps the iso-8859-1 →
 *    default-charset fallback chain intact (Kotlin's `String(bytes, charset(name))`
 *    would instead throw an *unchecked* `UnsupportedCharsetException` that escapes
 *    the catch).
 *  - `String.split` / `getBytes()` use `Pattern.split` / `defaultCharset()` to
 *    match Java's regex-split (with trailing-empty-strip) and default-charset
 *    encode, not Kotlin's literal-split / hard-coded-UTF-8 stdlib helpers.
 */
class EncodedStringValue : Cloneable {
    /**
     * The Char-set value. (Vendored getter/setter; the setter was a plain
     * assignment carrying a "validate against MIBEnum" TODO.)
     */
    var characterSet = 0

    /**
     * The Text-string value.
     */
    private var mData: ByteArray? = null

    /**
     * Constructor.
     *
     * @param charset the Char-set value
     * @param data the Text-string value
     * @throws NullPointerException if Text-string value is null.
     */
    constructor(charset: Int, data: ByteArray?) {
        // TODO: CharSet needs to be validated against MIBEnum.
        if (null == data) {
            throw NullPointerException("EncodedStringValue: Text-string is null.")
        }

        characterSet = charset
        mData = ByteArray(data.size)
        System.arraycopy(data, 0, mData!!, 0, data.size)
    }

    /**
     * Constructor.
     *
     * @param data the Text-string value
     * @throws NullPointerException if Text-string value is null.
     */
    constructor(data: ByteArray?) : this(CharacterSets.DEFAULT_CHARSET, data)

    // Param is nullable to match the vendored `EncodedStringValue(String)`
    // platform signature (Kotlin callers passed a `String?`); `data!!` preserves
    // the original NPE-on-null from `data.getBytes(...)`.
    constructor(data: String?) {
        try {
            mData = data!!.toByteArray(charset(CharacterSets.DEFAULT_CHARSET_NAME))
            characterSet = CharacterSets.DEFAULT_CHARSET
        } catch (e: UnsupportedEncodingException) {
            Log.e(TAG, "Default encoding must be supported.", e)
        }
    }

    /**
     * Get Text-string value.
     *
     * @return the value
     */
    fun getTextString(): ByteArray {
        val byteArray = ByteArray(mData!!.size)

        System.arraycopy(mData!!, 0, byteArray, 0, mData!!.size)
        return byteArray
    }

    /**
     * Set Text-string value.
     *
     * @param textString the Text-string value
     * @throws NullPointerException if Text-string value is null.
     */
    fun setTextString(textString: ByteArray?) {
        if (null == textString) {
            throw NullPointerException("EncodedStringValue: Text-string is null.")
        }

        mData = ByteArray(textString.size)
        System.arraycopy(textString, 0, mData!!, 0, textString.size)
    }

    /**
     * Convert this object to a [String]. If the encoding of
     * the EncodedStringValue is null or unsupported, it will be
     * treated as iso-8859-1 encoding.
     *
     * @return The decoded String.
     */
    val string: String
        get() {
            return if (CharacterSets.ANY_CHARSET == characterSet) {
                String(mData!!, Charset.defaultCharset()) // system default encoding.
            } else {
                try {
                    val name = CharacterSets.getMimeName(characterSet)
                    // Literal expansion of new String(mData, name): an unsupported
                    // charset name yields a *checked* UnsupportedEncodingException,
                    // so the fallback chain below triggers (not an unchecked throw).
                    if (!Charset.isSupported(name)) {
                        throw UnsupportedEncodingException(name)
                    }
                    String(mData!!, Charset.forName(name))
                } catch (e: UnsupportedEncodingException) {
                    if (LOCAL_LOGV) {
                        Log.v(TAG, e.message, e)
                    }
                    try {
                        String(mData!!, Charset.forName(CharacterSets.MIMENAME_ISO_8859_1))
                    } catch (f: UnsupportedEncodingException) {
                        String(mData!!, Charset.defaultCharset()) // system default encoding.
                    }
                }
            }
        }

    /**
     * Append to Text-string.
     *
     * @param textString the textString to append
     * @throws NullPointerException if the text String is null
     *                      or an IOException occured.
     */
    fun appendTextString(textString: ByteArray?) {
        if (null == textString) {
            throw NullPointerException("Text-string is null.")
        }

        if (null == mData) {
            mData = ByteArray(textString.size)
            System.arraycopy(textString, 0, mData!!, 0, textString.size)
        } else {
            val newTextString = ByteArrayOutputStream()
            try {
                newTextString.write(mData!!)
                newTextString.write(textString)
            } catch (e: IOException) {
                Log.e(TAG, "logging error", e)
                e.printStackTrace()
                throw NullPointerException(
                    "appendTextString: failed when write a new Text-string",
                )
            }

            mData = newTextString.toByteArray()
        }
    }

    /*
     * (non-Javadoc)
     * @see java.lang.Object#clone()
     */
    @Throws(CloneNotSupportedException::class)
    public override fun clone(): Any {
        super.clone()
        val len = mData!!.size
        val dstBytes = ByteArray(len)
        System.arraycopy(mData!!, 0, dstBytes, 0, len)

        return try {
            EncodedStringValue(characterSet, dstBytes)
        } catch (e: Exception) {
            Log.e(TAG, "logging error", e)
            e.printStackTrace()
            throw CloneNotSupportedException(e.message)
        }
    }

    /**
     * Split this encoded string around matches of the given pattern.
     *
     * @param pattern the delimiting pattern
     * @return the array of encoded strings computed by splitting this encoded
     *         string around matches of the given pattern
     */
    fun split(pattern: String): Array<EncodedStringValue>? {
        // Java's String.split(String) is regex-based and strips trailing empty
        // strings (limit 0); Pattern.split reproduces both, unlike Kotlin's
        // literal String.split.
        val temp = Pattern.compile(pattern).split(string)
        val ret = arrayOfNulls<EncodedStringValue>(temp.size)
        for (i in ret.indices) {
            try {
                ret[i] = EncodedStringValue(
                    characterSet,
                    temp[i].toByteArray(Charset.defaultCharset()),
                )
            } catch (e: NullPointerException) {
                // Can't arrive here
                return null
            }
        }
        @Suppress("UNCHECKED_CAST")
        return ret as Array<EncodedStringValue>
    }

    companion object {
        private const val TAG = "EncodedStringValue"
        private const val DEBUG = false
        private const val LOCAL_LOGV = false

        /**
         * Extract an EncodedStringValue[] from a given String.
         */
        @JvmStatic
        fun extract(src: String): Array<EncodedStringValue>? {
            val values = Pattern.compile(";").split(src)

            val list = ArrayList<EncodedStringValue>()
            for (i in values.indices) {
                if (values[i].isNotEmpty()) {
                    list.add(EncodedStringValue(values[i]))
                }
            }

            val len = list.size
            return if (len > 0) {
                list.toTypedArray()
            } else {
                null
            }
        }

        /**
         * Concatenate an EncodedStringValue[] into a single String.
         */
        @JvmStatic
        fun concat(addr: Array<EncodedStringValue>): String {
            val sb = StringBuilder()
            val maxIndex = addr.size - 1
            for (i in 0..maxIndex) {
                sb.append(addr[i].string)
                if (i < maxIndex) {
                    sb.append(";")
                }
            }

            return sb.toString()
        }

        @JvmStatic
        fun copy(value: EncodedStringValue?): EncodedStringValue? {
            if (value == null) {
                return null
            }

            return EncodedStringValue(value.characterSet, value.mData)
        }

        @JvmStatic
        fun encodeStrings(array: Array<String>): Array<EncodedStringValue>? {
            val count = array.size
            if (count > 0) {
                val encodedArray = arrayOfNulls<EncodedStringValue>(count)
                for (i in 0 until count) {
                    encodedArray[i] = EncodedStringValue(array[i])
                }
                @Suppress("UNCHECKED_CAST")
                return encodedArray as Array<EncodedStringValue>
            }
            return null
        }
    }
}
