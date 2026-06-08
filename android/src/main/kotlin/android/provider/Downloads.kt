/*
 * Copyright (C) 2008 The Android Open Source Project
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

package android.provider

import android.app.DownloadManager
import android.content.Context
import android.net.Uri

/**
 * The Download Manager — first-party Kotlin port of the vendored
 * `android.provider.Downloads` contract (Phase 5). Behaviour-faithful 1:1: the
 * same `Impl`/`RequestHeaders` constant surface (column names, content URIs,
 * status/destination/control/visibility codes), the same status-classification
 * helpers, and the same `removeAllDownloadsByPackage` provider delete.
 *
 * Faithful-port notes:
 * - `Impl`/`RequestHeaders` are Kotlin `object`s; `const val`s and `@JvmField`
 *   vals (for the `Uri` / framework-derived values that can't be `const`) keep
 *   `Downloads.Impl.X` resolving for Java and Kotlin consumers (e.g.
 *   `DrmConvertSession` reads `Downloads.Impl.STATUS_*`). Status helpers are
 *   `@JvmStatic`.
 * - TODO(layering): `removeAllDownloadsByPackage` deletes via `ContentResolver`
 *   directly; it will route through `simple_query` in the separate layering
 *   re-route change, not in this fidelity port.
 *
 * @pending
 */
class Downloads private constructor() {

    /**
     * Implementation details
     *
     * Exposes constants used to interact with the download manager's
     * content provider.
     * The constants URI ... STATUS are the names of columns in the downloads table.
     *
     * @hide
     */
    object Impl : BaseColumns {
        /** The permission to access the download manager */
        const val PERMISSION_ACCESS = "android.permission.ACCESS_DOWNLOAD_MANAGER"

        /** The permission to access the download manager's advanced functions */
        const val PERMISSION_ACCESS_ADVANCED =
            "android.permission.ACCESS_DOWNLOAD_MANAGER_ADVANCED"

        /** The permission to access the all the downloads in the manager. */
        const val PERMISSION_ACCESS_ALL =
            "android.permission.ACCESS_ALL_DOWNLOADS"

        /** The permission to directly access the download manager's cache directory */
        const val PERMISSION_CACHE = "android.permission.ACCESS_CACHE_FILESYSTEM"

        /** The permission to send broadcasts on download completion */
        const val PERMISSION_SEND_INTENTS =
            "android.permission.SEND_DOWNLOAD_COMPLETED_INTENTS"

        /**
         * The permission to download files to the cache partition that won't be automatically
         * purged when space is needed.
         */
        const val PERMISSION_CACHE_NON_PURGEABLE =
            "android.permission.DOWNLOAD_CACHE_NON_PURGEABLE"

        /** The permission to download files without any system notification being shown. */
        const val PERMISSION_NO_NOTIFICATION =
            "android.permission.DOWNLOAD_WITHOUT_NOTIFICATION"

        /** The content:// URI to access downloads owned by the caller's UID. */
        @JvmField
        val CONTENT_URI: Uri = Uri.parse("content://downloads/my_downloads")

        /**
         * The content URI for accessing all downloads across all UIDs (requires the
         * ACCESS_ALL_DOWNLOADS permission).
         */
        @JvmField
        val ALL_DOWNLOADS_CONTENT_URI: Uri = Uri.parse("content://downloads/all_downloads")

        /** URI segment to access a publicly accessible downloaded file */
        const val PUBLICLY_ACCESSIBLE_DOWNLOADS_URI_SEGMENT = "public_downloads"

        /**
         * The content URI for accessing publicly accessible downloads (i.e., it requires no
         * permissions to access this downloaded file)
         */
        @JvmField
        val PUBLICLY_ACCESSIBLE_DOWNLOADS_URI: Uri =
            Uri.parse("content://downloads/$PUBLICLY_ACCESSIBLE_DOWNLOADS_URI_SEGMENT")

