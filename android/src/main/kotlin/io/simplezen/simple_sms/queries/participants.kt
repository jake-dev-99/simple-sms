package io.simplezen.simple_sms.queries// /*
//  * Copyright (C) 2015 The Android Open Source Project
//  *
//  * Licensed under the Apache License, Version 2.0 (the "License");
//  * you may not use this file except in compliance with the License.
//  * You may obtain a copy of the License at
//  *
//  *      http://www.apache.org/licenses/LICENSE-2.0
//  *
//  * Unless required by applicable law or agreed to in writing, software
//  * distributed under the License is distributed on an "AS IS" BASIS,
//  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
//  * See the License for the specific language governing permissions and
//  * limitations under the License.
//  */
// package com.simplezen.unify_messages_plus.src.queries

// import com.simplezen.unify_messages_plus.src.queries.Columns.*
// import android.provider.BaseColumns
// import android.content.ContentValues
// import android.content.res.Resources
// import android.content.Context
// import android.database.Cursor
// import android.graphics.Color
// import android.os.Parcel
// import android.os.Parcelable
// import android.telephony.SubscriptionInfo
// import android.text.TextUtils
// import android.support.mms.MmsManager
// import androidx.collection.ArrayMap

// /**
//  * A class that encapsulates all of the data for a specific participant in a conversation.
//  */
// class ParticipantData(val context : Context) : Parcelable {
//     object ParticipantsQuery {
//         // Define ParticipantColumns object to fix unresolved references
//         val PROJECTION: Array<String?> = arrayOf<String?>(
//             BaseColumns._ID,
//             ParticipantColumns.SUB_ID,
//             ParticipantColumns.SIM_SLOT_ID,
//             ParticipantColumns.NORMALIZED_DESTINATION,
//             ParticipantColumns.SEND_DESTINATION,
//             ParticipantColumns.DISPLAY_DESTINATION,
//             ParticipantColumns.FULL_NAME,
//             ParticipantColumns.FIRST_NAME,
//             ParticipantColumns.PROFILE_PHOTO_URI,
//             ParticipantColumns.CONTACT_ID,
//             ParticipantColumns.LOOKUP_KEY,
//             ParticipantColumns.BLOCKED,
//             ParticipantColumns.SUBSCRIPTION_COLOR,
//             ParticipantColumns.SUBSCRIPTION_NAME,
//             ParticipantColumns.CONTACT_DESTINATION,
//         )

//         const val INDEX_ID: Int = 0
//         const val INDEX_SUB_ID: Int = 1
//         const val INDEX_SIM_SLOT_ID: Int = 2
//         const val INDEX_NORMALIZED_DESTINATION: Int = 3
//         const val INDEX_SEND_DESTINATION: Int = 4
//         const val INDEX_DISPLAY_DESTINATION: Int = 5
//         const val INDEX_FULL_NAME: Int = 6
//         const val INDEX_FIRST_NAME: Int = 7
//         const val INDEX_PROFILE_PHOTO_URI: Int = 8
//         const val INDEX_CONTACT_ID: Int = 9
//         const val INDEX_LOOKUP_KEY: Int = 10
//         const val INDEX_BLOCKED: Int = 11
//         const val INDEX_SUBSCRIPTION_COLOR: Int = 12
//         const val INDEX_SUBSCRIPTION_NAME: Int = 13
//         const val INDEX_CONTACT_DESTINATION: Int = 14
//     }

//     var id: String? = null
//         private set
//     var subId: Int = 0
//         private set
//     var slotId: Int = 0
//         private set
//     var normalizedDestination: String? = null
//         private set
//     var sendDestination: String? = null
//     var displayDestination: String? = null
//         private set
//     var contactDestination: String? = null
//     var fullName: String? = null
//     var firstName: String? = null
//     var profilePhotoUri: String? = null
//     var contactId: Long = 0
//     var lookupKey: String? = null
//     private var mSubscriptionColor = 0
//     private var mSubscriptionName: String? = null
//     var isEmail: Boolean = false
//         private set
//     var isBlocked: Boolean = false
//         private set

//     private fun maybeSetupUnknownSender() {
//         if (this.isUnknownSender) {
//             // Because your locale may change, we setup the display string for the unknown sender
//             // on the fly rather than relying on the version in the database.
//             this.displayDestination = "Unknown Sender"
//             this.fullName = this.displayDestination
//         }
//     }

