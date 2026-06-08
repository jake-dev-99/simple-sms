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
 * First-party Kotlin port of the vendored `ReadRecInd` (Phase 5 · pdu_alt), a
 * [GenericPdu] subclass. Behaviour-faithful 1:1. The compose constructor calls
 * the inherited [GenericPdu.setFrom] (no `from` override here, unlike
 * `ReadOrigInd`). The `(PduHeaders)` parse constructor is widened
 * package-private → `public` for the same-package non-subclass `PduParser`;
 * getters are `val` properties; `mPduHeaders` is dereferenced with `!!`,
 * mirroring the vendored bare derefs.
 */
class ReadRecInd : GenericPdu {
    /**
     * Constructor, used when composing a M-ReadRec.ind pdu.
     *
     * @param from the from value
     * @param messageId the message ID value
     * @param mmsVersion current viersion of mms
     * @param readStatus the read status value
     * @param to the to value
     * @throws InvalidHeaderValueException if parameters are invalid.
     *         NullPointerException if messageId or to is null.
     */
    @Throws(InvalidHeaderValueException::class)
    constructor(
        from: EncodedStringValue?,
        messageId: ByteArray?,
        mmsVersion: Int,
        readStatus: Int,
        to: Array<EncodedStringValue>?,
    ) : super() {
        setMessageType(PduHeaders.MESSAGE_TYPE_READ_REC_IND)
        setFrom(from)
        setMessageId(messageId)
        setMmsVersion(mmsVersion)
        setTo(to)
        setReadStatus(readStatus)
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
     * @param value the value
     * @throws NullPointerException if the value is null.
     */
    fun setMessageId(value: ByteArray?) {
        mPduHeaders!!.setTextString(value, PduHeaders.MESSAGE_ID)
    }

    /**
     * Get To value.
     *
     * @return the value
     */
    val to: Array<EncodedStringValue>?
        get() = mPduHeaders!!.getEncodedStringValues(PduHeaders.TO)

    /**
     * Set To value.
     *
     * @param value the value
     * @throws NullPointerException if the value is null.
     */
    fun setTo(value: Array<EncodedStringValue>?) {
        mPduHeaders!!.setEncodedStringValues(value, PduHeaders.TO)
    }

    /**
     * Get X-MMS-Read-status value.
     *
     * @return the value
     */
    val readStatus: Int
        get() = mPduHeaders!!.getOctet(PduHeaders.READ_STATUS)

    /**
     * Set X-MMS-Read-status value.
     *
     * @param value the value
     * @throws InvalidHeaderValueException if the value is invalid.
     */
    @Throws(InvalidHeaderValueException::class)
    fun setReadStatus(value: Int) {
        mPduHeaders!!.setOctet(value, PduHeaders.READ_STATUS)
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
     */
}
