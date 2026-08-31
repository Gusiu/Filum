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
                // Baza trzyma wyłącznie kolejkę i historię, nigdy dane użytkownika.
                // W razie niezgodności schematu odtworzenie jej od zera kosztuje jeden
                // pełny skan, a nie utratę czegokolwiek — to lepsze niż aplikacja,
                // która po aktualizacji nie wstaje.
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
    }
}
