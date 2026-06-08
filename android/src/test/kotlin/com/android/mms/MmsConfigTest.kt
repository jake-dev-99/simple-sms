package com.android.mms

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader

/**
 * Pins the [MmsConfig] port: `init()` parses the committed `res/xml/mms_config.xml`
 * into the static getters (end-to-end exercise of `loadMmsSettings` +
 * `beginDocument`/`nextElement` over the `bool`/`int`/`string` tags), and the two
 * public XPP helpers position/validate correctly. Needs Robolectric for the
 * resource `Context`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MmsConfigTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun parserFor(xml: String): XmlPullParser {
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        parser.setInput(StringReader(xml))
        return parser
    }

    @Test
    fun init_loadsValuesFromXmlResource() {
        MmsConfig.init(context)
        // Values straight out of res/xml/mms_config.xml.
        assertTrue("enabledMMS", MmsConfig.getMmsEnabled())
        assertEquals("maxMessageSize", 307200, MmsConfig.getMaxMessageSize())
        assertEquals("userAgent", "Android Messaging", MmsConfig.getUserAgent())
        assertEquals(
            "uaProfUrl",
            "http://www.gstatic.com/android/hangouts/hangouts_mms_ua_profile.xml",
            MmsConfig.getUaProfUrl(),
        )
    }

    @Test
    fun beginDocument_positionsAtMatchingRoot() {
        val parser = parserFor("<mms_config><bool name=\"x\">true</bool></mms_config>")
        MmsConfig.beginDocument(parser, "mms_config")
        assertEquals(XmlPullParser.START_TAG, parser.eventType)
        assertEquals("mms_config", parser.name)
    }

    @Test
    fun beginDocument_throwsOnUnexpectedRoot() {
        val parser = parserFor("<other></other>")
        assertThrows(XmlPullParserException::class.java) {
            MmsConfig.beginDocument(parser, "mms_config")
        }
    }

    @Test
    fun nextElement_advancesToNextStartTag() {
        val parser = parserFor("<root><a></a><b></b></root>")
        MmsConfig.beginDocument(parser, "root")
        MmsConfig.nextElement(parser)
        assertEquals(XmlPullParser.START_TAG, parser.eventType)
        assertEquals("a", parser.name)
    }

    @Test
    fun setters_overrideValues() {
        MmsConfig.setUserAgent("custom-ua")
        assertEquals("custom-ua", MmsConfig.getUserAgent())
        MmsConfig.setUaProfTagName("custom-tag")
        assertEquals("custom-tag", MmsConfig.getUaProfTagName())
        MmsConfig.setUaProfUrl("http://example/ua.xml")
        assertEquals("http://example/ua.xml", MmsConfig.getUaProfUrl())
    }
}
