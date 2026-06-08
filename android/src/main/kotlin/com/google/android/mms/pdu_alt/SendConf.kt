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
 * First-party Kotlin port of the vendored `SendConf` (Phase 5 · pdu_alt) — the
 * M-Send.conf PDU, a [GenericPdu] subclass. Behaviour-faithful 1:1. The
 * `(PduHeaders)` parse constructor is widened package-private → `public` for the
 * same-package non-subclass `PduParser`; getters are `val` properties;
 * `mPduHeaders` is dereferenced with `!!`, mirroring the vendored bare derefs.
 * (`getTransactionId`'s `@return` keeps the vendored copy-paste
 * "X-Mms-Report-Allowed" wording verbatim — parked on the codec-modernization
 * doc pass.)
 */
class SendConf : GenericPdu {
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
        setMessageType(PduHeaders.MESSAGE_TYPE_SEND_CONF)
    }

    /**
     * Constructor with given headers.
     *
     * @param headers Headers for this PDU.
     */
    constructor(headers: PduHeaders?) : super(headers)

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
     * @param value the value
     * @throws NullPointerException if the value is null.
     */
    fun setMessageId(value: ByteArray?) {
        mPduHeaders!!.setTextString(value, PduHeaders.MESSAGE_ID)
    }

    /**
     * Get X-Mms-Response-Status.
     *
     * @return the value
     */
    val responseStatus: Int
        get() = mPduHeaders!!.getOctet(PduHeaders.RESPONSE_STATUS)

    /**
     * Set X-Mms-Response-Status.
     *
     * @param value the values
     * @throws InvalidHeaderValueException if the value is invalid.
     */
    @Throws(InvalidHeaderValueException::class)
    fun setResponseStatus(value: Int) {
        mPduHeaders!!.setOctet(value, PduHeaders.RESPONSE_STATUS)
    }

    /**
     * Get X-Mms-Transaction-Id field value.
     *
     * @return the X-Mms-Report-Allowed value
     */
    val transactionId: ByteArray?
        get() = mPduHeaders!!.getTextString(PduHeaders.TRANSACTION_ID)

    /**
     * Set X-Mms-Transaction-Id field value.
     *
     * @param value the value
     * @throws NullPointerException if the value is null.
     */
    fun setTransactionId(value: ByteArray?) {
        mPduHeaders!!.setTextString(value, PduHeaders.TRANSACTION_ID)
    }

    /*
     * Optional, not supported header fields:
     *
     *    public byte[] getContentLocation() {return null;}
     *    public void setContentLocation(byte[] value) {}
     *
     *    public EncodedStringValue getResponseText() {return null;}
     *    public void setResponseText(EncodedStringValue value) {}
     *
     *    public byte getStoreStatus() {return 0x00;}
     *    public void setStoreStatus(byte value) {}
     *
     *    public byte[] getStoreStatusText() {return null;}
     *    public void setStoreStatusText(byte[] value) {}
     */
}
