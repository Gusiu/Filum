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
                // Besides the queue, the database holds the pairing, the user's settings and
                // folder exclusions and the foreground-service budget. Rebuilt from scratch, it
                // would cost a new pairing, not one full scan - so every schema version from 3
                // on comes with a migration. Versions 1 and 2 never left development; a
                // database from a newer build cannot be migrated backwards at all.
                .fallbackToDestructiveMigrationFrom(dropAllTables = true, 1, 2)
                .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
                .build()
    }
}
