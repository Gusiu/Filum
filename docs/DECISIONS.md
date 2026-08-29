# Filum — rejestr decyzji architektonicznych

Każdy wpis notuje kontekst, decyzję, uzasadnienie i **odrzucone alternatywy**.
Ta ostatnia rubryka jest najważniejsza: praca dyplomowa broni się tym, że pokazuje,
czego świadomie nie wybrano i dlaczego.

Stan na 2026-08-28. Wpisy oznaczone jako *przyjęta* obowiązują do czasu jawnej zmiany.

---

## ADR-001 — Backup jednokierunkowy zamiast synchronizacji

**Status:** przyjęta

**Kontekst.** Istniejące narzędzia (Syncthing, Resilio, FolderSync, Nextcloud)
realizują synchronizację dwukierunkową, w której usunięcie pliku propaguje się
na wszystkie repliki. Użytkownik porządkujący galerię w telefonie traci kopię
zapasową dokładnie wtedy, gdy najbardziej jej potrzebuje.

**Decyzja.** Strumień wyłącznie telefon → PC. Zbiór plików na serwerze jest
monotoniczny: może wyłącznie rosnąć. Serwer nie udostępnia operacji usuwania.

**Uzasadnienie.** To nie jest uproszczona synchronizacja, tylko inny model danych.
Rozróżnienie „backup ≠ synchronizacja" jest tezą całej pracy i z niego wynikają
niemal wszystkie pozostałe decyzje.

**Odrzucone.** Dwukierunkowość (podstawowy konflikt z celem), lustro z propagacją
usunięć (to samo), retencja czasowa z automatycznym czyszczeniem (użytkownik
traciłby dane bez świadomej decyzji).

**Konsekwencje.** Zajętość dysku rośnie bez ograniczeń — łagodzone przez raport
sierot (ADR-015), który informuje, ale nie kasuje.

---

## ADR-002 — Tożsamość pliku bez skrótu kryptograficznego

**Status:** przyjęta

**Kontekst.** Pierwsza synchronizacja obejmuje około 200 GB. Klasyczne narzędzia
wykrywają zmiany przez porównanie skrótów zawartości.

**Decyzja.** Plik identyfikuje trójka `(ścieżka, rozmiar, mtime)`. Skróty służą
wyłącznie kontroli integralności transferu i trybowi weryfikacji.

**Uzasadnienie.** Liczenie SHA-256 z 200 GB przy każdym cyklu jest nie do przyjęcia
energetycznie na urządzeniu zasilanym z akumulatora. Koszt zostanie zmierzony
empirycznie na urządzeniu docelowym i wynik trafi do rozdziału eksperymentalnego.

**Odrzucone.** Pełne hashowanie przy każdym cyklu (koszt energetyczny), hashowanie
przyrostowe wyłącznie plików nowych (nie wykrywa modyfikacji istniejących,
więc nie rozwiązuje problemu).

**Konsekwencje.** Modyfikacja pliku zachowująca rozmiar i mtime pozostanie
niewykryta. Ryzyko marginalne dla zdjęć i wideo; łagodzone trybem weryfikacji
uruchamianym świadomie przez użytkownika.

**Uwaga rozróżniająca.** Skrót liczony **w trakcie trwającego transferu** jest
niemal darmowy, bo bajty i tak przechodzą przez procesor. Odrzucamy hashowanie
jako metodę *wykrywania zmian*, nie jako metodę *kontroli integralności*.
Pomylenie tych dwóch zastosowań jest częstym błędem.

---

## ADR-003 — Serwer bez bazy danych; dysk jest źródłem prawdy

**Status:** przyjęta

**Kontekst.** Serwer musi odpowiadać, których plików z manifestu mu brakuje.

**Decyzja.** Brak indeksu zawartości. Odpowiedź powstaje przez `stat()` na każdej
pozycji partii i porównanie `(rozmiar, mtime)` z systemem plików.