        /**
         * Broadcast Action: this is sent by the download manager to the app
         * that had initiated a download when that download completes. The
         * download's content: uri is specified in the intent's data.
         */
        const val ACTION_DOWNLOAD_COMPLETED =
            "android.intent.action.DOWNLOAD_COMPLETED"

        /**
         * Broadcast Action: this is sent by the download manager to the app
         * that had initiated a download when the user selects the notification
         * associated with that download. The download's content: uri is specified
         * in the intent's data if the click is associated with a single download,
         * or Downloads.CONTENT_URI if the notification is associated with
         * multiple downloads.
         * Note: this is not currently sent for downloads that have completed
         * successfully.
         */
        const val ACTION_NOTIFICATION_CLICKED =
            "android.intent.action.DOWNLOAD_NOTIFICATION_CLICKED"

        /**
         * The name of the column containing the URI of the data being downloaded.
         * <P>Type: TEXT</P>
         * <P>Owner can Init/Read</P>
         */
        const val COLUMN_URI = "uri"

        /**
         * The name of the column containing application-specific data.
         * <P>Type: TEXT</P>
         * <P>Owner can Init/Read/Write</P>
         */
        const val COLUMN_APP_DATA = "entity"

        /**
         * The name of the column containing the flags that indicates whether
         * the initiating application is capable of verifying the integrity of
         * the downloaded file. When this flag is set, the download manager
         * performs downloads and reports success even in some situations where
         * it can't guarantee that the download has completed (e.g. when doing
         * a byte-range request without an ETag, or when it can't determine
         * whether a download fully completed).
         * <P>Type: BOOLEAN</P>
         * <P>Owner can Init</P>
         */
        const val COLUMN_NO_INTEGRITY = "no_integrity"

        /**
         * The name of the column containing the filename that the initiating
         * application recommends. When possible, the download manager will attempt
         * to use this filename, or a variation, as the actual name for the file.
         * <P>Type: TEXT</P>
         * <P>Owner can Init</P>
         */
        const val COLUMN_FILE_NAME_HINT = "hint"

        /**
         * The name of the column containing the filename where the downloaded data
         * was actually stored.
         * <P>Type: TEXT</P>
         * <P>Owner can Read</P>
         */
        const val _DATA = "_data"

        /**
         * The name of the column containing the MIME type of the downloaded data.
         * <P>Type: TEXT</P>
         * <P>Owner can Init/Read</P>
         */
        const val COLUMN_MIME_TYPE = "mimetype"

        /**
         * The name of the column containing the flag that controls the destination
         * of the download. See the DESTINATION_* constants for a list of legal values.
         * <P>Type: INTEGER</P>
         * <P>Owner can Init</P>
         */
        const val COLUMN_DESTINATION = "destination"

        /**
         * The name of the column containing the flags that controls whether the
         * download is displayed by the UI. See the VISIBILITY_* constants for
         * a list of legal values.
         * <P>Type: INTEGER</P>
         * <P>Owner can Init/Read/Write</P>
         */
        const val COLUMN_VISIBILITY = "visibility"

        /**
         * The name of the column containing the current control state  of the download.
         * Applications can write to this to control (pause/resume) the download.
         * the CONTROL_* constants for a list of legal values.
         * <P>Type: INTEGER</P>
         * <P>Owner can Read</P>
         */
        const val COLUMN_CONTROL = "control"

        /**
         * The name of the column containing the current status of the download.
         * Applications can read this to follow the progress of each download. See
         * the STATUS_* constants for a list of legal values.
         * <P>Type: INTEGER</P>
         * <P>Owner can Read</P>
         */
        const val COLUMN_STATUS = "status"

        /**
         * The name of the column containing the date at which some interesting
         * status changed in the download. Stored as a System.currentTimeMillis()
         * value.
         * <P>Type: BIGINT</P>
         * <P>Owner can Read</P>
         */
        const val COLUMN_LAST_MODIFICATION = "lastmod"

