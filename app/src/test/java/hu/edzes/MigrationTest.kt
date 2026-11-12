package hu.edzes

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import hu.edzes.data.EdzesDb
import hu.edzes.data.SetLog
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Az első kiadás (1-es séma) adatbázisa adatvesztés nélkül megnyílik a mostani verzióval. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MigrationTest {
    @Test fun `1-es semarol frissit`() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val file = ctx.getDatabasePath("regi.db").apply { parentFile!!.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL("CREATE TABLE IF NOT EXISTS `Program` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `json` TEXT NOT NULL, `weeks` INTEGER NOT NULL, `createdMs` INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE IF NOT EXISTS `Planned` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `programId` INTEGER, `date` INTEGER NOT NULL, `title` TEXT NOT NULL, `sport` TEXT NOT NULL, `workoutJson` TEXT NOT NULL, `status` TEXT NOT NULL, `activityId` INTEGER)")
            db.execSQL("CREATE TABLE IF NOT EXISTS `Activity` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `source` TEXT NOT NULL, `sport` TEXT NOT NULL, `title` TEXT NOT NULL, `startMs` INTEGER NOT NULL, `durationS` INTEGER NOT NULL, `distanceM` REAL, `avgHr` INTEGER, `calories` INTEGER, `hcId` TEXT, `stravaId` INTEGER, `stravaError` TEXT, `needsRoute` INTEGER NOT NULL, `hidden` INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE IF NOT EXISTS `SetLog` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `activityId` INTEGER NOT NULL, `exercise` TEXT NOT NULL, `reps` INTEGER, `weightKg` REAL, `durationS` INTEGER)")
            db.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
            db.execSQL("INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, '7e327854b341df563e9641f70541c046')")
            db.execSQL("INSERT INTO SetLog (activityId, exercise, reps, weightKg) VALUES (1, 'Guggolás', 5, 100.0)")
            db.version = 1
        }
        val dao = Room.databaseBuilder(ctx, EdzesDb::class.java, "regi.db").addMigrations(*EdzesDb.MIGRATIONS).build().dao()
        runBlocking {
            dao.insertSetLogs(listOf(SetLog(activityId = 1, exercise = "Gyors", durationS = 90, distanceM = 400.0)))
            assertEquals(
                listOf(SetLog(1, 1, "Guggolás", 5, 100.0), SetLog(2, 1, "Gyors", durationS = 90, distanceM = 400.0)),
                dao.setLogs(1),
            )
            val a = hu.edzes.data.Activity(source = "app", sport = "run", title = "F", startMs = 1, durationS = 2, hrHist = "140:60")
            assertEquals(a.copy(id = 1), dao.activity(dao.insert(a)))
        }
    }
}
