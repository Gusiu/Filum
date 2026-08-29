# Filum — serwer

.NET 9, ASP.NET Core minimal APIs. Implementacja zasobów z
[../docs/PROTOCOL.md](../docs/PROTOCOL.md).

## Projekty

| Projekt | Odpowiedzialność |
|---|---|
| `Filum.Protocol` | modele DTO, `PathRules`, stałe protokołu — bez operacji plikowych |
| `Filum.Core` | silnik składowania, certyfikat serwera, rejestr urządzeń |
| `Filum.Server` | Kestrel z mTLS, zasoby HTTP, parowanie, panel |
| `Filum.Tray` | ikona w zasobniku systemowym |
| `Filum.Protocol.Tests` | wektory ścieżek wspólne z klientem Androida |
| `Filum.Core.Tests` | silnik składowania, bezpieczeństwo, testy integracyjne API |

## Uruchamianie

```bash
cd server && dotnet run --project src/Filum.Server --no-launch-profile
```

| Ustawienie | Domyślnie | Znaczenie |
|---|---|---|
| `Filum:Root` | `%LOCALAPPDATA%\Filum\backup` | katalog backupu |
| `Filum:ConfigDirectory` | `%LOCALAPPDATA%\Filum` | klucz prywatny serwera |
| `Filum:Bind` | `0.0.0.0` | adres nasłuchu |
| `Filum:Port` | `8765` | port |
| `Filum:EnableMdns` | `true` | rozgłaszanie usługi w sieci lokalnej |

Każde ustawienie da się nadpisać zmienną środowiskową, np. `Filum__Root`.

Klucz prywatny leży **poza katalogiem backupu**. Ten bywa kopiowany na inny dysk
albo odtwarzany z kopii, więc klucz wyciekłby razem z danymi.

## Bezpieczeństwo

Od etapu 4 obowiązuje **wzajemne przypięcie kluczy** (§3.1 protokołu, ADR-005):

- serwer generuje przy pierwszym starcie parę EC P-256 i certyfikat samopodpisany,
- telefon przy parowaniu przekazuje swój certyfikat, serwer zapamiętuje odcisk klucza,
- każdy zasób poza `/api/v1/pair` wymaga certyfikatu klienta o znanym odcisku.

Nie ma urzędu certyfikacji ani tokenów wędrujących w każdym żądaniu. Odebranie
dostępu zgubionemu telefonowi to skreślenie jednego wpisu w rejestrze.

Dzięki temu nasłuch na `0.0.0.0` jest bezpieczny: bez sparowanego klucza żaden
zasób zapisujący nie odpowiada.

## Panel

Otwórz w przeglądarce **na komputerze z serwerem**:

```
https://127.0.0.1:8765/
```

Pokazuje stan serwera, wolne miejsce, listę sparowanych telefonów wraz z liczbą
plików i zajętością, a pod `/parowanie` rysuje kod QR do sparowania kolejnego
urządzenia.

Panel jest dostępny **wyłącznie z pętli zwrotnej** — wydaje tokeny parowania
i pokazuje zawartość kopii, więc wystawienie go w sieci pozwalałoby obcemu
urządzeniu sparować się bez wiedzy właściciela (ADR-010). Certyfikat jest
samopodpisany, więc przeglądarka ostrzeże przy pierwszym wejściu.

Ten sam token da się pobrać z wiersza poleceń:

```bash
curl -sk https://127.0.0.1:8765/api/v1/pair/new
```

Token jest jednorazowy i żyje 10 minut.

### Wyłączenie i rozparowanie urządzenia

Wyłączone urządzenie dostaje `403` na każde żądanie, ale jego kopia zostaje
nietknięta. Rozparowanie usuwa wyłącznie wpis `device.json` — pliki zostają,
bo najczęstszy powód rozparowania to zgubiony telefon, czyli dokładnie ta
chwila, w której kopia jest najbardziej potrzebna.

## Ikona w zasobniku

```bash
cd server && dotnet run --project src/Filum.Tray
```

Pokazuje, czy serwer działa, ilu telefonów dotyczy kopia i ile zostało miejsca.
Pod prawym przyciskiem: panel, ekran parowania i katalog kopii.

Jest **osobnym procesem**, bo usługa Windows działa w sesji 0 i nie może wyświetlać
interfejsu. Rozmawia z serwerem przez to samo lokalne API co panel — nie ma między
nimi żadnego kanału specjalnego.

Certyfikat serwera weryfikuje po odcisku klucza odczytanym z `server.pfx`, a nie po
łańcuchu zaufania. Dzięki temu nie ufa czemukolwiek, co zdąży zająć port 8765.

Katalog konfiguracji i port można nadpisać zmiennymi `FILUM_CONFIG` i `FILUM_PORT`.

## Testy

```bash
cd server && dotnet test
```

## Przejazd ręczny

Certyfikat serwera jest samopodpisany, więc `curl` potrzebuje `-k`; telefon
zamiast tego sprawdza odcisk klucza. Odcisk można zweryfikować niezależnie:

```bash
openssl s_client -connect 127.0.0.1:8765 </dev/null 2>/dev/null | openssl x509 -pubkey -noout | openssl pkey -pubin -outform der | openssl dgst -sha256 -binary | openssl base64 | tr '+/' '-_' | tr -d '='
```

Wynik musi być identyczny z polem `k` z `/api/v1/pair/new`.

Wysyłka pliku wymaga sparowanego certyfikatu (`X-Filum-Path` to base64url bez dopełnienia):

```bash
curl -sk --cert client.crt --key client.key -X PUT https://127.0.0.1:8765/api/v1/file -H "X-Filum-Path: RENJTS9DYW1lcmEvYS5qcGc" -H "X-Filum-Size: 28" -H "X-Filum-Mtime: 1756400000" -H "X-Filum-Offset: 0" --data-binary @plik.jpg
```

Wznowienie polega na powtórzeniu żądania z `X-Filum-Offset` równym wartości
`offset` z ostatniej odpowiedzi. Niezgodne przesunięcie daje `409` z polem
`expectedOffset`.

## Co jest zaimplementowane

| Zasób | Stan |
|---|---|
| panel: stan, urządzenia, kod QR | tak, tylko z pętli zwrotnej |
| `GET /api/v1/admin/status` | tak, tylko z pętli zwrotnej |
| `GET /api/v1/pair/new` | tak, tylko z pętli zwrotnej |
| `POST /api/v1/pair` | tak, token jednorazowy, limit 5 prób na 15 minut |
| `GET /api/v1/ping` | tak, z listą adresów lokalnych (§4.4) |
| `POST /api/v1/manifest` | tak, z polem `rejected` |
| `PUT /api/v1/file` | tak, wznawianie i wersjonowanie |
| mTLS z przypięciem kluczy | tak |
| rozgłaszanie mDNS | tak |
| `POST /api/v1/bundle` | nie — etap 8 |
| `POST /api/v1/verify`, `GET /api/v1/orphans` | nie — etap 9 |
| ikona w zasobniku systemowym | tak, osobny proces |