        /**
         * The name of the column containing the package name of the application
         * that initiating the download. The download manager will send
         * notifications to a component in this package when the download completes.
         * <P>Type: TEXT</P>
         * <P>Owner can Init/Read</P>
         */
        const val COLUMN_NOTIFICATION_PACKAGE = "notificationpackage"

        /**
         * The name of the column containing the component name of the class that
         * will receive notifications associated with the download. The
         * package/class combination is passed to
         * Intent.setClassName(String,String).
         * <P>Type: TEXT</P>
         * <P>Owner can Init/Read</P>
         */
        const val COLUMN_NOTIFICATION_CLASS = "notificationclass"

        /**
         * If extras are specified when requesting a download they will be provided in the intent that
         * is sent to the specified class and package when a download has finished.
         * <P>Type: TEXT</P>
         * <P>Owner can Init</P>
         */
        const val COLUMN_NOTIFICATION_EXTRAS = "notificationextras"

        /**
         * The name of the column contain the values of the cookie to be used for
         * the download. This is used directly as the value for the Cookie: HTTP
         * header that gets sent with the request.
         * <P>Type: TEXT</P>
         * <P>Owner can Init</P>
         */
        const val COLUMN_COOKIE_DATA = "cookiedata"

        /**
         * The name of the column containing the user agent that the initiating
         * application wants the download manager to use for this download.
         * <P>Type: TEXT</P>
         * <P>Owner can Init</P>
         */
        const val COLUMN_USER_AGENT = "useragent"

        /**
         * The name of the column containing the referer (sic) that the initiating
         * application wants the download manager to use for this download.
         * <P>Type: TEXT</P>
         * <P>Owner can Init</P>
         */
        const val COLUMN_REFERER = "referer"

        /**
         * The name of the column containing the total size of the file being
         * downloaded.
         * <P>Type: INTEGER</P>
         * <P>Owner can Read</P>
         */
        const val COLUMN_TOTAL_BYTES = "total_bytes"

        /**
         * The name of the column containing the size of the part of the file that
         * has been downloaded so far.
         * <P>Type: INTEGER</P>
         * <P>Owner can Read</P>
         */
        const val COLUMN_CURRENT_BYTES = "current_bytes"

        /**
         * The name of the column where the initiating application can provide the
         * UID of another application that is allowed to access this download. If
         * multiple applications share the same UID, all those applications will be
         * allowed to access this download. This column can be updated after the
         * download is initiated. This requires the permission
         * android.permission.ACCESS_DOWNLOAD_MANAGER_ADVANCED.
         * <P>Type: INTEGER</P>
         * <P>Owner can Init</P>
         */
        const val COLUMN_OTHER_UID = "otheruid"

        /**
         * The name of the column where the initiating application can provided the
         * title of this download. The title will be displayed ito the user in the
         * list of downloads.
         * <P>Type: TEXT</P>
         * <P>Owner can Init/Read/Write</P>
         */
        const val COLUMN_TITLE = "title"

        /**
         * The name of the column where the initiating application can provide the
         * description of this download. The description will be displayed to the
         * user in the list of downloads.
         * <P>Type: TEXT</P>
         * <P>Owner can Init/Read/Write</P>
         */
        const val COLUMN_DESCRIPTION = "description"

        /**
         * The name of the column indicating whether the download was requesting through the public
         * API.  This controls some differences in behavior.
         * <P>Type: BOOLEAN</P>
         * <P>Owner can Init/Read</P>
         */
        const val COLUMN_IS_PUBLIC_API = "is_public_api"

        /**
         * The name of the column holding a bitmask of allowed network types.  This is only used for
         * public API downloads.
         * <P>Type: INTEGER</P>
         * <P>Owner can Init/Read</P>
         */
        const val COLUMN_ALLOWED_NETWORK_TYPES = "allowed_network_types"

        /**
         * The name of the column indicating whether roaming connections can be used.  This is only
         * used for public API downloads.
         * <P>Type: BOOLEAN</P>
         * <P>Owner can Init/Read</P>
         */
        const val COLUMN_ALLOW_ROAMING = "allow_roaming"