//     fun getDisplayName(preferFullName: Boolean): String? {
//         if (preferFullName) {
//             // Prefer full name over first name
//             if (!TextUtils.isEmpty(this.fullName)) {
//                 return this.fullName
//             }
//             if (!TextUtils.isEmpty(this.firstName)) {
//                 return this.firstName
//             }
//         } else {
//             // Prefer first name over full name
//             if (!TextUtils.isEmpty(this.firstName)) {
//                 return this.firstName
//             }
//             if (!TextUtils.isEmpty(this.fullName)) {
//                 return this.fullName
//             }
//         }

//         // Fallback to the display destination
//         if (!TextUtils.isEmpty(this.displayDestination)) {
//             return this.displayDestination
//         }
//         return "Unknown Sender"
//     }

//     fun updatePhoneNumberForSelfIfChanged(): Boolean {
//         val phoneNumber: String? =
//             PhoneUtils.get(this.subId).getCanonicalForSelf(true /*allowOverride*/)
//         var changed = false
//         if (this.isSelf && !TextUtils.equals(phoneNumber, this.normalizedDestination)) {
//             this.normalizedDestination = phoneNumber
//             this.sendDestination = phoneNumber
//             this.displayDestination = if (this.isEmail) phoneNumber else PhoneUtils.default
//                 .formatForDisplay(phoneNumber)
//             changed = true
//         }
//         return changed
//     }

//     fun updateSubscriptionInfoForSelfIfChanged(subscriptionInfo: SubscriptionInfo?): Boolean {
//         var changed = false
//         if (this.isSelf) {
//             if (subscriptionInfo == null) {
//                 // The subscription is inactive. Check if the participant is still active.
//                 if (this.isActiveSubscription) {
//                     this.slotId =
//                         INVALID_SLOT_ID
//                     mSubscriptionColor = Color.TRANSPARENT
//                     mSubscriptionName = ""
//                     changed = true
//                 }
//             } else {
//                 val slotId: Int = subscriptionInfo.getSimSlotIndex()
//                 val color: Int = subscriptionInfo.getIconTint()
//                 val name: CharSequence = subscriptionInfo.getDisplayName()
//                 if (this.slotId != slotId || mSubscriptionColor != color || mSubscriptionName !== name) {
//                     this.slotId = slotId
//                     mSubscriptionColor = color
//                     mSubscriptionName = name.toString()
//                     changed = true
//                 }
//             }
//         }
//         return changed
//     }

//     val isActiveSubscription: Boolean
//         /**
//          * @return whether this sub is active. Note that [ParticipantData.DEFAULT_SELF_SUB_ID] is
//          * is considered as active if there is any active SIM.
//          */
//         get() = this.slotId != INVALID_SLOT_ID

//     val isDefaultSelf: Boolean
//         get() = this.subId == DEFAULT_SELF_SUB_ID

//     val displaySlotId: Int
//         /**
//          * Slot IDs in the subscription manager is zero-based, but we want to show it
//          * as 1-based in UI.
//          */
//         get() = this.slotId + 1

//     val subscriptionColor: Int
//         get() {
//             if(!this.isActiveSubscription) {
//                 return Color.TRANSPARENT
//             }
//             // Force the alpha channel to 0xff to ensure the returned color is solid.
//             return mSubscriptionColor or -0x1000000
//         }

//     val subscriptionName: String?
//         get() {
//                 if(!this.isActiveSubscription) {
//                 return null
//             }
//             return mSubscriptionName
//         }

//     val isSelf: Boolean
//         get() = (this.subId != OTHER_THAN_SELF_SUB_ID)

//     val isContactIdResolved: Boolean
//         get() = (this.contactId != PARTICIPANT_CONTACT_ID_NOT_RESOLVED)

//     val isUnknownSender: Boolean
//         get() {
//             val unknownSender: String =
//                 getUnknownSenderDestination()
//             return (TextUtils.equals(this.sendDestination, unknownSender))
//         }

//     fun toContentValues(): ContentValues {
//         val values: ContentValues = ContentValues()
//         values.put(ParticipantColumns.SUB_ID, this.subId)
//         values.put(ParticipantColumns.SIM_SLOT_ID, this.slotId)
//         values.put(ParticipantColumns.SEND_DESTINATION, this.sendDestination)

//         if (!this.isUnknownSender) {
//             values.put(
//                 ParticipantColumns.DISPLAY_DESTINATION,
//                 this.displayDestination
//             )
//             values.put(
//                 ParticipantColumns.NORMALIZED_DESTINATION,
//                 this.normalizedDestination
//             )
//             values.put(ParticipantColumns.FULL_NAME, this.fullName)
//             values.put(ParticipantColumns.FIRST_NAME, this.firstName)
//         }

