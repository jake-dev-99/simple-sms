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
 * M-Notification.ind PDU.
 *
 * First-party Kotlin port of the vendored `NotificationInd` (Phase 5 · pdu_alt),
 * a [GenericPdu] subclass. Behaviour-faithful 1:1. `from`/`setFrom` are
 * `override`s of [GenericPdu]'s `open` `from`/`setFrom` (the vendored re-declares
 * them with identical bodies). The `(PduHeaders)` parse constructor is widened
 * package-private → `public` for the same-package non-subclass `PduParser`; the
 * new getters are `val` properties; `mPduHeaders` is dereferenced with `!!`,
 * mirroring the vendored bare derefs.
 */
class NotificationInd : GenericPdu {
    /**
     * Empty constructor.
     * Since the Pdu corresponding to this class is constructed
     * by the Proxy-Relay server, this class is only instantiated
     * by the Pdu Parser.
     *
     * @throws InvalidHeaderValueException if error occurs.
     *         RuntimeException if an undeclared error occurs.
     */
    @Throws(InvalidHeaderValueException::class)
    constructor() : super() {
        setMessageType(PduHeaders.MESSAGE_TYPE_NOTIFICATION_IND)
    }

    /**
     * Constructor with given headers.
     *
     * @param headers Headers for this PDU.
     */
    constructor(headers: PduHeaders?) : super(headers)

    /**
     * Get X-Mms-Content-Class Value.
     *
     * @return the value
     */
    val contentClass: Int
        get() = mPduHeaders!!.getOctet(PduHeaders.CONTENT_CLASS)

    /**
     * Set X-Mms-Content-Class Value.
     *
     * @param value the value
     * @throws InvalidHeaderValueException if the value is invalid.
     *         RuntimeException if an undeclared error occurs.
     */
    @Throws(InvalidHeaderValueException::class)
    fun setContentClass(value: Int) {
        mPduHeaders!!.setOctet(value, PduHeaders.CONTENT_CLASS)
    }

    /**
     * Get X-Mms-Content-Location value.
     * When used in a PDU other than M-Mbox-Delete.conf and M-Delete.conf:
     * Content-location-value = Uri-value
     *
     * @return the value
     */
    val contentLocation: ByteArray?
        get() = mPduHeaders!!.getTextString(PduHeaders.CONTENT_LOCATION)

    /**
     * Set X-Mms-Content-Location value.
     *
     * @param value the value
     * @throws NullPointerException if the value is null.
     *         RuntimeException if an undeclared error occurs.
     */
    fun setContentLocation(value: ByteArray?) {
        mPduHeaders!!.setTextString(value, PduHeaders.CONTENT_LOCATION)
    }

    /**
     * Get X-Mms-Expiry value.
     *
     * Expiry-value = Value-length
     *      (Absolute-token Date-value | Relative-token Delta-seconds-value)
     *
     * @return the value
     */
    val expiry: Long
        get() = mPduHeaders!!.getLongInteger(PduHeaders.EXPIRY)

    /**
     * Set X-Mms-Expiry value.
     *
     * @param value the value
     * @throws RuntimeException if an undeclared error occurs.
     */
    fun setExpiry(value: Long) {
        mPduHeaders!!.setLongInteger(value, PduHeaders.EXPIRY)
    }

    /**
     * Get From value.
     * From-value = Value-length
     *      (Address-present-token Encoded-string-value | Insert-address-token)
     *
     * @return the value
     */
    override val from: EncodedStringValue?
        get() = mPduHeaders!!.getEncodedStringValue(PduHeaders.FROM)

    /**
     * Set From value.
     *
     * @param value the value
     * @throws NullPointerException if the value is null.
     *         RuntimeException if an undeclared error occurs.
     */
    override fun setFrom(value: EncodedStringValue?) {
        mPduHeaders!!.setEncodedStringValue(value, PduHeaders.FROM)
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
     *         RuntimeException if an undeclared error occurs.
     */
    fun setMessageClass(value: ByteArray?) {
        mPduHeaders!!.setTextString(value, PduHeaders.MESSAGE_CLASS)
    }

    /**
     * Get X-Mms-Message-Size value.
     * Message-size-value = Long-integer
     *
     * @return the value
     */
    val messageSize: Long
        get() = mPduHeaders!!.getLongInteger(PduHeaders.MESSAGE_SIZE)

    /**
     * Set X-Mms-Message-Size value.
     *
     * @param value the value
     * @throws RuntimeException if an undeclared error occurs.
     */
    fun setMessageSize(value: Long) {
        mPduHeaders!!.setLongInteger(value, PduHeaders.MESSAGE_SIZE)
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
     *         RuntimeException if an undeclared error occurs.
     */
    fun setSubject(value: EncodedStringValue?) {
        mPduHeaders!!.setEncodedStringValue(value, PduHeaders.SUBJECT)
    }

    /**
     * Get X-Mms-Transaction-Id.
     *
     * @return the value
     */
    val transactionId: ByteArray?
        get() = mPduHeaders!!.getTextString(PduHeaders.TRANSACTION_ID)

    /**
     * Set X-Mms-Transaction-Id.
     *
     * @param value the value
     * @throws NullPointerException if the value is null.
     *         RuntimeException if an undeclared error occurs.
     */
    fun setTransactionId(value: ByteArray?) {
        mPduHeaders!!.setTextString(value, PduHeaders.TRANSACTION_ID)
    }

    /**
     * Get X-Mms-Delivery-Report Value.
     *
     * @return the value
     */
    val deliveryReport: Int
        get() = mPduHeaders!!.getOctet(PduHeaders.DELIVERY_REPORT)

    /**
     * Set X-Mms-Delivery-Report Value.
     *
     * @param value the value
     * @throws InvalidHeaderValueException if the value is invalid.
     *         RuntimeException if an undeclared error occurs.
     */
    @Throws(InvalidHeaderValueException::class)
    fun setDeliveryReport(value: Int) {
        mPduHeaders!!.setOctet(value, PduHeaders.DELIVERY_REPORT)
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
     *     public byte getDrmContent() {return 0x00;}
     *     public void setDrmContent(byte value) {}
     *
     *     public byte getDistributionIndicator() {return 0x00;}
     *     public void setDistributionIndicator(byte value) {}
     *
     *     public ElementDescriptorValue getElementDescriptor() {return null;}
     *     public void getElementDescriptor(ElementDescriptorValue value) {}
     *
     *     public byte getPriority() {return 0x00;}
     *     public void setPriority(byte value) {}
     *
     *     public byte getRecommendedRetrievalMode() {return 0x00;}
     *     public void setRecommendedRetrievalMode(byte value) {}
     *
     *     public byte getRecommendedRetrievalModeText() {return 0x00;}
     *     public void setRecommendedRetrievalModeText(byte value) {}
     *
     *     public byte[] getReplaceId() {return 0x00;}
     *     public void setReplaceId(byte[] value) {}
     *
     *     public byte[] getReplyApplicId() {return 0x00;}
     *     public void setReplyApplicId(byte[] value) {}
     *
     *     public byte getReplyCharging() {return 0x00;}
     *     public void setReplyCharging(byte value) {}
     *
     *     public byte getReplyChargingDeadline() {return 0x00;}
     *     public void setReplyChargingDeadline(byte value) {}
     *
     *     public byte[] getReplyChargingId() {return 0x00;}
     *     public void setReplyChargingId(byte[] value) {}
     *
     *     public long getReplyChargingSize() {return 0;}
     *     public void setReplyChargingSize(long value) {}
     *
     *     public byte getStored() {return 0x00;}
     *     public void setStored(byte value) {}
     */
}