        /**
         * The name of the column indicating whether metered connections can be used.  This is only
         * used for public API downloads.
         * <P>Type: BOOLEAN</P>
         * <P>Owner can Init/Read</P>
         */
        const val COLUMN_ALLOW_METERED = "allow_metered"

        /**
         * Whether or not this download should be displayed in the system's Downloads UI.  Defaults
         * to true.
         * <P>Type: INTEGER</P>
         * <P>Owner can Init/Read</P>
         */
        const val COLUMN_IS_VISIBLE_IN_DOWNLOADS_UI = "is_visible_in_downloads_ui"

        /**
         * If true, the user has confirmed that this download can proceed over the mobile network
         * even though it exceeds the recommended maximum size.
         * <P>Type: BOOLEAN</P>
         */
        const val COLUMN_BYPASS_RECOMMENDED_SIZE_LIMIT =
            "bypass_recommended_size_limit"

        /**
         * Set to true if this download is deleted. It is completely removed from the database
         * when MediaProvider database also deletes the metadata asociated with this downloaded file.
         * <P>Type: BOOLEAN</P>
         * <P>Owner can Read</P>
         */
        const val COLUMN_DELETED = "deleted"

        /**
         * The URI to the corresponding entry in MediaProvider for this downloaded entry. It is
         * used to delete the entries from MediaProvider database when it is deleted from the
         * downloaded list.
         * <P>Type: TEXT</P>
         * <P>Owner can Read</P>
         */
        const val COLUMN_MEDIAPROVIDER_URI = "mediaprovider_uri"

        /**
         * The column that is used to remember whether the media scanner was invoked.
         * It can take the values: null or 0(not scanned), 1(scanned), 2 (not scannable).
         * <P>Type: TEXT</P>
         */
        const val COLUMN_MEDIA_SCANNED = "scanned"

        /**
         * The column with errorMsg for a failed downloaded.
         * Used only for debugging purposes.
         * <P>Type: TEXT</P>
         */
        const val COLUMN_ERROR_MSG = "errorMsg"

        /**
         * This column stores the source of the last update to this row.
         * This column is only for internal use.
         * Valid values are indicated by LAST_UPDATESRC_* constants.
         * <P>Type: INT</P>
         */
        const val COLUMN_LAST_UPDATESRC = "lastUpdateSrc"

        /**
         * default value for [COLUMN_LAST_UPDATESRC].
         * This value is used when this column's value is not relevant.
         */
        const val LAST_UPDATESRC_NOT_RELEVANT = 0

        /**
         * One of the values taken by [COLUMN_LAST_UPDATESRC].
         * This value is used when the update is NOT to be relayed to the DownloadService
         * (and thus spare DownloadService from scanning the database when this change occurs)
         */
        const val LAST_UPDATESRC_DONT_NOTIFY_DOWNLOADSVC = 1

        /*
         * Lists the destinations that an application can specify for a download.
         */

        /**
         * This download will be saved to the external storage. This is the
         * default behavior, and should be used for any file that the user
         * can freely access, copy, delete. Even with that destination,
         * unencrypted DRM files are saved in secure internal storage.
         * Downloads to the external destination only write files for which
         * there is a registered handler. The resulting files are accessible
         * by filename to all applications.
         */
        const val DESTINATION_EXTERNAL = 0

        /**
         * This download will be saved to the download manager's private
         * partition. This is the behavior used by applications that want to
         * download private files that are used and deleted soon after they
         * get downloaded. All file types are allowed, and only the initiating
         * application can access the file (indirectly through a content
         * provider). This requires the
         * android.permission.ACCESS_DOWNLOAD_MANAGER_ADVANCED permission.
         */
        const val DESTINATION_CACHE_PARTITION = 1

