package io.simplezen.simple_sms.queries

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.ContactsContract.CommonDataKinds
import android.provider.ContactsContract.Contacts
import android.provider.ContactsContract.Profile
import android.provider.ContactsContract.RawContacts
import android.provider.Telephony.BaseMmsColumns.MESSAGE_BOX_INBOX
import android.util.Log

val PROFILE_URI: Uri = Profile.CONTENT_URI!!


val PEOPLE_URI: Uri = Contacts.CONTENT_URI!!

//val contactDataUri = Data.CONTENT_URI

val CONTACT_URI: Uri = RawContacts.CONTENT_URI!!
val LOOKUPID_URI: Uri = Contacts.CONTENT_LOOKUP_URI!!
val NAME_URI: Uri = Contacts.CONTENT_FILTER_URI!!
val EMAIL_URI: Uri = CommonDataKinds.Email.CONTENT_LOOKUP_URI!!
val PHONE_URI: Uri = CommonDataKinds.Phone.CONTENT_FILTER_URI!!

//val contactDataUri = Data.CONTENT_URI

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
            throw Exception("Missing Parameter for ContactsFilter.$this")
        }
        return when {
            this == All -> CONTACT_URI
            this == Profile -> PROFILE_URI
            this == LookupId -> LOOKUPID_URI.buildUpon().appendPath(param).build()
            this == Name -> NAME_URI.buildUpon().appendPath(param).build()
            this == PhoneNumber -> PHONE_URI.buildUpon().appendPath(param).build()
            this == Email -> EMAIL_URI.buildUpon().appendPath(param).build()
            else -> throw Exception("Invalid MessageQueryEnum: $this")
        }
    }
}

class ContactQuery(val context: Context) {

//    fun fetchAll(): MutableList<Map<String, Any?>> {
//        val uri: Uri = ContactsFilter.All.uri()
//        Log.d("Contacts", " <<< Fetching all contacts")
//        Log.d("Contacts", " <<< URI: $uri")
//        val results: MutableList<Map<String, Any?>> = PrivateContactQuery(context).query(uri)
//        return results
//    }

    fun fetch(filter: ContactsFilter, param: String): Map<String, Any?>? {
        val uri = filter.uri(param)

        if (filter != ContactsFilter.Profile && param.isEmpty()) return null

        Log.d("Contacts", " <<< Fetching contact by $filter")
        val result = PrivateContactQuery(context).query(uri)
        Log.d("Contacts", " <<< Result $result")
        return if (result.isEmpty()) {
            null
        } else result.first()
    }
}

private class PrivateContactQuery(val context: Context) {

