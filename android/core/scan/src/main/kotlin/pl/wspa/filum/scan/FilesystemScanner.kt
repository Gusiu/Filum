package pl.wspa.filum.scan

import java.io.File
import java.util.ArrayDeque

/**
 * Pełny obchód drzewa katalogów (§3.3 architektury, ścieżka wolna).
 *
 * Używany przy pierwszym skanie, po wykryciu resetu licznika MediaStore i okresowo
 * jako rekoncyliacja. Zwraca [Sequence], a nie listę: przy stu tysiącach plików
 * zebranie wszystkiego do pamięci naraz jest niepotrzebnym ryzykiem, a i tak
 * przetwarzamy je partiami po 2000.
 *
 * Obchód jest iteracyjny, nie rekurencyjny — głębokie drzewa nie mogą przepełnić stosu.
 */
class FilesystemScanner(
    private val root: File,
    private val exclusions: ExclusionRules = ExclusionRules(),
    private val nowSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
    private val onSkipped: (String, String) -> Unit = { _, _ -> },
) {

    fun walk(): Sequence<ScannedFile> = sequence {
        val rootPath = root.absolutePath
        val pending = ArrayDeque<File>()
        pending.push(root)

        while (pending.isNotEmpty()) {
            val directory = pending.pop()

            // listFiles zwraca null przy braku uprawnień albo znikniętym katalogu.
            // Jedno takie miejsce nie może przerwać całego skanu.
            val children = directory.listFiles() ?: continue

            for (child in children) {
                val canonical = CanonicalPath.of(rootPath, child.absolutePath) ?: continue

                if (child.isDirectory) {
                    if (exclusions.isExcludedDirectory(canonical)) continue
                    pending.push(child)
                    continue
                }

                if (!child.isFile) continue
                if (exclusions.isExcluded(canonical)) continue

                val mtime = Mtime.fromMillis(child.lastModified())

                // Okno spokoju: plik zapisywany w tej chwili trafiłby na serwer
                // w połowie i wyglądałby tam na kompletny.
                if (nowSeconds() - mtime < ExclusionRules.QUIESCENCE_SECONDS) {
                    onSkipped(canonical, "zmodyfikowany przed chwilą")
                    continue
                }

                val size = child.length()
                if (size <= 0) continue

                yield(ScannedFile(canonical, child.absolutePath, size, mtime))
            }
        }
    }
}
