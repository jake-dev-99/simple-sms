package io.simplezen.simple_sms.queries

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.ContactsContract.CommonDataKinds
import android.provider.ContactsContract.Contacts
import android.provider.ContactsContract.Profile
import android.provider.ContactsContract.RawContacts
import android.util.Log

private const val TAG = "ContactQuery"

val PROFILE_URI: Uri = Profile.CONTENT_URI
val PEOPLE_URI: Uri = Contacts.CONTENT_URI
val CONTACT_URI: Uri = RawContacts.CONTENT_URI
val LOOKUPID_URI: Uri = Contacts.CONTENT_LOOKUP_URI
val NAME_URI: Uri = Contacts.CONTENT_FILTER_URI
val EMAIL_URI: Uri = CommonDataKinds.Email.CONTENT_LOOKUP_URI
val PHONE_URI: Uri = CommonDataKinds.Phone.CONTENT_FILTER_URI

enum class ContactsFilter {
    All,
    Profile,
    LookupId,
    Name,
    PhoneNumber,
    Email,
    ;

    fun uri(param: String? = null): Uri {
        if (this !in listOf(All, Profile) && param.isNullOrEmpty()) {
            throw IllegalArgumentException("Missing parameter for ContactsFilter.$this")
        }
        return when (this) {
            All -> CONTACT_URI
            Profile -> PROFILE_URI
            LookupId -> LOOKUPID_URI.buildUpon().appendPath(param).build()
            Name -> NAME_URI.buildUpon().appendPath(param).build()
            PhoneNumber -> PHONE_URI.buildUpon().appendPath(param).build()
            Email -> EMAIL_URI.buildUpon().appendPath(param).build()
        }
    }
}

class ContactQuery(val context: Context) {
    fun fetch(filter: ContactsFilter, param: String): Map<String, Any?>? {
        if (filter != ContactsFilter.Profile && param.isEmpty()) return null

        val uri = filter.uri(param)
        val result = PrivateContactQuery(context).query(uri)
        return result.firstOrNull()
    }
}

private class PrivateContactQuery(val context: Context) {

    fun query(uri: Uri): MutableList<Map<String, Any?>> {
        val contentResolver = context.contentResolver
        val contactList = mutableListOf<Map<String, Any?>>()

        val commonDataUrl: Uri = CommonDataKinds.Contactables.CONTENT_URI
            .buildUpon()
            .appendQueryParameter("VISIBLE_CONTACTS_ONLY", "true")
            .build()

        val commonDataCursor = contentResolver.query(commonDataUrl, null, null, null, null)
            ?: return contactList
        getAllCursorData(commonDataCursor)

        val rawContactCursor = contentResolver.query(uri, null, null, null, null)
            ?: return contactList
        val data = getAllCursorData(rawContactCursor)

        for (row in data) {
            if (row.isEmpty()) continue

            // Fetch phone numbers for this contact
            val phoneNumbers = mutableListOf<String>()
            val phoneNumCursor = contentResolver.query(
                CommonDataKinds.Phone.CONTENT_URI,
                null,
                "${CommonDataKinds.Phone.CONTACT_ID} = ?",
                arrayOf(row["_id"].toString()),
                null
            )
            if (phoneNumCursor != null) {
                val phoneNumData = getAllCursorData(phoneNumCursor)
                for (phoneNumRow in phoneNumData) {
                    try {
                        val phoneNumber = phoneNumRow[CommonDataKinds.Phone.NUMBER] as? String
                        if (phoneNumber != null) phoneNumbers.add(phoneNumber)
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to parse phone number for contact: ${e.message}")
                    }
                }
            }

            // Fetch emails for this contact
            val emails = mutableListOf<String>()
            val emailCursor = contentResolver.query(
                CommonDataKinds.Email.CONTENT_URI,
                null,
                "${CommonDataKinds.Email.CONTACT_ID} = ?",
                arrayOf(row["_id"].toString()),
                null
            )
            if (emailCursor != null) {
                val emailData = getAllCursorData(emailCursor)
                for (emailRow in emailData) {
                    val email = emailRow[CommonDataKinds.Email.ADDRESS] as? String
                    if (email != null) emails.add(email)
                }
            }

            val contact: HashMap<String, Any?> = hashMapOf(
                "externalId" to row["_id"].toString(),
                "name" to row["display_name"].toString(),
                "phoneNumbers" to phoneNumbers,
                "emailAddresses" to emails,
                "lastUpdated" to (row["contact_last_updated_timestamp"] as Long? ?: 0),
                "ringtone" to "",
                "primaryName" to row["display_name"].toString(),
                "alternativeName" to row["display_name_alt"].toString(),
                "hasPhoneNum" to phoneNumbers.isNotEmpty(),
                "inVisibleGroup" to true,
                "isUserProfile" to false,
                "lookupKey" to row["lookup"].toString(),
                "phoneticName" to (row["phonetic_name"]?.toString() ?: ""),
                "photoUri" to (row["photo_uri"]?.toString() ?: ""),
                "photoId" to (row["photo_id"]?.toString() ?: ""),
                "photoThumbnailUri" to (row["photo_thumb_uri"]?.toString() ?: ""),
                "starred" to ((row["starred"] as? Long ?: 0L) == 1L),
                "error" to ""
            )
            contactList.add(contact)
        }
        return contactList
    }

    private fun getAllCursorData(cursor: Cursor): List<Map<String, Any?>> {
        val rows = mutableListOf<Map<String, Any?>>()
        cursor.use {
            while (it.moveToNext()) {
                val row = HashMap<String, Any?>()
                for (index in 0 until it.columnCount) {
                    val columnName = it.getColumnName(index)
                    row[columnName] = when (it.getType(index)) {
                        Cursor.FIELD_TYPE_NULL -> ""
                        Cursor.FIELD_TYPE_INTEGER -> it.getLong(index)
                        Cursor.FIELD_TYPE_FLOAT -> it.getFloat(index)
                        Cursor.FIELD_TYPE_STRING -> it.getString(index)
                        Cursor.FIELD_TYPE_BLOB -> it.getBlob(index)
                        else -> throw IllegalStateException("Unknown column type for $columnName")
                    }
                }
                if (row.isNotEmpty()) rows.add(row)
            }
        }
        return rows
    }
}
