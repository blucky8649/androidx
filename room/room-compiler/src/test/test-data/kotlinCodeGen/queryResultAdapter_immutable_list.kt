import android.database.Cursor
import androidx.room.RoomDatabase
import androidx.room.RoomSQLiteQuery
import androidx.room.RoomSQLiteQuery.Companion.acquire
import androidx.room.util.getColumnIndexOrThrow
import androidx.room.util.query
import com.google.common.collect.ImmutableList
import java.lang.Class
import javax.`annotation`.processing.Generated
import kotlin.Int
import kotlin.String
import kotlin.Suppress
import kotlin.collections.List
import kotlin.jvm.JvmStatic

@Generated(value = ["androidx.room.RoomProcessor"])
@Suppress(names = ["UNCHECKED_CAST", "DEPRECATION", "REDUNDANT_PROJECTION"])
public class MyDao_Impl(
    __db: RoomDatabase,
) : MyDao {
    private val __db: RoomDatabase
    init {
        this.__db = __db
    }

    public override fun queryOfList(): ImmutableList<MyEntity> {
        val _sql: String = "SELECT * FROM MyEntity"
        val _statement: RoomSQLiteQuery = acquire(_sql, 0)
        __db.assertNotSuspendingTransaction()
        val _cursor: Cursor = query(__db, _statement, false, null)
        try {
            val _cursorIndexOfPk: Int = getColumnIndexOrThrow(_cursor, "pk")
            val _cursorIndexOfOther: Int = getColumnIndexOrThrow(_cursor, "other")
            val _immutableListBuilder: ImmutableList.Builder<MyEntity> = ImmutableList.builder()
            while (_cursor.moveToNext()) {
                val _item: MyEntity
                val _tmpPk: Int
                _tmpPk = _cursor.getInt(_cursorIndexOfPk)
                val _tmpOther: String
                _tmpOther = _cursor.getString(_cursorIndexOfOther)
                _item = MyEntity(_tmpPk,_tmpOther)
                _immutableListBuilder.add(_item)
            }
            val _result: ImmutableList<MyEntity> = _immutableListBuilder.build()
            return _result
        } finally {
            _cursor.close()
            _statement.release()
        }
    }

    public companion object {
        @JvmStatic
        public fun getRequiredConverters(): List<Class<*>> = emptyList()
    }
}