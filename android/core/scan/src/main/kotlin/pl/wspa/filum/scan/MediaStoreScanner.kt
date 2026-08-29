package pl.wspa.filum.scan

import android.content.ContentResolver
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import java.io.File

/**
 * Szybka ścieżka skanowania (§3.3 architektury).
 *
 * MediaStore prowadzi monotoniczny licznik zmian na wolumen. Zapamiętujemy jego stan
 * po skanie i przy następnym pytamy **jednym zapytaniem**, co zmieniło się od tamtej
 * pory. Dla zdjęć i wideo, czyli zdecydowanej większości z 200 GB, daje to różnicę
 * w milisekundach zamiast w minutach pełnego obchodu drzewa.
 *
 * Ograniczenia, przez które pełny obchód nadal jest potrzebny:
 * - licznik obejmuje wyłącznie pliki zaindeksowane przez MediaStore,
 * - resetuje się przy przebudowie indeksu, np. po przywróceniu ustawień fabrycznych.
 */
class MediaStoreScanner(
    private val context: Context,
    private val exclusions: ExclusionRules = ExclusionRules(),
    private val nowSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
) {

    private val resolver: ContentResolver get() = context.contentResolver

    /** Bieżący stan licznika. Zapisujemy go dopiero po **udanym** skanie. */
    fun currentGeneration(volume: String = VOLUME): Long =
        MediaStore.getGeneration(context, volume)

    /**
     * Pliki zmienione od podanej generacji.
     *
     * Zwraca `null`, gdy licznik cofnął się względem zapamiętanego — oznacza to
     * przebudowę indeksu i wymusza pełny obchód drzewa, bo różnica byłaby niepełna.
     */
    fun changedSince(generation: Long, volume: String = VOLUME): List<ScannedFile>? {
        val current = currentGeneration(volume)
        if (current < generation) {
            Log.i(TAG, "Licznik MediaStore cofnął się ($current < $generation) — potrzebny pełny skan")
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
            // DATA jest formalnie przestarzałe, ale mamy MANAGE_EXTERNAL_STORAGE
            // i otwieramy pliki wprost — deskryptor z MediaStore nie dałby nam
            // ścieżki kanonicznej, której wymaga manifest.
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

                // DATE_MODIFIED jest w sekundach — przechodzi przez tę samą bramę
                // co odczyt z systemu plików, żeby jednostki nigdy się nie rozjechały.
                val mtime = Mtime.fromSeconds(cursor.getLong(mtimeColumn))
                if (nowSeconds() - mtime < ExclusionRules.QUIESCENCE_SECONDS) continue

                // Indeks bywa nieaktualny: plik usunięty poza MediaStore zostaje
                // w bazie do najbliższego przeskanowania.
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

/** Wydzielone, żeby dało się podmienić w testach i żeby ścieżka główna miała jedno źródło. */
object Environment {
    fun externalStorageRootPath(): String =
        android.os.Environment.getExternalStorageDirectory().absolutePath

    /** Czy aplikacja ma pełny dostęp do pamięci (MANAGE_EXTERNAL_STORAGE, ADR-013). */
    fun hasFullStorageAccess(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.R ||
            android.os.Environment.isExternalStorageManager()
}