        /**
         * This download will be saved to the download manager's private
         * partition and will be purged as necessary to make space. This is
         * for private files (similar to CACHE_PARTITION) that aren't deleted
         * immediately after they are used, and are kept around by the download
         * manager as long as space is available.
         */
        const val DESTINATION_CACHE_PARTITION_PURGEABLE = 2

        /**
         * This download will be saved to the download manager's private
         * partition, as with DESTINATION_CACHE_PARTITION, but the download
         * will not proceed if the user is on a roaming data connection.
         */
        const val DESTINATION_CACHE_PARTITION_NOROAMING = 3

        /**
         * This download will be saved to the location given by the file URI in
         * [COLUMN_FILE_NAME_HINT].
         */
        const val DESTINATION_FILE_URI = 4

        /**
         * This download will be saved to the system cache ("/cache")
         * partition. This option is only used by system apps and so it requires
         * android.permission.ACCESS_CACHE_FILESYSTEM permission.
         */
        const val DESTINATION_SYSTEMCACHE_PARTITION = 5

        /**
         * This download was completed by the caller (i.e., NOT downloadmanager)
         * and caller wants to have this download displayed in Downloads App.
         */
        const val DESTINATION_NON_DOWNLOADMANAGER_DOWNLOAD = 6

        /** This download is allowed to run. */
        const val CONTROL_RUN = 0

        /** This download must pause at the first opportunity. */
        const val CONTROL_PAUSED = 1

        /*
         * Lists the states that the download manager can set on a download
         * to notify applications of the download progress.
         * The codes follow the HTTP families:
         * 1xx: informational
         * 2xx: success
         * 3xx: redirects (not used by the download manager)
         * 4xx: client errors
         * 5xx: server errors
         */

        /** Returns whether the status is informational (i.e. 1xx). */
        @JvmStatic
        fun isStatusInformational(status: Int): Boolean {
            return (status >= 100 && status < 200)
        }

        /** Returns whether the status is a success (i.e. 2xx). */
        @JvmStatic
        fun isStatusSuccess(status: Int): Boolean {
            return (status >= 200 && status < 300)
        }

        /** Returns whether the status is an error (i.e. 4xx or 5xx). */
        @JvmStatic
        fun isStatusError(status: Int): Boolean {
            return (status >= 400 && status < 600)
        }

        /** Returns whether the status is a client error (i.e. 4xx). */
        @JvmStatic
        fun isStatusClientError(status: Int): Boolean {
            return (status >= 400 && status < 500)
        }

        /** Returns whether the status is a server error (i.e. 5xx). */
        @JvmStatic
        fun isStatusServerError(status: Int): Boolean {
            return (status >= 500 && status < 600)
        }

        /**
         * this method determines if a notification should be displayed for a
         * given [COLUMN_VISIBILITY] value
         *
         * @param visibility the value of [COLUMN_VISIBILITY].
         * @return true if the notification should be displayed. false otherwise.
         */
        @JvmStatic
        fun isNotificationToBeDisplayed(visibility: Int): Boolean {
            return visibility == DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED ||
                visibility == DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_ONLY_COMPLETION
        }

        /**
         * Returns whether the download has completed (either with success or
         * error).
         */
        @JvmStatic
        fun isStatusCompleted(status: Int): Boolean {
            return (status >= 200 && status < 300) || (status >= 400 && status < 600)
        }

        /** This download hasn't stated yet */
        const val STATUS_PENDING = 190

        /** This download has started */
        const val STATUS_RUNNING = 192

        /** This download has been paused by the owning app. */
        const val STATUS_PAUSED_BY_APP = 193

        /** This download encountered some network error and is waiting before retrying the request. */
        const val STATUS_WAITING_TO_RETRY = 194

        /** This download is waiting for network connectivity to proceed. */
        const val STATUS_WAITING_FOR_NETWORK = 195

        /**
         * This download exceeded a size limit for mobile networks and is waiting for a Wi-Fi
         * connection to proceed.
         */
        const val STATUS_QUEUED_FOR_WIFI = 196

