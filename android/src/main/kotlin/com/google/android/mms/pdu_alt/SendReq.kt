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

import android.content.Context
import android.text.TextUtils
import android.util.Log
import com.google.android.mms.InvalidHeaderValueException
import com.klinker.android.send_message.Utils

/**
 * First-party Kotlin port of the vendored `SendReq` (Phase 5 · pdu_alt) — the
 * M-Send.req PDU, a [MultimediaMessagePdu] subclass. Behaviour-faithful 1:1.
 *
 * The `(PduHeaders)` and `(PduHeaders, PduBody)` parse constructors are widened
 * package-private → `public` for the same-package non-subclass `PduParser`.
 * `setTo` is added here (the base only has the `to` getter + `addTo`). Getters
 * are `val` properties; `mPduHeaders` is dereferenced with `!!`, mirroring the
 * vendored bare derefs. (`getTransactionId`'s `@return` is corrected from the
 * vendored copy-paste "X-Mms-Report-Allowed".)
 */
class SendReq : MultimediaMessagePdu {

    constructor() : super() {
        try {
            setMessageType(PduHeaders.MESSAGE_TYPE_SEND_REQ)
            setMmsVersion(PduHeaders.CURRENT_MMS_VERSION)
            // FIXME: Content-type must be decided according to whether
            // SMIL part present.
            setContentType("application/vnd.wap.multipart.related".toByteArray())
            setFrom(EncodedStringValue(PduHeaders.FROM_INSERT_ADDRESS_TOKEN_STR.toByteArray()))
            setTransactionId(generateTransactionId())
        } catch (e: InvalidHeaderValueException) {
            // Impossible to reach here since all headers we set above are valid.
            Log.e(TAG, "Unexpected InvalidHeaderValueException.", e)
            throw RuntimeException(e)
        }
    }

    private fun generateTransactionId(): ByteArray {
        val transactionId = "T" + java.lang.Long.toHexString(System.currentTimeMillis())
        return transactionId.toByteArray()
    }