**Uzasadnienie.** 2000 wywołań `stat()` na NTFS to rząd dziesiątek milisekund —
nieistotne wobec transferów trwających godzinami. W zamian znika cała klasa
problemów: rozjazd indeksu z rzeczywistością, migracje schematu, uszkodzenie
bazy po zaniku zasilania. Backup przeniesiony na inny dysk, skopiowany albo
odtworzony z kopii po prostu działa. Ręczne usunięcie pliku w Eksploratorze
zostanie wykryte i plik zostanie dosłany.

**Odrzucone.** SQLite jako indeks (rozjazd ze stanem faktycznym, konieczność
odbudowy), indeks w pamięci z zapisem okresowym (te same problemy plus utrata
przy nagłym wyłączeniu).

**Konsekwencje.** Struktury pomocnicze (`index.jsonl`, raporty sierot) są jawnie
oznaczone jako odtwarzalne i nieautorytatywne.

---

## ADR-004 — Serwer w C#/.NET

**Status:** przyjęta

**Kontekst.** Rozważano Python z FastAPI, Kotlin z Ktorem i C# z ASP.NET Core.

**Decyzja.** .NET 9, ASP.NET Core minimal APIs, publikacja jako pojedynczy plik
wykonywalny bez zewnętrznego środowiska uruchomieniowego.

**Uzasadnienie.** Wszystko, co po stronie serwera jest trudne, jest specyficzne
dla Windows: długie ścieżki NTFS, nazwy zastrzeżone, wolne miejsce na wolumenie,
reguły zapory, usługa systemowa, ikona w zasobniku. W .NET to natywne API,
w Pythonie obejścia przez `ctypes` lub `pywin32`. Instalacja u użytkownika
końcowego to jeden plik zamiast bundla z PyInstallera, który dodatkowo bywa
fałszywie alarmowany przez oprogramowanie antywirusowe.

**Odrzucone.** Python/FastAPI (słaba historia wdrożenia na Windows), Kotlin/Ktor
(kusi wspólnym modułem protokołu z klientem — patrz konsekwencje).

**Konsekwencje.** Rezygnujemy ze współdzielenia kodu protokołu z klientem
Androida. Uznajemy to za zaletę: dwie niezależne implementacje pogodzone przez
pisemną specyfikację i wspólne wektory testowe dowodzą, że specyfikacja jest
kompletna. Wspólny moduł jedynie ukrywałby jej braki.

---

## ADR-005 — Wzajemne przypięcie kluczy zamiast urzędu certyfikacji

**Status:** przyjęta

**Kontekst.** Serwer bywa osiągalny spoza sieci lokalnej (ADR-006), więc token
w postaci jawnej byłby niewystarczający.

**Decyzja.** Obie strony używają certyfikatów samopodpisanych jako pojemnika na
klucz. Klient przypina klucz serwera, serwer przypina klucze sparowanych klientów
(mTLS). Klucz prywatny telefonu powstaje w Android Keystore ze wsparciem sprzętowym.

**Uzasadnienie.** Symetryczne, bez logiki urzędu certyfikacji, bez wygasania,
bez ostrzeżeń przeglądarki dla użytkownika. Nie istnieje token, który mógłby
wyciec do dziennika. Odebranie dostępu zgubionemu telefonowi to skreślenie
jednego wpisu.

**Odrzucone.** Token `Bearer` po zwykłym HTTP (podsłuch w sieci lokalnej, jawna
treść backupu), token po TLS z certyfikatem publicznym (wymaga nazwy domenowej
i urzędu), własny mini-CA z podpisywaniem żądań CSR (więcej ruchomych części
bez zysku bezpieczeństwa).

**Konsekwencje.** Klient nie weryfikuje nazwy hosta. Jest to bezpieczne
**wyłącznie** dlatego, że przypięcie klucza ją zastępuje — i musi być tak
udokumentowane, żeby nikt nie usunął jednego zabezpieczenia w przekonaniu,
że drugie i tak działa.

---

## ADR-006 — Tożsamość przez klucz, nie przez adres

**Status:** przyjęta

**Kontekst.** Adres serwera zmienia się nieustannie: inna sieć, nowy przydział
DHCP, aktywny VPN, telefon poza domem.

