# Ping Room – pokoje z kodem i pingi jak w Valorancie / CS:GO

Mod **tylko po stronie klienta** (Fabric, Minecraft 26.2) – działa na **dowolnym serwerze**, także vanilla,
bo serwer Minecrafta nie musi nic o nim wiedzieć.

```
Gracz A ─┐                       ┌─ Gracz B
         ├── WebSocket ── relay ─┤        (relay to mały program w Node.js,
Gracz C ─┘   (pokój = kod)       └─ Gracz D  przekazuje tylko pingi)
```

Dlaczego relay? Serwer Minecrafta nie przekaże niestandardowych wiadomości między graczami, jeśli nie ma
po swojej stronie moda/pluginu. Żeby mod działał wszędzie, pingi lecą bokiem – przez malutki serwer-pośrednik.
Ktoś z ekipy musi go raz postawić (albo każdy uruchomi lokalnie do testów).

## Jak używać w grze

| Co | Jak |
|---|---|
| Ustaw adres relaya (jednorazowo, wszyscy ten sam) | `/pingroom relay ws://adres:8080` |
| Utwórz pokój | `/pingroom create` → dostajesz 6-znakowy kod |
| Dołącz | `/pingroom join KOD` (musisz być na **tym samym serwerze** co twórca) |
| Ping „tutaj” (cyjan) | klawisz **V** |
| Ping „uwaga!” (czerwony) | klawisz **X** |
| Wyjdź / info | `/pingroom leave`, `/pingroom info` |

Klawisze zmienisz w *Sterowanie → Ping Room*. Ping pojawia się u wszystkich w pokoju jako znacznik z nickiem
i odległością, z dźwiękiem; po kilku sekundach znika. Znacznik poza ekranem przykleja się do krawędzi.

## 1. Relay (Node.js 18+)

```
cd relay
npm install
npm start          # ws://localhost:8080
```

* Zmienne: `PORT` (domyślnie 8080), `TRUST_PROXY=1` (gdy stoisz za nginx/Caddy).
* Do gry przez internet postaw go na VPS / hostingu z WebSocketami i wystaw przez `wss://` (reverse proxy z TLS).
* Pokój znika, gdy wyjdzie ostatnia osoba. Max 16 osób w pokoju. Kod jest losowy (32^6 możliwości),
  próby dołączenia są limitowane.

## 2. Budowanie moda – jar bez instalowania czegokolwiek (GitHub Actions)

1. Załóż darmowe konto na github.com i utwórz nowe repozytorium.
2. Wrzuć do niego całą zawartość tego folderu (razem z ukrytym folderem `.github`).
3. Wejdź w zakładkę **Actions** → uruchomi się „Build mod” (albo kliknij *Run workflow*).
4. Po zakończeniu pobierz artefakt **pingroom-jar** – w środku jest `pingroom-1.0.0.jar`.

## 2b. Budowanie lokalnie

Potrzebujesz JDK 25 i np. IntelliJ IDEA 2025.3+.

1. Otwórz folder `mod` w IntelliJ (jako projekt Gradle) i poczekaj na import.
2. Zadanie Gradle `build` → gotowy plik `mod/build/libs/pingroom-1.0.0.jar`.
   (Z terminala: `gradle wrapper` jeśli masz Gradle, potem `./gradlew build`.)
3. Wrzuć jar do `.minecraft/mods` razem z **Fabric API** (Fabric Loader 0.19.3+).

Wersje są w `mod/gradle.properties`. Dla Minecrafta 26.3 zmień `minecraft_version=26.3`
i `fabric_api_version=0.161.0+26.3` (aktualne numery: https://fabricmc.net/develop/).

## Konfiguracja – `config/pingroom.json`

`relayUrl`, `pingLifetimeSeconds` (jak długo widać ping, 2–60), `maxPingDistance` (zasięg w blokach, 16–512).

## Ograniczenia / uwagi

* Relay widzi: nick, adres serwera, na którym grasz, i współrzędne pingów. Nie ma żadnych haseł ani tokenów.
  Nicków nie weryfikuje (ktoś z kodem może podać dowolny nick) – ochroną jest sam kod pokoju.
* Znaczniki są liczone od pozycji oczu gracza; w trzeciej osobie (F5) mogą być lekko przesunięte.
  FOV bierze z ustawień gry (sprint/efekty go chwilowo zmieniają → minimalny błąd).
* Ping trafia w bloki (do `maxPingDistance`); nie w niebo, nie w moby (możliwe rozszerzenie).
* Rozłączenie z relayem = wyjście z pokoju; wystarczy ponownie `/pingroom join KOD`.
* Zmiana serwera Minecrafta automatycznie wyprowadza z pokoju.
