# Filum

Jednokierunkowy backup plików z telefonu z Androidem na komputer z Windows,
bez chmury i bez pośredników przechowujących dane.

Praca inżynierska, WSPA.

---

## Czym to jest — i czym nie jest

Filum to **backup, nie synchronizacja**. Różnica jest zasadnicza i to z niej
wynika cała architektura: usunięcie pliku w telefonie **nie usuwa go z komputera**.

Popularne narzędzia (Syncthing, Resilio, FolderSync, Nextcloud) realizują
synchronizację dwukierunkową i propagują usunięcia. Użytkownik porządkujący
galerię traci kopię zapasową dokładnie wtedy, gdy najbardziej jej potrzebuje.
Zbiór plików przechowywanych przez Filum jest monotoniczny — może wyłącznie rosnąć.

## Komponenty

| Katalog | Opis | Stan |
|---|---|---|
| `server/` | usługa HTTP na Windows: .NET 9, panel w przeglądarce, ikona w zasobniku | do zbudowania |
| `android/` | klient: Kotlin, Compose, WorkManager, Room, OkHttp | do zbudowania |

## Najważniejsze właściwości

- **Nic nie ginie.** Serwer nie udostępnia operacji usuwania. Zmieniona zawartość
  pod znaną ścieżką nie nadpisuje poprzedniej — stara wersja jest odkładana.
- **Przezroczyste składowanie.** Na dysku leży zwykłe drzewo katalogów,
  przeglądalne w Eksploratorze. Odtworzenie nie wymaga tej aplikacji.
- **Bez hashowania przy każdym cyklu.** Plik identyfikuje trójka
  `(ścieżka, rozmiar, mtime)`. Liczenie SHA-256 z 200 GB co noc zabiłoby baterię.
- **Transfer wznawialny** na trzech poziomach: w środku pliku, w środku kolejki
  i w środku wielonocnej kampanii.
- **Adres nie jest tożsamością.** Serwer rozpoznaje się po kluczu publicznym,
  a klient trzyma zbiór tras i ściga je równolegle: sieć lokalna, mDNS, sieć
  nakładkowa. Zmiana routera czy wyjście z domu nie wymagają konfiguracji.
- **Wzajemne przypięcie kluczy (mTLS)** zamiast tokenu w postaci jawnej.

## Dokumentacja

| Dokument | Zawartość |
|---|---|
| [docs/PROTOCOL.md](docs/PROTOCOL.md) | specyfikacja protokołu — normatywna dla obu implementacji |
| [docs/PATH-RULES.md](docs/PATH-RULES.md) | normalizacja i sanityzacja ścieżek pod NTFS |
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | podział na moduły, przepływy, kolejność budowy |
| [docs/DECISIONS.md](docs/DECISIONS.md) | rejestr decyzji architektonicznych z odrzuconymi alternatywami |
| [docs/testvectors/paths.tsv](docs/testvectors/paths.tsv) | wspólne wektory testowe sanityzacji ścieżek |

Serwer i klient są pisane niezależnie, w różnych językach, i pogodzone wyłącznie
przez specyfikację oraz wspólne wektory testowe. Jest to celowe: implementacja
od zera na podstawie samego dokumentu jest dowodem, że dokument jest kompletny.

## Środowisko docelowe

- Telefon: Samsung Galaxy S24 Ultra, Android 15+, `targetSdk 36`
- Instalacja poza sklepem — wymagane `MANAGE_EXTERNAL_STORAGE`
- Komputer: Windows 11, NTFS
- Pierwsza synchronizacja: około 200 GB, rozłożona na kilka nocy

## Stan prac

Etap 1 z [tabeli kolejności budowy](docs/ARCHITECTURE.md#8-kolejność-budowy): reguły ścieżek.

| Element | Stan |
|---|---|
| specyfikacja protokołu i architektura | spisane |
| `Filum.Protocol.PathRules` (C#) | zaimplementowane, **60 testów przechodzi** |
| `pl.wspa.filum.protocol.PathRules` (Kotlin) | napisane, nieskompilowane — brak Gradle/Kotlina, patrz [android/README.md](android/README.md) |
| pozostałe moduły | niezaczęte |

## Budowanie

```bash
cd server && dotnet test
```

Klient Androida wymaga Android Studio — szczegóły w [android/README.md](android/README.md).