**Decyzja.** Klient przechowuje **zbiór tras** do serwera o znanym pinie i przy
każdym połączeniu ściga je równolegle, przyjmując pierwszą poprawną odpowiedź.
Odpowiedź `ping` zawiera listę adresów, pod którymi serwer sam siebie widzi,
więc zbiór aktualizuje się sam.

**Uzasadnienie.** Adres przestaje być tożsamością i staje się jedną z wielu dróg.
Użytkownik nigdy niczego nie konfiguruje: w domu wygra trasa lokalna jako
najszybsza, poza domem sieć nakładkowa, po zmianie routera mDNS znajdzie nowy
adres. Model sprawdzony w Syncthingu i w samym Tailscale.

**Odrzucone.** Zapamiętany pojedynczy adres (psuje się przy pierwszej zmianie
sieci), stały adres wymuszony na routerze (przerzuca konfigurację na użytkownika),
sam mDNS (nie działa poza siecią lokalną).

**Konsekwencje.** Wymaga przypięcia gniazda do konkretnego obiektu `Network` na
Androidzie, żeby trasy lokalne działały mimo aktywnego VPN. Przy VPN w trybie
blokującym ruch poza tunelem trasa lokalna przestanie być dostępna — degradacja
musi być cicha.

---

## ADR-007 — Zachowywanie poprzednich wersji zamiast nadpisywania

**Status:** przyjęta

**Kontekst.** Pod znaną ścieżką pojawia się zawartość o innym rozmiarze lub mtime.

**Decyzja.** Poprzednia zawartość trafia do `.filum/versions/` ze znacznikiem
czasu pochodzącym z jej własnego mtime. Katalog `files/` pozostaje czystym
lustrem obecnego stanu telefonu.

**Uzasadnienie.** Nadpisanie jest formą utraty danych, więc byłoby sprzeczne
z ADR-001. Trzymanie wersji poza `files/` chroni czytelność drzewa, które ma
pozostać przeglądalne w Eksploratorze bez żadnych narzędzi (ADR-003).

**Odrzucone.** Nadpisywanie (sprzeczne z ADR-001), wersjonowanie tylko plików
niebędących mediami (reguła zależna od typu pliku, trudna do obrony i do
przetestowania).

**Konsekwencje.** Brak polityki retencji — katalog wersji rośnie bez ograniczeń.
Odnotowane jako kwestia otwarta.

---

## ADR-008 — Agregacja małych plików

**Status:** przyjęta, do potwierdzenia pomiarem

**Kontekst.** W około 200 GB znajduje się szacunkowo 100 tys. plików, z czego
znaczna większość to drobne pliki poniżej 200 KB.

**Decyzja.** Osobny zasób `POST /bundle` przyjmujący strumień plików z ramkowaniem
długości. Próg wielkości podaje serwer w odpowiedzi `ping`.

**Uzasadnienie.** Przy jednym żądaniu HTTP na plik narzut protokołu zaczyna
dominować nad transferem danych.

**Odrzucone.** Wyłącznie `PUT` na plik (narzut), archiwum tar w całości
(uniemożliwia wznawianie i częściowe powodzenie).

**Konsekwencje.** Paczka nie obsługuje wznawiania — akceptowalne, bo dotyczy
wyłącznie plików małych. Próg musi zostać wyznaczony pomiarem, nie przyjęty
z góry; to jeden z eksperymentów do rozdziału wynikowego.

---

## ADR-009 — Kodowanie procentowe zamiast podmiany znaków

**Status:** przyjęta

**Kontekst.** NTFS zabrania znaków dozwolonych na ext4 i nie rozróżnia wielkości liter.

**Decyzja.** Znaki niedozwolone kodujemy procentowo, kolizje wielkości liter
rozstrzygamy deterministycznym przyrostkiem z funkcji skrótu.

