/*
 * Copyright (C) 2009 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.mms

import android.content.Context
import android.util.Log
import io.simplezen.simple_sms.R
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import java.io.IOException

/**
 * First-party Kotlin port of the vendored `MmsConfig` (Phase 5 · UNFY-133) — the
 * all-static MMS settings holder loaded from the `res/xml/mms_config.xml`
 * resource. Behaviour-faithful 1:1: the same field defaults, the same
 * `XmlResourceParser` walk over `bool`/`int`/`string` config tags, the same
 * case-insensitive key matching, and the same parse-error handling (XPP /
 * NumberFormat / IO are caught and logged; the parser is always closed).
 *
 * Faithful-port notes:
 * - Modeled as a Kotlin `object`; public methods are `@JvmStatic` so the prior
 *   `MmsConfig.getX()` static call sites (Java and Kotlin) keep compiling.
 * - `Integer.parseInt(text)` is preserved via `java.lang.Integer.parseInt` (not
 *   `text!!.toInt()`) so a null/blank value throws the same
 *   `NumberFormatException` the vendored code caught — not a different NPE.
 * - The `String` settings stay nullable (the vendored fields are plain
 *   `String`, assignable to a null `<string>` body); int/bool settings keep
 *   their primitive defaults.
 * - `@Suppress("unused")`: many settings are parsed-but-not-yet-read in the
 *   vendored source (no getter); they're retained verbatim so the config
 *   surface matches the upstream `mms_config.xml` contract 1:1.
 */
@Suppress("unused")
object MmsConfig {
    private const val TAG = "MmsConfig"
    private const val DEBUG = true
    private const val LOCAL_LOGV = false

    const val DEFAULT_HTTP_KEY_X_WAP_PROFILE = "x-wap-profile"
    const val DEFAULT_USER_AGENT = "Android-Mms/2.0"

    private const val MMS_APP_PACKAGE = "com.android.mms"

    private const val SMS_PROMO_DISMISSED_KEY = "sms_promo_dismissed_key"

    private const val MAX_IMAGE_HEIGHT = 480
    private const val MAX_IMAGE_WIDTH = 640
    private const val MAX_TEXT_LENGTH = 2000

    /**
     * Whether to hide MMS functionality from the user (i.e. SMS only).
     */
    private var mTransIdEnabled = false
    private var mMmsEnabled = true // default to true
    private var mMaxMessageSize = 800 * 1024 // default to 800k max size
    private var mUserAgent: String? = DEFAULT_USER_AGENT
    private var mUaProfTagName: String? = DEFAULT_HTTP_KEY_X_WAP_PROFILE
    private var mUaProfUrl: String? = null
    private var mHttpParams: String? = null
    private var mHttpParamsLine1Key: String? = null
    private var mEmailGateway: String? = null
    private var mMaxImageHeight = MAX_IMAGE_HEIGHT // default value
    private var mMaxImageWidth = MAX_IMAGE_WIDTH // default value
    private var mRecipientLimit = Int.MAX_VALUE // default value
    private var mDefaultSMSMessagesPerThread = 10000 // default value
    private var mDefaultMMSMessagesPerThread = 1000 // default value
    private var mMinMessageCountPerThread = 2 // default value
    private var mMaxMessageCountPerThread = 5000 // default value
    private var mHttpSocketTimeout = 60 * 1000 // default to 1 min
    private var mMinimumSlideElementDuration = 7 // default to 7 sec
    private var mNotifyWapMMSC = false
    private var mAllowAttachAudio = true

    // If mEnableMultipartSMS is true, long sms messages are always sent as
    // multi-part sms
    // messages, with no checked limit on the number of segments.
    // If mEnableMultipartSMS is false, then as soon as the user types a message
    // longer
    // than a single segment (i.e. 140 chars), then the message will turn into and
    // be sent
    // as an mms message. This feature exists for carriers that don't support
    // multi-part sms's.
    private var mEnableMultipartSMS = true

