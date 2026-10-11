package io.simplezen.simple_sms.messaging

import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * The respond-via-message service the default-SMS-app role requires; the
 * manifest declares it for `RESPOND_VIA_MESSAGE` / `SENDTO`. It sends nothing
 * itself — outbound sends go through [OutboundSender] on the messaging
 * channel.
 */
class OutboundMessagingHandler : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
}
