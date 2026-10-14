package pl.wspa.filum.scan

import java.io.File
import java.util.ArrayDeque

/**
 * A full walk of the directory tree (architecture §3.3, the slow path).
 *
 * Used on the first scan, after a detected MediaStore counter reset, and periodically as
 * reconciliation. It returns a [Sequence] rather than a list: across a hundred thousand
 * files, collecting everything into memory at once is a needless risk, and the consumer
 * przetwarzamy je partiami po 2000.
 *
 * The walk is iterative, not recursive - deep trees must not overflow the stack.
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

            // listFiles returns null on a permission failure or a vanished directory.
            // One such spot must not abort the entire scan.
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

                // The quiet window: a file being written right now would reach the server
                // half-formed and would look complete there.
                if (nowSeconds() - mtime < ExclusionRules.QUIESCENCE_SECONDS) {
                    onSkipped(canonical, "modified moments ago")
                    continue
                }

                val size = child.length()
                if (size <= 0) continue

                yield(ScannedFile(canonical, child.absolutePath, size, mtime))
            }
        }
    }
}
