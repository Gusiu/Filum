package pl.wspa.filum.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        PairedServerEntity::class,
        EndpointEntity::class,
        FileEntryEntity::class,
        SyncSessionEntity::class,
        ScanStateEntity::class,
        ExclusionEntity::class,
        BudgetWindowEntity::class,
        LogEventEntity::class,
        SettingsEntity::class,
    ],
    version = 3,
    exportSchema = true,
)
abstract class FilumDatabase : RoomDatabase() {

    abstract fun pairedServers(): PairedServerDao
    abstract fun endpoints(): EndpointDao
    abstract fun files(): FileEntryDao
    abstract fun sessions(): SyncSessionDao
    abstract fun scanState(): ScanStateDao
    abstract fun exclusions(): ExclusionDao
    abstract fun budget(): BudgetDao
    abstract fun log(): LogEventDao
    abstract fun settings(): SettingsDao

    companion object {
        const val NAME = "filum.db"

        fun open(context: Context): FilumDatabase =
            Room.databaseBuilder(context.applicationContext, FilumDatabase::class.java, NAME)
                // The database holds only the queue and the history, never user data.
                // On a schema mismatch, rebuilding it from scratch costs one full scan
                // rather than the loss of anything - which beats an application that
                // fails to start after an update.
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
    }
}
