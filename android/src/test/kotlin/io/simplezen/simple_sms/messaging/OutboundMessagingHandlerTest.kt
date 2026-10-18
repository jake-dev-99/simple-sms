package io.simplezen.simple_sms.messaging

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Respond-via-message quick replies (UNFY-319): the intent parser, the
 * service that sends a valid request natively and stops itself, and the
 * status-intent extras that let a failure reach Dart.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OutboundMessagingHandlerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val originalSend = OutboundMessagingHandler.send
    private val originalDeliver = OutboundMessagingHandler.deliver

    private class Delivery(val method: String, val payload: Map<String, Any?>)

    private val sends = mutableListOf<RespondViaMessageRequest>()
    private val deliveries = mutableListOf<Delivery>()

    @Before
    fun captureSendsAndDeliveries() {
        OutboundMessagingHandler.send = { _, request -> sends += request }
        OutboundMessagingHandler.deliver = { _, method, payload ->
            deliveries += Delivery(method, payload.toMap())
        }
    }

    @After
    fun restoreSeams() {
        OutboundMessagingHandler.send = originalSend
        OutboundMessagingHandler.deliver = originalDeliver
    }

    private fun quickReply(
        uri: String? = "smsto:5554",
        text: CharSequence? = "Can't talk now",
        action: String = TelephonyManager.ACTION_RESPOND_VIA_MESSAGE,
    ) = Intent(action).apply {
        if (uri != null) data = Uri.parse(uri)
        if (text != null) putExtra(Intent.EXTRA_TEXT, text)
    }

    private fun requestOf(intent: Intent?) =
        (parseRespondViaMessage(intent) as? RespondViaMessageParse.Accepted)?.request

    private fun startService(intent: Intent) =
        Robolectric.buildService(OutboundMessagingHandler::class.java, intent)
            .create()
            .startCommand(0, 1)

    // --- parser ---

    @Test
    fun smstoUri_parsesOneRecipientAndText() {
        val request = requestOf(quickReply("smsto:5554"))

        assertEquals(RespondViaMessageRequest(listOf("5554"), "Can't talk now", null), request)
    }

    @Test
    fun smsUri_splitsCommaAndSemicolonRecipients() {
        val request = requestOf(quickReply("sms:+15550001, +15550002;+15550003,;"))

        assertEquals(listOf("+15550001", "+15550002", "+15550003"), request?.recipients)
    }

    @Test
    fun mmstoUri_isAccepted() {
        assertEquals(listOf("5554"), requestOf(quickReply("mmsto:5554"))?.recipients)
        assertEquals(listOf("5554"), requestOf(quickReply("mms:5554"))?.recipients)
    }

    @Test
    fun otherAction_isRejected() {
        assertNull(requestOf(quickReply(action = Intent.ACTION_SENDTO)))
        assertNull(requestOf(quickReply(action = Intent.ACTION_VIEW)))
        assertNull(requestOf(quickReply(uri = "tel:5554")))
        assertNull(requestOf(quickReply(uri = null)))
        assertNull(requestOf(null))
    }

    @Test
    fun blankText_isRejected() {
        assertNull(requestOf(quickReply(text = "")))
        assertNull(requestOf(quickReply(text = " \n\t ")))
        assertNull(requestOf(quickReply(text = null)))
    }

    @Test
    fun noRecipient_isRejected() {
        assertNull(requestOf(quickReply("smsto:")))
        assertNull(requestOf(quickReply("sms:,; ,")))
        assertNull(requestOf(quickReply("sms:?body=hi")))
    }

    @Test
    fun queryPart_isStripped() {
        val request = requestOf(quickReply("sms:5554,5555?body=ignored%20text"))

        assertEquals(listOf("5554", "5555"), request?.recipients)
        assertEquals("Can't talk now", request?.body)
    }

    @Test
    fun subscriptionKey_isRead() {
        val request = requestOf(quickReply().putExtra("subscription", 3))

        assertEquals(3, request?.subscriptionId)
    }

    @Test
    fun subscriptionIndexFallback_isRead() {
        val request = requestOf(
            quickReply().putExtra(SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX, 2),
        )

        assertEquals(2, request?.subscriptionId)
    }

    @Test
    fun invalidSubscription_isNull() {
        val invalid = SubscriptionManager.INVALID_SUBSCRIPTION_ID

        assertNull(requestOf(quickReply().putExtra("subscription", invalid))?.subscriptionId)
        // A present key wins: an invalid value is not replaced by the fallback.
        val both = quickReply()
            .putExtra("subscription", invalid)
            .putExtra(SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX, 2)
        assertNull(requestOf(both)?.subscriptionId)
        // The request itself is still valid; only the SIM is dropped.
        assertNotNull(requestOf(both))
    }

    // --- service ---

    @Test
    fun validIntent_sendsThenStops() {
        val controller = startService(quickReply("smsto:5554").putExtra("subscription", 3))

        assertEquals(RespondViaMessageRequest(listOf("5554"), "Can't talk now", 3), sends.single())
        assertTrue(deliveries.isEmpty())
        assertTrue(shadowOf(controller.get()).isStoppedBySelf)
    }

    @Test
    fun invalidIntent_stopsWithoutSending() {
        val controller = startService(quickReply(text = " "))

        assertTrue(sends.isEmpty())
        assertTrue(shadowOf(controller.get()).isStoppedBySelf)
    }

    @Test
    fun onStartCommand_returnsNotSticky() {
        val service = Robolectric.buildService(OutboundMessagingHandler::class.java).create().get()

        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(quickReply(), 0, 1))
        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(quickReply(text = null), 0, 2))
        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(null, 0, 3))
    }

    @Test
    fun sendThatCannotStart_isAnnouncedToDart() {
        OutboundMessagingHandler.send = originalSend
        shadowOf(context.packageManager).setSystemFeature(PackageManager.FEATURE_TELEPHONY_MESSAGING, false)

        startService(quickReply("smsto:5554").putExtra("subscription", 3))

        val delivery = deliveries.single()
        assertEquals("receiveRespondViaMessageFailure", delivery.method)
        assertEquals(
            mapOf("recipients" to listOf("5554"), "body" to "Can't talk now", "subscriptionId" to 3),
            delivery.payload,
        )
    }

    // --- status-intent extras ---

    @Test
    fun quickReplyExtras_roundTrip() {
        val request = RespondViaMessageRequest(listOf("5554", "5556"), "Can't talk now", 3)

        assertEquals(request, Intent().putQuickReply(request).quickReply())
        val noSim = request.copy(subscriptionId = null)
        assertEquals(noSim, Intent().putQuickReply(noSim).quickReply())
    }

    @Test
    fun ordinaryStatusIntent_carriesNoQuickReply() {
        assertNull(Intent().putExtra("messageID", 1).quickReply())
    }

    // --- packaged manifest ---

    @Test
    fun manifestService_filtersAllFourSchemes() {
        val component = ComponentName(context, OutboundMessagingHandler::class.java)
        val info = context.packageManager.getServiceInfo(component, 0)
        assertEquals("android.permission.SEND_RESPOND_VIA_MESSAGE", info.permission)

        for (scheme in listOf("sms", "smsto", "mms", "mmsto")) {
            val intent = quickReply("$scheme:5554")
            val matches = context.packageManager.queryIntentServices(intent, 0)
            assertTrue(
                "$scheme: must resolve to the handler",
                matches.any { it.serviceInfo.name == OutboundMessagingHandler::class.java.name },
            )
        }
    }
}