//         values.put(ParticipantColumns.PROFILE_PHOTO_URI, this.profilePhotoUri)
//         values.put(ParticipantColumns.CONTACT_ID, this.contactId)
//         values.put(ParticipantColumns.LOOKUP_KEY, this.lookupKey)
//         values.put(ParticipantColumns.BLOCKED, this.isBlocked)
//         values.put(ParticipantColumns.SUBSCRIPTION_COLOR, mSubscriptionColor)
//         values.put(ParticipantColumns.SUBSCRIPTION_NAME, mSubscriptionName)
//         return values
//     }

//     constructor(`in`: Parcel) : this(`in`.readParcelable<Context>(Context::class.java.classLoader)!!) {
//         this.id = `in`.readString()
//         this.subId = `in`.readInt()
//         this.slotId = `in`.readInt()
//         this.normalizedDestination = `in`.readString()
//         this.sendDestination = `in`.readString()
//         this.displayDestination = `in`.readString()
//         this.fullName = `in`.readString()
//         this.firstName = `in`.readString()
//         this.profilePhotoUri = `in`.readString()
//         this.contactId = `in`.readLong()
//         this.lookupKey = `in`.readString()
//         this.isEmail = `in`.readInt() !== 0
//         this.isBlocked = `in`.readInt() !== 0
//         mSubscriptionColor = `in`.readInt()
//         mSubscriptionName = `in`.readString()
//     }

//     public override fun describeContents(): Int {
//         return 0
//     }

//     public override fun writeToParcel(dest: Parcel, flags: Int) {
//         dest.writeString(this.id)
//         dest.writeInt(this.subId)
//         dest.writeInt(this.slotId)
//         dest.writeString(this.normalizedDestination)
//         dest.writeString(this.sendDestination)
//         dest.writeString(this.displayDestination)
//         dest.writeString(this.fullName)
//         dest.writeString(this.firstName)
//         dest.writeString(this.profilePhotoUri)
//         dest.writeLong(this.contactId)
//         dest.writeString(this.lookupKey)
//         dest.writeInt(if (this.isEmail) 1 else 0)
//         dest.writeInt(if (this.isBlocked) 1 else 0)
//         dest.writeInt(mSubscriptionColor)
//         dest.writeString(mSubscriptionName)
//     }

//     companion object {
//         private val sSubIdtoParticipantIdCache: ArrayMap<Int?, String?> = ArrayMap<Int?, String?>()

//         // We always use -1 as default/invalid sub id although system may give us anything negative
//         val DEFAULT_SELF_SUB_ID: Int = MmsManager.DEFAULT_SUB_ID

//         // This needs to be something apart from valid or DEFAULT_SELF_SUB_ID
//         val OTHER_THAN_SELF_SUB_ID: Int =
//             DEFAULT_SELF_SUB_ID - 1

//         // Active slot ids are non-negative. Using -1 to designate to inactive self participants.
//         val INVALID_SLOT_ID: Int = -1

//         // TODO: may make sense to move this to common place?
//         val PARTICIPANT_CONTACT_ID_NOT_RESOLVED: Long = -1
//         val PARTICIPANT_CONTACT_ID_NOT_FOUND: Long = -2

//         val unknownSenderDestination: String
//             /**
//              * @return The MMS unknown sender participant entity
//              */
//             get() =// This is a hard coded string rather than a localized one because we don't want it to
//                 // change when you change locale.
//                 "\u02BCUNKNOWN_SENDER!\u02BC"