    // By default, the radio splits multipart sms, not the application. If the
    // carrier or radio
    // does not support this, and the recipient gets garbled text, set this to true.
    // If this is
    // true and mEnableMultipartSMS is false, the mSmsToMmsTextThreshold will be
    // observed,
    // converting to mms if we reach the required number of segments.
    private var mEnableSplitSMS = false

    // If mEnableMultipartSMS is true and mSmsToMmsTextThreshold > 1, then
    // multi-part SMS messages
    // will be converted into a single mms message. For example, if the
    // mms_config.xml file
    // specifies <int name="smsToMmsTextThreshold">4</int>, then on the 5th sms
    // segment, the
    // message will be converted to an mms.
    private var mSmsToMmsTextThreshold = -1

    private var mEnableSlideDuration = true
    private var mEnableMMSReadReports = true // key: "enableMMSReadReports"
    private var mEnableSMSDeliveryReports = true // key: "enableSMSDeliveryReports"
    private var mEnableMMSDeliveryReports = true // key: "enableMMSDeliveryReports"
    private var mMaxTextLength = -1

    // This is the max amount of storage multiplied by mMaxMessageSize that we
    // allow of unsent messages before blocking the user from sending any more
    // MMS's.
    private var mMaxSizeScaleForPendingMmsAllowed = 4 // default value

    // Email gateway alias support, including the master switch and different rules
    private var mAliasEnabled = false
    private var mAliasRuleMinChars = 2
    private var mAliasRuleMaxChars = 48

    private var mMaxSubjectLength = 40 // maximum number of characters allowed for mms
    // subject

    // If mEnableGroupMms is true, a message with multiple recipients, regardless of
    // contents,
    // will be sent as a single MMS message with multiple "TO" fields set for each
    // recipient.
    // If mEnableGroupMms is false, the group MMS setting/preference will be hidden
    // in the settings
    // activity.
    private var mEnableGroupMms = true

    @JvmStatic
    fun init(context: Context) {
        if (LOCAL_LOGV) {
            Log.v(TAG, "MmsConfig.init()")
        }
        // Always put the mnc/mcc in the log so we can tell which mms_config.xml was
        // loaded.

        loadMmsSettings(context)
    }

    @JvmStatic
    fun getMmsEnabled(): Boolean {
        return mMmsEnabled
    }

    @JvmStatic
    fun getMaxMessageSize(): Int {
        if (LOCAL_LOGV) {
            Log.v(TAG, "MmsConfig.getMaxMessageSize(): $mMaxMessageSize")
        }
        return mMaxMessageSize
    }

    /**
     * This function returns the value of "enabledTransID" present in mms_config
     * file.
     * In case of single segment wap push message, this "enabledTransID" indicates
     * whether
     * TransactionID should be appended to URI or not.
     */
    @JvmStatic
    fun getTransIdEnabled(): Boolean {
        return mTransIdEnabled
    }

    @JvmStatic
    fun getUserAgent(): String? {
        return mUserAgent
    }

    @JvmStatic
    fun getUaProfTagName(): String? {
        return mUaProfTagName
    }

    @JvmStatic
    fun getUaProfUrl(): String? {
        return mUaProfUrl
    }

    @JvmStatic
    fun getHttpParams(): String? {
        return mHttpParams
    }

    @JvmStatic
    fun getHttpParamsLine1Key(): String? {
        return mHttpParamsLine1Key
    }

    @JvmStatic
    fun getHttpSocketTimeout(): Int {
        return mHttpSocketTimeout
    }

    @JvmStatic
    fun getNotifyWapMMSC(): Boolean {
        return mNotifyWapMMSC
    }

    @JvmStatic
    @Throws(XmlPullParserException::class, IOException::class)
    fun beginDocument(parser: XmlPullParser, firstElementName: String) {
        var type = parser.next()
        while (type != XmlPullParser.START_TAG && type != XmlPullParser.END_DOCUMENT) {
            type = parser.next()
        }

        if (type != XmlPullParser.START_TAG) {
            throw XmlPullParserException("No start tag found")
        }

        if (parser.name != firstElementName) {
            throw XmlPullParserException(
                "Unexpected start tag: found " + parser.name +
                    ", expected " + firstElementName,
            )
        }
    }

