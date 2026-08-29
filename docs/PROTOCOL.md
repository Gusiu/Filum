# Filum — specyfikacja protokołu

Wersja protokołu: **1**
Status: projekt (draft), przed pierwszą implementacją.

Dokument jest normatywny dla obu implementacji: serwera (.NET) i klienta (Android).
Celem jest, żeby każdą ze stron dało się napisać od zera, wyłącznie na podstawie
tego dokumentu i [PATH-RULES.md](PATH-RULES.md).

Słowa **MUSI**, **NIE WOLNO**, **POWINIEN**, **MOŻE** mają znaczenie normatywne.

---

## 1. Model

Filum realizuje **jednokierunkowy, przyrostowy backup** z urządzenia mobilnego
na komputer osobisty. Kierunek jest wyłącznie telefon → PC.

Niezmienniki, na których opiera się cała reszta specyfikacji:

- **I1 — monotoniczność.** Zbiór plików przechowywanych przez serwer może wyłącznie
  rosnąć. Usunięcie pliku na telefonie nie wpływa na kopię. Serwer nie udostępnia
  żadnej operacji usuwania.
- **I2 — brak nadpisywania.** Jeżeli pod znaną ścieżką pojawia się inna zawartość,
  poprzednia wersja MUSI zostać zachowana (§8.3). Nadpisanie jest formą utraty danych.
- **I3 — przezroczystość składowania.** Dane na serwerze leżą jako zwykłe drzewo
  katalogów w systemie plików. Odtworzenie nie może wymagać tej aplikacji.
- **I4 — tożsamość przez klucz.** Strony rozpoznają się po kluczu publicznym,
  nigdy po adresie sieciowym (§3).
- **I5 — tożsamość pliku bez skrótu.** Plik identyfikuje trójka
  `(ścieżka, rozmiar, mtime)`. Skróty kryptograficzne służą wyłącznie kontroli
  integralności, nigdy wykrywaniu zmian (§9).

## 2. Warstwa transportowa

- HTTP/1.1 nad TLS 1.3 (TLS 1.2 dopuszczalne jako zapas).
- Serwer MUSI nasłuchiwać na `0.0.0.0` — nigdy na wybranym pojedynczym adresie.
  Wymóg wynika z §4: ten sam proces musi być osiągalny przez LAN, przez sieć
  nakładkową i przez `localhost`.
- Port domyślny: **8765/tcp**, konfigurowalny.
- Prefiks wszystkich zasobów: `/api/v1`.
- Kodowanie tekstu: UTF-8, bez BOM.
- Znaczniki czasu w ciałach JSON: ISO 8601 w UTC.
- Znaczniki `mtime`: **liczba całkowita sekund od epoki Uniksa, UTC**.
  Klient MUSI znormalizować mtime do sekund w jednym miejscu, na granicy odczytu
  z systemu plików (ADR-011 — `MediaStore` podaje sekundy, `File.lastModified()`
  milisekundy; pomieszanie jednostek powoduje widmowe różnice i ponowne wysyłki).

## 3. Tożsamość i uwierzytelnianie

### 3.1 Wzajemne przypięcie kluczy

Filum **nie używa urzędu certyfikacji**. Obie strony posługują się certyfikatami
samopodpisanymi, traktowanymi wyłącznie jako pojemnik na klucz publiczny.
Uwierzytelnienie polega na porównaniu odcisku klucza z zapamiętanym.

- Serwer generuje przy pierwszym uruchomieniu parę kluczy EC P-256 i certyfikat
  samopodpisany. Para jest stała przez cały czas życia instalacji.
- Klient przy parowaniu generuje własną parę kluczy. Klucz prywatny MUSI powstać
  w Android Keystore ze wsparciem sprzętowym (StrongBox, jeśli dostępny) i nie może
  opuścić elementu bezpiecznego.
- **Pin** to `base64url(SHA-256(SubjectPublicKeyInfo))` bez dopełnienia — 43 znaki.

Klient weryfikuje serwer po pinie i **nie weryfikuje nazwy hosta**. Jest to
bezpieczne wyłącznie dlatego, że pin całkowicie zastępuje weryfikację nazwy;
pominięcie obu byłoby luką. Konsekwencją jest, że jeden certyfikat obsługuje
wszystkie trasy z §4 bez pól SAN.

