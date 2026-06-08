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

import android.util.Log
import com.android.mms.util.ExternalLogger
import com.google.android.mms.ContentType
import com.google.android.mms.InvalidHeaderValueException
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.UnsupportedEncodingException
import java.util.Arrays

/**
 * First-party Kotlin port of the vendored `PduParser` (Phase 5 · pdu_alt) — the
 * inbound WSP/MMS byte decoder. Behaviour-faithful 1:1: the same WSP primitive
 * readers (uintvar / value-length / short- & long-integer / Text-String /
 * Encoded-string-value), the same header switch, the same multipart body walk,
 * and the same `parse()` → typed-PDU dispatch, so any well-formed PDU decodes
 * identically (pinned by `PduInboundGoldenTest` and `PduCodecHarnessTest`).
 *
 * Faithful-port notes:
 * - The vendored `static` `mTypeParam`/`mStartParam` fields are preserved as
 *   companion state — a deliberate vendored quirk: `parseHeaders` stashes the
 *   Content-Type `type`/`start` parameters there so the later
 *   `checkPartPosition` can recognise the presentation/root part.
 * - The `protected`/`protected static` members are made `private` — `PduParser`
 *   has no subclasses and nothing calls these helpers externally.
 * - The dead `if (pduDataStream == null)` guards are dropped: the field/params
 *   are non-null and `ByteArrayInputStream(...)` never returns null. The
 *   `assert(...)` debug checks (no-ops with `-ea` off, the default) are dropped.
 * - `EncodedStringValue.getTextString()` is non-null in the Kotlin port, so the
 *   vendored `if (null != address)` guards in the TO/CC/BCC and FROM arms are
 *   always true — the body runs unconditionally (exactly the address-present
 *   path the Java took). `new String(x)` / `x.getBytes()` map to Kotlin
 *   `String(x)` / `x.toByteArray()` (UTF-8 — identical to Android's default
 *   charset).
 */
class PduParser(pduDataStream: ByteArray, parseContentDisposition: Boolean) {

    /** The pdu data. */
    private val mPduDataStream: ByteArrayInputStream = ByteArrayInputStream(pduDataStream)

    /** Store pdu headers. */
    private var mHeaders: PduHeaders? = null

    /** Store pdu parts. */
    private var mBody: PduBody? = null

    /** Whether to parse content-disposition part header. */
    private val mParseContentDisposition: Boolean = parseContentDisposition

    /**
     * Constructor. Default the parsing content disposition.
     *
     * @param pduDataStream pdu data to be parsed.
     */
    constructor(pduDataStream: ByteArray) : this(pduDataStream, true)

    /**
     * Parse the pdu.
     *
     * @return the pdu structure if parsing successfully.
     *         null if parsing error happened or mandatory fields are not set.
     */
    fun parse(): GenericPdu? {
        /* parse headers */
        mHeaders = parseHeaders(mPduDataStream)
        if (null == mHeaders) {
            // Parse headers failed.
            return null
        }

        /* get the message type */
        val messageType = mHeaders!!.getOctet(PduHeaders.MESSAGE_TYPE)

        /* check mandatory header fields */
        if (false == checkMandatoryHeader(mHeaders)) {
            log("check mandatory headers failed!")
            return null
        }

        if ((PduHeaders.MESSAGE_TYPE_SEND_REQ == messageType) ||
            (PduHeaders.MESSAGE_TYPE_RETRIEVE_CONF == messageType)
        ) {
            /* need to parse the parts */
            mBody = parseParts(mPduDataStream)
            if (null == mBody) {
                // Parse parts failed.
                return null
            }
        }

        when (messageType) {
            PduHeaders.MESSAGE_TYPE_SEND_REQ -> {
                if (LOCAL_LOGV) {
                    Log.v(LOG_TAG, "parse: MESSAGE_TYPE_SEND_REQ")
                }
                return SendReq(mHeaders, mBody)
            }
            PduHeaders.MESSAGE_TYPE_SEND_CONF -> {
                if (LOCAL_LOGV) {
                    Log.v(LOG_TAG, "parse: MESSAGE_TYPE_SEND_CONF")
                }
                return SendConf(mHeaders)
            }
            PduHeaders.MESSAGE_TYPE_NOTIFICATION_IND -> {
                if (LOCAL_LOGV) {
                    Log.v(LOG_TAG, "parse: MESSAGE_TYPE_NOTIFICATION_IND")
                }
                return NotificationInd(mHeaders)
            }
            PduHeaders.MESSAGE_TYPE_NOTIFYRESP_IND -> {
                if (LOCAL_LOGV) {
                    Log.v(LOG_TAG, "parse: MESSAGE_TYPE_NOTIFYRESP_IND")
                }
                return NotifyRespInd(mHeaders)
            }
            PduHeaders.MESSAGE_TYPE_RETRIEVE_CONF -> {
                if (LOCAL_LOGV) {
                    Log.v(LOG_TAG, "parse: MESSAGE_TYPE_RETRIEVE_CONF")
                }
                val retrieveConf = RetrieveConf(mHeaders, mBody)

                val contentType = retrieveConf.contentType
                if (null == contentType) {
                    return null
                }
                val ctTypeStr = String(contentType)
                if (ctTypeStr == ContentType.MULTIPART_MIXED ||
                    ctTypeStr == ContentType.MULTIPART_RELATED ||
                    ctTypeStr == ContentType.MULTIPART_ALTERNATIVE
                ) {
                    // The MMS content type must be "application/vnd.wap.multipart.mixed"
                    // or "application/vnd.wap.multipart.related"
                    // or "application/vnd.wap.multipart.alternative"
                    return retrieveConf
                } else if (ctTypeStr == ContentType.MULTIPART_ALTERNATIVE) {
                    // "application/vnd.wap.multipart.alternative"
                    // should take only the first part.
                    val firstPart = mBody!!.getPart(0)
                    mBody!!.removeAll()
                    mBody!!.addPart(0, firstPart)
                    return retrieveConf
                } else if (ctTypeStr == ContentType.MULTIPART_SIGNED) {
                    // multipart/signed
                    return retrieveConf
                } else {
                    ExternalLogger.logMessage(LOG_TAG, "Unsupported ContentType: $ctTypeStr")
                }
                return null
            }
            PduHeaders.MESSAGE_TYPE_DELIVERY_IND -> {
                if (LOCAL_LOGV) {
                    Log.v(LOG_TAG, "parse: MESSAGE_TYPE_DELIVERY_IND")
                }
                return DeliveryInd(mHeaders)
            }
            PduHeaders.MESSAGE_TYPE_ACKNOWLEDGE_IND -> {
                if (LOCAL_LOGV) {
                    Log.v(LOG_TAG, "parse: MESSAGE_TYPE_ACKNOWLEDGE_IND")
                }
                return AcknowledgeInd(mHeaders)
            }
            PduHeaders.MESSAGE_TYPE_READ_ORIG_IND -> {
                if (LOCAL_LOGV) {
                    Log.v(LOG_TAG, "parse: MESSAGE_TYPE_READ_ORIG_IND")
                }
                return ReadOrigInd(mHeaders)
            }
            PduHeaders.MESSAGE_TYPE_READ_REC_IND -> {
                if (LOCAL_LOGV) {
                    Log.v(LOG_TAG, "parse: MESSAGE_TYPE_READ_REC_IND")
                }
                return ReadRecInd(mHeaders)
            }
            else -> {
                log("Parser doesn't support this message type in this version!")
                return null
            }
        }
    }

