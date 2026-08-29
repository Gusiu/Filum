# Filum — serwer

.NET 9, ASP.NET Core minimal APIs. Implementacja zasobów z
[../docs/PROTOCOL.md](../docs/PROTOCOL.md).

## Projekty

| Projekt | Odpowiedzialność |
|---|---|
| `Filum.Protocol` | modele DTO, `PathRules`, stałe protokołu — bez operacji plikowych |
| `Filum.Core` | silnik składowania: manifest, fragmenty, wersje, próg miejsca |
| `Filum.Server` | Kestrel, zasoby HTTP, parsowanie nagłówków |
| `Filum.Protocol.Tests` | wektory ścieżek wspólne z klientem Androida |
| `Filum.Core.Tests` | silnik składowania i testy integracyjne API |

## Uruchamianie

```bash
cd server && dotnet run --project src/Filum.Server --no-launch-profile
```

Katalog backupu ustawia `Filum:Root` w `appsettings.json` albo zmienna
środowiskowa `Filum__Root`. Bez tego serwer użyje
`%LOCALAPPDATA%\Filum\backup`.

## Ostrzeżenie: stan na etapie 2

Serwer nasłuchuje **wyłącznie na `127.0.0.1`** i nie ma jeszcze ani TLS, ani
żadnego uwierzytelniania. Tożsamość urządzenia jest ustalona w konfiguracji
(`Filum:DeviceSlug`), bo docelowy mechanizm — pin certyfikatu klienta z warstwy
TLS (§3.2 protokołu) — pojawi się dopiero na etapie 4.

Nie wolno zmieniać adresu nasłuchu na `0.0.0.0` przed etapem 4. W tym stanie
każdy w sieci lokalnej mógłby zapisywać pliki na dysku.

Miejsce, w którym to się podepnie, jest już przygotowane: interfejs
`IDeviceResolver` w `Filum.Server`. Implementacja `ConfiguredDeviceResolver`
zostanie zastąpiona odczytem pinu z certyfikatu.

## Testy

```bash
cd server && dotnet test
```

## Przejazd ręczny

```bash
curl -s http://127.0.0.1:8765/api/v1/ping
```

Manifest — czego serwerowi brakuje:

```bash
curl -s -X POST http://127.0.0.1:8765/api/v1/manifest -H 'Content-Type: application/json' -d '{"session":"s1","mode":"delta","batch":0,"final":true,"files":[{"p":"DCIM/Camera/a.jpg","s":28,"m":1756400000}]}'
```

Wysyłka pliku (`X-Filum-Path` to base64url bez dopełnienia):

```bash
curl -s -X PUT http://127.0.0.1:8765/api/v1/file -H "X-Filum-Path: RENJTS9DYW1lcmEvYS5qcGc" -H "X-Filum-Size: 28" -H "X-Filum-Mtime: 1756400000" -H "X-Filum-Offset: 0" --data-binary @plik.jpg
```

Wznowienie polega na powtórzeniu żądania z `X-Filum-Offset` równym wartości
`offset` z ostatniej odpowiedzi. Niezgodne przesunięcie daje `409` z polem
`expectedOffset`.

## Co jest zaimplementowane

| Zasób | Stan |
|---|---|
| `GET /api/v1/ping` | tak, z listą adresów lokalnych (§4.4) |
| `POST /api/v1/manifest` | tak, z polem `rejected` |
| `PUT /api/v1/file` | tak, wznawianie i wersjonowanie |
| `POST /api/v1/bundle` | nie — etap 8 |
| `POST /api/v1/verify` | nie — etap 9 |
| `GET /api/v1/orphans` | nie — etap 9 |
| `POST /api/v1/pair`, mTLS, mDNS | nie — etapy 4 i 5 |
| panel Blazor, ikona w zasobniku | nie — etap 7 |