    @JvmStatic
    @Throws(XmlPullParserException::class, IOException::class)
    fun nextElement(parser: XmlPullParser) {
        var type = parser.next()
        while (type != XmlPullParser.START_TAG && type != XmlPullParser.END_DOCUMENT) {
            type = parser.next()
        }
    }

    private fun loadMmsSettings(context: Context) {
        val parser = context.resources.getXml(R.xml.mms_config)

        try {
            beginDocument(parser, "mms_config")

            while (true) {
                nextElement(parser)
                val tag = parser.name
                if (tag == null) {
                    break
                }
                val name = parser.getAttributeName(0)
                val value = parser.getAttributeValue(0)
                var text: String? = null
                if (parser.next() == XmlPullParser.TEXT) {
                    text = parser.text
                }

                if (DEBUG) {
                    Log.v(TAG, "tag: $tag value: $value - $text")
                }
                if ("name".equals(name, ignoreCase = true)) {
                    if ("bool" == tag) {
                        // bool config tags go here
                        if ("enabledMMS".equals(value, ignoreCase = true)) {
                            mMmsEnabled = "true".equals(text, ignoreCase = true)
                        } else if ("enabledTransID".equals(value, ignoreCase = true)) {
                            mTransIdEnabled = "true".equals(text, ignoreCase = true)
                        } else if ("enabledNotifyWapMMSC".equals(value, ignoreCase = true)) {
                            mNotifyWapMMSC = "true".equals(text, ignoreCase = true)
                        } else if ("aliasEnabled".equals(value, ignoreCase = true)) {
                            mAliasEnabled = "true".equals(text, ignoreCase = true)
                        } else if ("allowAttachAudio".equals(value, ignoreCase = true)) {
                            mAllowAttachAudio = "true".equals(text, ignoreCase = true)
                        } else if ("enableMultipartSMS".equals(value, ignoreCase = true)) {
                            mEnableMultipartSMS = "true".equals(text, ignoreCase = true)
                        } else if ("enableSplitSMS".equals(value, ignoreCase = true)) {
                            mEnableSplitSMS = "true".equals(text, ignoreCase = true)
                        } else if ("enableSlideDuration".equals(value, ignoreCase = true)) {
                            mEnableSlideDuration = "true".equals(text, ignoreCase = true)
                        } else if ("enableMMSReadReports".equals(value, ignoreCase = true)) {
                            mEnableMMSReadReports = "true".equals(text, ignoreCase = true)
                        } else if ("enableSMSDeliveryReports".equals(value, ignoreCase = true)) {
                            mEnableSMSDeliveryReports = "true".equals(text, ignoreCase = true)
                        } else if ("enableMMSDeliveryReports".equals(value, ignoreCase = true)) {
                            mEnableMMSDeliveryReports = "true".equals(text, ignoreCase = true)
                        } else if ("enableGroupMms".equals(value, ignoreCase = true)) {
                            mEnableGroupMms = "true".equals(text, ignoreCase = true)
                        }
                    } else if ("int" == tag) {
                        // int config tags go here
                        if ("maxMessageSize".equals(value, ignoreCase = true)) {
                            mMaxMessageSize = java.lang.Integer.parseInt(text)
                        } else if ("maxImageHeight".equals(value, ignoreCase = true)) {
                            mMaxImageHeight = java.lang.Integer.parseInt(text)
                        } else if ("maxImageWidth".equals(value, ignoreCase = true)) {
                            mMaxImageWidth = java.lang.Integer.parseInt(text)
                        } else if ("defaultSMSMessagesPerThread".equals(value, ignoreCase = true)) {
                            mDefaultSMSMessagesPerThread = java.lang.Integer.parseInt(text)
                        } else if ("defaultMMSMessagesPerThread".equals(value, ignoreCase = true)) {
                            mDefaultMMSMessagesPerThread = java.lang.Integer.parseInt(text)
                        } else if ("minMessageCountPerThread".equals(value, ignoreCase = true)) {
                            mMinMessageCountPerThread = java.lang.Integer.parseInt(text)
                        } else if ("maxMessageCountPerThread".equals(value, ignoreCase = true)) {
                            mMaxMessageCountPerThread = java.lang.Integer.parseInt(text)
                        } else if ("recipientLimit".equals(value, ignoreCase = true)) {
                            mRecipientLimit = java.lang.Integer.parseInt(text)
                            if (mRecipientLimit < 0) {
                                mRecipientLimit = Int.MAX_VALUE
                            }
                        } else if ("httpSocketTimeout".equals(value, ignoreCase = true)) {
                            mHttpSocketTimeout = java.lang.Integer.parseInt(text)
                        } else if ("minimumSlideElementDuration".equals(value, ignoreCase = true)) {
                            mMinimumSlideElementDuration = java.lang.Integer.parseInt(text)
                        } else if ("maxSizeScaleForPendingMmsAllowed".equals(value, ignoreCase = true)) {
                            mMaxSizeScaleForPendingMmsAllowed = java.lang.Integer.parseInt(text)
                        } else if ("aliasMinChars".equals(value, ignoreCase = true)) {
                            mAliasRuleMinChars = java.lang.Integer.parseInt(text)
                        } else if ("aliasMaxChars".equals(value, ignoreCase = true)) {
                            mAliasRuleMaxChars = java.lang.Integer.parseInt(text)
                        } else if ("smsToMmsTextThreshold".equals(value, ignoreCase = true)) {
                            mSmsToMmsTextThreshold = java.lang.Integer.parseInt(text)
                        } else if ("maxMessageTextSize".equals(value, ignoreCase = true)) {
                            mMaxTextLength = java.lang.Integer.parseInt(text)
                        } else if ("maxSubjectLength".equals(value, ignoreCase = true)) {
                            mMaxSubjectLength = java.lang.Integer.parseInt(text)
                        }
                    } else if ("string" == tag) {
                        // string config tags go here
                        if ("userAgent".equals(value, ignoreCase = true)) {
                            mUserAgent = text
                        } else if ("uaProfTagName".equals(value, ignoreCase = true)) {
                            mUaProfTagName = text
                        } else if ("uaProfUrl".equals(value, ignoreCase = true)) {
                            mUaProfUrl = text
                        } else if ("httpParams".equals(value, ignoreCase = true)) {
                            mHttpParams = text
                        } else if ("httpParamsLine1Key".equals(value, ignoreCase = true)) {
                            mHttpParamsLine1Key = text
                        } else if ("emailGatewayNumber".equals(value, ignoreCase = true)) {
                            mEmailGateway = text
                        }
                    }
                }
            }
        } catch (e: XmlPullParserException) {
            Log.e(TAG, "loadMmsSettings caught ", e)
        } catch (e: NumberFormatException) {
            Log.e(TAG, "loadMmsSettings caught ", e)
        } catch (e: IOException) {
            Log.e(TAG, "loadMmsSettings caught ", e)
        } finally {
            parser.close()
        }

        var errorStr: String? = null

        if (getMmsEnabled() && mUaProfUrl == null) {
            errorStr = "uaProfUrl"
        }

        if (errorStr != null) {
            val err = String.format(
                "MmsConfig.loadMmsSettings mms_config.xml missing %s setting",
                errorStr,
            )
            Log.e(TAG, err)
        }
    }

    @JvmStatic
    fun setUserAgent(userAgent: String?) {
        mUserAgent = userAgent
    }

    @JvmStatic
    fun setUaProfUrl(url: String?) {
        mUaProfUrl = url
    }

    @JvmStatic
    fun setUaProfTagName(tagName: String?) {
        mUaProfTagName = tagName
    }
}
