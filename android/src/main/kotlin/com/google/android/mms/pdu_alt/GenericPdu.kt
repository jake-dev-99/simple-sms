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

import com.google.android.mms.InvalidHeaderValueException

/**
 * First-party Kotlin port of the vendored AOSP/Klinker `GenericPdu` (Phase 5 ·
 * `pdu_alt`). Behaviour-faithful 1:1: the base class of the whole PDU hierarchy
 * — it owns a [PduHeaders] and exposes the common Message-Type / MMS-Version /
 * From accessors that every PDU shares.
 *
 * Interop accommodations (the subclasses + codec cores are still Java):
 * - **`open`** so the still-Java subclasses (`SendReq`, `RetrieveConf`,
 *   `NotificationInd`, the `*Ind`/`*Conf` types, …) can keep extending it.
 * - **`mPduHeaders` is `@JvmField protected`** — the Java subclasses read it as
 *   an inherited field (`mPduHeaders.getOctet(...)`), which only resolves to a
 *   bare field via `@JvmField`; `protected` matches the access set (only
 *   subclasses touch the field directly).
 * - **`getPduHeaders()` is widened to `public`** — its callers (`PduComposer`,
 *   `PduPersister`) are same-package non-subclasses, and Kotlin can't express
 *   Java package-private (`internal` name-mangles out of Java's reach). This
 *   mirrors the visibility widening already applied in the `PduHeaders` port.
 * - **The getters are `open val` properties, the setters `open fun setX()`** —
 *   matching the merged `PduPart`/`PduBody` convention: the `val` compiles to
 *   the Java `getX()` signature *and* keeps Kotlin property access (`.from`,
 *   `.messageType`) working without the synthetic-property-on-a-Kotlin-`fun`
 *   deprecation. `getFrom`/`setFrom` (overridden by `ReadOrigInd`,
 *   `NotificationInd`, `RetrieveConf`) and the rest are `open`, preserving the
 *   Java default of virtual dispatch.
 * - **`mPduHeaders` typed nullable** to mirror the vendored `= null` field;
 *   the accessors deref with `!!`, matching the vendored bare derefs (same NPE
 *   if it were ever null — both constructors set it non-null).
 */
open class GenericPdu {
    /**
     * The headers of pdu.
     */
    @JvmField
    protected var mPduHeaders: PduHeaders? = null

    /**
     * Constructor.
     */
    constructor() {
        mPduHeaders = PduHeaders()
    }

    /**
     * Constructor.
     *
     * @param headers Headers for this PDU.
     */
    protected constructor(headers: PduHeaders?) {
        mPduHeaders = headers
    }

    /**
     * Get the headers of this PDU.
     *
     * @return A PduHeaders of this PDU.
     */
    fun getPduHeaders(): PduHeaders? {
        return mPduHeaders
    }

    /**
     * Get X-Mms-Message-Type field value.
     *
     * @return the X-Mms-Message-Type value
     */
    open val messageType: Int
        get() = mPduHeaders!!.getOctet(PduHeaders.MESSAGE_TYPE)

    /**
     * Set X-Mms-Message-Type field value.
     *
     * @param value the value
     * @throws InvalidHeaderValueException if the value is invalid.
     *         RuntimeException if field's value is not Octet.
     */
    @Throws(InvalidHeaderValueException::class)
    open fun setMessageType(value: Int) {
        mPduHeaders!!.setOctet(value, PduHeaders.MESSAGE_TYPE)
    }

    /**
     * Get X-Mms-MMS-Version field value.
     *
     * @return the X-Mms-MMS-Version value
     */
    open val mmsVersion: Int
        get() = mPduHeaders!!.getOctet(PduHeaders.MMS_VERSION)

    /**
     * Set X-Mms-MMS-Version field value.
     *
     * @param value the value
     * @throws InvalidHeaderValueException if the value is invalid.
     *         RuntimeException if field's value is not Octet.
     */
    @Throws(InvalidHeaderValueException::class)
    open fun setMmsVersion(value: Int) {
        mPduHeaders!!.setOctet(value, PduHeaders.MMS_VERSION)
    }

    /**
     * Get From value.
     * From-value = Value-length
     *      (Address-present-token Encoded-string-value | Insert-address-token)
     *
     * @return the value
     */
    open val from: EncodedStringValue?
        get() = mPduHeaders!!.getEncodedStringValue(PduHeaders.FROM)

    /**
     * Set From value.
     *
     * @param value the value
     * @throws NullPointerException if the value is null.
     */
    open fun setFrom(value: EncodedStringValue?) {
        mPduHeaders!!.setEncodedStringValue(value, PduHeaders.FROM)
    }
}
