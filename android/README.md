# Filum — klient Android

## Wymagane narzędzia

Na tym etapie repozytorium zawiera wyłącznie moduł `:core:protocol`, który jest
**czystym modułem JVM**, bez zależności od Androida. Jego testy uruchamiają się
na zwykłej maszynie wirtualnej — bez emulatora i bez Android SDK.

Do zbudowania potrzeba jednak Gradle'a i kompilatora Kotlina, których nie ma
jeszcze w systemie. Najprościej zainstalować **Android Studio** — dostarcza
komplet (Gradle, Kotlin, Android SDK), a i tak będzie potrzebne do modułów
`:app` i `:feature:*`.

Po instalacji:

```bash
cd android && ./gradlew :core:protocol:test
```

Katalogu `gradle/wrapper/` nie ma w repozytorium — wygeneruje go Android Studio
przy pierwszym otwarciu projektu, albo `gradle wrapper` z lokalnej instalacji.

## Stan modułów

| Moduł | Stan |
|---|---|
| `:core:protocol` | `PathRules` napisane, testy napisane, **nieskompilowane** |
| pozostałe | niezaczęte, patrz [../docs/ARCHITECTURE.md](../docs/ARCHITECTURE.md) §3.1 |

Logika `PathRules` została zweryfikowana krzyżowo: identyczna transkrypcja na
Javę przechodzi te same wektory `docs/testvectors/paths.tsv` co implementacja
w C#. Nie zastępuje to jednak uruchomienia testów kotlinowych — kod nie był
kompilowany i mogą w nim być błędy składni.

## Dlaczego `:core:protocol` nie jest modułem Android Library

Reguły ścieżek to miejsce, w którym błąd kosztuje ciche skasowanie pliku na
serwerze. Trzymanie ich w module JVM oznacza, że testy idą w sekundy, bez
emulatora, i dają się uruchomić w każdym środowisku ciągłej integracji.
Zależności od Androida (`MediaStore`, `Room`, `WorkManager`) pojawią się dopiero
w `:core:scan`, `:core:data` i `:core:sync`.