//         fun getFromCursor(cursor: Cursor): ParticipantData {
//             val pd: ParticipantData = ParticipantData(context)
//             pd.id =
//                 cursor.getString(ParticipantsQuery.INDEX_ID)
//             pd.subId =
//                 cursor.getInt(ParticipantsQuery.INDEX_SUB_ID)
//             pd.slotId =
//                 cursor.getInt(ParticipantsQuery.INDEX_SIM_SLOT_ID)
//             pd.normalizedDestination = cursor.getString(
//                 ParticipantsQuery.INDEX_NORMALIZED_DESTINATION
//             )
//             pd.sendDestination =
//                 cursor.getString(ParticipantsQuery.INDEX_SEND_DESTINATION)
//             pd.displayDestination =
//                 cursor.getString(ParticipantsQuery.INDEX_DISPLAY_DESTINATION)
//             pd.contactDestination =
//                 cursor.getString(ParticipantsQuery.INDEX_CONTACT_DESTINATION)
//             pd.fullName =
//                 cursor.getString(ParticipantsQuery.INDEX_FULL_NAME)
//             pd.firstName =
//                 cursor.getString(ParticipantsQuery.INDEX_FIRST_NAME)
//             pd.profilePhotoUri =
//                 cursor.getString(ParticipantsQuery.INDEX_PROFILE_PHOTO_URI)
//             pd.contactId =
//                 cursor.getLong(ParticipantsQuery.INDEX_CONTACT_ID)
//             pd.lookupKey =
//                 cursor.getString(ParticipantsQuery.INDEX_LOOKUP_KEY)
//             pd.isEmail = MmsSmsUtils.isEmailAddress(pd.sendDestination)
//             pd.isBlocked =
//                 cursor.getInt(ParticipantsQuery.INDEX_BLOCKED) !== 0
//             pd.mSubscriptionColor =
//                     cursor.getInt(ParticipantsQuery.INDEX_SUBSCRIPTION_COLOR)
//             pd.mSubscriptionName =
//                 cursor.getString(ParticipantsQuery.INDEX_SUBSCRIPTION_NAME)
//             pd.maybeSetupUnknownSender()
//             return pd
//         }

//         fun getFromId(
//             dbWrapper: DatabaseWrapper,
//             participantId: String?
//         ): ParticipantData? {
//             var cursor: Cursor? = null
//             try {
//                 cursor = dbWrapper.query(
//                     tables.PARTICIPANTS_TABLE,
//                     ParticipantsQuery.PROJECTION,
//                     BaseColumns._ID + " =?",
//                     arrayOf<String?>(participantId), null, null, null
//                 )

//                 if (cursor.moveToFirst()) {
//                     return getFromCursor(
//                         cursor
//                     )
//                 } else {
//                     return null
//                 }
//             } finally {
//                 if (cursor != null) {
//                     cursor.close()
//                 }
//             }
//         }

//         fun getFromRecipientEntry(recipientEntry: RecipientEntry): ParticipantData {
//             val pd: ParticipantData =   ParticipantData(context)
//             pd.id = null
//             pd.subId =
//                 OTHER_THAN_SELF_SUB_ID
//             pd.slotId =
//                 INVALID_SLOT_ID
//             pd.sendDestination = TextUtil.replaceUnicodeDigits(recipientEntry.getDestination())
//             pd.isEmail = MmsSmsUtils.isEmailAddress(pd.sendDestination)
//             pd.normalizedDestination =
//                 if (pd.isEmail) pd.sendDestination else PhoneUtils.default
//                     .getCanonicalBySystemLocale(pd.sendDestination)
//             pd.displayDestination =
//                 if (pd.isEmail) pd.normalizedDestination else PhoneUtils.default
//                     .formatForDisplay(pd.normalizedDestination)
//             pd.fullName = recipientEntry.getDisplayName()
//             pd.firstName = null
//             pd.profilePhotoUri =
//                 if (recipientEntry.getPhotoThumbnailUri() == null) null else recipientEntry.getPhotoThumbnailUri()
//                     .toString()
//             pd.contactId = recipientEntry.getContactId()
//             if (pd.contactId < 0) {
//                 // ParticipantData only supports real contact ids (>=0) based on faith that the contacts
//                 // provider will continue to only use non-negative ids.  The UI uses contactId < 0 for
//                 // special handling. We convert those to 'not resolved'
//                 pd.contactId =
//                     PARTICIPANT_CONTACT_ID_NOT_RESOLVED
//             }
//             pd.lookupKey = recipientEntry.getLookupKey()
//             pd.isBlocked = false
//             pd.mSubscriptionColor = Color.TRANSPARENT
//             pd.mSubscriptionName = null
//             pd.maybeSetupUnknownSender()
//             return pd
//         }

//         // Shared code for getFromRawPhoneBySystemLocale and getFromRawPhoneBySimLocale
//         private fun getFromRawPhone(phoneNumber: String?): ParticipantData {
//             Assert.isTrue(phoneNumber != null)
//             val pd: ParticipantData = ParticipantData(context)
//             pd.id = null
//             pd.subId =
//                 OTHER_THAN_SELF_SUB_ID
//             pd.slotId =
//                 INVALID_SLOT_ID
//             pd.sendDestination = TextUtil.replaceUnicodeDigits(phoneNumber)
//             pd.isEmail = MmsSmsUtils.isEmailAddress(pd.sendDestination)
//             pd.fullName = null
//             pd.firstName = null
//             pd.profilePhotoUri = null
//             pd.contactId =
//                 PARTICIPANT_CONTACT_ID_NOT_RESOLVED
//             pd.lookupKey = null
//             pd.isBlocked = false
//             pd.mSubscriptionColor = Color.TRANSPARENT
//             pd.mSubscriptionName = null
//             return pd
//         }

