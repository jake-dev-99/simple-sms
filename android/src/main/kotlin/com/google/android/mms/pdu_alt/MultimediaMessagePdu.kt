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
 * Multimedia message PDU.
 *
 * First-party Kotlin port of the vendored `MultimediaMessagePdu` (Phase 5 ·
 * pdu_alt), a [GenericPdu] subclass and the base of `RetrieveConf`/`SendReq`.
 * Behaviour-faithful 1:1.
 *
 * `open` so the still-Java `RetrieveConf`/`SendReq` keep extending it. The
 * single-arg `(PduHeaders)` constructor is widened package-private → `protected`
 * (only those subclasses call `super(headers)`); the no-arg and
 * `(PduHeaders, PduBody)` constructors stay `public` as in the vendored. The
 * getters are `val` properties (compiling to the vendored
 * `getBody()`/`getSubject()`/`getTo()`/`getPriority()`/`getDate()`); `mPduHeaders`
 * is dereferenced with `!!`, mirroring the vendored bare derefs. `mMessageBody`
 * stays a private field (the vendored field is private; subclasses use
 * `getBody`/`setBody`).
 */
open class MultimediaMessagePdu : GenericPdu {
    /**
     * The body.
     */
    private var mMessageBody: PduBody? = null

    /**
     * Constructor.
     */
    constructor() : super()

    /**
     * Constructor.
     *
     * @param header the header of this PDU
     * @param body the body of this PDU
     */
    constructor(header: PduHeaders?, body: PduBody?) : super(header) {
        mMessageBody = body
    }

    /**
     * Constructor with given headers.
     *
     * @param headers Headers for this PDU.
     */
    protected constructor(headers: PduHeaders?) : super(headers)

    /**
     * Get body of the PDU.
     *
     * @return the body
     */
    val body: PduBody?
        get() = mMessageBody

    /**
     * Set body of the PDU.
     *
     * @param body the body
     */
    fun setBody(body: PduBody?) {
        mMessageBody = body
    }

    /**
     * Get subject.
     *
     * @return the value
     */
    val subject: EncodedStringValue?
        get() = mPduHeaders!!.getEncodedStringValue(PduHeaders.SUBJECT)

    /**
     * Set subject.
     *
     * @param value the value
     * @throws NullPointerException if the value is null.
     */
    fun setSubject(value: EncodedStringValue?) {
        mPduHeaders!!.setEncodedStringValue(value, PduHeaders.SUBJECT)
    }

    /**
     * Get To value.
     *
     * @return the value
     */
    val to: Array<EncodedStringValue>?
        get() = mPduHeaders!!.getEncodedStringValues(PduHeaders.TO)

    /**
     * Add a "To" value.
     *
     * @param value the value
     * @throws NullPointerException if the value is null.
     */
    fun addTo(value: EncodedStringValue?) {
        mPduHeaders!!.appendEncodedStringValue(value, PduHeaders.TO)
    }

    /**
     * Get X-Mms-Priority value.
     *
     * @return the value
     */
    val priority: Int
        get() = mPduHeaders!!.getOctet(PduHeaders.PRIORITY)

    /**
     * Set X-Mms-Priority value.
     *
     * @param value the value
     * @throws InvalidHeaderValueException if the value is invalid.
     */
    @Throws(InvalidHeaderValueException::class)
    fun setPriority(value: Int) {
        mPduHeaders!!.setOctet(value, PduHeaders.PRIORITY)
    }

    /**
     * Get Date value.
     *
     * @return the value
     */
    val date: Long
        get() = mPduHeaders!!.getLongInteger(PduHeaders.DATE)

    /**
     * Set Date value in seconds.
     *
     * @param value the value
     */
    fun setDate(value: Long) {
        mPduHeaders!!.setLongInteger(value, PduHeaders.DATE)
    }
}
