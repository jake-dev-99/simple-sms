package io.simplezen.simple_sms.models

import android.content.ContentValues
import android.provider.Telephony.Mms


data class MmsAddr(
    var msgId: Long,
    var address: String,
    var type: Int,
    var charset: Int = 106,
) {
    val contentValues = ContentValues().apply {
        if(msgId != 0L)
            put(Mms.Addr.MSG_ID, msgId)
        if(address.isNotEmpty())
            put(Mms.Addr.ADDRESS, address)
        if(type != 0)
            put(Mms.Addr.TYPE, type)
        if(charset != 0)
            put(Mms.Addr.CHARSET, charset)
    }
}