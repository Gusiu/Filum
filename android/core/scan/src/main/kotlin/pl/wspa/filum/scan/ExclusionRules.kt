package pl.wspa.filum.scan

/**
 * Which paths the scan skips (architecture §3.3).
 *
 * The rules are directory prefixes or name patterns. Deliberately simple: the user has to
 * understand them on the Folders screen, and regular expressions in a user interface end
 * in questions nobody knows the answer to.
 */
class ExclusionRules(
    userPrefixes: List<String> = emptyList(),
    includeDefaults: Boolean = true,
) {

    private val prefixes: List<String> =
        ((if (includeDefaults) DEFAULT_PREFIXES else emptyList()) + userPrefixes)
            .map { it.trim().trim('/') }
            .filter { it.isNotEmpty() }
            .distinct()

    fun isExcluded(canonicalPath: String): Boolean {
        val path = canonicalPath.trimStart('/')

        if (prefixes.any { path == it || path.startsWith("$it/") }) return true

        val segments = path.split('/')

        // A directory anywhere in the tree, not only at the start of the path.
        if (segments.dropLast(1).any { it in EXCLUDED_DIRECTORY_NAMES }) return true

        val name = segments.last()
        return EXCLUDED_NAME_PREFIXES.any { name.startsWith(it) }
    }

    /** Whether entering the directory is worth it at all — this prunes a whole subtree. */
    fun isExcludedDirectory(canonicalDirectoryPath: String): Boolean {
        val path = canonicalDirectoryPath.trimStart('/').trimEnd('/')
        if (path.isEmpty()) return false

        if (prefixes.any { path == it || path.startsWith("$it/") }) return true

        return path.split('/').any { it in EXCLUDED_DIRECTORY_NAMES }
    }

    val activePrefixes: List<String> get() = prefixes

    companion object {

        /**
         * The default exclusions.
         *
         * `Android/data` and `Android/obb` are **inaccessible** since Android 11 even with
         * MANAGE_EXTERNAL_STORAGE, and we do not try to work around that (ADR-013). The rest is
         * reproducible data: thumbnails, trash, application caches.
         *
         * `Android/media` is deliberately **not** excluded — it is accessible and home to
         * WhatsApp among others, which is exactly the data a user does not want to lose.
         *
         */
        val DEFAULT_PREFIXES = listOf(
            "Android/data",
            "Android/obb",
        )

        /** Directory names skipped at any depth. */
        val EXCLUDED_DIRECTORY_NAMES = setOf(
            ".thumbnails",
            ".cache",
            "cache",
            ".trash",
            ".Trash",
        )

        /**
         * Prefixes of filenames created by the system.
         *
         * `.trashed-` i `.pending-` to files w koszu MediaStore i files w trakcie
         * being written — the first will vanish shortly, the second are not complete yet.
         */
        val EXCLUDED_NAME_PREFIXES = listOf(
            ".trashed-",
            ".pending-",
        )

        /**
         * Directories worth showing the user as candidates for exclusion.
         *
         * They are not excluded by default — the decision is the user's. But these are the ones
         * that most often hold reproducible or very large data: downloads
         * instalatory, filmy z gier, podcasty.
         */
        val SUGGESTED_FOR_EXCLUSION = setOf(
            "Download",
            "Podcasts",
            "Recordings",
            "Audiobooks",
        )

        /**
         * The quiet window: files modified within the last minute are skipped, because they
         * may be in the middle of being written (protocol §5.3). Sending half a recording would
         * leave a file on the server that looks complete and is not.
         */
        const val QUIESCENCE_SECONDS = 60L
    }
}
