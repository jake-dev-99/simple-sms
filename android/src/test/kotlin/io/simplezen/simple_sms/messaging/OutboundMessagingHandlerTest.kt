package io.simplezen.simple_sms.messaging

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Respond-via-message quick replies (UNFY-319): the intent parser and the
 * service that hands a valid request to Dart and stops itself.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OutboundMessagingHandlerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val originalDeliver = OutboundMessagingHandler.deliver

    private class Delivery(
        val method: String,
        val payload: Map<String, Any?>,
        val onResult: (Boolean) -> Unit,
    )

    private val deliveries = mutableListOf<Delivery>()

    @After
    fun restoreDelivery() {
        OutboundMessagingHandler.deliver = originalDeliver
    }

    private fun captureDeliveries() {
        OutboundMessagingHandler.deliver = { _, method, payload, onResult ->
            deliveries += Delivery(method, payload.toMap(), onResult)
        }
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
    fun validIntent_deliversToDartThenStops() {
        captureDeliveries()
        val controller = startService(quickReply("smsto:5554").putExtra("subscription", 3))

        val delivery = deliveries.single()
        assertEquals("receiveRespondViaMessage", delivery.method)
        assertEquals(
            mapOf(
                "recipients" to listOf("5554"),
                "body" to "Can't talk now",
                "subscriptionId" to 3,
            ),
            delivery.payload,
        )
        assertFalse("must keep running until Dart answers", shadowOf(controller.get()).isStoppedBySelf)

        delivery.onResult(true)

        assertTrue(shadowOf(controller.get()).isStoppedBySelf)
    }

    @Test
    fun undeliveredQuickReply_stillStops() {
        captureDeliveries()
        val controller = startService(quickReply())

        deliveries.single().onResult(false)

        assertTrue(shadowOf(controller.get()).isStoppedBySelf)
    }

    @Test
    fun invalidIntent_stopsWithoutDelivering() {
        captureDeliveries()
        val controller = startService(quickReply(text = " "))

        assertTrue(deliveries.isEmpty())
        assertTrue(shadowOf(controller.get()).isStoppedBySelf)
    }

    @Test
    fun onStartCommand_returnsNotSticky() {
        captureDeliveries()
        val service = Robolectric.buildService(OutboundMessagingHandler::class.java).create().get()

        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(quickReply(), 0, 1))
        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(quickReply(text = null), 0, 2))
        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(null, 0, 3))
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