**Uzasadnienie.** Odwzorowanie musi być różnowartościowe. Podmiana na `_` sprawia,
że `a<b.txt` i `a_b.txt` trafiają na tę samą ścieżkę i drugi plik cicho nadpisuje
pierwszy — bezpośrednie naruszenie ADR-001 i ADR-007.

**Odrzucone.** Podmiana na `_` (stratna), pełne kodowanie base64 nazw
(nieczytelne dla użytkownika, sprzeczne z ADR-003).

**Konsekwencje.** Nazwy ze znakami specjalnymi wyglądają na dysku nietypowo, ale
pozostają czytelne i w pełni odwracalne.

---

## ADR-010 — Panel webowy plus ikona w zasobniku

**Status:** przyjęta

**Kontekst.** Serwer potrzebuje interfejsu do parowania, podglądu i ustawień.

**Decyzja.** Interfejs jako strona serwowana przez sam serwer (Blazor), otwierana
z menu ikony w zasobniku systemowym.

**Uzasadnienie.** Serwer i tak jest serwerem HTTP, więc panel jest niemal darmowy —
nie trzeba drugiego zestawu bibliotek ani drugiego sposobu prezentowania stanu.
Ikona w zasobniku daje aplikacji fizyczną obecność w systemie.

**Korekta wprowadzona przy implementacji.** Pierwotnie zakładaliśmy, że panel będzie
osiągalny także z telefonu i z innego komputera w sieci, co bywa wygodne przy
diagnozowaniu transferu. Trzeba było z tego zrezygnować: panel **wydaje tokeny
parowania** i pokazuje zawartość kopii, więc wystawienie go w sieci pozwalałoby
obcemu urządzeniu sparować się bez wiedzy właściciela. Panel jest dostępny wyłącznie
z pętli zwrotnej. Wygoda diagnostyczna nie jest warta furtki do kopii zapasowej.

**Odrzucone.** Pełna aplikacja WPF/WinUI (dublowanie logiki prezentacji stanu),
sama konsola (nie do przyjęcia dla użytkownika końcowego).

**Konsekwencje.** Usługa systemowa działa w sesji 0 i nie może wyświetlać
interfejsu, więc docelowo zasobnik musi być osobnym procesem rozmawiającym
z serwerem przez to samo API.

---

## ADR-011 — Znaczniki mtime w sekundach, normalizowane w jednym miejscu

**Status:** przyjęta

**Kontekst.** `MediaStore.DATE_MODIFIED` zwraca sekundy, `File.lastModified()`
milisekundy. Skaner korzysta z obu źródeł.

**Decyzja.** Protokół operuje wyłącznie na sekundach od epoki Uniksa w UTC.
Normalizacja zachodzi w jednym miejscu, na granicy odczytu z systemu plików.

**Uzasadnienie.** Gdyby ten sam plik trafiał do manifestu raz z jednego, raz
z drugiego źródła, jego tożsamość zmieniałaby się między cyklami i plik byłby
wysyłany w kółko. Przy 200 GB byłaby to awaria, nie niedogodność.

**Odrzucone.** Milisekundy (część źródeł ich nie dostarcza, więc i tak potrzebna
byłaby konwersja w drugą stronę).

---

## ADR-012 — Pierwszy transfer przez sieć; tryb adopcji opcjonalny

**Status:** przyjęta

**Kontekst.** Pierwsza synchronizacja obejmuje około 200 GB. Rozważano wstępny
zrzut kablem i dosyłanie wyłącznie różnicy.

**Decyzja.** Ścieżką podstawową jest pełny transfer przez sieć, rozłożony na
kilka nocy. Tryb adopcji istniejącego zrzutu jest funkcją dodatkową.

**Uzasadnienie.** Kopiowanie przez MTP notorycznie nie zachowuje czasów
modyfikacji, a FAT ma granulację dwóch sekund. Niezgodny mtime sprawiłby, że
pierwsze uruchomienie uznałoby komplet plików za brakujące i wysłało całe 200 GB
mimo istniejącego zrzutu. Transfer sieciowy wymusza porządną wznawialność, która
i tak jest potrzebna, i eliminuje całą klasę błędów.