        /**
         * This download couldn't be completed due to insufficient storage
         * space.  Typically, this is because the SD card is full.
         */
        const val STATUS_INSUFFICIENT_SPACE_ERROR = 198

        /**
         * This download couldn't be completed because no external storage
         * device was found.  Typically, this is because the SD card is not
         * mounted.
         */
        const val STATUS_DEVICE_NOT_FOUND_ERROR = 199

        /**
         * This download has successfully completed.
         * Warning: there might be other status values that indicate success
         * in the future.
         * Use isSucccess() to capture the entire category.
         */
        const val STATUS_SUCCESS = 200

        /**
         * This request couldn't be parsed. This is also used when processing
         * requests with unknown/unsupported URI schemes.
         */
        const val STATUS_BAD_REQUEST = 400

        /**
         * This download can't be performed because the content type cannot be
         * handled.
         */
        const val STATUS_NOT_ACCEPTABLE = 406

        /**
         * This download cannot be performed because the length cannot be
         * determined accurately. This is the code for the HTTP error "Length
         * Required", which is typically used when making requests that require
         * a content length but don't have one, and it is also used in the
         * client when a response is received whose length cannot be determined
         * accurately (therefore making it impossible to know when a download
         * completes).
         */
        const val STATUS_LENGTH_REQUIRED = 411

        /**
         * This download was interrupted and cannot be resumed.
         * This is the code for the HTTP error "Precondition Failed", and it is
         * also used in situations where the client doesn't have an ETag at all.
         */
        const val STATUS_PRECONDITION_FAILED = 412

        /** The lowest-valued error status that is not an actual HTTP status code. */
        const val MIN_ARTIFICIAL_ERROR_STATUS = 488

        /** The requested destination file already exists. */
        const val STATUS_FILE_ALREADY_EXISTS_ERROR = 488

        /** Some possibly transient error occurred, but we can't resume the download. */
        const val STATUS_CANNOT_RESUME = 489

        /** This download was canceled */
        const val STATUS_CANCELED = 490

        /**
         * This download has completed with an error.
         * Warning: there will be other status values that indicate errors in
         * the future. Use isStatusError() to capture the entire category.
         */
        const val STATUS_UNKNOWN_ERROR = 491

        /**
         * This download couldn't be completed because of a storage issue.
         * Typically, that's because the filesystem is missing or full.
         * Use the more specific [STATUS_INSUFFICIENT_SPACE_ERROR]
         * and [STATUS_DEVICE_NOT_FOUND_ERROR] when appropriate.
         */
        const val STATUS_FILE_ERROR = 492

        /**
         * This download couldn't be completed because of an HTTP
         * redirect response that the download manager couldn't
         * handle.
         */
        const val STATUS_UNHANDLED_REDIRECT = 493

        /**
         * This download couldn't be completed because of an
         * unspecified unhandled HTTP code.
         */
        const val STATUS_UNHANDLED_HTTP_CODE = 494

        /**
         * This download couldn't be completed because of an
         * error receiving or processing data at the HTTP level.
         */
        const val STATUS_HTTP_DATA_ERROR = 495

        /**
         * This download couldn't be completed because of an
         * HttpException while setting up the request.
         */
        const val STATUS_HTTP_EXCEPTION = 496

        /**
         * This download couldn't be completed because there were
         * too many redirects.
         */
        const val STATUS_TOO_MANY_REDIRECTS = 497

        /**
         * This download has failed because requesting application has been
         * blocked by NetworkPolicyManager (removed).
         *
         * @hide
         */
        @Deprecated("since behavior now uses STATUS_WAITING_FOR_NETWORK")
        const val STATUS_BLOCKED = 498

