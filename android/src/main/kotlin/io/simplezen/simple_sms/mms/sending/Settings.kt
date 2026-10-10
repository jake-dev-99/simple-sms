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

import android.os.Build
import android.os.Bundle
import android.telephony.SmsManager
import android.util.Log

/**
 * Houses all of the settings used to send a message. First-party Kotlin port of
 * the vendored Klinker `Settings.java`; behaviour preserved exactly, including
 * the quirks called out below.
 *
 * Accessor names are kept identical to the Java original so the Kotlin
 * callers (`Transaction`, `Utils`, `SmsManagerFactory`) compile
 * unchanged — note the field/accessor mismatches the original carried:
 * `agent` ⇒ `getAgent`/`setAgent`, `userProfileUrl` ⇒ `getUserProfileUrl`.
 *
 * Faithful quirks (preserved deliberately):
 * - The **copy constructor does NOT copy `useSystemSending`** — a copy always
 *   starts with the field default (`false`), regardless of the source.
 * - The **full constructor sets `subscriptionId` via a direct null-check**, not
 *   the SDK-gated [setSubscriptionId]; only the public setter applies the
 *   `LOLLIPOP_MR1` gate.
 * - The full constructor always forces `agent`/`userProfileUrl`/`uaProfTagName`
 *   to `""` (they are only settable afterwards via their setters).
 */
class Settings {

    // MMS options
    var mmsc: String = ""
    var proxy: String = ""
    var port: String = ""
    var agent: String = ""
    var userProfileUrl: String = ""
    var uaProfTagName: String = ""
    var group: Boolean = false

    /**
     * Whether to use the system sending method (Lollipop+). The setter mirrors
     * the vendored guard: on pre-Lollipop it forces `false` and logs. The
     * property initializer assigns the backing field directly (no setter), so a
     * fresh/copied instance defaults to `false`.
     */
    var useSystemSending: Boolean = false
        set(value) {
            field = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                value
            } else {
                Log.e("Settings", "System sending only available on Lollipop+ devices")
                false
            }
        }

    // SMS options
    var deliveryReports: Boolean = false
    var split: Boolean = false
    var splitCounter: Boolean = false
    var stripUnicode: Boolean = false
    var signature: String = ""
    var preText: String = ""
    var sendLongAsMms: Boolean = false
    var sendLongAsMmsAfter: Int = 0

    // SIM options — asymmetric accessors (getter Int, setter Integer-with-logic),
    // so kept as explicit functions over a private backing field rather than a
    // property. (`agent`/`userProfileUrl` above already generate the original
    // `getAgent`/`getUserProfileUrl` accessor names from their property names.)
    private var subscriptionIdValue: Int = DEFAULT_SUBSCRIPTION_ID

    /** Default constructor — delegates to the full constructor exactly as the
     *  vendored source did (note `useSystemSending = true` and `port = "0"`). */
    constructor() : this(
        "", "", "0", true, false, false, false, false, "", "", true, 3, true,
        DEFAULT_SUBSCRIPTION_ID,
    )

    /** Copy constructor. Mirrors the vendored copy: copies every field EXCEPT
     *  `useSystemSending` (left at its `false` default), and copies
     *  `subscriptionId` directly (no SDK gating). */
    constructor(s: Settings) {
        this.mmsc = s.mmsc
        this.proxy = s.proxy
        this.port = s.port
        this.agent = s.agent
        this.userProfileUrl = s.userProfileUrl
        this.uaProfTagName = s.uaProfTagName
        this.group = s.group
        this.deliveryReports = s.deliveryReports
        this.split = s.split
        this.splitCounter = s.splitCounter
        this.stripUnicode = s.stripUnicode
        this.signature = s.signature
        this.preText = s.preText
        this.sendLongAsMms = s.sendLongAsMms
        this.sendLongAsMmsAfter = s.sendLongAsMmsAfter
        this.subscriptionIdValue = s.getSubscriptionId()
    }

    constructor(
        mmsc: String,
        proxy: String,
        port: String,
        group: Boolean,
        deliveryReports: Boolean,
        split: Boolean,
        splitCounter: Boolean,
        stripUnicode: Boolean,
        signature: String,
        preText: String,
        sendLongAsMms: Boolean,
        sendLongAsMmsAfter: Int,
        useSystemSending: Boolean,
        subscriptionId: Int?,
    ) {
        this.mmsc = mmsc
        this.proxy = proxy
        this.port = port
        this.agent = ""
        this.userProfileUrl = ""
        this.uaProfTagName = ""
        this.group = group
        this.deliveryReports = deliveryReports
        this.split = split
        this.splitCounter = splitCounter
        this.stripUnicode = stripUnicode
        this.signature = signature
        this.preText = preText
        this.sendLongAsMms = sendLongAsMms
        this.sendLongAsMmsAfter = sendLongAsMmsAfter
        this.useSystemSending = useSystemSending

        this.subscriptionIdValue = subscriptionId ?: DEFAULT_SUBSCRIPTION_ID
    }

    /** @return the subscription ID, or [DEFAULT_SUBSCRIPTION_ID] if unset. */
    fun getSubscriptionId(): Int = subscriptionIdValue

    /**
     * Sets the subscription ID for sending. Mirrors the vendored SDK gate: on
     * devices below `LOLLIPOP_MR1`, or when [subscriptionId] is null, the
     * default is kept.
     */
    fun setSubscriptionId(subscriptionId: Int?) {
        this.subscriptionIdValue =
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP_MR1 || subscriptionId == null) {
                DEFAULT_SUBSCRIPTION_ID
            } else {
                subscriptionId
            }
    }

    /**
     * Purpose: pass the user's group choice while Android supplies carrier transport settings.
     *
     * APN/MMSC, HTTP parameters, and message-size limits belong to the selected
     * subscription's platform MMS stack. UNFY-123 removes the inherited fixed
     * 800 KiB override instead of replacing Android's carrier configuration.
     *
     * @return a fresh Bundle containing only the group-MMS preference.
     */
    internal fun toMmsConfigOverrides(): Bundle = Bundle().apply {
        putBoolean(SmsManager.MMS_CONFIG_GROUP_MMS_ENABLED, group)
    }

    companion object {
        const val DEFAULT_SUBSCRIPTION_ID = -1
    }
}
