# Filum — klient Android

Kotlin, Compose, Room, OkHttp. Implementacja klienta z
[../docs/PROTOCOL.md](../docs/PROTOCOL.md) i [../docs/ARCHITECTURE.md](../docs/ARCHITECTURE.md).

## Budowanie

```bash
cd android && ./gradlew :app:assembleDebug
```

```bash
cd android && ./gradlew test
```

Wymaga Android Studio (Gradle, kompilator Kotlina, SDK). Wersje narzędzi:
AGP 9.3.2, Gradle 9.5, Kotlin 2.3.10, `compileSdk 37`, `targetSdk 36`.

`compileSdk` i `targetSdk` to dwie różne rzeczy. Kompilujemy wobec 37, bo tego
wymagają nowsze biblioteki AndroidX, ale o zachowaniu systemu decyduje `targetSdk` —
i ten zostaje na 36 zgodnie z ADR-013, żeby na razie ominąć wymóg
`ACCESS_LOCAL_NETWORK` z Androida 17.

## Moduły

| Moduł | Odpowiedzialność | Zależy od Androida |
|---|---|---|
| `:core:protocol` | modele wymiany, `PathRules`, odczyt kodu parowania | nie — czysty JVM |
| `:core:crypto` | klucz w Android Keystore, przypięcie klucza serwera | częściowo |
| `:core:net` | klient zasobów, wyścig tras, mDNS, przypięcie gniazd do Wi-Fi | częściowo |
| `:core:data` | Room: kolejka, dziennik, budżet usługi pierwszoplanowej | tak |
| `:core:scan` | `MediaStore` z licznikiem generacji, obchód drzewa, wykluczenia | częściowo |
| `:core:sync` | silnik cyklu: manifest, wznawianie, paczkowanie, klasyfikacja błędów | nie — za interfejsami |
| `:app` | parowanie, ekran główny, dziennik, składanie zależności | tak |

Podział nie jest ozdobnikiem. Najtrudniejsze części — reguły ścieżek, wyścig tras
i decyzje silnika o tym, co ponowić, a co odłożyć — leżą w modułach, które testują
się na zwykłej maszynie wirtualnej, bez emulatora i w sekundach.

## Testy

186 testów jednostkowych, wszystkie na JVM:

- **reguły ścieżek** — te same wektory `docs/testvectors/paths.tsv` co implementacja w C#,
- **przypięcie klucza** — wektory ze stałych certyfikatów z odciskami policzonymi przez openssl,
- **klient zasobów** — `MockWebServer` z prawdziwym TLS i `requireClientAuth`,
- **wyścig tras** — zegar wirtualny, więc opóźnienia w sekundach nie wydłużają wykonania,
- **skaner** — prawdziwe pliki w katalogu tymczasowym,
- **silnik** — pełne atrapy kolejki i serwera, sprawdzające przejścia stanów,
- **decyzja o ponowieniu pracy w tle** — wydzielona z workera, żeby dała się przetestować,
- **paczkowanie małych plików** — podział, limity i częściowe niepowodzenie paczki.

### Test zgodności między implementacjami

`EndToEndTest` uruchamia **prawdziwy klient kotlinowy przeciwko prawdziwemu serwerowi
w C#**, po prawdziwym mTLS. Testy jednostkowe po obu stronach sprawdzają, czy każda
implementacja robi to, co jej autor zamierzył — dopiero ten test sprawdza, czy
zamierzenia się pokrywają.

Jest pomijany, dopóki nie ma zmiennych środowiskowych, więc nie psuje zwykłej kompilacji.
Żeby go uruchomić, wystartuj serwer, pobierz kod parowania i podaj jego składowe:

```bash
curl -sk https://127.0.0.1:8765/api/v1/pair/new
```

```bash
cd android && FILUM_HOST=127.0.0.1 FILUM_PORT=8765 FILUM_PIN=<k> FILUM_TOKEN=<t> ./gradlew :core:sync:testDebugUnitTest --tests "*EndToEndTest*" --rerun-tasks
```

Test wysyła cztery pliki, w tym nazwę z dwukropkiem i nazwę z polskimi znakami,
a potem żąda manifestu po raz drugi. Serwer musi wtedy zgłosić, że ma komplet —
rozjazd w jednostkach `mtime` albo w normalizacji Unicode ujawniłby się właśnie
tutaj, jako ponowna prośba o te same pliki.

## Stan

| Element | Stan |
|---|---|
| parowanie, ping, manifest, wznawialny transfer | działa, sprawdzone przeciwko serwerowi |
| ekrany: parowanie, główny, dziennik | działają |
| skanowanie: `MediaStore` i pełny obchód | zaimplementowane, nieuruchomione na telefonie |
| harmonogram: ręcznie, codziennie, co tydzień | działa |
| usługa pierwszoplanowa z pilnowaniem budżetu | działa |
| wykrywanie przez mDNS | działa, sprawdzone przeciwko serwerowi |
| ekran wykluczeń, skaner kodu QR aparatem | niezaczęte |

Parowanie odbywa się przez wklejenie ciągu `filum1:…` ze schowka. Skaner aparatem
dojdzie razem z panelem serwera, który i tak musi ten kod narysować (etap 7).

**Cykl nie był jeszcze przejechany na prawdziwym telefonie.** Kod protokołu jest
zweryfikowany przeciwko serwerowi, ale `MediaStore`, Android Keystore, uprawnienie
`MANAGE_EXTERNAL_STORAGE` i przypięcie gniazd do Wi-Fi da się sprawdzić wyłącznie
na urządzeniu.