        /** {@hide} */
        @JvmStatic
        @Suppress("DEPRECATION")
        fun statusToString(status: Int): String {
            return when (status) {
                STATUS_PENDING -> "PENDING"
                STATUS_RUNNING -> "RUNNING"
                STATUS_PAUSED_BY_APP -> "PAUSED_BY_APP"
                STATUS_WAITING_TO_RETRY -> "WAITING_TO_RETRY"
                STATUS_WAITING_FOR_NETWORK -> "WAITING_FOR_NETWORK"
                STATUS_QUEUED_FOR_WIFI -> "QUEUED_FOR_WIFI"
                STATUS_INSUFFICIENT_SPACE_ERROR -> "INSUFFICIENT_SPACE_ERROR"
                STATUS_DEVICE_NOT_FOUND_ERROR -> "DEVICE_NOT_FOUND_ERROR"
                STATUS_SUCCESS -> "SUCCESS"
                STATUS_BAD_REQUEST -> "BAD_REQUEST"
                STATUS_NOT_ACCEPTABLE -> "NOT_ACCEPTABLE"
                STATUS_LENGTH_REQUIRED -> "LENGTH_REQUIRED"
                STATUS_PRECONDITION_FAILED -> "PRECONDITION_FAILED"
                STATUS_FILE_ALREADY_EXISTS_ERROR -> "FILE_ALREADY_EXISTS_ERROR"
                STATUS_CANNOT_RESUME -> "CANNOT_RESUME"
                STATUS_CANCELED -> "CANCELED"
                STATUS_UNKNOWN_ERROR -> "UNKNOWN_ERROR"
                STATUS_FILE_ERROR -> "FILE_ERROR"
                STATUS_UNHANDLED_REDIRECT -> "UNHANDLED_REDIRECT"
                STATUS_UNHANDLED_HTTP_CODE -> "UNHANDLED_HTTP_CODE"
                STATUS_HTTP_DATA_ERROR -> "HTTP_DATA_ERROR"
                STATUS_HTTP_EXCEPTION -> "HTTP_EXCEPTION"
                STATUS_TOO_MANY_REDIRECTS -> "TOO_MANY_REDIRECTS"
                STATUS_BLOCKED -> "BLOCKED"
                else -> status.toString()
            }
        }

        /**
         * This download is visible but only shows in the notifications
         * while it's in progress.
         */
        @JvmField
        val VISIBILITY_VISIBLE = DownloadManager.Request.VISIBILITY_VISIBLE

        /**
         * This download is visible and shows in the notifications while
         * in progress and after completion.
         */
        @JvmField
        val VISIBILITY_VISIBLE_NOTIFY_COMPLETED =
            DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED

        /** This download doesn't show in the UI or in the notifications. */
        @JvmField
        val VISIBILITY_HIDDEN = DownloadManager.Request.VISIBILITY_HIDDEN

        /** Constants related to HTTP request headers associated with each download. */
        object RequestHeaders {
            const val HEADERS_DB_TABLE = "request_headers"
            const val COLUMN_DOWNLOAD_ID = "download_id"
            const val COLUMN_HEADER = "header"
            const val COLUMN_VALUE = "value"

            /** Path segment to add to a download URI to retrieve request headers */
            const val URI_SEGMENT = "headers"

            /**
             * Prefix for ContentValues keys that contain HTTP header lines, to be passed to
             * DownloadProvider.insert().
             */
            const val INSERT_KEY_PREFIX = "http_header_"
        }
    }

    companion object {
        /** Query where clause for general querying. */
        private const val QUERY_WHERE_CLAUSE =
            Impl.COLUMN_NOTIFICATION_PACKAGE + "=? AND " + Impl.COLUMN_NOTIFICATION_CLASS + "=?"

        /** Delete all the downloads for a package/class pair. */
        @JvmStatic
        fun removeAllDownloadsByPackage(
            context: Context,
            notification_package: String,
            notification_class: String,
        ) {
            // TODO(layering): route this provider delete through simple_query.
            context.contentResolver.delete(
                Impl.CONTENT_URI,
                QUERY_WHERE_CLAUSE,
                arrayOf(notification_package, notification_class),
            )
        }
    }
}