//         /**
//          * Get an instance from a raw phone number and using system locale to normalize it.
//          *
//          * Use this when creating a participant that is for displaying UI and not associated
//          * with a specific SIM. For example, when creating a conversation using user entered
//          * phone number.
//          *
//          * @param phoneNumber The raw phone number
//          * @return instance
//          */
//         fun getFromRawPhoneBySystemLocale(phoneNumber: String?): ParticipantData {
//             val pd: ParticipantData =
//                 getFromRawPhone(
//                     phoneNumber
//                 )
//             pd.normalizedDestination =
//                 if (pd.isEmail) pd.sendDestination else PhoneUtils.default
//                     .getCanonicalBySystemLocale(pd.sendDestination)
//             pd.displayDestination =
//                 if (pd.isEmail) pd.normalizedDestination else PhoneUtils.default
//                     .formatForDisplay(pd.normalizedDestination)
//             pd.maybeSetupUnknownSender()
//             return pd
//         }

//         /**
//          * Get an instance from a raw phone number and using SIM or system locale to normalize it.
//          *
//          * Use this when creating a participant that is associated with a specific SIM. For example,
//          * the sender of a received message or the recipient of a sending message that is already
//          * targeted at a specific SIM.
//          *
//          * @param phoneNumber The raw phone number
//          * @return instance
//          */
//         fun getFromRawPhoneBySimLocale(
//             phoneNumber: String?, subId: Int
//         ): ParticipantData {
//             val pd: ParticipantData =
//                 getFromRawPhone(
//                     phoneNumber
//                 )
//             pd.normalizedDestination = if (pd.isEmail) pd.sendDestination else PhoneUtils.get(subId)
//                 .getCanonicalBySimLocale(pd.sendDestination)
//             pd.displayDestination =
//                 if (pd.isEmail) pd.normalizedDestination else PhoneUtils.default
//                     .formatForDisplay(pd.normalizedDestination)
//             pd.maybeSetupUnknownSender()
//             return pd
//         }

//         fun getSelfParticipant(subId: Int, context: Context): ParticipantData {
//             if(subId == OTHER_THAN_SELF_SUB_ID) {
//                 throw IllegalArgumentException("SubId is not self")
//             }
//             val pd: ParticipantData = ParticipantData(context)
//             pd.id = null
//             pd.subId = subId
//             pd.slotId =
//                 INVALID_SLOT_ID
//             pd.isEmail = false
//             pd.sendDestination = null
//             pd.normalizedDestination = null
//             pd.displayDestination = null
//             pd.fullName = null
//             pd.firstName = null
//             pd.profilePhotoUri = null
//             pd.contactId =
//                 PARTICIPANT_CONTACT_ID_NOT_RESOLVED
//             pd.lookupKey = null
//             pd.isBlocked = false
//             pd.mSubscriptionColor = Color.TRANSPARENT
//             pd.mSubscriptionName = null
//             return pd
//         }

//         fun getParticipantId(subId: Int, context: Context): String? {
//             var id: String?
//             synchronized(sSubIdtoParticipantIdCache) {
//                 id =
//                     sSubIdtoParticipantIdCache.get(
//                         subId
//                     )
//             }

//             if (id != null) {
//                 return id
//             }

//             val contentResolver = context.contentResolver
//             contentResolver.query(
//                 tables.PARTICIPANTS_TABLE,
//                 arrayOf<String?>(BaseColumns._ID),
//                 ParticipantColumns.SUB_ID + " =?",
//                 arrayOf<String>(subId.toString()), null, null, null
//             ).use { cursor ->
//                 if (cursor.moveToFirst()) {
//                     // We found an existing participant in the database
//                     id = cursor.getString(0)
//                     synchronized(sSubIdtoParticipantIdCache) {
//                         // Add it to the cache for next time
//                         sSubIdtoParticipantIdCache.put(
//                             subId,
//                             id
//                         )
//                     }
//                 }
//             }
//             return id
//         }

//         val CREATOR
//                 : Parcelable.Creator<ParticipantData?> = object : Parcelable.Creator<ParticipantData?> {
//             public override fun createFromParcel(`in`: Parcel): ParticipantData {
//                     return ParticipantData(`in`)
//             }

//             public override fun newArray(size: Int): Array<ParticipantData?> {
//                 return arrayOfNulls<ParticipantData>(size)
//             }
//         }
//     }
// }

