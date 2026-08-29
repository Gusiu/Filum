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

| Katalog | Opis |
|---|---|
| `server/` | usługa HTTP na Windows: .NET 9, panel w przeglądarce, ikona w zasobniku |
| `android/` | klient: Kotlin, Compose, WorkManager, Room, OkHttp |

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

Etapy 1–9 z [tabeli kolejności budowy](docs/ARCHITECTURE.md#8-kolejność-budowy).

| Element | Stan |
|---|---|
| specyfikacja protokołu i architektura | spisane |
| serwer: parowanie, ping, manifest, wznawialny transfer, wersjonowanie | działa |
| mTLS z wzajemnym przypięciem kluczy | działa, sprawdzone na prawdziwym TLS |
| klient: siedem modułów, od reguł ścieżek po silnik cyklu | działa, APK się buduje |
| harmonogram i praca w tle z pilnowaniem budżetu 6 h | działa |
| panel serwera i ikona w zasobniku | działa |
| wykrywanie serwera przez mDNS | działa |
| agregacja małych plików w paczki | działa |
| kontrola integralności i raport sierot | działa |
| **test zgodności klient↔serwer** | przechodzi |
| **160 testów serwera, 186 testów klienta** | zielone |
| skaner QR aparatem, usługa Windows | niezaczęte |

Serwer i klient są pisane niezależnie, w różnych językach, i pogodzone wyłącznie
przez specyfikację oraz wspólne wektory testowe. `EndToEndTest` uruchamia prawdziwy
klient kotlinowy przeciwko prawdziwemu serwerowi w C# i sprawdza, czy oba wyliczają
tę samą tożsamość pliku — szczegóły w [android/README.md](android/README.md).

Cykl nie był jeszcze przejechany na prawdziwym telefonie: `MediaStore`, Android
Keystore i uprawnienie do pamięci da się sprawdzić wyłącznie na urządzeniu.

## Budowanie

```bash
cd server && dotnet test
```

```bash
cd server && dotnet run --project src/Filum.Server --no-launch-profile
```

Klient Androida wymaga Android Studio — szczegóły w [android/README.md](android/README.md).
