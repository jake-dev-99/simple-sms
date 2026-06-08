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
 * M-Delivery.Ind Pdu.
 *
 * First-party Kotlin port of the vendored `DeliveryInd` (Phase 5 · pdu_alt), a
 * [GenericPdu] subclass. Behaviour-faithful 1:1. The `(PduHeaders)` parse
 * constructor is widened package-private → `public` for the same-package
 * non-subclass `PduParser` (Kotlin can't express Java package-private);
 * getters are `val` properties and `mPduHeaders` is dereferenced with `!!`,
 * mirroring the vendored bare derefs.
 */
class DeliveryInd : GenericPdu {
    /**
     * Empty constructor.
     * Since the Pdu corresponding to this class is constructed
     * by the Proxy-Relay server, this class is only instantiated
     * by the Pdu Parser.
     *
     * @throws InvalidHeaderValueException if error occurs.
     */
    @Throws(InvalidHeaderValueException::class)
    constructor() : super() {
        setMessageType(PduHeaders.MESSAGE_TYPE_DELIVERY_IND)
    }

    /**
     * Constructor with given headers.
     *
     * @param headers Headers for this PDU.
     */
    constructor(headers: PduHeaders?) : super(headers)

    /**
     * Get Date value.
     *
     * @return the value
     */
    val date: Long
        get() = mPduHeaders!!.getLongInteger(PduHeaders.DATE)

    /**
     * Set Date value.
     *
     * @param value the value
     */
    fun setDate(value: Long) {
        mPduHeaders!!.setLongInteger(value, PduHeaders.DATE)
    }

    /**
     * Get Message-ID value.
     *
     * @return the value
     */
    val messageId: ByteArray?
        get() = mPduHeaders!!.getTextString(PduHeaders.MESSAGE_ID)

    /**
     * Set Message-ID value.
     *
     * @param value the value, should not be null
     * @throws NullPointerException if the value is null.
     */
    fun setMessageId(value: ByteArray?) {
        mPduHeaders!!.setTextString(value, PduHeaders.MESSAGE_ID)
    }

    /**
     * Get Status value.
     *
     * @return the value
     */
    val status: Int
        get() = mPduHeaders!!.getOctet(PduHeaders.STATUS)

    /**
     * Set Status value.
     *
     * @param value the value
     * @throws InvalidHeaderValueException if the value is invalid.
     */
    @Throws(InvalidHeaderValueException::class)
    fun setStatus(value: Int) {
        mPduHeaders!!.setOctet(value, PduHeaders.STATUS)
    }

    /**
     * Get To value.
     *
     * @return the value
     */
    val to: Array<EncodedStringValue>?
        get() = mPduHeaders!!.getEncodedStringValues(PduHeaders.TO)

    /**
     * set To value.
     *
     * @param value the value
     * @throws NullPointerException if the value is null.
     */
    fun setTo(value: Array<EncodedStringValue>?) {
        mPduHeaders!!.setEncodedStringValues(value, PduHeaders.TO)
    }

    /*
     * Optional, not supported header fields:
     *
     *     public byte[] getApplicId() {return null;}
     *     public void setApplicId(byte[] value) {}
     *
     *     public byte[] getAuxApplicId() {return null;}
     *     public void getAuxApplicId(byte[] value) {}
     *
     *     public byte[] getReplyApplicId() {return 0x00;}
     *     public void setReplyApplicId(byte[] value) {}
     *
     *     public EncodedStringValue getStatusText() {return null;}
     *     public void setStatusText(EncodedStringValue value) {}
     */
}
