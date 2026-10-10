/*
 * Copyright 2013 Jacob Klinker
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

// Modified by Simple Zen: Kotlin port maintained in the first-party MMS namespace (UNFY-123).

package io.simplezen.simple_sms.mms.sending

import android.telephony.SmsMessage

/**
 * Transliterates accented / Greek characters to their GSM-7 equivalents when a
 * message would otherwise span more than one SMS segment (which can collapse it
 * back into fewer segments). First-party Kotlin port of the vendored Klinker
 * `StripAccents.java`; behaviour preserved.
 *
 * The two tables are parallel (138 chars each): CHARACTERS[i] maps to GSM[i].
 * Copied verbatim from the vendored source.
 */
object StripAccents {

    private const val CHARACTERS = "αβγδεζηθικλμν" +
        "ξοπρσςτυφχψωάέ" +
        "ήίόύώϊϋΐΰΑΒΕΖΗΙ" +
        "ΚΜΝΟΡΤΥΧΆΈΉΊΌΏΪ" +
        "ΫŰűŐőąćęłńśźżĄĆ" +
        "ĘŁŃŚŹŻÀÂÃÈÊÌÎÒÕ" +
        "ÙÛâãêîõúûçěščřžď" +
        "ťňáíéóýůĚŠČŘŽĎŤŇ" +
        "ÁÉÍÓÝÚŮŕĺľôŔĹĽÔÏïëË"

    private const val GSM = "ABΓΔEZHΘIKΛMNΞOΠPΣΣTYΦXΨΩAEHIOY" +
        "ΩIYIYABEZHIKMNOPTYXAEHIOΩIYÜüÖöacelnszzACELNSZZAAAEEIIOOUU" +
        "aaeiouucescrzdtnaieoyuESCRZDTNAEIOYUUrlloRLLOIIee"

    /**
     * If [s] would span more than one SMS segment, transliterate its accented /
     * Greek characters to GSM-7. Single-segment messages are returned
     * unchanged. Mirrors the vendored guard
     * `SmsMessage.calculateLength(s, false)[0] != 1`.
     */
    @JvmStatic
    fun stripAccents(s: String): String {
        val messageData = SmsMessage.calculateLength(s, false)
        return if (messageData[0] != 1) transliterate(s) else s
    }

    /**
     * Pure GSM-7 transliteration with no Android dependency — the testable core.
     * Applies the table substitutions in order, exactly as the vendored loop did
     * (`s.replaceAll(char, gsm)` per index; the table chars are all letters, so
     * literal replacement is identical to the original regex replacement).
     */
    internal fun transliterate(s: String): String {
        var result = s
        for (i in CHARACTERS.indices) {
            result = result.replace(CHARACTERS[i].toString(), GSM[i].toString())
        }
        return result
    }
}