    /**
     * Parse pdu headers.
     *
     * @param pduDataStream pdu data input stream
     * @return headers in PduHeaders structure, null when parse fail
     */
    private fun parseHeaders(pduDataStream: ByteArrayInputStream): PduHeaders? {
        var keepParsing = true
        val headers = PduHeaders()

        while (keepParsing && (pduDataStream.available() > 0)) {
            pduDataStream.mark(1)
            val headerField = extractByteValue(pduDataStream)
            /* parse custom text header */
            if ((headerField >= TEXT_MIN) && (headerField <= TEXT_MAX)) {
                pduDataStream.reset()
                val bVal = parseWapString(pduDataStream, TYPE_TEXT_STRING)
                if (LOCAL_LOGV) {
                    Log.v(LOG_TAG, "TextHeader: " + String(bVal!!))
                }
                /* we should ignore it at the moment */
                continue
            }
            when (headerField) {
                PduHeaders.MESSAGE_TYPE -> {
                    val messageType = extractByteValue(pduDataStream)
                    if (LOCAL_LOGV) {
                        Log.v(LOG_TAG, "parseHeaders: messageType: $messageType")
                    }
                    when (messageType) {
                        // We don't support these kind of messages now.
                        PduHeaders.MESSAGE_TYPE_FORWARD_REQ,
                        PduHeaders.MESSAGE_TYPE_FORWARD_CONF,
                        PduHeaders.MESSAGE_TYPE_MBOX_STORE_REQ,
                        PduHeaders.MESSAGE_TYPE_MBOX_STORE_CONF,
                        PduHeaders.MESSAGE_TYPE_MBOX_VIEW_REQ,
                        PduHeaders.MESSAGE_TYPE_MBOX_VIEW_CONF,
                        PduHeaders.MESSAGE_TYPE_MBOX_UPLOAD_REQ,
                        PduHeaders.MESSAGE_TYPE_MBOX_UPLOAD_CONF,
                        PduHeaders.MESSAGE_TYPE_MBOX_DELETE_REQ,
                        PduHeaders.MESSAGE_TYPE_MBOX_DELETE_CONF,
                        PduHeaders.MESSAGE_TYPE_MBOX_DESCR,
                        PduHeaders.MESSAGE_TYPE_DELETE_REQ,
                        PduHeaders.MESSAGE_TYPE_DELETE_CONF,
                        PduHeaders.MESSAGE_TYPE_CANCEL_REQ,
                        PduHeaders.MESSAGE_TYPE_CANCEL_CONF,
                        -> return null
                    }
                    try {
                        headers.setOctet(messageType, headerField)
                    } catch (e: InvalidHeaderValueException) {
                        log(
                            "Set invalid Octet value: " + messageType +
                                " into the header filed: " + headerField,
                        )
                        return null
                    } catch (e: RuntimeException) {
                        log("$headerField is not Octet header field!")
                        return null
                    }
                }

                /* Octect value */
                PduHeaders.REPORT_ALLOWED,
                PduHeaders.ADAPTATION_ALLOWED,
                PduHeaders.DELIVERY_REPORT,
                PduHeaders.DRM_CONTENT,
                PduHeaders.DISTRIBUTION_INDICATOR,
                PduHeaders.QUOTAS,
                PduHeaders.READ_REPORT,
                PduHeaders.STORE,
                PduHeaders.STORED,
                PduHeaders.TOTALS,
                PduHeaders.SENDER_VISIBILITY,
                PduHeaders.READ_STATUS,
                PduHeaders.CANCEL_STATUS,
                PduHeaders.PRIORITY,
                PduHeaders.STATUS,
                PduHeaders.REPLY_CHARGING,
                PduHeaders.MM_STATE,
                PduHeaders.RECOMMENDED_RETRIEVAL_MODE,
                PduHeaders.CONTENT_CLASS,
                PduHeaders.RETRIEVE_STATUS,
                PduHeaders.STORE_STATUS,
                /**
                 * The following field has a different value when
                 * used in the M-Mbox-Delete.conf and M-Delete.conf PDU.
                 * For now we ignore this fact, since we do not support these PDUs
                 */
                PduHeaders.RESPONSE_STATUS,
                -> {
                    val value = extractByteValue(pduDataStream)
                    if (LOCAL_LOGV) {
                        Log.v(LOG_TAG, "parseHeaders: byte: $headerField value: $value")
                    }

                    try {
                        headers.setOctet(value, headerField)
                    } catch (e: InvalidHeaderValueException) {
                        log(
                            "Set invalid Octet value: " + value +
                                " into the header filed: " + headerField,
                        )
                        return null
                    } catch (e: RuntimeException) {
                        log("$headerField is not Octet header field!")
                        return null
                    }
                }

                /* Long-Integer */
                PduHeaders.DATE,
                PduHeaders.REPLY_CHARGING_SIZE,
                PduHeaders.MESSAGE_SIZE,
                -> {
                    try {
                        val value = parseLongInteger(pduDataStream)
                        if (LOCAL_LOGV) {
                            Log.v(LOG_TAG, "parseHeaders: longint: $headerField value: $value")
                        }
                        headers.setLongInteger(value, headerField)
                    } catch (e: RuntimeException) {
                        log("$headerField is not Long-Integer header field!")
                        return null
                    }
                }

                /* Integer-Value */
                PduHeaders.MESSAGE_COUNT,
                PduHeaders.START,
                PduHeaders.LIMIT,
                -> {
                    try {
                        val value = parseIntegerValue(pduDataStream)
                        if (LOCAL_LOGV) {
                            Log.v(LOG_TAG, "parseHeaders: int: $headerField value: $value")
                        }
                        headers.setLongInteger(value, headerField)
                    } catch (e: RuntimeException) {
                        log("$headerField is not Long-Integer header field!")
                        return null
                    }
                }

                /* Text-String */
                PduHeaders.TRANSACTION_ID,
                PduHeaders.REPLY_CHARGING_ID,
                PduHeaders.AUX_APPLIC_ID,
                PduHeaders.APPLIC_ID,
                PduHeaders.REPLY_APPLIC_ID,
                /**
                 * The next three header fields are email addresses
                 * as defined in RFC2822,
                 * not including the characters "<" and ">"
                 */
                PduHeaders.MESSAGE_ID,
                PduHeaders.REPLACE_ID,
                PduHeaders.CANCEL_ID,
                /**
                 * The following field has a different value when
                 * used in the M-Mbox-Delete.conf and M-Delete.conf PDU.
                 * For now we ignore this fact, since we do not support these PDUs
                 */
                PduHeaders.CONTENT_LOCATION,
                -> {
                    val value = parseWapString(pduDataStream, TYPE_TEXT_STRING)
                    if (null != value) {
                        try {
                            if (LOCAL_LOGV) {
                                Log.v(
                                    LOG_TAG,
                                    "parseHeaders: string: $headerField value: " + String(value),
                                )
                            }
                            headers.setTextString(value, headerField)
                        } catch (e: NullPointerException) {
                            log("null pointer error!")
                        } catch (e: RuntimeException) {
                            log("$headerField is not Text-String header field!")
                            return null
                        }
                    }
                }

                /* Encoded-string-value */
                PduHeaders.SUBJECT,
                PduHeaders.RECOMMENDED_RETRIEVAL_MODE_TEXT,
                PduHeaders.RETRIEVE_TEXT,
                PduHeaders.STATUS_TEXT,
                PduHeaders.STORE_STATUS_TEXT,
                /*
                 * the next one is not support
                 * M-Mbox-Delete.conf and M-Delete.conf now
                 */
                PduHeaders.RESPONSE_TEXT,
                -> {
                    val value = parseEncodedStringValue(pduDataStream)
                    if (null != value) {
                        try {
                            if (LOCAL_LOGV) {
                                Log.v(
                                    LOG_TAG,
                                    "parseHeaders: encoded string: $headerField value: " + value.string,
                                )
                            }
                            headers.setEncodedStringValue(value, headerField)
                        } catch (e: NullPointerException) {
                            log("null pointer error!")
                        } catch (e: RuntimeException) {
                            log("$headerField is not Encoded-String-Value header field!")
                            return null
                        }
                    }
                }

                /* Addressing model */
                PduHeaders.BCC,
                PduHeaders.CC,
                PduHeaders.TO,
                -> {
                    val value = parseEncodedStringValue(pduDataStream)
                    if (null != value) {
                        // getTextString() is non-null in the Kotlin port, so the
                        // vendored `if (null != address)` guard always passed.
                        val address = value.getTextString()
                        var str = String(address)
                        if (LOCAL_LOGV) {
                            Log.v(
                                LOG_TAG,
                                "parseHeaders: (to/cc/bcc) address: $headerField value: $str",
                            )
                        }
                        val endIndex = str.indexOf("/")
                        if (endIndex > 0) {
                            str = str.substring(0, endIndex)
                        }
                        try {
                            value.setTextString(str.toByteArray())
                        } catch (e: NullPointerException) {
                            log("null pointer error!")
                            return null
                        }

                        try {
                            headers.appendEncodedStringValue(value, headerField)
                        } catch (e: NullPointerException) {
                            log("null pointer error!")
                        } catch (e: RuntimeException) {
                            log("$headerField is not Encoded-String-Value header field!")
                            return null
                        }
                    }
                }

                /*
                 * Value-length
                 * (Absolute-token Date-value | Relative-token Delta-seconds-value)
                 */
                PduHeaders.DELIVERY_TIME,
                PduHeaders.EXPIRY,
                PduHeaders.REPLY_CHARGING_DEADLINE,
                -> {
                    /* parse Value-length */
                    parseValueLength(pduDataStream)

                    /* Absolute-token or Relative-token */
                    val token = extractByteValue(pduDataStream)

                    /* Date-value or Delta-seconds-value */
                    var timeValue: Long
                    try {
                        timeValue = parseLongInteger(pduDataStream)
                    } catch (e: RuntimeException) {
                        log("$headerField is not Long-Integer header field!")
                        return null
                    }
                    if (PduHeaders.VALUE_RELATIVE_TOKEN == token) {
                        /*
                         * need to convert the Delta-seconds-value
                         * into Date-value
                         */
                        timeValue = System.currentTimeMillis() / 1000 + timeValue
                    }

                    try {
                        if (LOCAL_LOGV) {
                            Log.v(LOG_TAG, "parseHeaders: time value: $headerField value: $timeValue")
                        }
                        headers.setLongInteger(timeValue, headerField)
                    } catch (e: RuntimeException) {
                        log("$headerField is not Long-Integer header field!")
                        return null
                    }
                }

                PduHeaders.FROM -> {
                    /*
                     * From-value =
                     * Value-length
                     * (Address-present-token Encoded-string-value | Insert-address-token)
                     */
                    var from: EncodedStringValue? = null
                    parseValueLength(pduDataStream) /* parse value-length */

                    /* Address-present-token or Insert-address-token */
                    val fromToken = extractByteValue(pduDataStream)

                    /* Address-present-token or Insert-address-token */
                    if (PduHeaders.FROM_ADDRESS_PRESENT_TOKEN == fromToken) {
                        /* Encoded-string-value */
                        from = parseEncodedStringValue(pduDataStream)
                        if (null != from) {
                            // getTextString() is non-null in the Kotlin port, so the
                            // vendored `if (null != address)` guard always passed.
                            val address = from.getTextString()
                            var str = String(address)
                            val endIndex = str.indexOf("/")
                            if (endIndex > 0) {
                                str = str.substring(0, endIndex)
                            }
                            try {
                                from.setTextString(str.toByteArray())
                            } catch (e: NullPointerException) {
                                log("null pointer error!")
                                return null
                            }
                        }
                    } else {
                        try {
                            from = EncodedStringValue(
                                PduHeaders.FROM_INSERT_ADDRESS_TOKEN_STR.toByteArray(),
                            )
                        } catch (e: NullPointerException) {
                            log("$headerField is not Encoded-String-Value header field!")
                            return null
                        }
                    }

                    try {
                        if (LOCAL_LOGV) {
                            Log.v(
                                LOG_TAG,
                                "parseHeaders: from address: $headerField value: " + from!!.string,
                            )
                        }
                        headers.setEncodedStringValue(from, PduHeaders.FROM)
                    } catch (e: NullPointerException) {
                        log("null pointer error!")
                    } catch (e: RuntimeException) {
                        log("$headerField is not Encoded-String-Value header field!")
                        return null
                    }
                }

                PduHeaders.MESSAGE_CLASS -> {
                    /* Message-class-value = Class-identifier | Token-text */
                    pduDataStream.mark(1)
                    val messageClass = extractByteValue(pduDataStream)
                    if (LOCAL_LOGV) {
                        Log.v(LOG_TAG, "parseHeaders: MESSAGE_CLASS: $headerField value: $messageClass")
                    }

                    if (messageClass >= PduHeaders.MESSAGE_CLASS_PERSONAL) {
                        /* Class-identifier */
                        try {
                            if (PduHeaders.MESSAGE_CLASS_PERSONAL == messageClass) {
                                headers.setTextString(
                                    PduHeaders.MESSAGE_CLASS_PERSONAL_STR.toByteArray(),
                                    PduHeaders.MESSAGE_CLASS,
                                )
                            } else if (PduHeaders.MESSAGE_CLASS_ADVERTISEMENT == messageClass) {
                                headers.setTextString(
                                    PduHeaders.MESSAGE_CLASS_ADVERTISEMENT_STR.toByteArray(),
                                    PduHeaders.MESSAGE_CLASS,
                                )
                            } else if (PduHeaders.MESSAGE_CLASS_INFORMATIONAL == messageClass) {
                                headers.setTextString(
                                    PduHeaders.MESSAGE_CLASS_INFORMATIONAL_STR.toByteArray(),
                                    PduHeaders.MESSAGE_CLASS,
                                )
                            } else if (PduHeaders.MESSAGE_CLASS_AUTO == messageClass) {
                                headers.setTextString(
                                    PduHeaders.MESSAGE_CLASS_AUTO_STR.toByteArray(),
                                    PduHeaders.MESSAGE_CLASS,
                                )
                            }
                        } catch (e: NullPointerException) {
                            log("null pointer error!")
                        } catch (e: RuntimeException) {
                            log("$headerField is not Text-String header field!")
                            return null
                        }
                    } else {
                        /* Token-text */
                        pduDataStream.reset()
                        val messageClassString = parseWapString(pduDataStream, TYPE_TEXT_STRING)
                        if (null != messageClassString) {
                            try {
                                headers.setTextString(messageClassString, PduHeaders.MESSAGE_CLASS)
                            } catch (e: NullPointerException) {
                                log("null pointer error!")
                            } catch (e: RuntimeException) {
                                log("$headerField is not Text-String header field!")
                                return null
                            }
                        }
                    }
                }

                PduHeaders.MMS_VERSION -> {
                    val version = parseShortInteger(pduDataStream)

                    try {
                        if (LOCAL_LOGV) {
                            Log.v(LOG_TAG, "parseHeaders: MMS_VERSION: $headerField value: $version")
                        }
                        headers.setOctet(version, PduHeaders.MMS_VERSION)
                    } catch (e: InvalidHeaderValueException) {
                        log(
                            "Set invalid Octet value: " + version +
                                " into the header filed: " + headerField,
                        )
                        return null
                    } catch (e: RuntimeException) {
                        log("$headerField is not Octet header field!")
                        return null
                    }
                }

                PduHeaders.PREVIOUSLY_SENT_BY -> {
                    /*
                     * Previously-sent-by-value =
                     * Value-length Forwarded-count-value Encoded-string-value
                     */
                    /* parse value-length */
                    parseValueLength(pduDataStream)

                    /* parse Forwarded-count-value */
                    try {
                        parseIntegerValue(pduDataStream)
                    } catch (e: RuntimeException) {
                        log("$headerField is not Integer-Value")
                        return null
                    }

                    /* parse Encoded-string-value */
                    val previouslySentBy = parseEncodedStringValue(pduDataStream)
                    if (null != previouslySentBy) {
                        try {
                            if (LOCAL_LOGV) {
                                Log.v(
                                    LOG_TAG,
                                    "parseHeaders: PREVIOUSLY_SENT_BY: $headerField value: " +
                                        previouslySentBy.string,
                                )
                            }
                            headers.setEncodedStringValue(
                                previouslySentBy,
                                PduHeaders.PREVIOUSLY_SENT_BY,
                            )
                        } catch (e: NullPointerException) {
                            log("null pointer error!")
                        } catch (e: RuntimeException) {
                            log("$headerField is not Encoded-String-Value header field!")
                            return null
                        }
                    }
                }

                PduHeaders.PREVIOUSLY_SENT_DATE -> {
                    /*
                     * Previously-sent-date-value =
                     * Value-length Forwarded-count-value Date-value
                     */
                    /* parse value-length */
                    parseValueLength(pduDataStream)

                    /* parse Forwarded-count-value */
                    try {
                        parseIntegerValue(pduDataStream)
                    } catch (e: RuntimeException) {
                        log("$headerField is not Integer-Value")
                        return null
                    }

                    /* Date-value */
                    try {
                        val perviouslySentDate = parseLongInteger(pduDataStream)
                        if (LOCAL_LOGV) {
                            Log.v(
                                LOG_TAG,
                                "parseHeaders: PREVIOUSLY_SENT_DATE: $headerField value: $perviouslySentDate",
                            )
                        }
                        headers.setLongInteger(
                            perviouslySentDate,
                            PduHeaders.PREVIOUSLY_SENT_DATE,
                        )
                    } catch (e: RuntimeException) {
                        log("$headerField is not Long-Integer header field!")
                        return null
                    }
                }

                PduHeaders.MM_FLAGS -> {
                    /*
                     * MM-flags-value =
                     * Value-length
                     * ( Add-token | Remove-token | Filter-token )
                     * Encoded-string-value
                     */
                    if (LOCAL_LOGV) {
                        Log.v(LOG_TAG, "parseHeaders: MM_FLAGS: $headerField NOT REALLY SUPPORTED")
                    }

                    /* parse Value-length */
                    parseValueLength(pduDataStream)

                    /* Add-token | Remove-token | Filter-token */
                    extractByteValue(pduDataStream)

                    /* Encoded-string-value */
                    parseEncodedStringValue(pduDataStream)

                    /*
                     * not store this header filed in "headers",
                     * because now PduHeaders doesn't support it
                     */
                }

                /*
                 * Value-length
                 * (Message-total-token | Size-total-token) Integer-Value
                 */
                PduHeaders.MBOX_TOTALS,
                PduHeaders.MBOX_QUOTAS,
                -> {
                    if (LOCAL_LOGV) {
                        Log.v(LOG_TAG, "parseHeaders: MBOX_TOTALS: $headerField")
                    }
                    /* Value-length */
                    parseValueLength(pduDataStream)

                    /* Message-total-token | Size-total-token */
                    extractByteValue(pduDataStream)

                    /* Integer-Value */
                    try {
                        parseIntegerValue(pduDataStream)
                    } catch (e: RuntimeException) {
                        log("$headerField is not Integer-Value")
                        return null
                    }

                    /*
                     * not store these headers filed in "headers",
                     * because now PduHeaders doesn't support them
                     */
                }

                PduHeaders.ELEMENT_DESCRIPTOR -> {
                    if (LOCAL_LOGV) {
                        Log.v(LOG_TAG, "parseHeaders: ELEMENT_DESCRIPTOR: $headerField")
                    }
                    parseContentType(pduDataStream, null)

                    /*
                     * not store this header filed in "headers",
                     * because now PduHeaders doesn't support it
                     */
                }

                PduHeaders.CONTENT_TYPE -> {
                    val map = HashMap<Int, Any>()
                    val contentType = parseContentType(pduDataStream, map)

                    if (null != contentType) {
                        try {
                            if (LOCAL_LOGV) {
                                Log.v(
                                    LOG_TAG,
                                    "parseHeaders: CONTENT_TYPE: " + headerField + contentType.toString(),
                                )
                            }
                            headers.setTextString(contentType, PduHeaders.CONTENT_TYPE)
                        } catch (e: NullPointerException) {
                            log("null pointer error!")
                        } catch (e: RuntimeException) {
                            log("$headerField is not Text-String header field!")
                            return null
                        }
                    }

                    /* get start parameter */
                    mStartParam = map[PduPart.P_START] as ByteArray?

                    /* get charset parameter */
                    mTypeParam = map[PduPart.P_TYPE] as ByteArray?

                    keepParsing = false
                }

                /*
                 * PduHeaders.CONTENT, PduHeaders.ADDITIONAL_HEADERS and
                 * PduHeaders.ATTRIBUTES fall through to the unknown-header branch
                 * below (the vendored default), as does any unrecognised field.
                 */
                else -> {
                    if (LOCAL_LOGV) {
                        Log.v(LOG_TAG, "parseHeaders: Unknown header: $headerField")
                    }
                    log("Unknown header")
                }
            }
        }

        return headers
    }