Serwer weryfikuje klienta przez mTLS: akceptuje wyłącznie certyfikaty, których pin
znajduje się na liście sparowanych urządzeń.

### 3.2 Tożsamość urządzenia

Urządzenie identyfikuje **pin jego certyfikatu klienta**. Protokół nie zawiera
nagłówka z identyfikatorem urządzenia — byłby zbędny i podatny na podszycie.
Serwer wyprowadza tożsamość z warstwy TLS.

Etykieta urządzenia (`label`, np. „Galaxy S24 Ultra") jest wyłącznie prezentacyjna
i może być zmieniana bez wpływu na tożsamość.

### 3.3 Parowanie

Ładunek kodu QR generowanego przez panel serwera:

```
filum1:<base64url(JSON)>
```

```jsonc
{
  "n": "DESKTOP-ABC",              // nazwa serwera, prezentacyjna
  "k": "<pin serwera, 43 znaki>",  // §3.1
  "t": "<token parowania>",        // 32 bajty losowe, base64url
  "p": 8765,                       // port
  "a": ["192.168.1.10", "100.64.10.20"],  // znane adresy
  "s": "filum-a3f2"                // nazwa instancji usługi mDNS (§4.1)
}
```

Token parowania MUSI być jednorazowy i wygasać po **10 minutach**.

```
POST /api/v1/pair
Authorization: Bearer <token parowania>
Content-Type: application/json
```
```jsonc
{ "cert": "<certyfikat klienta, DER, base64>", "label": "Galaxy S24 Ultra" }
```

Połączenie odbywa się nad TLS zweryfikowanym pinem z kodu QR, ale **bez**
certyfikatu klienta — to jedyny zasób dostępny bez mTLS.

Odpowiedź `200`:
```jsonc
{ "deviceKey": "<pin klienta>", "serverInstanceId": "<uuid>", "protocol": 1 }
```

Po udanym parowaniu serwer MUSI unieważnić token. Każdy inny zasób wywołany bez
ważnego certyfikatu klienta MUSI zwrócić `401`.

Serwer MUSI ograniczyć próby parowania do **5 na 15 minut** z jednego adresu źródłowego.

### 3.4 Wydawanie tokenu

```
GET /api/v1/pair/new
```

Zasób pomocniczy, **nieużywany przez klienta mobilnego**. Wydaje token i zwraca
gotowy ładunek kodu QR na potrzeby interfejsu serwera.

Serwer MUSI go udostępniać wyłącznie połączeniom z pętli zwrotnej. Jest to jedyny
zasób wydający sekret, a osoba siedząca przy komputerze jest z definicji uprawniona
do sparowania telefonu — udostępnienie go w sieci pozwalałoby obcemu urządzeniu
wygenerować sobie token parowania.

---

## 4. Wykrywanie i wybór trasy

Klient nie przechowuje „adresu serwera". Przechowuje **zbiór kandydatów** — tras,
którymi można dotrzeć do serwera o znanym pinie.

### 4.1 Rozgłaszanie w sieci lokalnej

Serwer MUSI rozgłaszać usługę mDNS/DNS-SD:

- typ: `_filum._tcp.local`
- instancja: `filum-<8 znaków pinu>`
- TXT: `v=1`, `k=<pin serwera>`, `n=<nazwa serwera>`

Klient MUSI przy skanowaniu mDNS przytrzymać `MulticastLock` — bez tego na wielu
urządzeniach odpowiedzi nie docierają do aplikacji.

### 4.2 Wyścig tras

Przy nawiązywaniu połączenia klient POWINIEN wysłać `GET /ping` **równolegle** do
wszystkich kandydatów, z limitem czasu **2000 ms**, i użyć pierwszej odpowiedzi,
której pin serwera się zgadza. Pozostałe połączenia porzuca.

Kandydaci, w kolejności preferencji przy zbliżonym czasie odpowiedzi:

1. instancja znaleziona przez mDNS w bieżącej sieci,
2. ostatni skuteczny adres LAN (z pamięci podręcznej),
3. adres sieci nakładkowej (Meshnet / Tailscale / WireGuard),
4. nazwa DDNS lub przekaźnik, jeśli skonfigurowane.

Klient POWINIEN zapamiętać zwycięzcę i próbować go pierwszego, nie rezygnując
z równoległego sprawdzania pozostałych.

### 4.3 Trasy lokalne przy aktywnym VPN

Gdy na telefonie działa VPN, ruch domyślnie wchodzi w tunel i adresy z §4.2 pkt 1–2
przestają być osiągalne. Klient POWINIEN dla tras lokalnych przypiąć gniazdo do
konkretnego obiektu `Network` uzyskanego przez `ConnectivityManager` z
`TRANSPORT_WIFI`, zamiast korzystać z trasy domyślnej.

Jeżeli VPN działa w trybie blokującym ruch poza tunelem, przypięcie zawiedzie —
klient MUSI wtedy po cichu zdegradować się do tras z §4.2 pkt 3–4, bez zgłaszania
błędu użytkownikowi.

### 4.4 Samoaktualizacja zbioru tras

Odpowiedź `GET /ping` zawiera listę adresów, pod którymi serwer sam siebie widzi
(§5.1). Klient MUSI scalić ją ze swoim zbiorem kandydatów.

Dzięki temu pojedyncze udane połączenie **dowolną** trasą odświeża wszystkie
pozostałe. Zmiana routera w domu nie wymaga żadnej interakcji użytkownika: telefon
pozna nowy adres LAN przy najbliższym połączeniu przez sieć nakładkową.

---

## 5. Zasoby

### 5.1 `GET /api/v1/ping`

Kontrola dostępności, negocjacja limitów i odświeżenie tras. Tani — używany
w wyścigu z §4.2, więc serwer nie powinien wykonywać przy nim operacji dyskowych
cięższych niż odczyt wolnego miejsca.

Odpowiedź `200`:
```jsonc
{
  "protocol": 1,
  "server":   { "name": "DESKTOP-ABC", "version": "1.0.0", "instanceId": "<uuid>" },
  "endpoints": ["192.168.1.10:8765", "100.64.10.20:8765"],
  "storage":  { "freeBytes": 812000000000, "totalBytes": 2000000000000 },
  "device":   { "label": "Galaxy S24 Ultra", "fileCount": 128431, "bytesStored": 201000000000 },
  "limits":   {
    "manifestBatch": 2000,
    "smallFileThreshold": 262144,
    "bundleMaxBytes": 8388608,
    "bundleMaxCount": 256,
    "maxPathBytes": 4096
  },
  "time": "2026-08-28T21:14:00Z"
}
```

Klient MUSI odrzucić serwer o nieobsługiwanym `protocol` i pokazać czytelny komunikat.
Klient MUSI stosować się do `limits` — nie wolno mu zakładać wartości domyślnych,
jeśli serwer podał inne.

### 5.2 `POST /api/v1/manifest`

Klient zgłasza, co ma; serwer odpowiada, czego mu brakuje.

```jsonc
{
  "session": "<uuid>",     // spina partie w jedną operację
  "mode": "full",          // "full" | "delta"
  "batch": 3,              // numer partii, od 0
  "final": false,          // true w ostatniej partii sesji
  "files": [
    { "p": "DCIM/Camera/IMG_0001.JPG", "s": 4812233, "m": 1756400000 }
  ]
}
```

- Liczba pozycji w `files` nie może przekroczyć `limits.manifestBatch`.
- `mode: "full"` oznacza, że sesja obejmuje **komplet** plików na urządzeniu.
  Tylko wtedy serwer wylicza raport sierot (§5.6).
- `mode: "delta"` oznacza przyrost od poprzedniego skanu i nie powoduje
  wyliczania sierot.

Odpowiedź `200`:
```jsonc
{
  "have": 1834,
  "needed": [
    { "p": "DCIM/Camera/IMG_0002.JPG", "offset": 0 },
    { "p": "Movies/klip.mp4",          "offset": 5242880 },
    { "p": "Documents/notatka.txt",    "offset": 0, "versioned": true }
  ],
  "rejected": [
    { "p": "dziwna/../ścieżka.txt", "error": "traversal" }
  ]
}
```

- `offset > 0` oznacza, że na serwerze leży niedokończony transfer i klient
  POWINIEN wznowić od tego miejsca.
- `versioned: true` sygnalizuje, że ścieżka jest już zajęta przez inną zawartość
  i po zakończeniu transferu poprzednia wersja zostanie odłożona (§8.3).
  Pole jest informacyjne — służy prezentacji w interfejsie.
- `rejected` zawiera ścieżki nienadające się do składowania (§7), z wartością
  `error` z tabeli w §4 dokumentu PATH-RULES. Pojedyncza zła ścieżka **nie może**
  unieważniać całej partii. Klient MUSI oznaczyć takie pozycje jako trwale nieudane
  i nigdy nie próbować ich wysyłać.

  Bez tego pola klient dowiedziałby się o odrzuceniu dopiero z odpowiedzi `422`
  na `PUT`, czyli po jednym zmarnowanym żądaniu na każdy taki plik — a przy
  wysyłce po sieci komórkowej to realny koszt.

Serwer wyznacza `have`/`needed` przez porównanie `(rozmiar, mtime)` z systemem
plików. Serwer **nie utrzymuje własnego indeksu zawartości** (ADR-003).

Tolerancja mtime: **0 sekund**. Wyjątkiem jest tryb adopcji (§10).

### 5.3 `PUT /api/v1/file`

Przesłanie fragmentu pliku. Ciało żądania to **surowe bajty**, bez opakowania.

| Nagłówek | Wymagany | Znaczenie |
|---|---|---|
| `X-Filum-Path`   | tak | ścieżka względna, `base64url` bez dopełnienia |
| `X-Filum-Size`   | tak | całkowity rozmiar pliku w bajtach |
| `X-Filum-Mtime`  | tak | mtime pliku, sekundy od epoki, UTC |
| `X-Filum-Offset` | tak | przesunięcie, od którego zaczyna się ciało |
| `X-Filum-Digest` | nie | `sha256` całego pliku, hex; patrz §9 |

Semantyka:

- `X-Filum-Offset: 0` rozpoczyna nowy transfer i porzuca ewentualny wcześniejszy fragment.
- Jeżeli `X-Filum-Offset` nie równa się aktualnej długości fragmentu na serwerze,
  serwer MUSI odpowiedzieć `409` z `{"expectedOffset": N}`.
- Jeżeli zapisany fragment powstał dla innej pary `(rozmiar, mtime)` niż podana
  w nagłówkach, serwer MUSI odrzucić wznowienie przez `409` z `{"expectedOffset": 0}`.
  Chroni to przed sklejeniem dwóch różnych wersji pliku — np. gdy nagranie wideo
  było jeszcze w trakcie zapisu.
- Transfer jest kompletny, gdy `offset + długość_ciała == X-Filum-Size`. Wtedy
  serwer wykonuje domknięcie (§8.2).

Odpowiedź `200`:
```jsonc
{ "received": 5242880, "offset": 10485760, "complete": false }
```
```jsonc
{ "received": 1204112, "offset": 4812233, "complete": true, "versioned": null }
```

Klient POWINIEN pomijać pliki zmodyfikowane w ciągu ostatnich **60 sekund** —
mogą być właśnie zapisywane.

### 5.4 `POST /api/v1/bundle`

Agregacja małych plików. Przy dziesiątkach tysięcy plików poniżej
`limits.smallFileThreshold` narzut pojedynczego żądania HTTP zaczyna dominować
nad transferem (ADR-008).

`Content-Type: application/vnd.filum.bundle`

Ramkowanie binarne, powtarzane `N` razy, bez wyrównania:

```
u32be  metaLen
bytes  meta        // JSON UTF-8: {"p":"<ścieżka>","s":123,"m":1756400000,"d":"<sha256 hex>"}
bytes  payload     // dokładnie s bajtów
```

- Suma rozmiarów nie może przekroczyć `limits.bundleMaxBytes`.
- Liczba plików nie może przekroczyć `limits.bundleMaxCount`.
- Paczka nie obsługuje wznawiania. Pliki są małe — w razie błędu klient ponawia
  całość albo przenosi pozycje do `PUT /file`.
- Pole `d` jest **wymagane** w paczce; koszt liczenia skrótu małego pliku jest pomijalny.

Odpowiedź `200` — wynik per plik, żądanie może się powieść częściowo:
```jsonc
{ "results": [
  { "p": "Pictures/a.jpg", "ok": true,  "versioned": null },
  { "p": "Pictures/b.jpg", "ok": false, "error": "digest_mismatch" }
] }
```

### 5.5 `POST /api/v1/verify`

Tryb weryfikacji integralności — operacja **jawnie uruchamiana przez użytkownika**,
nigdy część cyklu automatycznego (I5).

```jsonc
{ "files": [ { "p": "DCIM/Camera/IMG_0001.JPG", "d": "<sha256 hex>" } ] }
```

Odpowiedź `200`:
```jsonc
{ "ok": ["..."], "mismatch": ["..."], "missing": ["..."] }
```

Serwer liczy skrót swoich kopii, więc zasób jest kosztowny. Serwer MUSI go
ograniczać przepustowościowo i POWINIEN odrzucać żądania większe niż 256 pozycji.

### 5.6 `GET /api/v1/orphans?session=<uuid>`

Raport sierot: ścieżki obecne na serwerze, których **nie było** w kompletnym
manifeście danej sesji — czyli pliki usunięte lub przeniesione na telefonie.

Zasób jest dostępny wyłącznie dla sesji zamkniętej z `mode: "full"` i `final: true`.

```jsonc
{
  "session": "<uuid>",
  "generatedAt": "2026-08-28T03:12:00Z",
  "count": 412,
  "bytes": 8100000000,
  "paths": ["DCIM/Camera/IMG_0500.JPG"]
}
```

Raport jest **wyłącznie informacyjny**. Ani serwer, ani klient nie usuwa niczego
na jego podstawie (I1). Decyzja należy do użytkownika i zapada ręcznie w panelu.

---

## 6. Kody błędów

| Kod | Znaczenie | Reakcja klienta |
|---|---|---|
| `400` | błędne nagłówki lub ciało | błąd trwały, do dziennika, nie ponawiać |
| `401` | brak lub nieznany certyfikat klienta | zatrzymać cykl, wymagać ponownego parowania |
| `403` | urządzenie wyłączone w panelu | zatrzymać cykl, komunikat dla użytkownika |
| `409` | niezgodne przesunięcie fragmentu | wznowić od `expectedOffset` |
| `413` | ciało przekracza limit | zmniejszyć paczkę lub przejść na `PUT /file` |
| `422` | ścieżka nie do przyjęcia (§7) | błąd trwały dla tego pliku, pominąć i zgłosić |
| `429` | ograniczenie częstotliwości | wycofanie wykładnicze, uszanować `Retry-After` |
| `503` | serwer wstrzymany lub zajęty | wycofanie wykładnicze |
| `507` | za mało miejsca na dysku | przerwać cykl, powiadomić użytkownika |

Ciało błędu:
```jsonc
{ "error": "offset_mismatch", "message": "...", "expectedOffset": 5242880 }
```

Klient MUSI odróżniać błędy **przejściowe** (`429`, `503`, sieciowe) od **trwałych**
(`400`, `422`) i nie ponawiać w nieskończoność tych drugich. Każdy błąd trwały MUSI
trafić do dziennika widocznego w aplikacji.

---

## 7. Ścieżki

Reguły normalizacji, sanityzacji pod NTFS i rozstrzygania kolizji opisuje osobny
dokument: **[PATH-RULES.md](PATH-RULES.md)**.
Wektory testowe: [`testvectors/paths.tsv`](testvectors/paths.tsv) — obie
implementacje MUSZĄ przechodzić ten sam zestaw.

---

## 8. Składowanie po stronie serwera

### 8.1 Układ katalogów

```
<root>/
  <device-slug>/
    files/                      # żywe drzewo, lustro telefonu, przeglądalne w Eksploratorze
      DCIM/Camera/IMG_0001.JPG
    .filum/
      device.json               # pin, etykieta, data parowania
      incoming/                 # transfery w toku
        <id>.part
        <id>.meta               # {"p":..,"s":..,"m":..,"received":..}
      versions/                 # poprzednie wersje (§8.3)
        DCIM/Camera/IMG_0001.20260828T211430Z.JPG
      index.jsonl               # mapowania ścieżek rozstrzygniętych z kolizji
      orphans-<session>.json
```

Katalog `files/` MUSI zawierać wyłącznie kompletne pliki. Fragmenty nie mogą być
w nim widoczne — użytkownik ma w każdej chwili oglądać spójne drzewo (I3).

### 8.2 Domknięcie transferu

Po odebraniu ostatniego bajtu serwer MUSI wykonać kolejno:

1. sprawdzić `X-Filum-Digest`, jeżeli był podany; niezgodność → odrzucić fragment i `409`,
2. jeżeli docelowa ścieżka istnieje — odłożyć jej dotychczasową zawartość (§8.3),
3. przenieść plik z `.filum/incoming/` do `files/` operacją atomową w obrębie wolumenu,
4. ustawić mtime na wartość z `X-Filum-Mtime`,
5. usunąć plik `.meta`.

Kolejność jest istotna: krok 3 nie może wyprzedzić kroku 2, bo naruszyłoby to I2.

### 8.3 Wersjonowanie

Gdy pod istniejącą ścieżką pojawia się zawartość o innym `(rozmiar, mtime)`,
dotychczasowy plik MUSI zostać przeniesiony do
`.filum/versions/<ta sama ścieżka>/<nazwa>.<YYYYMMDD>T<HHmmss>Z<.rozszerzenie>`,
gdzie znacznik czasu pochodzi z **mtime starej kopii**, nie z czasu operacji.

Wersje trzymamy poza `files/`, żeby żywe drzewo pozostało czystym lustrem telefonu.

### 8.4 Próg wolnego miejsca

Serwer MUSI odrzucać zapisy (`507`), gdy wolne miejsce na wolumenie spadłoby poniżej
`max(5 GiB, 2% pojemności)`. Chroni to system operacyjny przed zapchaniem dysku
przez urządzenie, które z definicji nigdy niczego nie kasuje.

---

## 9. Integralność

Rozróżniamy dwa zastosowania skrótów kryptograficznych, których **nie wolno mylić**:

- **Wykrywanie zmian** — odrzucone (I5, ADR-002). Liczenie SHA-256 z ~200 GB przy
  każdym cyklu jest nie do przyjęcia energetycznie na urządzeniu mobilnym.
- **Kontrola integralności transferu** — dozwolona i tania, bo bajty i tak przechodzą
  przez procesor po obu stronach. Klient MOŻE liczyć skrót przyrostowo w trakcie
  odczytu i podać go w `X-Filum-Digest`.

Zastrzeżenie: przy transferze wznawianym w kilku sesjach klient musiałby odczytać
plik od początku, żeby domknąć skrót. Dlatego `X-Filum-Digest` jest **opcjonalny**
w `PUT /file` — klient POWINIEN go podawać, gdy plik przechodzi w jednym przebiegu,
i pomijać przy wznowieniu. W `POST /bundle` jest wymagany.

Pełna weryfikacja istniejącej kopii odbywa się przez `POST /verify` (§5.5),
uruchamiana świadomie przez użytkownika.

---

## 10. Tryb adopcji (opcjonalny, ADR-012)

Przewidziany dla scenariusza, w którym użytkownik wgrał pierwsze ~200 GB kablem.
Kopiowanie przez MTP notorycznie nie zachowuje mtime, więc porównanie z §5.2
uznałoby komplet plików za brakujące.

W trybie adopcji serwer dopasowuje po `(ścieżka, rozmiar)` z tolerancją mtime `±2 s`
(granulacja FAT) albo z całkowitym pominięciem mtime, a następnie **zapisuje mtime
podany przez telefon jako kanoniczny**, po czym tryb się wyłącza.

Tryb MUSI być jednorazowy, jawnie włączany w panelu serwera i ograniczony czasowo.
Ścieżką podstawową pozostaje pełny transfer przez sieć (ADR-012).

---

## 11. Kwestie otwarte

- Format przyrostowego raportu postępu dla panelu (SignalR czy odpytywanie).
- Czy `POST /bundle` powinien mieć wariant strumieniowy dla bardzo dużych paczek.
- Zachowanie przy dwóch urządzeniach zapisujących tę samą ścieżkę — obecnie
  rozdzielone przez `<device-slug>`, ale warto rozważyć widok scalony w panelu.
- Polityka retencji wersji w `.filum/versions/` — obecnie brak, katalog rośnie
  bez ograniczeń.
