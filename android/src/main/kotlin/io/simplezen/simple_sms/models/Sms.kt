package io.simplezen.simple_sms.models

import android.content.ContentValues
import android.content.Context
import android.provider.Telephony.Mms
import com.google.android.mms.pdu_alt.PduPart
import java.io.File

data class SmsObject(
    var seq: Int,
    var filename: String,
    var name: String,

    var charset: Int,
    var mimeType: String,
    var contentLocation: String,
    var contentDisposition: String,
    var contentId: String,
    var data: ByteArray,
    var size: Long = 0,
    var text: String,
) {

    companion object {
        fun pduPartToMmsPart(context: Context, seq: Int, part: PduPart): MmsPart {
            val charset: Int = part.charset
            val name = String(part.name ?: byteArrayOf())
            val data: ByteArray = part.data
            val size: Long = part.data?.size?.toLong() ?: 0L
            var text: String =
                if (String(part.data ?: byteArrayOf()).contains("�") == false)
                    String(part.data)
                else ""

            val contentDisposition = String(part.contentDisposition ?: byteArrayOf())
            val contentId = String(part.contentId ?: byteArrayOf())
            val contentType = String(part.contentType ?: byteArrayOf())
            val filename = String(part.filename ?: byteArrayOf())
            val contentLocation = String(part.contentLocation ?: byteArrayOf())

            return MmsPart(
                seq = seq,
                mimeType = contentType,
                filename = filename,
                contentLocation = contentLocation,
                charset = charset,
                contentDisposition = contentDisposition,
                contentId = contentId,
                name = name,
                data = data,
                size = size,
                text = text,
            )
        }
    }

    val contentValues = ContentValues().apply {
        put(Mms.Part.SEQ, seq)
        put(Mms.Part.CONTENT_TYPE, mimeType)
        put(Mms.Part.NAME, name)

        put(Mms.Part.CONTENT_LOCATION,
            if(contentLocation.isNotEmpty())
                contentLocation
            else if(name.isNotEmpty())
                name
            else
                name
        )

        put(Mms.Part.CONTENT_ID,
            if(contentId.isNotEmpty())
                contentId
            else
                "<${File(name).nameWithoutExtension}>"
        )

        if(text.isNotEmpty())
            put(Mms.Part.TEXT, text)
        if(filename.isNotEmpty())
            put(Mms.Part.FILENAME, filename)
        if(charset > 0)
            put(Mms.Part.CHARSET, charset)
        if(contentDisposition.isNotEmpty())
            put(Mms.Part.CONTENT_DISPOSITION, contentDisposition)
    }
}