package com.klinker.android.send_message

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