    fun query(uri: Uri): MutableList<Map<String, Any?>> {

        val contentResolver = context.contentResolver
        val contactList = mutableListOf<Map<String, Any?>>()
        Log.d("Contacts", " <<< Querying URI: $uri")

        val commonDataUrl: Uri = CommonDataKinds.Contactables.CONTENT_URI
            .buildUpon()
            .appendQueryParameter("VISIBLE_CONTACTS_ONLY", "true")
            .build()

        val commonDataCursor = contentResolver.query(commonDataUrl, null, null, null, null)
        val commonData = getAllCursorData(commonDataCursor!!)
        Log.d("Contacts", " <<< Common Data: ${commonData.size}")

        val rawContactCursor = contentResolver.query(uri, null, null, null, null)
        val data = getAllCursorData(rawContactCursor!!)
        for (row in data) {
            if (row.isEmpty()) {
                continue
            }

            // Fetch phone numbers for this contact
            val phoneNumbers = mutableListOf<String>()
            val phoneNumCursor =
                contentResolver.query(
                    CommonDataKinds.Phone.CONTENT_URI,
                    null,
                    "${CommonDataKinds.Phone.CONTACT_ID} = ?",
                    arrayOf(row["_id"].toString()),
                    null,
                    null
                )
            val phoneNumData = getAllCursorData(phoneNumCursor!!)
            printQueryResults( row)

            for (phoneNumRow in phoneNumData) {
                if (phoneNumRow.isNotEmpty()) {
                    Log.d("Contacts", " <<< PhoneNumRow: $phoneNumRow")
                    try {
                        val phoneNumber: String =
                            phoneNumRow[CommonDataKinds.Phone.NUMBER] as String
                        phoneNumbers.add(phoneNumber)
                    } catch (_: Exception) {}
                }
            }

            // Fetch Emails for this contact
            val emails = mutableListOf<String>()
            val emailCursor =
                contentResolver.query(
                    CommonDataKinds.Email.CONTENT_URI,
                    null,
                    "${CommonDataKinds.Email.CONTACT_ID} = ?",
                    arrayOf(row["_ID"].toString()),
                    null,
                    null
                )
            val emailData = getAllCursorData(emailCursor!!)

            for (emailRow in emailData) {
                val email = emailRow[CommonDataKinds.Email.ADDRESS] as String
                emails.add(email)
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
                "hasPhoneNum" to true,
                "inVisibleGroup" to true,
                "isUserProfile" to false,
                "lookupKey" to row["lookup"].toString(),
                "phoneticName" to row["lookupKey"].toString(),
                "photoUri" to row["lookupKey"].toString(),
                "photoId" to row["lookupKey"].toString(),
                "photoThumbnailUri" to row["lookupKey"].toString(),
                "starred" to false,
                "error" to ""
            )
            Log.d("Contacts", " <<< Final Contact: $contact")
            Log.d("Contacts", " <<< externalId: ${contact["externalId"]}")
            Log.d("Contacts", " <<< name: ${contact["name"]}")
            Log.d("Contacts", " <<< phoneNumbers: ${contact["phoneNumbers"]}")
            Log.d("Contacts", " <<< emailAddresses: ${contact["emailAddresses"]}")
            Log.d("Contacts", " <<< lastUpdated: ${contact["lastUpdated"]}")
            Log.d("Contacts", " <<< ringtone: ${contact["ringtone"]}")
            Log.d("Contacts", " <<< primaryName: ${contact["primaryName"]}")
            Log.d("Contacts", " <<< alternativeName: ${contact["alternativeName"]}")
            Log.d("Contacts", " <<< hasPhoneNum: ${contact["hasPhoneNum"]}")
            Log.d("Contacts", " <<< inVisibleGroup: ${contact["inVisibleGroup"]}")
            Log.d("Contacts", " <<< isUserProfile: ${contact["isUserProfile"]}")
            Log.d("Contacts", " <<< lookupKey: ${contact["lookupKey"]}")
            Log.d("Contacts", " <<< phoneticName: ${contact["phoneticName"]}")
            Log.d("Contacts", " <<< photoUri: ${contact["photoUri"]}")
            Log.d("Contacts", " <<< photoId: ${contact["photoId"]}")
            Log.d("Contacts", " <<< photoThumbnailUri: ${contact["photoThumbnailUri"]}")
            Log.d("Contacts", " <<< starred: ${contact["starred"]}")
            Log.d("Contacts", " <<< error: ${contact["error"]}")

            contactList.add(contact)
        }
        return contactList
    }

    private fun getAllCursorData(cursor: Cursor): List<Map<String, Any?>> {
        val returnable: MutableList<Map<String, Any?>> = mutableListOf()
        cursor.use {
            while (it.moveToNext()) {
                val row: HashMap<String, Any?> = HashMap()
                for (index in 0 until cursor.columnCount) {
                    val columnName = cursor.getColumnName(index)
                    val columnType = cursor.getType(index)
                    when (columnType) {
                        Cursor.FIELD_TYPE_NULL -> row[columnName] = ""
                        Cursor.FIELD_TYPE_INTEGER -> row[columnName] = cursor.getLong(index)
                        Cursor.FIELD_TYPE_FLOAT -> row[columnName] = cursor.getFloat(index)
                        Cursor.FIELD_TYPE_STRING -> row[columnName] = cursor.getString(index)
                        Cursor.FIELD_TYPE_BLOB -> row[columnName] = cursor.getBlob(index)
                        else -> throw Exception("Unknown column type: $columnType")
                    }
                }
                if (row.isNotEmpty()) {
                    returnable.add(row)
                }
            }
        }
        return returnable
    }

    private fun printQueryResults( results: Map<String, Any?>) {
        val sortedMap = results.toSortedMap(compareBy<String> { it }.thenBy { it.length })

        Log.d("printQueryResults", " <<< ")
        Log.d("printQueryResults", " <<< -------------------------------")
        Log.d("printQueryResults", " <<<    >>>> Contact Data ")
        Log.d("printQueryResults", " <<< -------------------------------")
        Log.d("printQueryResults", " <<< ")
        for (key in sortedMap.keys) Log.d("printQueryResults", " <<< $key: ${sortedMap[key]}")
        Log.d("printQueryResults", " <<< ")
        Log.d("printQueryResults", " <<< ")
        Log.d("printQueryResults", " <<< ")
    }
}