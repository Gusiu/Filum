package pl.wspa.filum.scan

import android.content.ContentResolver
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import java.io.File

/**
 * The fast scanning path (architecture §3.3).
 *
 * MediaStore keeps a monotonic change counter per volume. We remember its value after a
 * scan and, on the next one, ask in **a single query** what has changed since. For photos
 * and video, usually the bulk of the library, this is the difference between milliseconds
 * and the minutes a full tree walk takes.
 *
 * The limitations that keep the full walk necessary:
 * - the counter covers only files indexed by MediaStore,
 * - it resets when the index is rebuilt, e.g. after a factory reset.
 */
class MediaStoreScanner(
    private val context: Context,
    private val exclusions: ExclusionRules = ExclusionRules(),
    private val nowSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
) {

    private val resolver: ContentResolver get() = context.contentResolver

    /** The counter's current value. We store it only after a **successful** scan. */
    fun currentGeneration(volume: String = VOLUME): Long =
        MediaStore.getGeneration(context, volume)

    /**
     * Pliki zmienione od podanej generacji.
     *
     * Returns `null` when the counter has gone backwards relative to the remembered value —
     * that means the index was rebuilt and forces a full walk, since the delta would be partial.
     */
    fun changedSince(generation: Long, volume: String = VOLUME): List<ScannedFile>? {
        val current = currentGeneration(volume)
        if (current < generation) {
            Log.i(TAG, "The MediaStore counter went backwards ($current < $generation) — a full scan is needed")
            return null
        }

        return query(
            volume = volume,
            selection = "${MediaStore.MediaColumns.GENERATION_MODIFIED} > ?",
            arguments = arrayOf(generation.toString()),
        )
    }

    fun all(volume: String = VOLUME): List<ScannedFile> = query(volume, null, null)

    private fun query(
        volume: String,
        selection: String?,
        arguments: Array<String>?,
    ): List<ScannedFile> {
        val projection = arrayOf(
            // DATA is formally deprecated, but we hold MANAGE_EXTERNAL_STORAGE and open
            // files directly — a MediaStore descriptor would not give us the canonical
            // path the manifest requires.
            MediaStore.MediaColumns.DATA,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED,
        )

        val root = Environment.externalStorageRootPath()
        val results = mutableListOf<ScannedFile>()

        resolver.query(
            MediaStore.Files.getContentUri(volume),
            projection,
            selection,
            arguments,
            null,
        )?.use { cursor ->
            val dataColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA)
            val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val mtimeColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)

            while (cursor.moveToNext()) {
                val absolute = cursor.getString(dataColumn) ?: continue
                val canonical = CanonicalPath.of(root, absolute) ?: continue
                if (exclusions.isExcluded(canonical)) continue

                val size = cursor.getLong(sizeColumn)
                if (size <= 0) continue

                // DATE_MODIFIED is in seconds — it goes through the same gate as the
                // filesystem read, so the units can never drift apart.
                val mtime = Mtime.fromSeconds(cursor.getLong(mtimeColumn))
                if (nowSeconds() - mtime < ExclusionRules.QUIESCENCE_SECONDS) continue

                // The index can be stale: a file deleted outside MediaStore stays in the
                // database until the next rescan.
                if (!File(absolute).isFile) continue

                results += ScannedFile(canonical, absolute, size, mtime)
            }
        }

        return results
    }

    private companion object {
        const val TAG = "FilumMediaStore"
        val VOLUME: String = MediaStore.VOLUME_EXTERNAL_PRIMARY
    }
}

/** Extracted so it can be substituted in tests and the main path has a single source. */
object Environment {
    fun externalStorageRootPath(): String =
        android.os.Environment.getExternalStorageDirectory().absolutePath

    /** Whether the application has full storage access (MANAGE_EXTERNAL_STORAGE, ADR-013). */
    fun hasFullStorageAccess(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.R ||
            android.os.Environment.isExternalStorageManager()
}
