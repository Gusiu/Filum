# Filum — reguły ścieżek

Dokument normatywny. Obie implementacje MUSZĄ przechodzić zestaw
[`testvectors/paths.tsv`](testvectors/paths.tsv).

To jest miejsce, w którym najłatwiej po cichu stracić dane, dlatego reguły są
tu wyłożone szczegółowo, a nie zostawione implementacji.

---

## 1. Problem

Android używa systemów plików z rodziny ext4/f2fs: rozróżniają wielkość liter
i dopuszczają w nazwie każdy bajt poza `/` i `\0`. Windows z NTFS jest pod tym
względem znacznie bardziej restrykcyjny:

- zabronione znaki `< > : " | ? * \` oraz kody sterujące `0x01`–`0x1F`,
- nazwy zastrzeżone po DOS-ie: `CON`, `PRN`, `AUX`, `NUL`, `COM1`–`COM9`, `LPT1`–`LPT9`,
  także z dowolnym rozszerzeniem i niezależnie od wielkości liter,
- końcowe kropki i spacje są po cichu obcinane,
- **nazwy nie rozróżniają wielkości liter** — `Foto.jpg` i `foto.jpg` to ten sam plik,
- limit 255 jednostek UTF-16 na składową i 260 znaków na całą ścieżkę, o ile nie
  użyje się prefiksu `\\?\`.

Naiwna sanityzacja przez zamianę złych znaków na `_` jest **stratna i niewstrzykiwalna
odwrotnie**: `a<b.txt` i `a_b.txt` trafiają na tę samą ścieżkę, więc drugi plik
cicho nadpisuje pierwszy. Łamie to niezmiennik I2 z [PROTOCOL.md](PROTOCOL.md).

## 2. Zasada naczelna

**Odwzorowanie ścieżki telefonu na ścieżkę Windows MUSI być różnowartościowe.**
Dwie różne ścieżki źródłowe nigdy nie mogą trafić na tę samą ścieżkę docelową.

Realizujemy to przez kodowanie procentowe (zamiast podmiany na `_`), które jest
odwracalne, oraz przez jawne rozstrzyganie kolizji wielkości liter (§6).

---

## 3. Postać kanoniczna po stronie klienta

Zanim ścieżka trafi do manifestu, klient MUSI:

1. wyrazić ją **względem katalogu głównego pamięci**, bez wiodącego `/`,
2. użyć `/` jako separatora,
3. zakodować w UTF-8 i znormalizować do postaci **Unicode NFC**,
4. nie zawierać segmentów `.` ani `..`, segmentów pustych ani bajtu `0x00`.

Normalizacja NFC jest istotna dla nazw z polskimi znakami: ta sama nazwa
zapisana w NFD i NFC to dla porównania bajtowego dwie różne ścieżki, co
powodowałoby cykliczne wysyłanie tych samych plików.

Manifest (`POST /manifest`) operuje **wyłącznie na postaci kanonicznej**.
Sanityzacja z §5 jest szczegółem składowania po stronie serwera i nigdy nie
pojawia się w protokole.

---

## 4. Odrzucenie (HTTP 422)

Serwer MUSI odrzucić ścieżkę, jeżeli:

| Warunek | `error` |
|---|---|
| zaczyna się od `/` | `absolute` |
| zawiera segment `..` | `traversal` |
| zawiera segment `.` | `dot_segment` |
| zawiera segment pusty (`a//b`) | `empty_segment` |
| zawiera bajt `0x00` | `nul` |
| przekracza `limits.maxPathBytes` | `path_too_long` |

Odrzucenie jest błędem **trwałym** dla danego pliku: klient odnotowuje je
w dzienniku i nie ponawia.

> **Uwaga implementacyjna (.NET).** Litera dysku nie jest odrzucana, bo po
> zakodowaniu z §5 przestaje być literą dysku (`C:` → `C%3A`). Nie zmienia to
> jednak faktu, że `Path.Combine(root, cokolwiek_zakorzenionego)` **odrzuca
> pierwszy argument** i zwraca ścieżkę bezwzględną. Sanityzacja MUSI poprzedzać
> złączenie, a po złączeniu implementacja MUSI dodatkowo sprawdzić, że
> rozwiązana ścieżka bezwzględna leży wewnątrz katalogu urządzenia. Obrona
> w dwóch warstwach, bo koszt pomyłki to zapis dowolnego pliku na dysku.

---

## 5. Kodowanie składowych

Reguły stosuje się **niezależnie do każdego segmentu**, w podanej kolejności.
Cyfry szesnastkowe zapisujemy **wielkimi literami**.

