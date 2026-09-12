package app.knock.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface TaskDao {
    @Query("SELECT * FROM tasks ORDER BY day ASC, anytime ASC, dueAt ASC, id ASC")
    fun observeAll(): Flow<List<Task>>

    @Query("SELECT * FROM tasks")
    suspend fun all(): List<Task>

    @Query("SELECT * FROM tasks WHERE id = :id")
    suspend fun get(id: Long): Task?

    @Query("SELECT * FROM tasks WHERE id = :id")
    fun observe(id: Long): Flow<Task?>

    @Query("SELECT * FROM tasks WHERE state = 'PENDING'")
    suspend fun pending(): List<Task>

    @Insert suspend fun insert(task: Task): Long
    @Update suspend fun update(task: Task)
    @Delete suspend fun delete(task: Task)

    @Query("DELETE FROM tasks") suspend fun clearTasks()

    @Insert suspend fun insertEvent(event: ReminderEvent)

    @Query("SELECT * FROM events WHERE taskId = :taskId ORDER BY at DESC")
    fun observeEvents(taskId: Long): Flow<List<ReminderEvent>>

    @Query("SELECT * FROM events")
    suspend fun allEvents(): List<ReminderEvent>

    @Query("DELETE FROM events WHERE taskId = :taskId") suspend fun deleteEvents(taskId: Long)
    @Query("DELETE FROM events") suspend fun clearEvents()
}

@Database(entities = [Task::class, ReminderEvent::class], version = 1, exportSchema = false)
@TypeConverters(Converters::class)
abstract class KnockDatabase : RoomDatabase() {
    abstract fun dao(): TaskDao

    companion object {
        @Volatile private var instance: KnockDatabase? = null

        fun get(context: Context): KnockDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, KnockDatabase::class.java, "knock.db")
                .fallbackToDestructiveMigration()
                .build()
                .also { instance = it }
        }
    }
}
