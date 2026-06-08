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
 * M-Acknowledge.ind PDU.
 *
 * First-party Kotlin port of the vendored `AcknowledgeInd` (Phase 5 · pdu_alt),
 * a [GenericPdu] subclass. Behaviour-faithful 1:1.
 *
 * The `(PduHeaders)` parse-constructor (package-private in the vendored Java) is
 * widened to `public`: its caller `PduParser` is a same-package non-subclass,
 * and Kotlin can't express Java package-private (`internal` name-mangles out of
 * Java's reach). The getters are `val` properties (compiling to the vendored
 * `getReportAllowed()`/`getTransactionId()`); `mPduHeaders` is dereferenced with
 * `!!`, mirroring the vendored bare derefs.
 */
class AcknowledgeInd : GenericPdu {

    /**
     * Constructor, used when composing a M-Acknowledge.ind pdu.
     *
     * @param mmsVersion current viersion of mms
     * @param transactionId the transaction-id value
     * @throws InvalidHeaderValueException if parameters are invalid.
     *         NullPointerException if transactionId is null.
     */
    @Throws(InvalidHeaderValueException::class)
    constructor(mmsVersion: Int, transactionId: ByteArray?) : super() {
        setMessageType(PduHeaders.MESSAGE_TYPE_ACKNOWLEDGE_IND)
        setMmsVersion(mmsVersion)
        setTransactionId(transactionId)
    }

    /**
     * Constructor with given headers.
     *
     * @param headers Headers for this PDU.
     */
    constructor(headers: PduHeaders?) : super(headers)

    /**
     * Get X-Mms-Report-Allowed field value.
     *
     * @return the X-Mms-Report-Allowed value
     */
    val reportAllowed: Int
        get() = mPduHeaders!!.getOctet(PduHeaders.REPORT_ALLOWED)

    /**
     * Set X-Mms-Report-Allowed field value.
     *
     * @param value the value
     * @throws InvalidHeaderValueException if the value is invalid.
     */
    @Throws(InvalidHeaderValueException::class)
    fun setReportAllowed(value: Int) {
        mPduHeaders!!.setOctet(value, PduHeaders.REPORT_ALLOWED)
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
}