1. **`%` → `%25`.** Musi być pierwsze, inaczej kodowanie przestaje być odwracalne.
2. Znaki zabronione przez NTFS:
   `<` → `%3C`, `>` → `%3E`, `:` → `%3A`, `"` → `%22`,
   `|` → `%7C`, `?` → `%3F`, `*` → `%2A`, `\` → `%5C`.
3. Kody sterujące `0x01`–`0x1F` → `%01`–`%1F`.
4. **Końcowa kropka lub spacja**: zakodować **wyłącznie ostatni znak** segmentu
   (`.` → `%2E`, ` ` → `%20`). Kropki i spacje w środku nazwy zostają nietknięte,
   bo Windows obcina tylko końcowe. Reguła jest jednokrotna: `pliki...` → `pliki..%2E`.
5. **Nazwa zastrzeżona**: jeżeli segment przed pierwszą kropką jest równy (bez
   względu na wielkość liter) jednej z nazw `CON PRN AUX NUL COM1..COM9 LPT1..LPT9`,
   zakodować **pierwszy znak** segmentu. `CON` → `%43ON`, `con.txt` → `%63on.txt`.
   Kodujemy pierwszy znak w jego oryginalnej wielkości, więc odwrócenie odtwarza
   nazwę dokładnie. `CONSOLE.txt` nie jest nazwą zastrzeżoną i zostaje bez zmian.
6. **Limit długości**: jeżeli segment po zakodowaniu przekracza 255 jednostek
   UTF-16, przyciąć do 247 i dopisać `~` oraz 6 pierwszych znaków szesnastkowych
   **małymi literami** z `SHA-256` oryginalnego segmentu (czyli pierwsze 3 bajty
   skrótu). Daje to najwyżej 254 jednostki. Wpis trafia do `index.jsonl` (§7).

   Przycięcie **nie może rozciąć sekwencji `%XX`** — inaczej kodowanie przestaje
   być odwracalne. Jeżeli granica wypada wewnątrz sekwencji, należy cofnąć się do
   jej początku. Każdy `%` w tekście zakodowanym rozpoczyna sekwencję, bo literalny
   `%` został zamieniony na `%25` w regule 1, więc rozpoznanie jest jednoznaczne.

Segmenty łączy się separatorem systemowym i dokleja do
`<root>/<device-slug>/files/`.

Serwer MUSI wykonywać wszystkie operacje plikowe przez ścieżki z prefiksem
`\\?\`, żeby ominąć limit 260 znaków. Sam prefiks nie zwalnia z limitu 255
jednostek na składową — stąd reguła 6.

---

## 6. Kolizje wielkości liter

NTFS nie rozróżnia wielkości liter, więc `Pictures/Foto.jpg` i `Pictures/foto.jpg`
z telefonu wskazują na jeden plik na dysku. Bez obsługi drugi cicho nadpisałby
pierwszy — znowu naruszenie I2.

Wykrycie nie wymaga żadnego indeksu, bo **Windows zachowuje wielkość liter przy
zapisie**, mimo że jej nie rozróżnia przy wyszukiwaniu:

1. Wyznacz ścieżkę docelową `S` według §5.
2. Sprawdź, czy plik pod `S` istnieje. Jeżeli nie — zapis wprost, koniec.
3. Jeżeli istnieje, odczytaj **rzeczywistą nazwę z katalogu** i porównaj ją z `S`
   bajt po bajcie.
   - Zgodna → to ten sam plik, normalna ścieżka aktualizacji (wersjonowanie, §8.3 protokołu).
   - Niezgodna → kolizja wielkości liter.
4. Przy kolizji doklej do nazwy (przed rozszerzeniem) `~` i 6 pierwszych znaków
   szesnastkowych **małymi literami** z `SHA-256` **pełnej oryginalnej ścieżki**:
   `Pictures/foto.jpg` → `Pictures/foto~420670.jpg`.

Wartość `~420670` z powyższego przykładu jest wiążąca i sprawdzana testem po obu
stronach — jeżeli implementacja daje inny wynik, rozjeżdża się z dokumentem.

Rozstrzygnięcie jest deterministyczne i stabilne: ta sama ścieżka źródłowa zawsze
daje tę samą nazwę docelową, także po odtworzeniu backupu z kopii.

---

## 7. `index.jsonl`

Plik `<root>/<device-slug>/.filum/index.jsonl` zawiera **wyłącznie** wpisy dla
ścieżek, których nie da się odwrócić samym dekodowaniem procentowym — czyli
przypadki z §5 pkt 6 i §6.

```jsonc
{"p":"Pictures/foto.jpg","s":"Pictures/foto~420670.jpg","r":"case_collision"}
```

Plik jest **pomocniczy, nie autorytatywny**: da się go odtworzyć z dowolnego
kompletnego manifestu, bo obie reguły są deterministyczne. Jego utrata nie
powoduje utraty danych, a jedynie utratę czytelnej informacji o oryginalnej
nazwie. Dysk pozostaje źródłem prawdy (ADR-003).

---

## 8. Odwracanie

Dekodowanie procentowe jest jednoznaczne, więc dla zdecydowanej większości
plików oryginalną nazwę odtwarza się bez żadnego stanu pobocznego. Przypadki
z `~<hash>` wymagają zajrzenia do `index.jsonl` albo do manifestu.

Utrzymanie odwracalności to nie jest czystość dla samej czystości — to warunek
tego, żeby backup dało się w całości przenieść z powrotem na urządzenie
z systemem plików rozróżniającym wielkość liter.
