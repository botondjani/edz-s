package hu.edzes

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.SharedPreferences
import androidx.room.Room
import androidx.work.Configuration
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import hu.edzes.data.EdzesDao
import hu.edzes.data.EdzesDb
import hu.edzes.sync.SyncWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.util.concurrent.TimeUnit

class App : Application(), Configuration.Provider {
    val db by lazy { Room.databaseBuilder(this, EdzesDb::class.java, "edzes.db").addMigrations(EdzesDb.MIGRATION_1_2).build() }
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    override val workManagerConfiguration get() = Configuration.Builder().build()

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Edzés közben", NotificationManager.IMPORTANCE_LOW)
        )
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "sync", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<SyncWorker>(6, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build(),
        )
    }

    companion object { const val CHANNEL = "player" }
}

val Context.app get() = applicationContext as App
val Context.dao: EdzesDao get() = app.db.dao()
val Context.prefs: SharedPreferences get() = getSharedPreferences("edzes", Context.MODE_PRIVATE)