    /**
     * Parse pdu parts.
     *
     * @param pduDataStream pdu data input stream
     * @return parts in PduBody structure
     */
    private fun parseParts(pduDataStream: ByteArrayInputStream): PduBody? {
        val count = parseUnsignedInt(pduDataStream) // get the number of parts
        val body = PduBody()

        for (i in 0 until count) {
            val headerLength = parseUnsignedInt(pduDataStream)
            val dataLength = parseUnsignedInt(pduDataStream)
            var part = PduPart()
            val startPos = pduDataStream.available()
            if (startPos <= 0) {
                // Invalid part.
                return null
            }

            /* parse part's content-type */
            val map = HashMap<Int, Any>()
            val contentType = parseContentType(pduDataStream, map)
            if (null != contentType) {
                part.setContentType(contentType)
            } else {
                part.setContentType(PduContentTypes.contentTypes[0].toByteArray()) // "*/*"
            }

            /* get name parameter */
            val name = map[PduPart.P_NAME] as ByteArray?
            if (null != name) {
                part.setName(name)
            }

            /* get charset parameter */
            val charset = map[PduPart.P_CHARSET] as Int?
            if (null != charset) {
                part.setCharset(charset)
            }

            /* parse part's headers */
            val endPos = pduDataStream.available()
            val partHeaderLen = headerLength - (startPos - endPos)
            if (partHeaderLen > 0) {
                if (false == parsePartHeaders(pduDataStream, part, partHeaderLen)) {
                    // Parse part header faild.
                    return null
                }
            } else if (partHeaderLen < 0) {
                // Invalid length of content-type.
                return null
            }

            /*
             * FIXME: check content-id, name, filename and content location,
             * if not set anyone of them, generate a default content-location
             */
            if ((null == part.contentLocation) &&
                (null == part.name) &&
                (null == part.filename) &&
                (null == part.contentId)
            ) {
                part.setContentLocation(
                    java.lang.Long.toOctalString(System.currentTimeMillis()).toByteArray(),
                )
            }

            /* get part's data */
            if (dataLength > 0) {
                var partData: ByteArray? = ByteArray(dataLength)
                val partContentType = String(part.contentType!!)
                pduDataStream.read(partData, 0, dataLength)
                if (partContentType.equals(ContentType.MULTIPART_ALTERNATIVE, ignoreCase = true)) {
                    // parse "multipart/vnd.wap.multipart.alternative".
                    val childBody = parseParts(ByteArrayInputStream(partData))
                    // take the first part of children.
                    part = childBody!!.getPart(0)
                } else {
                    // Check Content-Transfer-Encoding.
                    val partDataEncoding = part.contentTransferEncoding
                    if (null != partDataEncoding) {
                        val encoding = String(partDataEncoding)
                        if (encoding.equals(PduPart.P_BASE64, ignoreCase = true)) {
                            // Decode "base64" into "binary".
                            partData = Base64.decodeBase64(partData!!)
                        } else if (encoding.equals(PduPart.P_QUOTED_PRINTABLE, ignoreCase = true)) {
                            // Decode "quoted-printable" into "binary".
                            partData = QuotedPrintable.decodeQuotedPrintable(partData)
                        } else {
                            // "binary" is the default encoding.
                        }
                    }
                    if (null == partData) {
                        log("Decode part data error!")
                        return null
                    }
                    part.setData(partData)
                }
            }

            /* add this part to body */
            if (THE_FIRST_PART == checkPartPosition(part)) {
                /* this is the first part */
                body.addPart(0, part)
            } else {
                /* add the part to the end */
                body.addPart(part)
            }
        }

        return body
    }