**Odrzucone.** Wyłącznie zrzut kablem z obowiązkową adopcją (ryzyko pojedynczego
punktu awarii przy pierwszym uruchomieniu).

**Konsekwencje.** Pierwsze uruchomienie trwa kilka nocy. Akceptowalne, bo
odbywa się raz.

---

## ADR-013 — targetSdk 36 i dystrybucja poza sklepem

**Status:** przyjęta

**Kontekst.** Dostęp do całej pamięci wymaga `MANAGE_EXTERNAL_STORAGE`, którego
Google Play nie dopuszcza dla aplikacji tego rodzaju.

**Decyzja.** `targetSdk 36`, instalacja przez sideload, poza sklepem.

**Uzasadnienie.** `targetSdk 36` pozwala na razie ominąć wymóg
`ACCESS_LOCAL_NETWORK` wprowadzony w Androidzie 17. Skoro dystrybucja i tak
odbywa się poza sklepem, nie obowiązuje wymóg aktualizowania poziomu docelowego.

**Odrzucone.** Storage Access Framework (zbyt wolny przy setkach tysięcy plików),
ograniczenie się do `MediaStore` (pominęłoby dokumenty i pobrane pliki).

**Konsekwencje.** Zależność od wersji systemu, która wygaśnie. Świadomie
odnotowana jako znane ograniczenie, do opisania w pracy. `/Android/data`
i `/Android/obb` pozostają niedostępne i nie próbujemy tego obchodzić;
`/Android/media` jest dostępne i objęte backupem.

---

## ADR-014 — Sieć nakładkowa jako wymienny profil wdrożeniowy

**Status:** przyjęta

**Kontekst.** Dostęp spoza sieci lokalnej wymaga punktu spotkania: przekierowania
portu, sieci nakładkowej albo własnego przekaźnika. Czwartej możliwości nie ma.

**Decyzja.** Aplikacja **nie integruje się** z żadnym dostawcą. Sieć nakładkowa
wnosi wyłącznie jeden dodatkowy adres do zbioru tras z ADR-006.

**Uzasadnienie.** Dzięki temu Meshnet, Tailscale, ręcznie postawiony WireGuard,
DDNS z przekierowaniem portu i własny przekaźnik są wymienne bez zmiany
jednej linii kodu. Praca opisuje architekturę dopuszczającą dowolny z nich,
zamiast opierać się na komercyjnym produkcie.

**Odrzucone.** Integracja z API konkretnego dostawcy (zależność i utrata
neutralności), wbudowana implementacja WireGuarda (ogromny nakład bez związku
z tematem pracy).

**Konsekwencje.** Deklarowane „bez chmury" wymaga doprecyzowania w pracy:
oznacza „dane nigdy nie są składowane u osoby trzeciej", nie „żaden pakiet nigdy
nie przechodzi przez cudzą maszynę". Dosłowną wersję zapewnia dopiero własny
przekaźnik albo przekierowanie portu.

---

## ADR-015 — Raport sierot bez usuwania

**Status:** przyjęta

**Kontekst.** Skoro serwer nigdy nie kasuje, przenoszenie plików w telefonie
mnoży kopie, a zajętość dysku rośnie w nieskończoność.

**Decyzja.** Po zamknięciu sesji z kompletnym manifestem serwer wylicza listę
ścieżek obecnych u siebie, a nieobecnych na telefonie, i udostępnia ją jako
raport. Nic nie usuwa.

**Uzasadnienie.** Zachowuje niezmiennik z ADR-001, a jednocześnie daje
użytkownikowi kontrolę nad przyrostem zajętości. Sieroty są produktem ubocznym
rekoncyliacji, którą i tak trzeba okresowo wykonać.

**Odrzucone.** Automatyczne usuwanie po okresie karencji (sprzeczne z ADR-001),
całkowite pominięcie problemu (dysk zapełniłby się bez wyjaśnienia).

**Konsekwencje.** Decyzja o usunięciu należy wyłącznie do użytkownika i zapada
ręcznie, poza aplikacją albo przez jawną akcję w panelu.