    /**
     * Constructor, used when composing a M-Send.req pdu.
     *
     * @param contentType   the content type value
     * @param from          the from value
     * @param mmsVersion    current viersion of mms
     * @param transactionId the transaction-id value
     * @throws InvalidHeaderValueException if parameters are invalid.
     *                                     NullPointerException if contentType, form
     *                                     or transactionId is null.
     */
    @Throws(InvalidHeaderValueException::class)
    constructor(
        contentType: ByteArray?,
        from: EncodedStringValue?,
        mmsVersion: Int,
        transactionId: ByteArray?,
    ) : super() {
        setMessageType(PduHeaders.MESSAGE_TYPE_SEND_REQ)
        setContentType(contentType)
        setFrom(from)
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
     * Constructor with given headers and body
     *
     * @param headers Headers for this PDU.
     * @param body    Body of this PDu.
     */
    constructor(headers: PduHeaders?, body: PduBody?) : super(headers, body)

    /**
     * Get Bcc value.
     *
     * @return the value
     */
    val bcc: Array<EncodedStringValue>?
        get() = mPduHeaders!!.getEncodedStringValues(PduHeaders.BCC)

    /**
     * Add a "BCC" value.
     *
     * @param value the value
     * @throws NullPointerException if the value is null.
     */
    fun addBcc(value: EncodedStringValue?) {
        mPduHeaders!!.appendEncodedStringValue(value, PduHeaders.BCC)
    }

    /**
     * Set "BCC" value.
     *
     * @param value the value
     * @throws NullPointerException if the value is null.
     */
    fun setBcc(value: Array<EncodedStringValue>?) {
        mPduHeaders!!.setEncodedStringValues(value, PduHeaders.BCC)
    }

    /**
     * Get CC value.
     *
     * @return the value
     */
    val cc: Array<EncodedStringValue>?
        get() = mPduHeaders!!.getEncodedStringValues(PduHeaders.CC)

    /**
     * Add a "CC" value.
     *
     * @param value the value
     * @throws NullPointerException if the value is null.
     */
    fun addCc(value: EncodedStringValue?) {
        mPduHeaders!!.appendEncodedStringValue(value, PduHeaders.CC)
    }

    /**
     * Set "CC" value.
     *
     * @param value the value
     * @throws NullPointerException if the value is null.
     */
    fun setCc(value: Array<EncodedStringValue>?) {
        mPduHeaders!!.setEncodedStringValues(value, PduHeaders.CC)
    }

    /**
     * Get Content-type value.
     *
     * @return the value
     */
    val contentType: ByteArray?
        get() = mPduHeaders!!.getTextString(PduHeaders.CONTENT_TYPE)

    /**
     * Set Content-type value.
     *
     * @param value the value
     * @throws NullPointerException if the value is null.
     */
    fun setContentType(value: ByteArray?) {
        mPduHeaders!!.setTextString(value, PduHeaders.CONTENT_TYPE)
    }

    /**
     * Get X-Mms-Delivery-Report value.
     *
     * @return the value
     */
    val deliveryReport: Int
        get() = mPduHeaders!!.getOctet(PduHeaders.DELIVERY_REPORT)

    /**
     * Set X-Mms-Delivery-Report value.
     *
     * @param value the value
     * @throws InvalidHeaderValueException if the value is invalid.
     */
    @Throws(InvalidHeaderValueException::class)
    fun setDeliveryReport(value: Int) {
        mPduHeaders!!.setOctet(value, PduHeaders.DELIVERY_REPORT)
    }

    /**
     * Get X-Mms-Expiry value.
     *
     * Expiry-value = Value-length
     * (Absolute-token Date-value | Relative-token Delta-seconds-value)
     *
     * @return the value
     */
    val expiry: Long
        get() = mPduHeaders!!.getLongInteger(PduHeaders.EXPIRY)

    /**
     * Set X-Mms-Expiry value.
     *
     * @param value the value
     */
    fun setExpiry(value: Long) {
        mPduHeaders!!.setLongInteger(value, PduHeaders.EXPIRY)
    }

    /**
     * Get X-Mms-MessageSize value.
     *
     * Expiry-value = size of message
     *
     * @return the value
     */
    val messageSize: Long
        get() = mPduHeaders!!.getLongInteger(PduHeaders.MESSAGE_SIZE)

    /**
     * Set X-Mms-MessageSize value.
     *
     * @param value the value
     */
    fun setMessageSize(value: Long) {
        mPduHeaders!!.setLongInteger(value, PduHeaders.MESSAGE_SIZE)
    }

    /**
     * Get X-Mms-Message-Class value.
     * Message-class-value = Class-identifier | Token-text
     * Class-identifier = Personal | Advertisement | Informational | Auto
     *
     * @return the value
     */
    val messageClass: ByteArray?
        get() = mPduHeaders!!.getTextString(PduHeaders.MESSAGE_CLASS)

    /**
     * Set X-Mms-Message-Class value.
     *
     * @param value the value
     * @throws NullPointerException if the value is null.
     */
    fun setMessageClass(value: ByteArray?) {
        mPduHeaders!!.setTextString(value, PduHeaders.MESSAGE_CLASS)
    }

    /**
     * Get X-Mms-Read-Report value.
     *
     * @return the value
     */
    val readReport: Int
        get() = mPduHeaders!!.getOctet(PduHeaders.READ_REPORT)

    /**
     * Set X-Mms-Read-Report value.
     *
     * @param value the value
     * @throws InvalidHeaderValueException if the value is invalid.
     */
    @Throws(InvalidHeaderValueException::class)
    fun setReadReport(value: Int) {
        mPduHeaders!!.setOctet(value, PduHeaders.READ_REPORT)
    }

    /**
     * Set "To" value.
     *
     * @param value the value
     * @throws NullPointerException if the value is null.
     */
    fun setTo(value: Array<EncodedStringValue>?) {
        mPduHeaders!!.setEncodedStringValues(value, PduHeaders.TO)
    }

    /**
     * Get X-Mms-Transaction-Id field value.
     *
     * @return the X-Mms-Transaction-Id value
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

    /**
     * prepares and sets from address info in the request.
     *
     * @param context        context
     * @param fromAddress    from address info from client
     * @param subscriptionId subscription id to use
     */
    fun prepareFromAddress(context: Context, fromAddress: String?, subscriptionId: Int) {
        val phoneNumber = Utils.getMyPhoneNumberFromSubscription(context, subscriptionId)
        if (!TextUtils.isEmpty(phoneNumber)) {
            setFrom(EncodedStringValue(phoneNumber))
        } else if (!TextUtils.isEmpty(fromAddress)) {
            setFrom(EncodedStringValue(fromAddress))
        }
    }

    companion object {
        private const val TAG = "SendReq"
    }

    /*
     * Optional, not supported header fields:
     *
     * public byte getAdaptationAllowed() {return 0};
     * public void setAdaptationAllowed(btye value) {};
     *
     * public byte[] getApplicId() {return null;}
     * public void setApplicId(byte[] value) {}
     *
     * public byte[] getAuxApplicId() {return null;}
     * public void getAuxApplicId(byte[] value) {}
     *
     * public byte getContentClass() {return 0x00;}
     * public void setApplicId(byte value) {}
     *
     * public long getDeliveryTime() {return 0};
     * public void setDeliveryTime(long value) {};
     *
     * public byte getDrmContent() {return 0x00;}
     * public void setDrmContent(byte value) {}
     *
     * public MmFlagsValue getMmFlags() {return null;}
     * public void setMmFlags(MmFlagsValue value) {}
     *
     * public MmStateValue getMmState() {return null;}
     * public void getMmState(MmStateValue value) {}
     *
     * public byte[] getReplyApplicId() {return 0x00;}
     * public void setReplyApplicId(byte[] value) {}
     *
     * public byte getReplyCharging() {return 0x00;}
     * public void setReplyCharging(byte value) {}
     *
     * public byte getReplyChargingDeadline() {return 0x00;}
     * public void setReplyChargingDeadline(byte value) {}
     *
     * public byte[] getReplyChargingId() {return 0x00;}
     * public void setReplyChargingId(byte[] value) {}
     *
     * public long getReplyChargingSize() {return 0;}
     * public void setReplyChargingSize(long value) {}
     *
     * public byte[] getReplyApplicId() {return 0x00;}
     * public void setReplyApplicId(byte[] value) {}
     *
     * public byte getStore() {return 0x00;}
     * public void setStore(byte value) {}
     */
}
