package hu.edzes.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

@Serializable
@Entity
data class Program(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val json: String,
    val weeks: Int,
    val createdMs: Long,
)

const val PLANNED = "PLANNED"
const val DONE = "DONE"
const val SKIPPED = "SKIPPED"

@Serializable
@Entity
data class Planned(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val programId: Long?,
    val date: Long,               // epochDay
    val title: String,
    val sport: String,
    val workoutJson: String,
    val status: String = PLANNED,
    val activityId: Long? = null,
)

@Serializable
@Entity
data class Activity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val source: String,           // app | hc | strava
    val sport: String,
    val title: String,
    val startMs: Long,
    val durationS: Int,
    val distanceM: Double? = null,
    val avgHr: Int? = null,
    val calories: Int? = null,
    val hcId: String? = null,
    val stravaId: Long? = null,
    val stravaError: String? = null,
    val needsRoute: Boolean = false,   // a Health Connect útvonalához még kell a felhasználó engedélye
    val hidden: Boolean = false,       // importált edzés "törlése": rejtve marad, hogy a szinkron ne hozza vissza
)

@Serializable
@Entity
data class SetLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val activityId: Long,
    val exercise: String,
    val reps: Int? = null,
    val weightKg: Double? = null,
    val durationS: Int? = null,
)

@Serializable
data class Backup(
    val programs: List<Program>,
    val planned: List<Planned>,
    val activities: List<Activity>,
    val setLogs: List<SetLog>,
)

@Dao
interface EdzesDao {
    @Query("SELECT * FROM Program ORDER BY id DESC") fun programs(): Flow<List<Program>>
    @Insert suspend fun insert(p: Program): Long
    @Query("DELETE FROM Program WHERE id = :id") suspend fun deleteProgramRow(id: Long)
    @Query("DELETE FROM Planned WHERE programId = :id AND status != 'DONE'") suspend fun deleteOpenPlanned(id: Long)

    @Transaction
    suspend fun deleteProgram(id: Long) { deleteOpenPlanned(id); deleteProgramRow(id) }

    @Query("SELECT * FROM Planned ORDER BY date, id") fun planned(): Flow<List<Planned>>
    @Query("SELECT * FROM Planned WHERE date = :date ORDER BY id") suspend fun plannedOn(date: Long): List<Planned>
    @Query("SELECT * FROM Planned WHERE id = :id") suspend fun plannedById(id: Long): Planned?
    @Insert suspend fun insertPlanned(list: List<Planned>)
    @Update suspend fun update(p: Planned)
    @Query("DELETE FROM Planned WHERE id = :id") suspend fun deletePlanned(id: Long)
    @Query("UPDATE Planned SET activityId = NULL WHERE activityId = :activityId") suspend fun unlinkActivity(activityId: Long)

    @Query("SELECT * FROM Activity ORDER BY startMs DESC") fun activities(): Flow<List<Activity>>
    @Query("SELECT * FROM Activity WHERE startMs >= :sinceMs ORDER BY startMs") suspend fun activitiesSince(sinceMs: Long): List<Activity>
    @Query("SELECT * FROM Activity WHERE id = :id") suspend fun activity(id: Long): Activity?
    @Insert suspend fun insert(a: Activity): Long
    @Update suspend fun update(a: Activity)
    @Query("DELETE FROM Activity WHERE id = :id") suspend fun deleteActivityRow(id: Long)

    @Transaction
    suspend fun deleteActivity(id: Long) { unlinkActivity(id); deleteSetLogs(id); deleteActivityRow(id) }

    @Query("SELECT * FROM SetLog WHERE activityId = :activityId ORDER BY id") suspend fun setLogs(activityId: Long): List<SetLog>
    @Query("SELECT * FROM SetLog WHERE exercise = :exercise AND reps IS NOT NULL ORDER BY id DESC LIMIT 1") suspend fun lastSet(exercise: String): SetLog?
    @Insert suspend fun insertSetLogs(list: List<SetLog>)
    @Query("DELETE FROM SetLog WHERE activityId = :activityId") suspend fun deleteSetLogs(activityId: Long)

    // Mentés / visszaállítás
    @Query("SELECT * FROM Program") suspend fun allPrograms(): List<Program>
    @Query("SELECT * FROM Planned") suspend fun allPlanned(): List<Planned>
    @Query("SELECT * FROM SetLog") suspend fun allSetLogs(): List<SetLog>
    @Insert suspend fun insertPrograms(list: List<Program>)
    @Insert suspend fun insertActivities(list: List<Activity>)
    @Query("DELETE FROM Program") suspend fun clearPrograms()
    @Query("DELETE FROM Planned") suspend fun clearPlanned()
    @Query("DELETE FROM Activity") suspend fun clearActivities()
    @Query("DELETE FROM SetLog") suspend fun clearSetLogs()

    suspend fun backup() = Backup(allPrograms(), allPlanned(), activitiesSince(0), allSetLogs())

    @Transaction
    suspend fun restore(b: Backup) {
        clearPrograms(); clearPlanned(); clearActivities(); clearSetLogs()
        insertPrograms(b.programs); insertPlanned(b.planned); insertActivities(b.activities); insertSetLogs(b.setLogs)
    }
}

@Database(entities = [Program::class, Planned::class, Activity::class, SetLog::class], version = 1, exportSchema = false)
abstract class EdzesDb : RoomDatabase() {
    abstract fun dao(): EdzesDao
}