    /**
     * Parse part's headers.
     *
     * @param pduDataStream pdu data input stream
     * @param part          to store the header informations of the part
     * @param length        length of the headers
     * @return true if parse successfully, false otherwise
     */
    private fun parsePartHeaders(
        pduDataStream: ByteArrayInputStream,
        part: PduPart,
        length: Int,
    ): Boolean {
        /**
         * From oma-ts-mms-conf-v1_3.pdf, chapter 10.2.
         * A name for multipart object SHALL be encoded using name-parameter
         * for Content-Type header in WSP multipart headers.
         * In decoding, name-parameter of Content-Type SHALL be used if available.
         * If name-parameter of Content-Type is not available,
         * filename parameter of Content-Disposition header SHALL be used if available.
         * If neither name-parameter of Content-Type header nor filename parameter
         * of Content-Disposition header is available,
         * Content-Location header SHALL be used if available.
         *
         * Within SMIL part the reference to the media object parts SHALL use
         * either Content-ID or Content-Location mechanism [RFC2557]
         * and the corresponding WSP part headers in media object parts
         * contain the corresponding definitions.
         */
        val startPos = pduDataStream.available()
        var tempPos = 0
        var lastLen = length
        while (0 < lastLen) {
            val header = pduDataStream.read()
            lastLen--

            if (header > TEXT_MAX) {
                // Number assigned headers.
                when (header) {
                    PduPart.P_CONTENT_LOCATION -> {
                        /**
                         * From wap-230-wsp-20010705-a.pdf, chapter 8.4.2.21
                         * Content-location-value = Uri-value
                         */
                        val contentLocation = parseWapString(pduDataStream, TYPE_TEXT_STRING)
                        if (null != contentLocation) {
                            part.setContentLocation(contentLocation)
                        }

                        tempPos = pduDataStream.available()
                        lastLen = length - (startPos - tempPos)
                    }
                    PduPart.P_CONTENT_ID -> {
                        /**
                         * From wap-230-wsp-20010705-a.pdf, chapter 8.4.2.21
                         * Content-ID-value = Quoted-string
                         */
                        val contentId = parseWapString(pduDataStream, TYPE_QUOTED_STRING)
                        if (null != contentId) {
                            part.setContentId(contentId)
                        }

                        tempPos = pduDataStream.available()
                        lastLen = length - (startPos - tempPos)
                    }
                    PduPart.P_DEP_CONTENT_DISPOSITION,
                    PduPart.P_CONTENT_DISPOSITION,
                    -> {
                        /**
                         * From wap-230-wsp-20010705-a.pdf, chapter 8.4.2.21
                         * Content-disposition-value = Value-length Disposition *(Parameter)
                         * Disposition = Form-data | Attachment | Inline | Token-text
                         * Form-data = <Octet 128>
                         * Attachment = <Octet 129>
                         * Inline = <Octet 130>
                         */

                        /*
                         * some carrier mmsc servers do not support content_disposition
                         * field correctly
                         */
                        if (mParseContentDisposition) {
                            val len = parseValueLength(pduDataStream)
                            pduDataStream.mark(1)
                            val thisStartPos = pduDataStream.available()
                            var thisEndPos = 0
                            var value = pduDataStream.read()

                            if (value == PduPart.P_DISPOSITION_FROM_DATA) {
                                part.setContentDisposition(PduPart.DISPOSITION_FROM_DATA)
                            } else if (value == PduPart.P_DISPOSITION_ATTACHMENT) {
                                part.setContentDisposition(PduPart.DISPOSITION_ATTACHMENT)
                            } else if (value == PduPart.P_DISPOSITION_INLINE) {
                                part.setContentDisposition(PduPart.DISPOSITION_INLINE)
                            } else {
                                pduDataStream.reset()
                                /* Token-text */
                                part.setContentDisposition(
                                    parseWapString(pduDataStream, TYPE_TEXT_STRING),
                                )
                            }

                            /* get filename parameter and skip other parameters */
                            thisEndPos = pduDataStream.available()
                            if (thisStartPos - thisEndPos < len) {
                                value = pduDataStream.read()
                                if (value == PduPart.P_FILENAME) { // filename is text-string
                                    part.setFilename(parseWapString(pduDataStream, TYPE_TEXT_STRING))
                                }

                                /* skip other parameters */
                                thisEndPos = pduDataStream.available()
                                if (thisStartPos - thisEndPos < len) {
                                    val last = len - (thisStartPos - thisEndPos)
                                    val temp = ByteArray(last)
                                    pduDataStream.read(temp, 0, last)
                                }
                            }

                            tempPos = pduDataStream.available()
                            lastLen = length - (startPos - tempPos)
                        }
                    }
                    else -> {
                        if (LOCAL_LOGV) {
                            Log.v(LOG_TAG, "Not supported Part headers: $header")
                        }
                        if (-1 == skipWapValue(pduDataStream, lastLen)) {
                            Log.e(LOG_TAG, "Corrupt Part headers")
                            return false
                        }
                        lastLen = 0
                    }
                }
            } else if ((header >= TEXT_MIN) && (header <= TEXT_MAX)) {
                // Not assigned header.
                val tempHeader = parseWapString(pduDataStream, TYPE_TEXT_STRING)
                val tempValue = parseWapString(pduDataStream, TYPE_TEXT_STRING)

                // Check the header whether it is "Content-Transfer-Encoding".
                if (true == PduPart.CONTENT_TRANSFER_ENCODING.equals(
                        String(tempHeader!!),
                        ignoreCase = true,
                    )
                ) {
                    part.setContentTransferEncoding(tempValue)
                }

                tempPos = pduDataStream.available()
                lastLen = length - (startPos - tempPos)
            } else {
                if (LOCAL_LOGV) {
                    Log.v(LOG_TAG, "Not supported Part headers: $header")
                }
                // Skip all headers of this part.
                if (-1 == skipWapValue(pduDataStream, lastLen)) {
                    Log.e(LOG_TAG, "Corrupt Part headers")
                    return false
                }
                lastLen = 0
            }
        }

        if (0 != lastLen) {
            Log.e(LOG_TAG, "Corrupt Part headers")
            return false
        }

        return true
    }

