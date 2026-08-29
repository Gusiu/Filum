package pl.wspa.filum.scan

/**
 * Które ścieżki pomijamy w skanie (§3.3 architektury).
 *
 * Reguły są prefiksami katalogów albo wzorcami nazw. Świadomie proste: użytkownik
 * ma je rozumieć na ekranie „Foldery", a wyrażenia regularne w interfejsie
 * kończą się pytaniami, na które nikt nie zna odpowiedzi.
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

        // Katalog gdziekolwiek w drzewie, nie tylko na początku ścieżki.
        if (segments.dropLast(1).any { it in EXCLUDED_DIRECTORY_NAMES }) return true

        val name = segments.last()
        return EXCLUDED_NAME_PREFIXES.any { name.startsWith(it) }
    }

    /** Czy warto w ogóle wchodzić do katalogu — pozwala uciąć całe poddrzewo. */
    fun isExcludedDirectory(canonicalDirectoryPath: String): Boolean {
        val path = canonicalDirectoryPath.trimStart('/').trimEnd('/')
        if (path.isEmpty()) return false

        if (prefixes.any { path == it || path.startsWith("$it/") }) return true

        return path.split('/').any { it in EXCLUDED_DIRECTORY_NAMES }
    }

    val activePrefixes: List<String> get() = prefixes

    companion object {

        /**
         * Wykluczenia domyślne.
         *
         * `Android/data` i `Android/obb` są **niedostępne** od Androida 11 nawet przy
         * MANAGE_EXTERNAL_STORAGE i nie próbujemy tego obchodzić (ADR-013). Reszta to
         * dane odtwarzalne: miniatury, kosze, pamięci podręczne aplikacji.
         *
         * `Android/media` celowo **nie jest** wykluczony — jest dostępny i mieszka tam
         * między innymi WhatsApp, czyli dokładnie te dane, których użytkownik nie chce
         * stracić.
         */
        val DEFAULT_PREFIXES = listOf(
            "Android/data",
            "Android/obb",
        )

        /** Nazwy katalogów pomijane na dowolnej głębokości. */
        val EXCLUDED_DIRECTORY_NAMES = setOf(
            ".thumbnails",
            ".cache",
            "cache",
            ".trash",
            ".Trash",
        )

        /**
         * Przedrostki nazw plików tworzonych przez system.
         *
         * `.trashed-` i `.pending-` to pliki w koszu MediaStore i pliki w trakcie
         * zapisu — jedne zaraz znikną, drugie nie są jeszcze kompletne.
         */
        val EXCLUDED_NAME_PREFIXES = listOf(
            ".trashed-",
            ".pending-",
        )

        /**
         * Katalogi, które warto pokazać użytkownikowi jako kandydatów do wykluczenia.
         *
         * Nie są wykluczone domyślnie — decyzja należy do użytkownika. Ale to właśnie
         * one najczęściej zawierają dane odtwarzalne albo bardzo duże: pobrane
         * instalatory, filmy z gier, podcasty.
         */
        val SUGGESTED_FOR_EXCLUSION = setOf(
            "Download",
            "Podcasts",
            "Recordings",
            "Audiobooks",
        )

        /**
         * Okno spokoju: pliki zmodyfikowane w ciągu ostatniej minuty pomijamy,
         * bo mogą być właśnie zapisywane (§5.3 protokołu). Wysłanie połowy nagrania
         * dałoby na serwerze plik, który wygląda na kompletny, a nie jest.
         */
        const val QUIESCENCE_SECONDS = 60L
    }
}
