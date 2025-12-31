/*
 * Copyright 2013 Jacob Klinker
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.klinker.android.send_message;

import android.telephony.SmsMessage;

public class StripAccents {

    public static String characters = "αβγδεζηθικλμν" +
            "ξοπρσςτυφχψωάέ" +
            "ήίόύώϊϋΐΰΑΒΕΖΗΙ" +
            "ΚΜΝΟΡΤΥΧΆΈΉΊΌΏΪ" +
            "ΫŰűŐőąćęłńśźżĄĆ" +
            "ĘŁŃŚŹŻÀÂÃÈÊÌÎÒÕ" +
            "ÙÛâãêîõúûçěščřžď" +
            "ťňáíéóýůĚŠČŘŽĎŤŇ" +
            "ÁÉÍÓÝÚŮŕĺľôŔĹĽÔÏïëË";

    public static String gsm = "ABΓΔEZHΘIKΛMNΞOΠPΣΣTYΦXΨΩAEHIOY" +
            "ΩIYIYABEZHIKMNOPTYXAEHIOΩIYÜüÖöacelnszzACELNSZZAAAEEIIOOUU" +
            "aaeiouucescrzdtnaieoyuESCRZDTNAEIOYUUrlloRLLOIIee";

    public static String stripAccents(String s) {
        int[] messageData = SmsMessage.calculateLength(s, false);

        if (messageData[0] != 1) {
            for (int i = 0; i < characters.length(); i++) {
                s = s.replaceAll(characters.substring(i, i + 1), gsm.substring(i, i + 1));
            }
        }

        return s;
    }
}