    companion object {
        /**
         * The next are WAP values defined in WSP specification.
         */
        private const val QUOTE = 127
        private const val LENGTH_QUOTE = 31
        private const val TEXT_MIN = 32
        private const val TEXT_MAX = 127
        private const val SHORT_INTEGER_MAX = 127
        private const val SHORT_LENGTH_MAX = 30
        private const val LONG_INTEGER_LENGTH_MAX = 8
        private const val QUOTED_STRING_FLAG = 34
        private const val END_STRING_FLAG = 0x00

        // The next two are used by the interface "parseWapString" to
        // distinguish Text-String and Quoted-String.
        private const val TYPE_TEXT_STRING = 0
        private const val TYPE_QUOTED_STRING = 1
        private const val TYPE_TOKEN_STRING = 2

        /**
         * Specify the part position.
         */
        private const val THE_FIRST_PART = 0
        private const val THE_LAST_PART = 1

        /**
         * Store the "type" parameter in "Content-Type" header field.
         *
         * Preserved as vendored `static` state: `parseHeaders` writes it from the
         * Content-Type `type` parameter so `checkPartPosition` can spot the root
         * part. (Mutable shared state across instances — a known vendored quirk
         * carried over faithfully.)
         */
        private var mTypeParam: ByteArray? = null

        /**
         * Store the "start" parameter in "Content-Type" header field. (See
         * [mTypeParam] for the vendored `static` rationale.)
         */
        private var mStartParam: ByteArray? = null

        /**
         * The log tag.
         */
        private const val LOG_TAG = "PduParser"
        private const val DEBUG = false
        private const val LOCAL_LOGV = false

        /**
         * Log status.
         *
         * @param text log information
         */
        private fun log(text: String) {
            if (LOCAL_LOGV) {
                Log.v(LOG_TAG, text)
            }
        }

        /**
         * Parse unsigned integer.
         *
         * @param pduDataStream pdu data input stream
         * @return the integer, -1 when failed
         */
        private fun parseUnsignedInt(pduDataStream: ByteArrayInputStream): Int {
            /**
             * From wap-230-wsp-20010705-a.pdf
             * The maximum size of a uintvar is 32 bits.
             * So it will be encoded in no more than 5 octets.
             */
            var result = 0
            var temp = pduDataStream.read()
            if (temp == -1) {
                return temp
            }

            while ((temp and 0x80) != 0) {
                result = result shl 7
                result = result or (temp and 0x7F)
                temp = pduDataStream.read()
                if (temp == -1) {
                    return temp
                }
            }

            result = result shl 7
            result = result or (temp and 0x7F)

            return result
        }

        /**
         * Parse value length.
         *
         * @param pduDataStream pdu data input stream
         * @return the integer
         */
        private fun parseValueLength(pduDataStream: ByteArrayInputStream): Int {
            /**
             * From wap-230-wsp-20010705-a.pdf
             * Value-length = Short-length | (Length-quote Length)
             * Short-length = <Any octet 0-30>
             * Length-quote = <Octet 31>
             * Length = Uintvar-integer
             * Uintvar-integer = 1*5 OCTET
             */
            val temp = pduDataStream.read()
            val first = temp and 0xFF

            if (first <= SHORT_LENGTH_MAX) {
                return first
            } else if (first == LENGTH_QUOTE) {
                return parseUnsignedInt(pduDataStream)
            }

            throw RuntimeException("Value length > LENGTH_QUOTE!")
        }

        /**
         * Parse encoded string value.
         *
         * @param pduDataStream pdu data input stream
         * @return the EncodedStringValue
         */
        private fun parseEncodedStringValue(pduDataStream: ByteArrayInputStream): EncodedStringValue? {
            /**
             * From OMA-TS-MMS-ENC-V1_3-20050927-C.pdf
             * Encoded-string-value = Text-string | Value-length Char-set Text-string
             */
            pduDataStream.mark(1)
            val returnValue: EncodedStringValue?
            var charset = 0
            val temp = pduDataStream.read()
            val first = temp and 0xFF
            if (first == 0) {
                return EncodedStringValue("")
            }

            pduDataStream.reset()
            if (first < TEXT_MIN) {
                parseValueLength(pduDataStream)

                charset = parseShortInteger(pduDataStream) // get the "Charset"
            }

            val textString = parseWapString(pduDataStream, TYPE_TEXT_STRING)

            try {
                returnValue = if (0 != charset) {
                    EncodedStringValue(charset, textString)
                } else {
                    EncodedStringValue(textString)
                }
            } catch (e: Exception) {
                return null
            }

            return returnValue
        }

        /**
         * Parse Text-String or Quoted-String.
         *
         * @param pduDataStream pdu data input stream
         * @param stringType    TYPE_TEXT_STRING or TYPE_QUOTED_STRING
         * @return the string without End-of-string in byte array
         */
        private fun parseWapString(pduDataStream: ByteArrayInputStream, stringType: Int): ByteArray? {
            /**
             * From wap-230-wsp-20010705-a.pdf
             * Text-string = [Quote] *TEXT End-of-string
             * If the first character in the TEXT is in the range of 128-255,
             * a Quote character must precede it.
             * Otherwise the Quote character must be omitted.
             * The Quote is not part of the contents.
             * Quote = <Octet 127>
             * End-of-string = <Octet 0>
             *
             * Quoted-string = <Octet 34> *TEXT End-of-string
             *
             * Token-text = Token End-of-string
             */

            // Mark supposed beginning of Text-string
            // We will have to mark again if first char is QUOTE or QUOTED_STRING_FLAG
            pduDataStream.mark(1)

            // Check first char
            val temp = pduDataStream.read()
            if ((TYPE_QUOTED_STRING == stringType) &&
                (QUOTED_STRING_FLAG == temp)
            ) {
                // Mark again if QUOTED_STRING_FLAG and ignore it
                pduDataStream.mark(1)
            } else if ((TYPE_TEXT_STRING == stringType) &&
                (QUOTE == temp)
            ) {
                // Mark again if QUOTE and ignore it
                pduDataStream.mark(1)
            } else {
                // Otherwise go back to origin
                pduDataStream.reset()
            }

            // We are now definitely at the beginning of string
            /**
             * Return *TOKEN or *TEXT (Text-String without QUOTE,
             * Quoted-String without QUOTED_STRING_FLAG and without End-of-string)
             */
            return getWapString(pduDataStream, stringType)
        }

        /**
         * Check TOKEN data defined in RFC2616.
         *
         * @param ch checking data
         * @return true when ch is TOKEN, false when ch is not TOKEN
         */
        private fun isTokenCharacter(ch: Int): Boolean {
            /**
             * Token = 1*<any CHAR except CTLs or separators>
             * separators = "("(40) | ")"(41) | "<"(60) | ">"(62) | "@"(64)
             * | ","(44) | ";"(59) | ":"(58) | "\"(92) | <">(34)
             * | "/"(47) | "["(91) | "]"(93) | "?"(63) | "="(61)
             * | "{"(123) | "}"(125) | SP(32) | HT(9)
             * CHAR = <any US-ASCII character (octets 0 - 127)>
             * CTL = <any US-ASCII control character
             * (octets 0 - 31) and DEL (127)>
             * SP = <US-ASCII SP, space (32)>
             * HT = <US-ASCII HT, horizontal-tab (9)>
             */
            if ((ch < 33) || (ch > 126)) {
                return false
            }

            when (ch) {
                '"'.code, /* '"' */
                '('.code, /* '(' */
                ')'.code, /* ')' */
                ','.code, /* ',' */
                '/'.code, /* '/' */
                ':'.code, /* ':' */
                ';'.code, /* ';' */
                '<'.code, /* '<' */
                '='.code, /* '=' */
                '>'.code, /* '>' */
                '?'.code, /* '?' */
                '@'.code, /* '@' */
                '['.code, /* '[' */
                '\\'.code, /* '\' */
                ']'.code, /* ']' */
                '{'.code, /* '{' */
                '}'.code, /* '}' */
                -> return false
            }

            return true
        }

        /**
         * Check TEXT data defined in RFC2616.
         *
         * @param ch checking data
         * @return true when ch is TEXT, false when ch is not TEXT
         */
        private fun isText(ch: Int): Boolean {
            /**
             * TEXT = <any OCTET except CTLs,
             * but including LWS>
             * CTL = <any US-ASCII control character
             * (octets 0 - 31) and DEL (127)>
             * LWS = [CRLF] 1*( SP | HT )
             * CRLF = CR LF
             * CR = <US-ASCII CR, carriage return (13)>
             * LF = <US-ASCII LF, linefeed (10)>
             */
            if (((ch >= 32) && (ch <= 126)) || ((ch >= 128) && (ch <= 255))) {
                return true
            }

            when (ch) {
                '\t'.code, /* '\t' */
                '\n'.code, /* '\n' */
                '\r'.code, /* '\r' */
                -> return true
            }

            return false
        }

        private fun getWapString(pduDataStream: ByteArrayInputStream, stringType: Int): ByteArray? {
            val out = ByteArrayOutputStream()
            var temp = pduDataStream.read()
            while ((-1 != temp) && ('\u0000'.code != temp)) {
                // check each of the character
                if (stringType == TYPE_TOKEN_STRING) {
                    if (isTokenCharacter(temp)) {
                        out.write(temp)
                    }
                } else {
                    if (isText(temp)) {
                        out.write(temp)
                    }
                }

                temp = pduDataStream.read()
            }

            if (out.size() > 0) {
                return out.toByteArray()
            }

            return null
        }

        /**
         * Extract a byte value from the input stream.
         *
         * @param pduDataStream pdu data input stream
         * @return the byte
         */
        private fun extractByteValue(pduDataStream: ByteArrayInputStream): Int {
            val temp = pduDataStream.read()
            return temp and 0xFF
        }

        /**
         * Parse Short-Integer.
         *
         * @param pduDataStream pdu data input stream
         * @return the byte
         */
        private fun parseShortInteger(pduDataStream: ByteArrayInputStream): Int {
            /**
             * From wap-230-wsp-20010705-a.pdf
             * Short-integer = OCTET
             * Integers in range 0-127 shall be encoded as a one
             * octet value with the most significant bit set to one (1xxx xxxx)
             * and with the value in the remaining least significant bits.
             */
            val temp = pduDataStream.read()
            return temp and 0x7F
        }

        /**
         * Parse Long-Integer.
         *
         * @param pduDataStream pdu data input stream
         * @return long integer
         */
        private fun parseLongInteger(pduDataStream: ByteArrayInputStream): Long {
            /**
             * From wap-230-wsp-20010705-a.pdf
             * Long-integer = Short-length Multi-octet-integer
             * The Short-length indicates the length of the Multi-octet-integer
             * Multi-octet-integer = 1*30 OCTET
             * The content octets shall be an unsigned integer value
             * with the most significant octet encoded first (big-endian representation).
             * The minimum number of octets must be used to encode the value.
             * Short-length = <Any octet 0-30>
             */
            var temp = pduDataStream.read()
            val count = temp and 0xFF

            if (count > LONG_INTEGER_LENGTH_MAX) {
                throw RuntimeException("Octet count greater than 8 and I can't represent that!")
            }

            var result: Long = 0

            for (i in 0 until count) {
                temp = pduDataStream.read()
                result = result shl 8
                result += (temp and 0xFF)
            }

            return result
        }

        /**
         * Parse Integer-Value.
         *
         * @param pduDataStream pdu data input stream
         * @return long integer
         */
        private fun parseIntegerValue(pduDataStream: ByteArrayInputStream): Long {
            /**
             * From wap-230-wsp-20010705-a.pdf
             * Integer-Value = Short-integer | Long-integer
             */
            pduDataStream.mark(1)
            val temp = pduDataStream.read()
            pduDataStream.reset()
            return if (temp > SHORT_INTEGER_MAX) {
                parseShortInteger(pduDataStream).toLong()
            } else {
                parseLongInteger(pduDataStream)
            }
        }

        /**
         * To skip length of the wap value.
         *
         * @param pduDataStream pdu data input stream
         * @param length        area size
         * @return the values in this area
         */
        private fun skipWapValue(pduDataStream: ByteArrayInputStream, length: Int): Int {
            val area = ByteArray(length)
            val readLen = pduDataStream.read(area, 0, length)
            return if (readLen < length) { // The actually read length is lower than the length
                -1
            } else {
                readLen
            }
        }

        /**
         * Parse content type parameters. For now we just support
         * four parameters used in mms: "type", "start", "name", "charset".
         *
         * @param pduDataStream pdu data input stream
         * @param map           to store parameters of Content-Type field
         * @param length        length of all the parameters
         */
        private fun parseContentTypeParams(
            pduDataStream: ByteArrayInputStream,
            map: HashMap<Int, Any>?,
            length: Int,
        ) {
            /**
             * From wap-230-wsp-20010705-a.pdf
             * Parameter = Typed-parameter | Untyped-parameter
             * Typed-parameter = Well-known-parameter-token Typed-value
             * the actual expected type of the value is implied by the well-known parameter
             * Well-known-parameter-token = Integer-value
             * the code values used for parameters are specified in the Assigned Numbers
             * appendix
             * Typed-value = Compact-value | Text-value
             * In addition to the expected type, there may be no value.
             * If the value cannot be encoded using the expected type, it shall be encoded
             * as text.
             * Compact-value = Integer-value |
             * Date-value | Delta-seconds-value | Q-value | Version-value |
             * Uri-value
             * Untyped-parameter = Token-text Untyped-value
             * the type of the value is unknown, but it shall be encoded as an integer,
             * if that is possible.
             * Untyped-value = Integer-value | Text-value
             */
            val startPos = pduDataStream.available()
            var tempPos = 0
            var lastLen = length
            while (0 < lastLen) {
                val param = pduDataStream.read()
                lastLen--

                when (param) {
                    /**
                     * From rfc2387, chapter 3.1
                     * The type parameter must be specified and its value is the MIME media
                     * type of the "root" body part. It permits a MIME user agent to
                     * determine the content-type without reference to the enclosed body
                     * part. If the value of the type parameter and the root body part's
                     * content-type differ then the User Agent's behavior is undefined.
                     *
                     * From wap-230-wsp-20010705-a.pdf
                     * type = Constrained-encoding
                     * Constrained-encoding = Extension-Media | Short-integer
                     * Extension-media = *TEXT End-of-string
                     */
                    PduPart.P_TYPE,
                    PduPart.P_CT_MR_TYPE,
                    -> {
                        pduDataStream.mark(1)
                        val first = extractByteValue(pduDataStream)
                        pduDataStream.reset()
                        if (first > TEXT_MAX) {
                            // Short-integer (well-known type)
                            val index = parseShortInteger(pduDataStream)

                            if (index < PduContentTypes.contentTypes.size) {
                                val type = PduContentTypes.contentTypes[index].toByteArray()
                                map!![PduPart.P_TYPE] = type
                            } else {
                                // not support this type, ignore it.
                            }
                        } else {
                            // Text-String (extension-media)
                            val type = parseWapString(pduDataStream, TYPE_TEXT_STRING)
                            if ((null != type) && (null != map)) {
                                map[PduPart.P_TYPE] = type
                            }
                        }

                        tempPos = pduDataStream.available()
                        lastLen = length - (startPos - tempPos)
                    }

                    /**
                     * From oma-ts-mms-conf-v1_3.pdf, chapter 10.2.3.
                     * Start Parameter Referring to Presentation
                     *
                     * From rfc2387, chapter 3.2
                     * The start parameter, if given, is the content-ID of the compound
                     * object's "root". If not present the "root" is the first body part in
                     * the Multipart/Related entity. The "root" is the element the
                     * applications processes first.
                     *
                     * From wap-230-wsp-20010705-a.pdf
                     * start = Text-String
                     */
                    PduPart.P_START,
                    PduPart.P_DEP_START,
                    -> {
                        val start = parseWapString(pduDataStream, TYPE_TEXT_STRING)
                        if ((null != start) && (null != map)) {
                            map[PduPart.P_START] = start
                        }

                        tempPos = pduDataStream.available()
                        lastLen = length - (startPos - tempPos)
                    }

                    /**
                     * From oma-ts-mms-conf-v1_3.pdf
                     * In creation, the character set SHALL be either us-ascii
                     * (IANA MIBenum 3) or utf-8 (IANA MIBenum 106)[Unicode].
                     * In retrieval, both us-ascii and utf-8 SHALL be supported.
                     *
                     * From wap-230-wsp-20010705-a.pdf
                     * charset = Well-known-charset|Text-String
                     * Well-known-charset = Any-charset | Integer-value
                     * Both are encoded using values from Character Set
                     * Assignments table in Assigned Numbers
                     * Any-charset = <Octet 128>
                     * Equivalent to the special RFC2616 charset value "*"
                     */
                    PduPart.P_CHARSET -> {
                        pduDataStream.mark(1)
                        val firstValue = extractByteValue(pduDataStream)
                        pduDataStream.reset()
                        // Check first char
                        if (((firstValue > TEXT_MIN) && (firstValue < TEXT_MAX)) ||
                            (END_STRING_FLAG == firstValue)
                        ) {
                            // Text-String (extension-charset)
                            val charsetStr = parseWapString(pduDataStream, TYPE_TEXT_STRING)
                            try {
                                val charsetInt = CharacterSets.getMibEnumValue(
                                    String(charsetStr!!),
                                )
                                map!![PduPart.P_CHARSET] = charsetInt
                            } catch (e: UnsupportedEncodingException) {
                                // Not a well-known charset, use "*".
                                Log.e(LOG_TAG, Arrays.toString(charsetStr), e)
                                map!![PduPart.P_CHARSET] = CharacterSets.ANY_CHARSET
                            }
                        } else {
                            // Well-known-charset
                            val charset = parseIntegerValue(pduDataStream).toInt()
                            if (map != null) {
                                map[PduPart.P_CHARSET] = charset
                            }
                        }

                        tempPos = pduDataStream.available()
                        lastLen = length - (startPos - tempPos)
                    }

                    /**
                     * From oma-ts-mms-conf-v1_3.pdf
                     * A name for multipart object SHALL be encoded using name-parameter
                     * for Content-Type header in WSP multipart headers.
                     *
                     * From wap-230-wsp-20010705-a.pdf
                     * name = Text-String
                     */
                    PduPart.P_DEP_NAME,
                    PduPart.P_NAME,
                    -> {
                        val name = parseWapString(pduDataStream, TYPE_TEXT_STRING)
                        if ((null != name) && (null != map)) {
                            map[PduPart.P_NAME] = name
                        }

                        tempPos = pduDataStream.available()
                        lastLen = length - (startPos - tempPos)
                    }
                    else -> {
                        if (LOCAL_LOGV) {
                            Log.v(LOG_TAG, "Not supported Content-Type parameter")
                        }
                        if (-1 == skipWapValue(pduDataStream, lastLen)) {
                            Log.e(LOG_TAG, "Corrupt Content-Type")
                        } else {
                            lastLen = 0
                        }
                    }
                }
            }

            if (0 != lastLen) {
                Log.e(LOG_TAG, "Corrupt Content-Type")
            }
        }

        /**
         * Parse content type.
         *
         * @param pduDataStream pdu data input stream
         * @param map           to store parameters in Content-Type header field
         * @return Content-Type value
         */
        private fun parseContentType(
            pduDataStream: ByteArrayInputStream,
            map: HashMap<Int, Any>?,
        ): ByteArray? {
            /**
             * From wap-230-wsp-20010705-a.pdf
             * Content-type-value = Constrained-media | Content-general-form
             * Content-general-form = Value-length Media-type
             * Media-type = (Well-known-media | Extension-Media) *(Parameter)
             */
            var contentType: ByteArray? = null
            pduDataStream.mark(1)
            var temp = pduDataStream.read()
            pduDataStream.reset()

            val cur = (temp and 0xFF)

            if (cur < TEXT_MIN) {
                val length = parseValueLength(pduDataStream)
                val startPos = pduDataStream.available()
                pduDataStream.mark(1)
                temp = pduDataStream.read()
                pduDataStream.reset()
                val first = (temp and 0xFF)

                if ((first >= TEXT_MIN) && (first <= TEXT_MAX)) {
                    contentType = parseWapString(pduDataStream, TYPE_TEXT_STRING)
                } else if (first > TEXT_MAX) {
                    val index = parseShortInteger(pduDataStream)

                    if (index < PduContentTypes.contentTypes.size) { // well-known type
                        contentType = PduContentTypes.contentTypes[index].toByteArray()
                    } else {
                        pduDataStream.reset()
                        contentType = parseWapString(pduDataStream, TYPE_TEXT_STRING)
                    }
                } else {
                    Log.e(LOG_TAG, "Corrupt content-type")
                    return PduContentTypes.contentTypes[0].toByteArray() // "*/*"
                }

                val endPos = pduDataStream.available()
                val parameterLen = length - (startPos - endPos)
                if (parameterLen > 0) { // have parameters
                    parseContentTypeParams(pduDataStream, map, parameterLen)
                }

                if (parameterLen < 0) {
                    Log.e(LOG_TAG, "Corrupt MMS message")
                    return PduContentTypes.contentTypes[0].toByteArray() // "*/*"
                }
            } else if (cur <= TEXT_MAX) {
                contentType = parseWapString(pduDataStream, TYPE_TEXT_STRING)
            } else {
                contentType =
                    PduContentTypes.contentTypes[parseShortInteger(pduDataStream)].toByteArray()
            }

            return contentType
        }

        /**
         * Check the position of a specified part.
         *
         * @param part the part to be checked
         * @return part position, THE_FIRST_PART when it's the
         *         first one, THE_LAST_PART when it's the last one.
         */
        private fun checkPartPosition(part: PduPart): Int {
            if ((null == mTypeParam) &&
                (null == mStartParam)
            ) {
                return THE_LAST_PART
            }

            /* check part's content-id */
            if (null != mStartParam) {
                val contentId = part.contentId
                if (null != contentId) {
                    if (mStartParam.contentEquals(contentId)) {
                        return THE_FIRST_PART
                    }
                }
            }

            /* check part's content-type */
            if (null != mTypeParam) {
                val contentType = part.contentType
                if (null != contentType) {
                    if (mTypeParam.contentEquals(contentType)) {
                        return THE_FIRST_PART
                    }
                }
            }

            return THE_LAST_PART
        }

        /**
         * Check mandatory headers of a pdu.
         *
         * @param headers pdu headers
         * @return true if the pdu has all of the mandatory headers, false otherwise.
         */
        private fun checkMandatoryHeader(headers: PduHeaders?): Boolean {
            if (null == headers) {
                return false
            }

            /* get message type */
            val messageType = headers.getOctet(PduHeaders.MESSAGE_TYPE)

            /* check Mms-Version field */
            val mmsVersion = headers.getOctet(PduHeaders.MMS_VERSION)
            if (0 == mmsVersion) {
                // Every message should have Mms-Version field.
                return false
            }

            /* check mandatory header fields */
            when (messageType) {
                PduHeaders.MESSAGE_TYPE_SEND_REQ -> {
                    // Content-Type field.
                    val srContentType = headers.getTextString(PduHeaders.CONTENT_TYPE)
                    if (null == srContentType) {
                        return false
                    }

                    // From field.
                    val srFrom = headers.getEncodedStringValue(PduHeaders.FROM)
                    if (null == srFrom) {
                        return false
                    }

                    // Transaction-Id field.
                    val srTransactionId = headers.getTextString(PduHeaders.TRANSACTION_ID)
                    if (null == srTransactionId) {
                        return false
                    }
                }
                PduHeaders.MESSAGE_TYPE_SEND_CONF -> {
                    // Response-Status field.
                    val scResponseStatus = headers.getOctet(PduHeaders.RESPONSE_STATUS)
                    if (0 == scResponseStatus) {
                        return false
                    }

                    // Transaction-Id field.
                    val scTransactionId = headers.getTextString(PduHeaders.TRANSACTION_ID)
                    if (null == scTransactionId) {
                        return false
                    }
                }
                PduHeaders.MESSAGE_TYPE_NOTIFICATION_IND -> {
                    // Content-Location field.
                    val niContentLocation = headers.getTextString(PduHeaders.CONTENT_LOCATION)
                    if (null == niContentLocation) {
                        return false
                    }

                    // Expiry field.
                    val niExpiry = headers.getLongInteger(PduHeaders.EXPIRY)
                    if (-1L == niExpiry) {
                        return false
                    }

                    // Message-Class field.
                    val niMessageClass = headers.getTextString(PduHeaders.MESSAGE_CLASS)
                    if (null == niMessageClass) {
                        return false
                    }

                    // Message-Size field.
                    val niMessageSize = headers.getLongInteger(PduHeaders.MESSAGE_SIZE)
                    if (-1L == niMessageSize) {
                        return false
                    }

                    // Transaction-Id field.
                    val niTransactionId = headers.getTextString(PduHeaders.TRANSACTION_ID)
                    if (null == niTransactionId) {
                        return false
                    }
                }
                PduHeaders.MESSAGE_TYPE_NOTIFYRESP_IND -> {
                    // Status field.
                    val nriStatus = headers.getOctet(PduHeaders.STATUS)
                    if (0 == nriStatus) {
                        return false
                    }

                    // Transaction-Id field.
                    val nriTransactionId = headers.getTextString(PduHeaders.TRANSACTION_ID)
                    if (null == nriTransactionId) {
                        return false
                    }
                }
                PduHeaders.MESSAGE_TYPE_RETRIEVE_CONF -> {
                    // Content-Type field.
                    val rcContentType = headers.getTextString(PduHeaders.CONTENT_TYPE)
                    if (null == rcContentType) {
                        return false
                    }

                    // Date field.
                    val rcDate = headers.getLongInteger(PduHeaders.DATE)
                    if (-1L == rcDate) {
                        return false
                    }
                }
                PduHeaders.MESSAGE_TYPE_DELIVERY_IND -> {
                    // Date field.
                    val diDate = headers.getLongInteger(PduHeaders.DATE)
                    if (-1L == diDate) {
                        return false
                    }

                    // Message-Id field.
                    val diMessageId = headers.getTextString(PduHeaders.MESSAGE_ID)
                    if (null == diMessageId) {
                        return false
                    }

                    // Status field.
                    val diStatus = headers.getOctet(PduHeaders.STATUS)
                    if (0 == diStatus) {
                        return false
                    }

                    // To field.
                    val diTo = headers.getEncodedStringValues(PduHeaders.TO)
                    if (null == diTo) {
                        return false
                    }
                }
                PduHeaders.MESSAGE_TYPE_ACKNOWLEDGE_IND -> {
                    // Transaction-Id field.
                    val aiTransactionId = headers.getTextString(PduHeaders.TRANSACTION_ID)
                    if (null == aiTransactionId) {
                        return false
                    }
                }
                PduHeaders.MESSAGE_TYPE_READ_ORIG_IND -> {
                    // Date field.
                    val roDate = headers.getLongInteger(PduHeaders.DATE)
                    if (-1L == roDate) {
                        return false
                    }

                    // From field.
                    val roFrom = headers.getEncodedStringValue(PduHeaders.FROM)
                    if (null == roFrom) {
                        return false
                    }

                    // Message-Id field.
                    val roMessageId = headers.getTextString(PduHeaders.MESSAGE_ID)
                    if (null == roMessageId) {
                        return false
                    }

                    // Read-Status field.
                    val roReadStatus = headers.getOctet(PduHeaders.READ_STATUS)
                    if (0 == roReadStatus) {
                        return false
                    }

                    // To field.
                    val roTo = headers.getEncodedStringValues(PduHeaders.TO)
                    if (null == roTo) {
                        return false
                    }
                }
                PduHeaders.MESSAGE_TYPE_READ_REC_IND -> {
                    // From field.
                    val rrFrom = headers.getEncodedStringValue(PduHeaders.FROM)
                    if (null == rrFrom) {
                        return false
                    }

                    // Message-Id field.
                    val rrMessageId = headers.getTextString(PduHeaders.MESSAGE_ID)
                    if (null == rrMessageId) {
                        return false
                    }

                    // Read-Status field.
                    val rrReadStatus = headers.getOctet(PduHeaders.READ_STATUS)
                    if (0 == rrReadStatus) {
                        return false
                    }

                    // To field.
                    val rrTo = headers.getEncodedStringValues(PduHeaders.TO)
                    if (null == rrTo) {
                        return false
                    }
                }
                else ->
                    // Parser doesn't support this message type in this version.
                    return false
            }

            return true
        }
    }
}
