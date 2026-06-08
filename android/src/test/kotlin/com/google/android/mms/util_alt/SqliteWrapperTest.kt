package com.google.android.mms.util_alt

import android.content.Context
import android.database.sqlite.SQLiteException
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pins the [SqliteWrapper] (util_alt) port's exception policy: `checkSQLiteException`
 * shows a toast (and returns) for the low-memory signature message, and rethrows
 * for anything else; plus a smoke check that `query` delegates and returns null
 * when no provider answers. Needs Robolectric for `Context`/`ContentResolver`/`Toast`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SqliteWrapperTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun checkSQLiteException_lowMemoryMessage_doesNotThrow() {
        // The exact low-memory detail message → toast path, no rethrow.
        SqliteWrapper.checkSQLiteException(
            context,
            SQLiteException("unable to open database file"),
        )
    }

    @Test
    fun checkSQLiteException_otherMessage_rethrows() {
        val e = SQLiteException("some other failure")
        val thrown = assertThrows(SQLiteException::class.java) {
            SqliteWrapper.checkSQLiteException(context, e)
        }
        // The original exception is rethrown as-is.
        org.junit.Assert.assertSame(e, thrown)
    }

    @Test
    fun query_noProvider_returnsNull() {
        val result = SqliteWrapper.query(
            context,
            context.contentResolver,
            Uri.parse("content://com.example.absent/items"),
            null,
            null,
            null,
            null,
        )
        assertNull(result)
    }
}
