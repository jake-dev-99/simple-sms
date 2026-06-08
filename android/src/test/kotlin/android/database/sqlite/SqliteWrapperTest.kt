package android.database.sqlite

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pins the [SqliteWrapper] (android.database.sqlite) port's exception policy:
 * `checkSQLiteException` shows a toast (and returns) for the low-memory signature
 * message, and rethrows the same instance otherwise; plus a smoke check that
 * `query` returns null when no provider answers. Needs Robolectric.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SqliteWrapperTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun checkSQLiteException_lowMemoryMessage_doesNotThrow() {
        SqliteWrapper.checkSQLiteException(
            context,
            SQLiteException("unable to open database file"),
        )
    }

    @Test
    fun checkSQLiteException_otherMessage_rethrowsSameInstance() {
        val e = SQLiteException("some other failure")
        val thrown = assertThrows(SQLiteException::class.java) {
            SqliteWrapper.checkSQLiteException(context, e)
        }
        assertSame(e, thrown)
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
